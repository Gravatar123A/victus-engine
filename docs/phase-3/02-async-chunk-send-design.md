# Async Chunk Send — Design Brief (v1)

_asyncsend-understand research workflow (5 agents, source-verified vs MC 26.2), 2026-07-21._
_Key finding: Paper already ships the async-packet ordering rail (ClientboundLevelChunkWithLightPacket.isReady()/setReady() + Connection pendingActions FIFO). v1 = snapshot-on-main (section.copy) -> serialize-on-worker -> send via that rail. Default OFF; byte-for-byte equivalence harness = headless correctness gate._

All load-bearing seams confirmed against the 26.2 decomp. Key refinements from the grep: the packet's byte payload is `private final byte[] buffer` inside `ClientboundLevelChunkPacketData` (line 33), filled via `getWriteBuffer()` at line 60; `write()` is a pure `writeVarInt(len)+writeBytes(buffer)` memcpy (lines 92-93); the `IllegalStateException("Didn't fill chunk buffer...")` guard is line 127; `states` is `final` but `biomes` is reassignable (LCS 18-19); `acquire()/release()` delegate to `states` only (LCS 79-84); `states.data` is the swappable reference (LCS 204). Synthesis follows.

---

# VICTUS ENGINE â€” ASYNC CHUNK SEND: Implementation-Ready Design Brief (v1)

Paper 26.2 hard-fork, Mojang names. Line refs are `E:/victus-tmp/cs-src`. `[F]`=file-verified, `[P]`=Paper-architecture (verify on real tree before patch).

The four reports converge on one thing: **the offload rail already exists in-tree** (`ClientboundLevelChunkWithLightPacket.ready` + `isReady()/setReady()`, lines 22-30, tagged "Paper - Async-Anti-Xray"). Paper already runs anti-xray *obfuscation* off-thread and gates the netty flush on this flag. Our job is to extend that same rail to cover the *palette serialization*, made safe by a main-thread snapshot. We do NOT invent a new send path or ordering queue.

---

## 1. V1 SCOPE DECISION

**SHIP:** *Snapshot-on-main â†’ serialize-on-worker â†’ send-in-order via the existing ready-gate.* Default **OFF**, profile-gated (like async-path/DAB v1). Geyser/Floodgate Bedrock clients are excluded and use Paper's original synchronous construction path: Bedrock movement is server-authoritative, so head-of-line blocking behind a not-ready chunk shell presents as movement freezes followed by position corrections on mobile.

The split:
- **MAIN (in patched `PlayerChunkSender.sendChunk`, line 81):** read all live world state â€” `shouldModify` [83], light data, block-entity NBT, heightmap `long[]` clone, and a **`section.copy()` snapshot of each non-empty section** [LCS 337]. Then enqueue a `ready=false` packet **shell** via `connection.send(...)` **immediately, in send order** â€” this reserves the per-player FIFO slot. Fire `PlayerChunkLoadEvent` [87] on main.
- **WORKER:** from the snapshot only â€” `calculateChunkSize` [97] â†’ `new byte[]` â†’ `extractChunkData`/`section.write` [60/LCS 294] â†’ anti-xray `modifyBlocks` â†’ attach `chunkData` â†’ `setReady(true)` â†’ poke flush.
- **NETTY:** drains the FIFO front-to-back, head-of-line-blocking on `isReady()==false`; `write()` is a memcpy [92-93].

**Anti-Xray decision â€” OFFLOAD it too (unified single worker task), do NOT force-sync in v1.** Rationale: Paper *already* runs `modifyBlocks` obfuscation off-thread today (that is what the `ready` flag is for); offloading serialization onto that same stage adds **no new off-thread hop for anti-xray**. Because we serialize the *target* chunk from a frozen snapshot, our anti-xray target-chunk read is **strictly safer than upstream** (upstream captures presets from the live chunk on main, then obfuscates async). The one residual live off-thread read is anti-xray's **neighbour-edge sampling** (to avoid revealing visible ores) â€” but that read **already happens off-thread in stock Paper**, so it is status-quo risk, not new risk. We keep an escape hatch: `anti-xray.force-sync=false` (Â§5) that, if adversarial review of `ChunkPacketBlockControllerAntiXray` finds the neighbour read unsafe in 26.2, forces the vanilla synchronous path whenever `shouldModify==true`.

**DEFERRED (explicitly out of v1, too risky):**
- Live off-thread serialization without snapshot (Leaf's approach â€” silent tearing, see Â§4/R2).
- Off-thread light-engine reads, off-thread block-entity NBT (`getUpdateTag`).
- Any change to batch flow-control (`unacknowledgedBatches`, `desiredChunksPerTick`, `onChunkBatchReceivedByClient` [127-139]).
- Per-player or per-world worker pools; adaptive pool sizing.
- Suppressing the "Didn't fill chunk buffer" guard [127]. **We KEEP it** â€” with a snapshot, size and bytes derive from the *same frozen container*, so it must never trip; if it does, that is a real invariant violation and we want the throw (which routes to sync fallback), unlike Leaf which suppresses it.

**Default OFF.** Enable per-profile only after headless boot + join-storm/elytra soak + adversarial review.

---

## 2. ARCHITECTURE

### 2.1 Executor â€” dedicated, NOT the Moonrise IO pool
Do **not** piggyback on Moonrise's chunk-load/save IO pool: coupling serialization to disk IO risks mutual starvation and breaks subsystem isolation/profiling. Use a dedicated pool in `cloud.victus.engine.net.AsyncChunkSerializer`:

```
ThreadPoolExecutor(
  core = size, max = size, keepAlive = 0,
  workQueue = ArrayBlockingQueue<>(bounded, e.g. 4 * size * viewDiam),   // bounded â†’ backpressure
  threadFactory = named "Victus Async Chunk Serializer #%d", DAEMON=true,
      priority = NORM-1, UncaughtExceptionHandler = MinecraftServer::onThreadException,
  rejectedHandler = CallerRunsPolicy)   // overflow runs INLINE on the main thread = safe sync fallback, never drops
```
- **Sizing:** `clamp(availableProcessors / 4, 1, 4)` default, config-overridable. Conservative; the ready-gate (Â§2.3) makes order independent of worker count, so we are free to parallelize a join-storm without Leaf's single-thread 1-core cap.
- **Daemon + shutdown:** daemon threads so a hung worker never blocks JVM exit; PLUS an explicit `shutdown()` + `awaitTermination(2s)` then `shutdownNow()` wired into server stop (Â§3) so in-flight snapshots aren't leaked and Bukkit doesn't warn about async tasks after disable. (Leaf ships neither daemon nor shutdown â€” we fix both.)

### 2.2 Flow: capture-on-main â†’ serialize-on-worker â†’ send-in-order
1. **Capture (main):** freeze everything the serializer reads (Â§4 table) into an immutable `ChunkSerializeJob` bundle. Cost = `section.copy()` (`long[]` arraycopy of BitStorage + palette list) â‰ª the varint-encode + obfuscate it replaces â†’ net main-thread win, but honestly non-zero (the Amdahl caveat, same class as async-path v1).
2. **Reserve slot (main):** build the packet shell (`x`, `z`, `lightData` set; `chunkData=null`; `ready=false`) and `connection.send(shell)` **before** submitting the job. This fixes the wire position **now**, in the exact `BatchStart[67] â†’ chunkÃ—N[70] â†’ BatchFinished[73]` order.
3. **Serialize (worker):** build `ClientboundLevelChunkPacketData` from the snapshot (its internal `final byte[] buffer` [33] is allocated + filled here), run anti-xray, then `shell.chunkData = data; shell.setReady(true);` and poke `connection.tick()`/flush.
4. **Flush (netty):** ready-gated drain (Â§2.3).

### 2.3 PER-PLAYER ORDERING â€” the load-bearing correctness piece
Reuse Paper's `Connection` per-player packet queue, which **head-of-line-blocks on `Packet.isReady()`** [P]: `canSendImmediately()` requires an empty pending queue AND `isReady()`, and the drain **stops at the first not-ready head**, holding everything behind it. Consequences we depend on:
- A chunk shell enqueued `ready=false` **holds back every later packet on that connection** â€” subsequent `ClientboundBlockUpdate`, `ClientboundBlockEntityData`, section-blocks-update, and even `ChunkBatchFinished` â€” until its worker sets ready. â†’ **A block change cannot overtake its chunk** (the exact "ghost/disappearing blocks" bug Leaf's design forfeits, because Leaf sends chunks from a worker without reserving the main-thread slot, and routes only pool packets through its FIFO â€” see report C Â§1b, PR #724).
- **Workers may complete out of order** with zero reordering risk: even if chunk #3 finishes before #1, #3 waits behind #1/#2 in the FIFO. We therefore do **not** need in-order completion, a completion sequencer, or Leaf's single-thread executor.
- `ChunkBatchFinished(chunksToSend.size())` [73]: count is computed on main (correct); the packet is `ready=true` but sits behind the N not-ready chunks in FIFO, so it flushes only after all N flush â†’ **ACK-driven pacing self-throttles** (slow workers â†’ delayed Finished â†’ delayed client ACK â†’ next batch delayed). No change to flow-control code needed.

**This gate is the entire correctness argument.** It MUST be verified against real 26.2 `net.minecraft.network.Connection` before enabling (Â§7 open item): confirm drain still stops-at-first-not-ready (does not skip/drop), and confirm the NoOp anti-xray controller sets `ready=true` synchronously so anti-xray-off chunks aren't accidentally gated forever.

---

## 3. PATCH PLAN

**P1 â€” `net.minecraft.server.network.PlayerChunkSender.sendChunk` [81-97]** `[F]`
Replace the inline `connection.send(new ClientboundLevelChunkWithLightPacket(...))` [84] with a capture-and-dispatch branch; keep [83],[87-88],[96] on main; keep the vanilla body as the `else`/fallback.

```java
public static void sendChunk(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk) {
    final boolean shouldModify = level.chunkPacketBlockController.shouldModify(connection.player, chunk); // [83] MAIN

    if (cloud.victus.engine.net.AsyncChunkSerializer.enabledFor(level, shouldModify)) {
        // ---- CAPTURE (MAIN) ----
        var job = cloud.victus.engine.net.AsyncChunkSerializer.capture(level, chunk, shouldModify);
        // job holds: section.copy()[LCS337] per non-empty section, heightmap long[].clone(),
        //            ClientboundLightUpdatePacketData (light read on main),
        //            BlockEntityInfo list + extraPackets (getUpdateTag on main), chunkPos, shouldModify.
        var shell = ClientboundLevelChunkWithLightPacket.deferred(job.pos(), job.lightData()); // ready=false, chunkData=null
        connection.send(shell);                                   // [reserve FIFO slot, in order]
        cloud.victus.engine.net.AsyncChunkSerializer.submit(connection, job, shell);
    } else {
        connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null, shouldModify)); // [84] vanilla
    }

    if (PlayerChunkLoadEvent.getHandlerList().getRegisteredListeners().length > 0) { /* [87-88] MAIN */ }
    level.debugSynchronizers().startTrackingChunk(connection.player, chunk.getPos()); // [96] MAIN
}
```

**P2 â€” `ClientboundLevelChunkWithLightPacket`** `[F]`
- Reuse `ready`/`isReady`/`setReady` [22-30].
- Make `chunkData` settable: drop `final` on the field (or add `void setChunkData(ClientboundLevelChunkPacketData)`), mirroring the existing `setReady` addition.
- Add static factory `deferred(ChunkPos, ClientboundLightUpdatePacketData)` â†’ constructs with `chunkData=null`, `ready=false`.
- Publication is via the existing `volatile ready`: worker writes `chunkData` (plain) then `setReady(true)` (volatile store); netty reads `ready` (volatile load) then `chunkData` â€” happens-before covers the plain write. Same model Paper uses to publish the anti-xray `buffer`.

**P3 â€” `ClientboundLevelChunkPacketData`** `[F]`
- Add a snapshot ctor/entry taking the `ChunkSerializeJob` (copied sections + cloned heightmaps + prebuilt `blockEntitiesData`/`extraPackets`) instead of the live `LevelChunk` [49-76].
- `calculateChunkSize` [97] and `extractChunkData` [60/118] run here **on the worker, against the snapshot**. The `final byte[] buffer` [33] is allocated + filled here.
- **Keep** the `IllegalStateException("Didn't fill chunk buffer")` guard [127] â€” snapshot makes sizeâ†”bytes consistent, so it becomes a real invariant tripwire that triggers sync fallback.

**P4 â€” `cloud.victus.engine.net.AsyncChunkSerializer`** (new helper, called from patched minecraft)
- `enabledFor(level, shouldModify)`: profile flag + `!forceSyncWhenAntiXray || !shouldModify` + pool-not-shutdown.
- `capture(level, chunk, shouldModify)`: builds the immutable `ChunkSerializeJob` on main (the Â§4 "captured-on-main" set).
- `submit(connection, job, shell)`: bounded-queue `execute`, each task wrapped:

```java
executor.execute(() -> {
  try {
    if (!connection.isConnected()) return;                       // R5 short-circuit (shell will be discarded on close)
    var data = ClientboundLevelChunkPacketData.fromSnapshot(job); // size+extract+section.write from snapshot
    if (job.shouldModify()) level.chunkPacketBlockController.modifyBlocks(shell, data.chunkPacketInfo()); // anti-xray on buffer
    shell.setChunkData(data);
  } catch (Throwable t) {
    ThrottledLogger.warn("async chunk serialize failed; sync fallback", t);
    MinecraftServer.getServer().execute(() -> rebuildSyncInto(shell, job)); // R7: rebuild on main
  } finally {
    // guaranteed READY (R1): even sync-fallback path ends in setReady(true) inside rebuildSyncInto
    shell.setReady(true);
    connection.connection.tick();   // poke flush [P: verify flush entrypoint]
  }
});
```
Note: on the exception path, `setReady(true)` in `finally` runs *before* the main-thread `rebuildSyncInto` completes â€” so `rebuildSyncInto` must set `chunkData` **then** call `setReady(true)` again, and the `finally` here must NOT set ready when it dispatched a rebuild. Corrected shape: exception path sets a flag and lets `rebuildSyncInto` own the final `setReady`; the `finally` only sets ready on the success path. (Bake this ordering into review â€” it is the R1 hot spot.)

- **Watchdog (R1):** a lightweight scan (piggyback the main tick, every N ticks) over shells still `!ready` past a deadline â†’ force `rebuildSyncInto` on main + `setReady(true)`. Prevents permanent connection stall from a lost/hung task.

**P5 â€” Shutdown hook**: call `AsyncChunkSerializer.shutdown()` from the server-stop path (`MinecraftServer.stopServer` / existing victus lifecycle) â†’ `shutdown()`, `awaitTermination(2s)`, `shutdownNow()`.

**P6 â€” victus-core config**: register keys (Â§5) in the profile-driven resolver; `enabledFor` reads the resolved per-world value.

**Verify-before-patch (not in local decomp)** `[P]`: `Connection` ready-gate drain semantics + flush entrypoint; `ChunkPacketBlockControllerAntiXray.modifyBlocks`/`getChunkPacketInfo` (per-packet lifetime, executor, neighbour-read timing, where it currently calls `setReady`); NoOp controller sets ready synchronously; `PacketEncoder` `TWO_MEGABYTES` guard.

---

## 4. THREAD-SAFETY RECIPE (the PathTypeCache hunt)

| State | Where read today | Hazard if read off-thread | v1 disposition |
|---|---|---|---|
| **`PalettedContainer<BlockState> states`** LCS 18 (`final`) | `section.write`â†’`states.write` [LCS 294/298] | Main `setBlockState`â†’`states.getAndSet` [LCS 133] swaps `states.data` [LCS 204] (palette/BitStorage resize). `write`/`getSerializedSize` **do NOT `acquire()`** (acquire delegates to states but only the write path calls it, LCS 79-84 vs 133) â†’ **silent torn read / AIOOBE**, not a clean ThreadingDetector throw. **Do NOT make the worker `acquire()`** â€” that would make the detector throw. | **CAPTURE-ON-MAIN via `section.copy()` [LCS 337]** (deep-copies `states.copy()`+`biomes.copy()`, LCS 50-51). Worker touches only the private copy. Kills the race. |
| **`PalettedContainer<Holder<Biome>> biomes`** LCS 19 (non-`final`, "CraftBukkit read/write") | `biomes.write(buffer,null,idx)` [LCS 299] | Reassigned wholesale by `recreate`/`readBiomes`/`fillBiomesFromNoise` [LCS 273/284/323] â†’ reference swap â†’ tearing. | Same `section.copy()` snapshot. |
| **sizeâ†”bytes skew** | `calculateChunkSize` [55/97] then `extractChunkData` [60] | If size from one state, bytes from another â†’ guard throw [127]. | Compute **both from the same snapshot** on worker. |
| **`blockEntities` map** LCSâ†’LChunk 758-759 (live field) | ctor loop [64], `BlockEntityInfo.create`â†’`getUpdateTag` [190], `sanitizeSentNbt` [193] | Off-thread iterate = **CME**; `getUpdateTag` reads live chest/sign contents = NBT corruption. | **CAPTURE-ON-MAIN**: build `blockEntitiesData` + `extraPackets` (oversized-BE, `BLOCK_ENTITY_LIMIT` [37]) on main, pass in the job. |
| **Heightmaps `long[]`** | `.getRawData().clone()` [51-54] | Main mutates in `setBlockState` path [LChunk 384-387]; **Leaf passes raw uncloned â†’ torn heightmap (a real Leaf bug)**. | **Keep `.clone()` ON MAIN** in `capture()`. |
| **`LevelLightEngine`** | `ClientboundLightUpdatePacketData` ctor [56] | Not thread-safe; DataLayers swapped/nulled â†’ NPE/torn light. | **Build `lightData` ON MAIN** in `capture()` (cheap). |
| **`nonEmptyBlockCount`/`fluidCount`** LCS 296-297 | `writeShort` in `section.write` | Plain shorts mutated on main â†’ stale header. Snapshotted by `section.copy()`. | Covered by `section.copy()`. Benign even if slightly stale (client self-heals via block updates). |
| **Anti-xray neighbour edges** | `modifyBlocks` [57] | Reads 4 neighbour chunks' live palettes off-thread. | **Inherited-from-Paper** (already async upstream). Kept in v1; escape hatch `anti-xray.force-sync` (Â§5). Verify in review. |
| **`ChunkPacketInfo`** [54] | per-packet, `setBuffer` [58] | Fresh per call â†’ **not shared, safe** on worker. | Worker-owned. |

**Worker-touchable:** the `ChunkSerializeJob` snapshot, the per-packet `ChunkPacketInfo`, the packet's private `byte[] buffer`, `extraPackets` list. Nothing shared is mutated by serialization itself (`section.write` writes only into the per-packet buffer).

**Exception / fallback:** any worker throw (incl. guard [127]) â†’ throttled-log â†’ `MinecraftServer.execute(rebuildSyncInto)` on main â†’ that sets `chunkData` then `setReady(true)`. **Never drop, never leave a shell not-ready** (R1). Pool overflow â†’ `CallerRunsPolicy` runs the task inline on main = degraded-but-correct. `enabled=false`/`forceSyncWhenAntiXray` â†’ vanilla path [84].

---

## 5. CONFIG (victus.yml + victus-core)

```yaml
victus:
  chunk:
    async-send:
      enabled: false                 # master, default OFF (profile-overridable per world)
      threads: -1                    # -1 => clamp(cores/4, 1, 4)
      queue-capacity: -1             # -1 => 4 * threads * (viewDistance*2+1); overflow => CallerRuns (sync inline)
      watchdog-deadline-ms: 1500     # not-ready past this => force sync rebuild on main
      anti-xray:
        force-sync: false            # true => shouldModify chunks always take vanilla sync path (max-conservative escape hatch)
      fallback-on-exception: true    # false only for debugging (would rethrow)
```

victus-core fields (resolver + profiles, same shape as async-path):
- `boolean asyncChunkSend.enabled` (per-world resolved)
- `int asyncChunkSend.threads` (resolved â†’ clamp)
- `int asyncChunkSend.queueCapacity`
- `long asyncChunkSend.watchdogDeadlineMs`
- `boolean asyncChunkSend.antiXrayForceSync`
- `boolean asyncChunkSend.fallbackOnException`

Resolution: master off â‡’ helper is fully inert (vanilla path only, zero overhead). Async modules **restart-only** (match Leaf/async-path; no hot reload of the pool).

---

## 6. RISK REGISTER

| # | Risk | Sev | Baked-in mitigation |
|---|---|---|---|
| R1 | **Connection stalls FOREVER** if a shell is never set ready (lost task, worker death, pool bug). Head-of-line block â‡’ player frozen on "Loading terrain", all later packets blocked. **Worse than a crash.** | CRIT | `try/finally` guarantees `setReady(true)` on every path; bounded queue + `CallerRunsPolicy` (never drops); **watchdog** force-rebuilds on main past deadline; connection-close discards pending shells. |
| R2 | **Torn live-palette read off-thread** (silent, ThreadingDetector doesn't guard the read path) â†’ corrupt packet / AIOOBE / guard throw. | CRIT | **Mandatory `section.copy()` on main**; worker never touches live chunk; size+bytes from one snapshot; keep guard [127] as tripwire. |
| R3 | **Block-update / BE / light packet overtakes its chunk** â†’ ghost blocks / desync (the Leaf bug, PR #724). | HIGH | Enqueue `ready=false` shell **synchronously in send order on main** BEFORE submit; ready-gate FIFO holds all later packets behind it. **Verify Connection drain semantics (Â§7).** |
| R4 | **CME / NBT corruption** iterating live `blockEntities` + `getUpdateTag` off-thread. | HIGH | Build `blockEntitiesData`+`extraPackets` on main in `capture()`. |
| R5 | Player disconnect mid-flight. | MED | Snapshot decouples from live chunk (no dangling ref); `isConnected()` short-circuit; `setReady` on an orphaned shell is a harmless no-op. |
| R6 | Chunk unload / moved out of view mid-flight (`dropChunk`â†’`ForgetLevelChunk` [46]). | LOW | Snapshot independent of live chunk; shell was ordered before any later Forget; `collectChunksToSend` already removed it from pending [121]. Worst case = wasted bytes, not incorrectness. |
| R7 | Worker exception during serialize/obfuscate. | HIGH | Catch â†’ `MinecraftServer.execute(rebuildSyncInto)` on main â†’ set chunkData + setReady. Never kill pool (UncaughtExceptionHandler + task try/catch). Throttled log. |
| R8 | **Anti-Xray leak / mis-obfuscation** off-thread (neighbour-edge read, engine-mode edge cases; cf. Leaf PR #823). | HIGH | Target chunk snapshotted (safer than upstream); neighbour read is upstream status-quo; escape hatch `anti-xray.force-sync`; adversarial review of `ChunkPacketBlockControllerAntiXray`; verify NoOp controller sets ready synchronously. |
| R9 | Plugin compat: `PlayerChunkLoadEvent`/`PlayerChunkUnloadEvent` [48-50/87] must stay main-thread; ProtocolLib/packet-listeners reading `getReadBuffer()` [148] must see a **filled** buffer. | HIGH | Events stay on main in `sendChunk`. Packet-out interception fires at **flush (post-ready)**, so listeners see the filled buffer; the shell is never encoded while `chunkData==null`. **Top ecosystem verification item (Â§7).** |
| R10 | Batch count / pacing drift. | LOW | `BatchFinished(count)` computed on main, rides FIFO behind its chunks; ACK pacing self-corrects. No flow-control code touched. |
| R11 | Unbounded serialization backlog on join-storm (workers behind netty). | MED | Bounded queue + CallerRuns caps backlog; watchdog bounds latency; pool sized to cores. |

---

## 7. VERIFICATION PLAN

**Headless boot CAN confirm (no players):**
- Boots with `enabled=true`; dedicated pool threads appear named "Victus Async Chunk Serializer #n" (daemon).
- `sendChunk` async branch exercised by an automated fake/bot connection or the server's own initial chunk gen path; assert no `IllegalStateException` from guard [127], no CME, no ThreadingDetector throw in logs.
- **Byte-for-byte equivalence harness (the key correctness gate):** for a fixed seed region, serialize each chunk **both** ways (vanilla sync path [84] and snapshot worker path) and assert identical `byte[] buffer` (anti-xray off) â€” proves the snapshot serializer is a faithful reimplementation. With anti-xray on, assert obfuscated output matches the sync anti-xray output for a static (non-mutating) world.
- Watchdog unit test: submit a task that never completes â†’ assert force-rebuild fires and shell reaches ready before deadline+margin.
- Clean shutdown: assert no "async task after plugin disable" warnings, pool terminates.

**Needs gameplay / soak (cannot be proven headless):**
- **Join-storm** (script N bots joining simultaneously) and **elytra-fly / `/tp`** across fresh terrain â€” the target scenarios. Watch for ghost/disappearing blocks (R3), desync, "Loading terrain" hangs (R1).
- Redstone-heavy / fast-block-change area sent during load (R2/R3 window).
- Anti-xray on, with a client that attempts x-ray, to confirm no ore leak (R8).
- ViaVersion/ViaBackwards + ProtocolLib in the pipeline (R9) â€” legacy clients + packet listeners must render chunks correctly.
- Geyser/Floodgate mobile movement soak with async send enabled: verify Bedrock players take the synchronous bypass and can walk continuously while chunks load (no freeze/correction cycle); Java players should continue through the async path.

**Prove the MSPT win:** spark sampler, main-thread only, before/after. Expect **self-time on `ClientboundLevelChunkPacketData.extractChunkData` / `LevelChunkSection.write` / `states.write` and `ChunkPacketBlockControllerAntiXray.modifyBlocks` to drop toward zero on the main thread** and reappear on "Victus Async Chunk Serializer" threads. Net main-thread cost should be dominated by the new `section.copy()` in `capture()` (verify it is materially cheaper than the removed encode+obfuscate â€” the Amdahl honesty check). Measure MSPT during a scripted join-storm with `/tps` + spark `tickmonitor`.

**Prove no desync:** the byte-equivalence harness (above) + a "place block during send" integration test asserting the block-update packet flushes *after* its chunk (packet-capture on a test client), demonstrating the ready-gate ordering holds.

**Rollout:** default OFF â†’ adversarial review (focus R1/R2/R3/R8 + the 4 `[P]` verify items) â†’ enable on one non-prod profile â†’ join-storm soak â†’ graduate per-profile. Fallback is always the untouched vanilla `sendChunk` [84].