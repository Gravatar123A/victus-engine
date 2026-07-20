# Async Pathfinding — Design Brief (v1)

_Produced by the asyncpath-understand research workflow (5 agents, source-verified vs MC 26.2), 2026-07-20._
_Implementation note: v1 simplifies the evaluator pool to synchronized(finder) serialization; default OFF pending a gameplay soak (see §7)._

I have verified everything I need against the actual 26.2 source. Key confirmations: `Path` is `public final class` (Path.java:14) with `final` `nodes`/`target`/`distToTarget`/`reached`; the seam funnels through `createPath(...)` at PathNavigation.java:156-205; `moveTo(Entity)` (L220-238) increments `pathfindFailures` on a null/failed path; `moveTo(Path)` (L240-264) early-returns `false` via `isDone()` (L250) when nodes are empty; `mob.onPathfindingStart()`/`onPathfindingDone()` fire inside the evaluator (WalkNodeEvaluator L41/L46); `getPathfindingMalus` is a live `Object2FloatMap` read hit ~10Ã— per node expansion. Here is the design brief.

---

# VICTUS ENGINE â€” ASYNC PATHFINDING v1 DESIGN BRIEF
Target: PaperMC hard-fork, Minecraft 26.2, unobfuscated Mojang names. Mandate: correctness/stability > speed. Model chosen: the **promise-`AsyncPath`** design (Airplaneâ†’Pufferfishâ†’Petalâ†’Kaiijuâ†’Leaf lineage), which is the only one of the four candidate models that is *correctness-safe by construction* â€” see Â§0.

## Â§0. THE LOAD-BEARING DECISION (resolves the conflict between the four reports)
Reports A/B use the **promise-`AsyncPath`** model (return a non-null lazy `Path` now, compute inline if the main thread ever needs it before the worker finishes). Reports C/D lean toward a **`PendingPath` + return-old-path** model with a full mob-parameter snapshot refactor.

**We adopt the promise-`AsyncPath` model as the backbone**, for one decisive reason: its **inline-compute-on-access safety net**. Every `Path` accessor on `AsyncPath` first calls `process()`, which â€” if the result is not ready â€” computes the A\* *synchronously on the calling thread under `synchronized(finder)`*. Consequence: **any consumer we forget to gate, any plugin that calls `getPath()`/reads nodes, any brain behavior, degrades to exactly today's synchronous behavior â€” never garbage, never a crash.** This is the property that satisfies "a subtle race that crashes intermittently is unacceptable": missing a gate costs performance, not correctness. The `PendingPath` model has the opposite failure mode â€” a missed call-site returns a stale/empty path silently.

We then layer the **Victus safety delta** that Leaf omits and reports B/C/D prove necessary:
1. Hoist `mob.onPathfindingStart()`/`onPathfindingDone()` to the main thread (they mutate the live mob â€” H2).
2. Neutralize off-thread `getBlockEntity()` (the one true crash class â€” H4).
3. Wrap the worker A\* in `try/catch(Throwable)` â†’ discard async result â†’ inline sync recompute (never kill a worker thread, never crash the server).
4. Daemon threads + explicit executor shutdown on `stopServer()`.
5. `CALLER_RUNS` reject policy default (degrade to sync on saturation, don't burst-drain onto main).
6. Pooled, thread-confined `NodeEvaluator` + `ThreadLocal` A\* scratch (so worker never touches the per-nav instance the main thread may touch).

**On the mob-param snapshot (Report D Â§4):** verified `getPathfindingMalus` is a live `Object2FloatMap` read (WalkNodeEvaluator L106/112/126/242/345/359/396-414). Torn 64-bit reads are non-issues on HotSpot x86-64/ARM64; immutable-ref reads (AABB) are atomic; the *only* residual is a fastutil map resize racing a rare mid-tick `setPathfindingMalus`. That is a real but low-probability window, and it is fully absorbed by delta-item #3 (catch-all â†’ sync fallback). Therefore v1 **accepts live-mob reads (Leaf-proven for years)** and defers the full evaluator `params.` refactor to v2. A cheap interim hardening (snapshot just the malus table + scalar flags) is offered as v1.5 if soak reveals any malus contention.

---

## Â§1. V1 SCOPE DECISION

**Navigations offloaded in v1 (default ON):**
- **`GroundPathNavigation` (`WalkNodeEvaluator`) â€” YES, the only default-on target.** This is ~all of the pathfinding MSPT (zombies, skeletons, villagers-via-brain, animals, piglins, raids). Its `createPath(BlockPos,Entity,int)` override prologue (`getChunkNow` + `findSurfacePosition`) stays main-thread; only `PathFinder.findPath` (PathNavigation.java:196) goes async. Its `trimPath()` override (reads `canSeeSky`/live level) runs in the main-thread completion, unchanged.

**Deferred to v1.1 (ships behind its own sub-flag, default OFF until audited):**
- **`FlyingPathNavigation` (`FlyNodeEvaluator`).** Same mechanism, but `FlyNodeEvaluator` was **not** in the extracted sources â€” it must be audited for the same instance-state-reuse pattern before enabling, and its overridden `tick()` needs its own `isProcessed()` gate. Unverified = not shipped.

**Deferred to v2 (explicitly OUT of v1):**
- **`AmphibiousPathNavigation` / `WaterBoundPathNavigation` (swim/amphibious).** This is the canonical async regression (Pufferfish #45, Petal #12 â€” "mobs spin in place in water"). Fewer mobs, cheaper paths, highest risk. Force sync in v1.
- Full immutable **mob-param snapshot + pooled stateless-per-task evaluators** (Report D Â§4 robustness upgrade).
- Deep-copying palettes/block-entities into `PathNavigationRegion` (H4 hard fix; v1 uses the null-guard instead).
- Any determinism guarantees.

**Force SYNC always, regardless of config (the fallback is always-correct vanilla `createPath`):**
- `WallClimberNavigation` (spiders) â€” overrides `createPath` and caches `pathToPosition` with bespoke state; not worth the race surface.
- **Bukkit `CraftPathfinder#findPath(Location)`** and any API entry expecting a populated `Path` return synchronously (hard plugin contract).
- Any call **not on the main thread** (Folia region threads, async chunk callbacks).
- Any `PathNavigation` subclass we don't recognize as vanilla (unknown plugin overrides) â€” conservative default.
- **Under a Folia/regionized profile: async globally OFF** (the shallow snapshot's "stale-but-valid" guarantee depends on single-owner ticking; a global worker pool reading another region's chunks violates it â€” Report B Â§4).
- Queue-full rejection â†’ `CALLER_RUNS` (compute this one inline on main).

**Default posture:** `asyncPathfinding=true` (already the ResolvedConfig default) for `standard`/`performance` profiles, **Ground-only**. A `strict`/`vanilla-parity` profile and the Folia profile force it OFF. Recommend a **soak on DE-1 before wide fleet rollout** (Â§7); a one-line kill-switch (`asyncPathfinding=false`) reverts to stock synchronous behavior with zero code path change.

---

## Â§2. ARCHITECTURE

### 2.1 Executor (`cloud.victus.engine.path.VictusAsyncPathProcessor`)
- **Type:** one static `ThreadPoolExecutor` per server (`@Nullable`, null when disabled).
- **Sizing:** `corePoolSize = 1`; `maximumPoolSize = resolvedMaxThreads`. `max-threads=0` â†’ `Math.max(Runtime.availableProcessors() / 3, 1)` (Victus uses cores/3, not Leaf's cores/4 â€” DE-1 is a dedicated game node; still bounded). Forced to `0`/disabled when `asyncPathfinding=false`.
- **Keep-alive:** 60 s.
- **Queue:** **bounded** `LinkedBlockingQueue<Runnable>(resolvedQueueSize)`; `queue-size=0` â†’ `maxThreads * 256`.
- **ThreadFactory:** Guava `ThreadFactoryBuilder`, name `"Victus Async Pathfinding #%d"`, **`setDaemon(true)`** (Victus fix â€” Leaf leaves them non-daemon), priority `Thread.NORM_PRIORITY - 2` (=3, below main), uncaught handler `net.minecraft.util.Util::onThreadException`.
- **Reject policy:** custom `RejectedExecutionHandler`. Default **`CALLER_RUNS`** on typical (<12-core) game nodes (run the rejected task on the caller = degrade to sync). `FLUSH_ALL` (drain+run on caller) only opt-in for `cores>=12 && queueSize<512`. Throttled (30 s) saturation warning.
- **Per-task watchdog:** submit via `CompletableFuture.runAsync(task, EXECUTOR).orTimeout(60, SECONDS).exceptionally(logRateLimited)` â€” no pathological path can hang a worker forever.
- **Lifecycle:** created in `VictusEngine.init()` (already resolves `ResolvedConfig`) when `asyncPathfinding` enabled. **Shutdown on `MinecraftServer.stopServer()`** (Â§3): `executor.shutdown()` â†’ `awaitTermination(5s)` â†’ `shutdownNow()`; daemon flag guarantees no lingering threads on `/reload` or restart.

### 2.2 Async flow from `createPath`
`PathNavigation.createPath(...)` (L156-205) stays split exactly at the report-verified seam:

- **MAIN, unchanged (L158-195):** empty/`getMinY`/`canUpdatePath` guards (L158-168); the applied-path early-return (L170); the **`EntityPathfindEvent` loop (L174-190)**; `profiler.push` (move to wrap only the sync submit); build `PathNavigationRegion region` (L195).
- **MAIN, new â€” dedupe guard** (before submitting, complementing L170): if `this.path instanceof AsyncPath ap && !ap.isProcessed() && ap.hasSameProcessingPositions(targets)` â†’ `return this.path` (no second job for the same in-flight request; protects the shared `PathFinder` and prevents per-tick queue flooding).
- **MAIN, new â€” hoist mob callback:** call `this.mob.onPathfindingStart()` here (H2), and stamp `this.pathRequestGeneration++`.
- **WORKER (was L196):** `this.pathFinder.findPath(region, mob, targets, maxPathLength, reachRange, maxVisitedNodesMultiplier)` â€” but when async, `PathFinder.findPath` returns `new AsyncPath(finder, targets, gen, pathFn)` immediately, whose constructor enqueues `pathFn` onto the executor. `pathFn` checks out a pooled `NodeEvaluator`, runs `prepare(context-only)` + `getStart()` + traversal + `done(context-only)` using `HEAP_LOCAL`/`NEIGHBORS_LOCAL` `ThreadLocal` scratch, then returns the real `Path` (or null â†’ treated as empty/no-path).
- **MAIN, deferred application:** the finished `Path` lands via the `isProcessed()` gates + a completion callback (see 2.3). `onPathfindingDone()` is called on main in that callback.

### 2.3 Deferred-application mechanism (exact fields set)
Application is **pull-based** via `isProcessed()` gates (no global completion queue). `AsyncPath.isProcessed()` returns true once `ret` has arrived (base `Path.isProcessed()` returns `true`, so all vanilla paths are always "processed").

- **`PathNavigation.tick()` (L270):** insert at the very top, before `this.tick++` â€” `if (this.path != null && !this.path.isProcessed()) return;`. While pending, the mob follows its *previous* path or idles ~1 tick; no accessor is touched (so no premature inline compute).
- **`followThePath()` (L299):** `if (!this.path.isProcessed()) return;` at top.
- **`moveTo(@Nullable Path, speed)` (L240):** patch so that when `newPath` is an unprocessed `AsyncPath`: set `this.path = newPath` and **`return true` optimistically**, skipping the `isDone()` early-return (L250), `trimPath()` (L254) and `getNodeCount()<=0` check (L255). This is what prevents `moveTo(Entity)` (L228) from seeing a false failure and spuriously incrementing `pathfindFailures` (L233) â†’ no relocation of the failure counter is needed (the promise model returns non-null immediately, unlike the PendingPath model).
- **Completion callback** registered in `createPath` via `AsyncPathProcessor.awaitProcessing(asyncPath, gen, processed -> { â€¦ })`, which fires immediately if already processed else on the main thread when `complete()` runs. Body replicates L198-204:
  - guard: `if (processed != this.path || gen != this.pathRequestGeneration) return;` (stale/superseded â€” discard);
  - guard: `if (!mob.isAlive() || mob.isRemoved() || mob.level() != this.level) return;`
  - `this.mob.onPathfindingDone();` (H2, main);
  - if `processed.getTarget() != null`: `this.targetPos = processed.getTarget()` (field L52); `this.reachRange = <captured reachRange>` (L53); `this.resetStuckTimeout()` (L385 â†’ clears `timeoutCachedNode` L44 / `timeoutTimer` L45 / `timeoutLimit` L47 / `isStuck` L56).
  - The `speedModifier` (L40), `lastStuckCheck` (L42), `lastStuckCheckPos` (L43) are set the next tick when `followThePath`/`moveTo` runs against the now-processed path (they already re-run each tick), so no extra bookkeeping is required.
- **`recomputePath()` (L101-110):** in async mode **do not** execute `this.path = null` (L105) before recompute (avoids the visible stutter); submit and let completion swap in place. Set `this.timeLastRecompute` (L107) and clear `hasDelayedRecomputation` at submit time to preserve the 20-gametick throttle cadence.

### 2.4 Request versioning & cancellation (prevent stale application)
Two mechanisms, both cheap:
1. **Identity guard (primary, Leaf-proven):** the completion callback no-ops unless `processed == this.path`. `stop()` (L400) sets `this.path=null`; a superseding `moveTo` replaces `this.path`; either way the stale task's callback discards its result. The old task still runs to completion on the worker (logical, not hard, cancel â€” a little wasted CPU, zero correctness risk).
2. **Generation stamp (defense-in-depth for removal/world-unload):** `int pathRequestGeneration` on the navigation, bumped at each submit and in `stop()`. Completion also checks `gen == this.pathRequestGeneration`. This covers the window where `this.path` was reassigned to a *new* `AsyncPath` that happens to be `==`-distinct but the mob was removed/unloaded between submit and apply.

No hard `Future.cancel`; no `.get()` blocking on the main thread ever (only `AsyncPath.process()`'s inline compute, which is bounded work equal to a sync path).

---

## Â§3. PATCH PLAN (exact files / methods / seams)

**Minecraft layer (patched vanilla classes):**

1. **`net/minecraft/world/level/pathfinder/Path.java`** â€” de-`final` the class (L14: `public final class Path` â†’ `public class Path`); de-`final` `nodes` (L16), `target` (L19), `distToTarget` (L20), `reached` (L21) so `AsyncPath.complete()` can copy results in. Add `public boolean isProcessed() { return true; }`. (These are the minimal edits enabling subclassing; all vanilla behavior preserved.)

2. **`net/minecraft/world/level/pathfinder/PathFinder.java`**
   - Add ctor `PathFinder(NodeEvaluator, int maxVisitedNodes, NodeEvaluatorGenerator)` (generator non-null â‡’ async-capable).
   - In `findPath(...)` (L43-67): when async is enabled for this finder and on main thread, **return `new AsyncPath(this, targets, gen, finder -> realFindPath(...))`** instead of running L51-66 inline. The lambda body = the current L51-66 but using a **checked-out pooled evaluator** and `HEAP_LOCAL`/`NEIGHBORS_LOCAL` `ThreadLocal` scratch instead of `this.openSet` (L27) / `this.neighbors` (L24).
   - Add an async `findPath` overload taking `(localOpenSet, localNeighbors, localNodeEvaluator, â€¦)` so the private inner `findPath` (L69-159) never touches `this.openSet`/`this.neighbors` off-thread.
   - Tighten the inner result to non-null for the async path (empty-targets/`from==null` already early-return at L54; if `from==null` the lambda returns null and `AsyncPath` marks itself processed-empty = `isDone()`).
   - Snapshot `captureDebug.getAsBoolean()` (L81) on main at dispatch, pass in (H5).

3. **`net/minecraft/world/level/pathfinder/WalkNodeEvaluator.java`** â€” split the mob-mutating callbacks out of the async path: `prepare()` must **not** call `entity.onPathfindingStart()` (L41) when invoked from a worker (hoisted to main in `createPath`); `done()` must **not** call `this.mob.onPathfindingDone()` (L46) (hoisted to main completion). Context/`nodes` setup (`currentContext = new PathfindingContext(level, entity)`, NodeEvaluator L27) stays in the worker. `invalidateCache()`/`clearNodes()` run on checkout/return so `pathTypesByPosCacheByMob` (L377) and `collisionCache` (L373) never leak across tasks.

4. **`net/minecraft/world/level/PathNavigationRegion.java`** â€” harden `getBlockEntity(pos)` (L121-124): when called off the main thread, **return `null`** (or a pre-captured snapshot) instead of hitting the live `LevelChunk` HashMap. This alone removes the only crash-class read. Add a static-shape fallback for BE-dependent shapes (shulker box â†’ `Shapes.block()`, piston moving) when off-thread. Keep `getBlockStateIfLoaded`/`getFluidState` live (lock-free, benign staleness).

5. **`net/minecraft/world/entity/ai/navigation/PathNavigation.java`**
   - `createPath(...)` (L156-205): insert the dedupe guard, the `onPathfindingStart()` hoist, the generation bump, and route L196 through the async helper (see 2.2 + pseudocode below).
   - `tick()` (L270): top-of-method `isProcessed()` gate.
   - `followThePath()` (L299): top gate.
   - `moveTo(Path,speed)` (L240): optimistic-true branch for unprocessed `AsyncPath`.
   - `recomputePath()` (L101-110): async no-null-first branch.
   - `stop()` (L400): after `this.path=null`, bump `pathRequestGeneration` to orphan any in-flight callback.

6. **`FlyingPathNavigation.java`** (v1.1) â€” its own `tick()` gate.

7. **`net/minecraft/server/MinecraftServer` `stopServer()`** â€” call `VictusAsyncPathProcessor.shutdown()` after the final tick.

**Paper-server layer (new, `cloud.victus.engine.path.*`, VictusDab pattern â€” a helper read from the patched minecraft methods):**
- `AsyncPath extends Path` â€” `volatile @Nullable Path ret`, `Function<PathFinder,Path> pathFn`, `PathFinder finder`, `Set<BlockPos> positions`, `int generation`, `List<Consumer<Path>> postProcessing`. Ctor enqueues via processor. `process()` (readyâ†’return; `ret!=null`â†’`complete(ret)`; else inline compute under `synchronized(finder)`), `complete(Path)` (copy nodes/target/distToTarget/reached, run callbacks), `isProcessed()`, `schedulePostProcessing`, `hasSameProcessingPositions(Set)`. Override every node-data accessor (`getNodeCount`, `getNode`, `getNextNode`, `getNextNodePos`, `getNextEntityPos`, `getEndNode`, `advance`, `isDone`, `canReach`, `getDistToTarget`, `getTarget`, `sameAs`) to `process()` first.
- `VictusAsyncPathProcessor` â€” the executor (2.1) + `queue(Runnable)`, `awaitProcessing(AsyncPath, gen, Consumer<Path>)`, `init(ResolvedConfig)`, `shutdown()`.
- `NodeEvaluatorCache` (global `synchronized` checkout pool keyed by packed feature int), `NodeEvaluatorGenerator`, `NodeEvaluatorFeatures.fromNodeEvaluator(...)`, plus `HEAP_LOCAL = ThreadLocal.withInitial(BinaryHeap::new)`, `NEIGHBORS_LOCAL = ThreadLocal.withInitial(() -> new Node[32])`.

**victus-core:** add resolved fields (Â§5).

### Intended Java shape â€” async `createPath` seam (main thread)
```java
// ... L158-190 unchanged: guards, applied-path reuse (L170), EntityPathfindEvent loop ...
BlockPos fromPos = above ? mob.blockPosition().above() : mob.blockPosition();
int radius = (int)(maxPathLength + radiusOffset);
PathNavigationRegion region = new PathNavigationRegion(this.level, fromPos.offset(-radius,-radius,-radius),
                                                                    fromPos.offset(radius,radius,radius));

if (!VictusAsyncPath.shouldRunAsync(this)) {                 // disabled / WallClimber / off-main / Folia / Bukkit API / non-vanilla
    Path p = this.pathFinder.findPath(region, mob, targets, maxPathLength, reachRange, this.maxVisitedNodesMultiplier); // vanilla L196-204
    if (p != null && p.getTarget() != null) { this.targetPos = p.getTarget(); this.reachRange = reachRange; this.resetStuckTimeout(); }
    return p;
}

// async: dedupe an in-flight identical request
if (this.path instanceof AsyncPath ap && !ap.isProcessed() && ap.hasSameProcessingPositions(targets)) return this.path;

final int gen = ++this.pathRequestGeneration;
this.mob.onPathfindingStart();                               // H2: hoisted to main
final Set<BlockPos> targetsCopy = Set.copyOf(targets);      // defensive: main may mutate the source set
final int capturedReach = reachRange;
AsyncPath async = (AsyncPath) this.pathFinder.findPath(      // returns AsyncPath immediately; enqueues worker A*
        region, mob, targetsCopy, maxPathLength, reachRange, this.maxVisitedNodesMultiplier);

VictusAsyncPathProcessor.awaitProcessing(async, gen, processed -> {   // fires on MAIN when ready (or now if ready)
    if (processed != this.path || gen != this.pathRequestGeneration) return;   // superseded / stopped
    if (!mob.isAlive() || mob.isRemoved() || mob.level() != this.level) return; // R1
    this.mob.onPathfindingDone();                            // H2: hoisted to main
    if (processed.getTarget() != null) {
        this.targetPos = processed.getTarget(); this.reachRange = capturedReach; this.resetStuckTimeout();
    }
});
return async;                                                // non-null: moveTo() boolean contract preserved
```

### Intended Java shape â€” `tick()` pickup (main thread)
```java
public void tick() {
    if (this.path != null && !this.path.isProcessed()) return;   // pending: idle/follow-old, no accessor touched
    this.tick++;
    if (this.hasDelayedRecomputation) this.recomputePath();
    if (!this.isDone()) {                                         // isProcessed() true here â†’ accessors safe
        if (this.canUpdatePath()) this.followThePath();
        else if (this.path != null && !this.path.isDone()) { /* L280-284 unchanged */ }
        if (!this.isDone()) {
            Vec3 t = this.path.getNextEntityPos(this.mob);
            this.mob.getMoveControl().setWantedPosition(t.x, this.getGroundY(t), t.z, this.speedModifier);
        }
    }
}
```

---

## Â§4. THREAD-SAFETY RECIPE

**Captured / done ON MAIN (before dispatch):**
- `EntityPathfindEvent` loop (L174-190) â€” event bus + plugin handlers.
- `PathNavigationRegion` construction (L195, `getChunkNow`) and the `GroundPathNavigation` prologue (`findSurfacePosition`).
- `mob.onPathfindingStart()` (H2) and `captureDebug` boolean (H5).
- Defensive `Set.copyOf(targets)` (main may mutate the source after the event copies it at L181).
- Generation bump.

**Worker-touchable (read-only, snapshot-backed, thread-confined):**
- `PathFinder.findPath` inner loop: `getBlockStateIfLoaded`/`getFluidState` (lock-free `PalettedContainer` reads â†’ immutable interned `BlockState`; worst case stale-but-valid = slightly wrong path, not a crash), cached immutable `VoxelShape`s.
- Live-mob scalar/immutable-ref reads (`getX/Y/Z`, `blockPosition`, `getBoundingBox`, `getBbWidth/Height`, `maxUpStep`, `getMaxFallDistance`, `onGround`, `isInWater`, `canFloat`, `getPathfindingMalus`) â€” accepted (Leaf-proven; benign on 64-bit JVMs; the sole mutable-collection read, the malus `Object2FloatMap`, is covered by the catch-all fallback).
- **Pooled `NodeEvaluator`** (checked out under the `NodeEvaluatorCache` monitor â€” never shared across threads) + **`HEAP_LOCAL`/`NEIGHBORS_LOCAL`** scratch. The per-nav `this.pathFinder.openSet`/`neighbors` and `this.nodeEvaluator` are never used off-thread.
- `PathNavigationRegion.getEntityCollisions()` already returns `List.of()` â†’ no live-entity iteration off-thread (the single worst hazard is structurally absent).

**Must NOT run off-thread (neutralized):**
- `getBlockEntity()` â†’ returns null off-thread + static-shape fallback for shulker/piston (H4, only crash class).
- `mob.onPathfindingStart/onPathfindingDone` (H2) â€” hoisted to main.
- `Profiler.get()` â€” thread-local; do not straddle `push/pop` across the boundary (wrap only the sync submit or drop for async).

**Re-validated ON MAIN (before applying):**
- Completion guards: `processed == this.path`, `gen == pathRequestGeneration`, `mob.isAlive() && !isRemoved() && level unchanged`.
- `moveTo(Path)` â†’ `trimPath()` (L254/L408-422) re-reads the **live** world on main (`level.getBlockState`, L413) â€” the built-in correctness backstop against the â‰¤1-tick stale snapshot.
- `mob.onPathfindingDone()` on main; set `targetPos`/`reachRange`/`resetStuckTimeout`.

**Exception / fallback handling (never crash the server):**
- Worker A\* wrapped in `try/catch(Throwable)`: on any throw (CME/`ThreadingDetector`/AIOOBE/NPE) â€” log rate-limited (mob type + pos), discard the async result; the next accessor's `process()` sees `ret==null` and computes inline synchronously (self-heals to vanilla). Pool uses a self-healing factory (`ThreadPoolExecutor` replaces dead workers).
- `orTimeout(60s)` watchdog per task.
- Queue full â†’ `CALLER_RUNS` (sync on main). Never drop silently, never block.

---

## Â§5. CONFIG

**`victus.yml` (section `optimizations.async-pathfinding`):**
```yaml
optimizations:
  async-pathfinding:
    enabled: true            # maps ResolvedConfig.asyncPathfinding (already exists, default true)
    max-threads: 0           # 0 -> max(cores/3, 1); forced 0 when disabled
    queue-size: 0            # 0 -> max-threads * 256 (bounded)
    keepalive-seconds: 60
    reject-policy: CALLER_RUNS   # CALLER_RUNS | FLUSH_ALL
    navigations:
      ground: true           # v1 default-on
      flying: false          # v1.1
      water: false           # v2 (amphibious/waterbound)
```

**victus-core `ResolvedConfig` fields (add to existing `boolean asyncPathfinding`):**
```java
boolean asyncPathfinding;              // existing, default true
int     asyncPathfindingMaxThreads;    // default 0 -> resolve max(cores/3,1)
int     asyncPathfindingQueueSize;     // default 0 -> maxThreads*256
int     asyncPathfindingKeepaliveSeconds; // 60
String  asyncPathfindingRejectPolicy;  // "CALLER_RUNS" (default) | "FLUSH_ALL"
boolean asyncPathfindingGround;        // true
boolean asyncPathfindingFlying;        // false (v1.1)
boolean asyncPathfindingWater;         // false (v2)
```

**Per-profile:**
- `standard` / `performance`: `enabled=true`, ground-only, `CALLER_RUNS`.
- `strict` / `vanilla-parity`: `enabled=false` (pure synchronous).
- `folia` / regionized: `enabled=false` (forced; snapshot-ownership invariant breaks â€” Report B Â§4).
- Resolve `max-threads`/`queue-size`/`reject-policy` in `VictusEngine.init()` from `availableProcessors()`; auto-`FLUSH_ALL` only if `cores>=12 && queueSize<512` and the profile opts in.

---

## Â§6. RISK REGISTER (top risks, mitigations baked in)

| ID | Risk | Sev | Baked-in mitigation |
|----|------|-----|---------------------|
| R1 | Off-thread `getBlockEntity()` HashMap race â†’ CME/NPE (only crash class) | S1 | `PathNavigationRegion.getBlockEntity` returns null off-thread + static-shape fallback (Â§3.4). |
| R2 | `onPathfindingStart/Done` mutate live mob off-thread | S1 | Hoisted to main (dispatch / completion). |
| R3 | Two A\* on the same per-nav `PathFinder`/`NodeEvaluator` â†’ scratch corruption | S1 | Pooled evaluator + `ThreadLocal` heap/neighbors; single-in-flight via dedupe guard; `synchronized(finder)` + volatile `ret` double-check. |
| R4 | Stale/superseded result applied after target change, `stop()`, death, or unload | S2 | Identity guard (`processed==this.path`) + generation stamp + alive/level re-validation in completion. |
| R5 | Any throwable on worker kills thread / crashes server | S1 | `try/catch(Throwable)` â†’ discard â†’ inline sync recompute; self-healing factory; `orTimeout(60s)`. |
| R6 | `pathfindFailures` 40-tick lockout fires on "pending" (null-overload) | S2 | Promise model returns non-null `AsyncPath`; `moveTo(Path)` returns true optimistically while unprocessed â†’ counter never spuriously trips. |
| R7 | Queue backpressure under raid/mob-farm spike â†’ OOM or ticks-late paths | S1 | Bounded queue + `CALLER_RUNS` (degrade to sync); per-tick dedupe caps ~1 task/mob; synergy with **VictusDab** distance throttle cuts request volume at source. |
| R8 | Live palette-resize torn read â†’ wrong path (rare AIOOBE) | S1(rare)/S2 | Lock-free read = stale-but-valid; AIOOBE caught by R5; `trimPath` re-validates on main. |
| R9 | Recompute nulls path â†’ visible stutter | S3 | Async `recomputePath` does not null `this.path`; double-buffered swap on completion. |
| R10 | 1-tick reaction latency; interaction with DAB (mob looks frozen) | S3 | Documented/expected; DAB-blacklist pathfinding-heavy mob farms; latency-sensitive goals stay effectively sync via inline `process()` when they read nodes same-tick. |
| R11 | Shutdown/reload leaks non-daemon threads or reads freed chunks | S1 | Daemon threads + `stopServer()` shutdown (drainâ†’`shutdownNow`); world-changed guard on apply. |
| R12 | Live-mob malus `Object2FloatMap` resize during rare mid-tick `setPathfindingMalus` | S2(rare) | Accept in v1 (Leaf-proven) under R5 catch-all; v1.5 snapshot the malus table if soak shows contention; v2 full param snapshot. |
| R13 | Folia/regionized cross-region snapshot reads | S1 | Async force-OFF under Folia profile. |
| R14 | Missed consumer/plugin call-site reads unprocessed path | â€” | **Non-issue by construction:** `AsyncPath` accessors `process()` â†’ inline sync compute = vanilla behavior. |

---

## Â§7. VERIFICATION PLAN (DE-1 node)

**What a HEADLESS BOOT can confirm (CI gate, no players):**
- Patch applies + compiles against unobfuscated 26.2; `Path` subclassing/`AsyncPath` links.
- `VictusEngine.init()` resolves config; executor starts with exactly `resolvedMaxThreads` **daemon** threads named `Victus Async Pathfinding #N` (assert via thread dump), priority 3.
- **Shutdown hygiene:** `stopServer()` and `/reload` leave **zero** `Victus Async Pathfinding` threads (thread-dump diff before/after) â€” proves R11.
- Synthetic path test: `/summon zombie` + set a target 30 blocks away via a test harness/command; assert the mob reaches it; assert `AsyncPath.isProcessed()` flips and `onPathfindingStart/Done` fired on the main thread (instrument a main-thread assertion / `AsyncCatcher`-style check that no mob write occurs off the pathfinding threads).
- Kill-switch: `enabled=false` â†’ identical code path to stock (byte-for-byte path results on a fixed seed).
- Unit-level: `NodeEvaluatorCache` checkout/return leaves no evaluator loaned; `HEAP_LOCAL`/`NEIGHBORS_LOCAL` isolation under a 4-thread hammer test on a static region snapshot.

**What NEEDS GAMEPLAY / load (soak on DE-1 before fleet rollout):**
- **Stress:** spawn 2-5k zombies/skeletons + trigger a pillager raid; measure the engine's **per-subsystem "pathfinding" MSPT** (already instrumented) async-on vs async-off â€” expect main-thread pathfind MSPT to drop toward ~0 and total MSPT to fall; watch worker pool depth + `CALLER_RUNS` fallback rate (a high fallback rate = raise `max-threads`).
- **Backpressure:** mob-farm + raid simultaneously to saturate the bounded queue; confirm graceful `CALLER_RUNS` degradation, no OOM, no ticks-late freeze cascade (R7).
- **Behavior correctness (the async regressions):** ground mobs through **doors/trapdoors** (DoorInteractGoal), **cauldrons** (`trimPath` path, L414), fences, over ledges; **villager brain** (MoveToTargetSink / AcquirePoi / bed sensor) navigation; melee/flee/tempt goals react within 1 tick. Diff paths async-on vs async-off across a scripted mob-movement scenario (allow â‰¤1-tick divergence; flag any stuck/spinning mob).
- **DAB interaction:** confirm distant DAB-throttled mobs plus async don't compound into visible freezes; verify DAB blacklist on pathfinding farms.
- **Stability soak:** 24-48 h with players + `spark`/`--async-profiler`; **zero** off-thread AsyncCatcher trips, zero pathfinding-thread `Throwable` in logs (R5 counter should stay ~0), no mob-behavior complaints.

**Explicitly still-manual / out-of-headless:** swim/amphibious regression (deferred to v2, so not needed for v1 sign-off), plugin compat with Citizens/MythicMobs/Sentinel driving their own navigation (verify they receive a populated `Path` via the inline `process()` path â€” spot-check on the live plugin set before rollout).

**Sign-off bar for flipping default-on fleet-wide:** CI headless gate green + 48 h DE-1 soak with zero off-thread writes, zero worker throwables, MSPT win demonstrated, and door/brain/goal behavior parity confirmed.

---

**Files verified this session (all `E:/victus-tmp/apath-src/`):** `net/minecraft/world/entity/ai/navigation/PathNavigation.java` (seam L156-205, tick L270, moveTo L212-264, recompute L101-110, stop L400), `net/minecraft/world/level/pathfinder/PathFinder.java` (findPath L43-67, shared scratch L24/L27), `net/minecraft/world/level/pathfinder/Path.java` (final class L14, final fields L16-21, isDone L38, sameAs L100), plus targeted greps confirming `getPathfindingMalus` = live `Object2FloatMap` reads (WalkNodeEvaluator L106-414) and `onPathfindingStart/Done` at WalkNodeEvaluator L41/L46, `currentContext` lifecycle at NodeEvaluator L27/L36.
---

## §8. Implementation findings (2026-07-20, during core build — corrections to the brief)

While starting the core, source-level checks turned up facts that refine/correct the brief:

1. **`onPathfindingStart()`/`onPathfindingDone()` are EMPTY in base `Mob`** (Mob.java:205-209) — only `Sniffer` overrides them. So the brief's "hoist mob callbacks to main" (H2) machinery is unnecessary; instead **force-sync any mob overriding these (Sniffer)**. Removes the only off-thread mob-mutation with far less code.
2. **The ground pathfinder path does NOT call `getBlockEntity()`** (no hits in WalkNodeEvaluator/NodeEvaluator/PathFinder). So the brief's H4/R1 "off-thread getBlockEntity crash class" is **not triggered by ground navigation in 26.2** — the `PathNavigationRegion.getBlockEntity` guard is not needed for v1-ground (keep it in mind for flying/water later).
3. **NEW HAZARD the brief MISSED — `PathTypeCache` (S1-ish, correctness):** `PathfindingContext` (built in `NodeEvaluator.prepare`) grabs the **shared per-`ServerLevel` `PathTypeCache`** (`serverLevel.getPathTypeCache()`) and `getPathTypeFromState` → `cache.getOrCompute()` **reads and writes** it. It is a plain fixed-size `long[4096]`/`PathType[4096]` with **no synchronization**. Concurrent async workers (different mobs, same world) racing `compute()` can leave a **torn entry** (`positions[i]=keyB` but `pathTypes[i]=typeA`) → a later lookup returns the WRONG `PathType` → a mob paths into a hazard / avoids safe ground. Memory-safe (no crash, arrays don't corrupt) and self-correcting (paths recompute; cache overwrites), but a real behavior race.
   - **Required v1 mitigation:** give each async worker a **thread-local `PathTypeCache`** (thread-confined), not the shared level cache. Patch `PathfindingContext` ctor: when on an async pathfinding thread, use `VictusAsyncPath.threadLocalPathTypeCache()` instead of `serverLevel.getPathTypeCache()`. Main-thread pathfinding keeps using the shared cache. This eliminates the race with a small extra patch.
   - **Lesson:** shared per-level mutable caches are the real async hazard class in modern MC (added ~1.20.2, post-dating Airplane/Petal). The core build must hunt for others (e.g. any other `serverLevel.getX()Cache()` touched during `findPath`) before enabling.

**Consequence for scope:** the core needs the thread-local `PathTypeCache` patch on top of the brief. Because a first pass already surfaced a brief-missed shared-state race, the core will be implemented deliberately (with an adversarial concurrency review specifically hunting shared mutable state) and remains **default-OFF pending a gameplay soak** — never rushed.
