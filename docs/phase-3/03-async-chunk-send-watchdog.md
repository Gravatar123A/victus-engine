# Async Chunk Send — Anti-Stall Watchdog (ready-to-apply)

**Status:** designed + reviewed, NOT YET built (blocked on the dev-box commit-memory wall —
a fresh Paper decompile needs a hard 4 GB and C: is full so the page file can't grow; fix =
add a page file on E:, then `applyAllPatches`). Apply the edits below via the normal flow:
`./gradlew applyAllPatches` → edit the working-tree files → `./gradlew rebuildMinecraftSourcePatches`
+ `rebuildPaperServerFilePatches` → build jar → deploy → bot-soak.

## Problem it closes

Async chunk send reserves a per-player FIFO slot on the main thread (`connection.send(shell)` with
`ready=false`) and a worker later fills `chunkData` + calls `setReady(true)` to release it. Today the
worker task is guarded by a `Throwable` catch + `CallerRunsPolicy` + a main-thread rebuild fallback,
so serialize *exceptions* and pool *saturation* are both covered. The **only** uncovered failure is a
serializer thread that **dies between dequeue and the try block** (OS kill / async `ThreadDeath`): its
`shell` never becomes ready → that player sees a permanent "Loading terrain". Rare, but it must be
impossible before async-send can be a fleet default. The watchdog guarantees every reserved slot is
released within `watchdogMs`.

## Design (single source of truth = one AtomicBoolean per dispatch)

`PendingChunk.settled` is CAS'd false→true by **whoever finishes first** — the worker OR the watchdog.
The winner is the sole caller of `setChunkData`/`setReady`; the loser no-ops. This eliminates the
double-apply race (worker was merely slow, not dead) while still recovering genuine stalls. Because a
normal serialize is ~1 ms and `watchdogMs` defaults to 1500, the watchdog never fires on healthy load —
only on a real stall. A single daemon thread scans a `ConcurrentLinkedQueue` every 250 ms.

### Edit 1 — `ClientboundLevelChunkPacketData.java` (add inside the Victus async block, before `// Victus end`)

```java
    // Victus start - async chunk send anti-stall watchdog
    private static volatile long watchdogMs = 1500L;
    private static final java.util.Queue<PendingChunk> WATCHDOG_QUEUE = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile Thread watchdogThread;

    public static void setWatchdogMs(final long ms) { watchdogMs = ms >= 100L ? ms : 1500L; }

    /** One in-flight dispatch. {@code settled} = the single owner-election flag (worker vs watchdog). */
    private static final class PendingChunk {
        final ClientboundLevelChunkWithLightPacket shell;
        final Snapshot snap;
        final net.minecraft.server.MinecraftServer srv;
        final long deadlineNanos;
        final java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean(false);
        PendingChunk(ClientboundLevelChunkWithLightPacket shell, Snapshot snap,
                     net.minecraft.server.MinecraftServer srv, long deadlineNanos) {
            this.shell = shell; this.snap = snap; this.srv = srv; this.deadlineNanos = deadlineNanos;
        }
    }

    /** Dispatch a chunk for async serialization WITH watchdog protection. Replaces the caller's raw
     *  submitAsync(...) + manual setReady so the settle-guard and stall recovery live in one place. */
    public static void submitAsyncChunk(final ClientboundLevelChunkWithLightPacket shell,
                                        final Snapshot snap,
                                        final net.minecraft.server.MinecraftServer srv) {
        final PendingChunk pc = new PendingChunk(shell, snap, srv, System.nanoTime() + watchdogMs * 1_000_000L);
        WATCHDOG_QUEUE.add(pc);
        ensureWatchdog();
        submitAsync(() -> {
            ClientboundLevelChunkPacketData data = null;
            try { data = fromSnapshot(snap); }
            catch (Throwable t) { org.slf4j.LoggerFactory.getLogger("Victus").warn("async chunk serialize failed; main fallback: " + t); }
            if (data != null) {
                if (pc.settled.compareAndSet(false, true)) { shell.setChunkData(data); shell.setReady(true); }
            } else if (pc.settled.compareAndSet(false, true)) {
                settleOnMain(pc); // serialize threw → guarded main-thread rebuild
            }
        });
    }

    /** Rebuild + release on the main thread; guard already won by the caller. */
    private static void settleOnMain(final PendingChunk pc) {
        try {
            pc.srv.execute(() -> {
                try { pc.shell.setChunkData(fromSnapshot(pc.snap)); }
                catch (Throwable t) { org.slf4j.LoggerFactory.getLogger("Victus").warn("async chunk main-rebuild failed: " + t); }
                pc.shell.setReady(true);
            });
        } catch (Throwable rejected) {
            pc.shell.setReady(true); // server executor gone (shutdown) — release anyway so the FIFO drains
        }
    }

    private static synchronized void ensureWatchdog() {
        if (watchdogThread != null) return;
        Thread wt = new Thread(() -> {
            while (true) {
                try { Thread.sleep(250L); } catch (InterruptedException ie) { return; }
                long now = System.nanoTime();
                for (java.util.Iterator<PendingChunk> it = WATCHDOG_QUEUE.iterator(); it.hasNext();) {
                    PendingChunk pc = it.next();
                    if (pc.settled.get()) { it.remove(); continue; }        // worker already owns it
                    if (now - pc.deadlineNanos >= 0L) {                     // overflow-safe deadline compare
                        if (pc.settled.compareAndSet(false, true)) {
                            org.slf4j.LoggerFactory.getLogger("Victus")
                                .warn("async chunk send: serializer stalled >" + watchdogMs + "ms — main-thread recovery");
                            settleOnMain(pc);
                        }
                        it.remove();
                    }
                }
            }
        }, "Victus Async Chunk Watchdog");
        wt.setDaemon(true);
        watchdogThread = wt;
        wt.start();
    }
    // Victus end
```

### Edit 2 — `PlayerChunkSender.java` (replace the inline `srv` + `submitAsync(() -> {...})` block)

```java
            connection.send(shell); // MAIN: reserve the per-player FIFO slot IN ORDER
            net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.submitAsyncChunk(shell, snap, level.getServer());
```

(Removes the hand-rolled serialize/setReady/fallback lambda — all of it now lives in `submitAsyncChunk`.)

### Edit 3 — `VictusEngine.java` (right after the `configureAsync(...)` call in `init()`)

```java
        net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.setWatchdogMs(config.asyncChunkSendWatchdogMs);
```

## Correctness argument (adversarial)

1. **No double-apply** — exactly one of {worker, watchdog} wins the `settled` CAS; the loser returns.
2. **FIFO order preserved** — the `shell` is `send()`-reserved on main *before* dispatch; only `setReady`
   (worker or watchdog) releases it. The connection's `pendingActions` head-of-line-blocks until then.
3. **Slow ≠ dead** — 1500 ms ≫ a ~1 ms serialize, so healthy workers always win; the watchdog fires only
   on a true stall. Reducing `watchdogMs` toward 100 (floored) only trades a little safety margin.
4. **Shutdown safe** — if `srv.execute` is rejected (server stopping), `settleOnMain` still `setReady`s so
   the FIFO drains; the watchdog thread is a daemon and dies with the JVM.
5. **Memory publish** — `setChunkData` (plain write) then `setReady(true)` (Paper's volatile flag) gives a
   happens-before to the connection flush thread; identical to the existing live path.
6. **Visibility** — `settled` is an `AtomicBoolean`; `deadlineNanos` is final; `System.nanoTime()` is monotonic.

## Verify after build

- `-Dvictus.chunksend.selftest=true` → byte-equivalence PASS (unchanged).
- Bot-soak join + fly → no crash, chunks decode, no "Loading terrain" stall.
- Fault-injection (optional): temporarily throw inside the worker before `fromSnapshot` → confirm the
  watchdog log line appears once and the client still receives the chunk.
