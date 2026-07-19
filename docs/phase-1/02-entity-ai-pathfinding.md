# Phase 1 · Mob AI & pathfinding

## Goal

Make mob AI cheap on mob-heavy worlds **without** changing plugin behavior in the default path, and
without silently altering the mob-timing that technical builders depend on (farm rates, aggro
timing, trade/POI behavior).

Concretely, Victus's delta over Paper 26.1 is three cooperating optimizations plus their safety
seams, all driven by `victus.yml`:

1. **DAB — Dynamic Activation of Brain.** Distance-bucketed *throttling* (not full deactivation) of
   each mob's brain / goal-selector / sensor tick cadence. Paper already has static per-sensor and
   per-behavior tick-rate overrides and Spigot activation ranges; DAB adds the **dynamic,
   distance-adaptive** layer that scales cadence continuously with how far the mob is from the
   nearest player.
2. **Async pathfinding.** Move the A\* search off the main thread: snapshot the region on-thread,
   run the search on a worker pool, and apply the finished `Path` back on the main thread via a
   drained completion queue. The path *result* is identical to sync; only *when* it is applied
   moves. A synchronous fast-path is preserved for every API call and code path that expects an
   immediate `Path`.
3. **Event-driven AI & path caching.** Cache the two hottest AI inputs — sensor/POI query results
   and freshly-computed paths — with short TTLs and event-based invalidation, so repeated identical
   queries in a crowd of mobs collapse to one.

Everything here is a toggle whose compat-preserving value is always reachable, is surfaced in
`/victus doctor`, and is chosen automatically by behavior-sensitive profiles.

## Upstream baseline (what Paper 26.1 already provides)

Paper already does a large fraction of entity-AI optimization. **We do not reimplement any of it —
we add the dynamic and async layers on top.**

- **Spigot entity activation ranges.** `spigot.yml → entity-activation-range` (animals, monsters,
  raiders, misc, water, water-underground-creature, villagers, flying-monsters) plus
  `tick-inactive-villagers` and the `wake-up-inactive` throttles. Entities **beyond** their
  activation range have most of their AI / movement skipped entirely. This is a hard on/off gate by
  distance; it is already on and already default.
- **Paper per-sensor / per-behavior tick rates.** `paper-world-defaults.yml → tick-rates` exposes
  `sensor.<entity>.<sensor>` and `behavior.<entity>.<behavior>` overrides (e.g. villager
  `secondarypoisensor`, `nearest_bed_sensor`). This is a **static, global, per-type** multiplier —
  the operator sets it once per sensor/behavior; it does not adapt to distance or load.
- **Paper mob-spawning controls.** `per-player-mob-spawns` (fairer, per-player caps) and
  `max-entity-collisions`, entity-per-chunk save limits, etc. `per-player-mob-spawns` is referenced
  by Victus's tracker spec (`optimizations.entities.per-player-mob-spawns`) and is out of scope here.
- **Paper POI / villager work optimizations.** Paper has already backported a number of
  point-of-interest and villager-brain fixes reducing redundant POI lookups.
- **Moonrise chunk system + Starlight.** Async chunk load/gen/I/O and lighting are baseline; they
  bound *where* entities exist but do not touch AI cadence or pathfinding. Out of scope here.
- **Pathfinding is fully synchronous.** `PathFinder#findPath` runs the A\* search on the main tick
  thread. Paper already builds a read-only `PathNavigationRegion` block snapshot *before* the search
  (to make the block reads cheap and consistent) — this is the property that makes async feasible,
  but Paper itself does **not** run the search off-thread.
- **No DAB.** Distance-bucketed brain throttling does **not** exist in Paper; it is a Pufferfish /
  Purpur feature. **No async pathfinding, no AI path cache** in Paper either.

So the Victus delta is: (a) a dynamic distance-bucket throttler over Paper's static tick-rates and
Spigot's on/off activation range; (b) an async executor + completion-drain around the existing
`PathFinder`/`PathNavigation` with a sync fallback; (c) TTL caches over sensor/POI queries and paths;
plus the profile overlays and compat seams that keep the default 100% plugin-compatible.

`TODO(verify against Paper 26.1 source once building online)` — the internal class/method names
below are given in Mojang-mapping form (26.1 is the first **unobfuscated** release, so they should be
close to official), but the exact `serverAiStep` / `Brain#tick` / sensor-dispatch call sites may have
shifted across the unobfuscation cutover.

## Design / patch plan

All three subsystems land under `patches/paper-server/optimizations/entities/` (GPL-3.0), each in
its own sub-group so they can be reverted independently:
`.../entities/dab/`, `.../entities/async-pathfinding/`, `.../entities/ai-cache/`.
Config resolution (key parsing, profile overlays, boolean⇄map coercion) reuses the Phase 0
`victus.yml` loader — this spec only registers the keys, their coercion, and their profile defaults.

### 1. DAB — Dynamic Activation of Brain (`optimizations/entities/dab/`)

**Where.** `net.minecraft.world.entity.Mob#serverAiStep` (and the `LivingEntity` AI step it is
called from), which drives `GoalSelector#tick` (goal + target selectors), `Brain#tick` (the behavior
controller for brain-based mobs like villagers/piglins/axolotls), and the sensor dispatch
(`Sensor#tick` via the brain). `TODO(verify)` the exact 26.1 call sites.

**Approach.** For each mob, compute an **activation interval** `N` from the distance to the nearest
player, then run the *brain / goal-selector / target-selector / sensor* computation only once every
`N` ticks. `N` grows in buckets with distance:

```
d   = distanceToNearestPlayer (blocks, squared-compare in impl)
N   = clamp( 1 + floor( (d - start-distance) / activation-dist-mod ), 1, max-tick-interval )
run = (serverTickCount + entityId) % N == 0      # entityId offset staggers the herd
```

- `d <= start-distance` ⇒ `N == 1` ⇒ full vanilla cadence (mobs near players are never throttled).
- The `+ entityId` phase offset spreads a far-away herd's expensive ticks across different server
  ticks instead of bunching them all onto the same tick — this is what turns the average saving into
  a flatter MSPT distribution, not just a lower mean.
- DAB **throttles the AI decision layer**, not the per-tick essentials. Physics/movement integration,
  collision, damage/effects, and despawn checks still run every tick. Navigation *travel* (advancing
  along an already-computed `Path`) is preserved per-tick by default so mid-range throttled mobs glide
  smoothly rather than stutter; only the *decision* to (re)path/(re)target is throttled.
  `TODO(verify)` — Pufferfish throttles the step more aggressively (including navigation); keeping
  travel per-tick is a deliberate smoothness/parity trade, exposed via
  `dab.throttle-navigation-travel` (default `false` = keep travel per-tick).

**Auto-exclusions (never throttled), independent of the blacklist:**
- entities with passengers or that are themselves riding, and leashed entities (visible, interactive);
- entities currently targeted-by / targeting a player, and mobs mid-attack-cooldown;
- entities flagged persistent/owned by a plugin (see compat section) and named mobs if
  `dab.exempt-named` is set;
- boss entities via the type `blacklist` default.

**Skipped-tick accounting.** Behaviors/goals that integrate over time (e.g. eating, breeding
cooldowns, `TemptGoal` timers) must not silently lose the skipped ticks. Where a goal reads
`level.getGameTime()` it is already correct; where a goal decrements an internal per-tick counter,
the throttled step passes the elapsed-tick delta `N` so the counter advances by the right amount.
`TODO(verify)` the set of counter-based goals in 26.1 that need the delta; default is to pass the
delta to `GoalSelector`/`Brain` so no behavior is starved.

**Optional load-adaptive mode (off by default).** When `dab.adaptive` is on, `max-tick-interval` is
scaled up while measured MSPT exceeds `hosting.limits.max-mspt`, and relaxed back under budget. This
ties DAB into the hosting control plane / lag-doctor. Default off ⇒ purely distance-deterministic so
farm behavior is reproducible.

### 2. Async pathfinding (`optimizations/entities/async-pathfinding/`)

**Where.** `net.minecraft.world.level.pathfinder.PathFinder#findPath` (the A\* itself) and its
callers `PathNavigation#createPath` / `#recomputePath` (and the `GroundPathNavigation` /
`FlyingPathNavigation` / `WaterBoundPathNavigation` subclasses). The read-only snapshot type is
`PathNavigationRegion`. `TODO(verify)` names against 26.1.

**Flow (compute off-thread, apply on-thread):**

1. **Snapshot on the main thread.** The `PathNavigationRegion` (block/collision snapshot of the box
   around start+target) is built *on the tick thread*, exactly as vanilla already does. All world
   reads happen here — the worker never touches live world state.
2. **Submit to the worker pool.** Instead of running `findPath` inline, enqueue a request
   `(region snapshot, start, targets, params)` to the async pathfinder pool and record a *pending
   navigation* on the mob. The A\* runs on a worker against the immutable snapshot only (pure
   function of its inputs ⇒ thread-safe).
3. **Await via callback, no stall.** The mob keeps executing its **current** path (or idles if it had
   none) while the request is in flight. Duplicate requests for the same mob are coalesced — a newer
   request supersedes an older in-flight one for that mob.
4. **Apply on the main thread.** Completed `Path`s land in a per-world completion queue drained at the
   **start of the next tick** on the main thread. On apply we re-validate: if the target moved beyond
   a small threshold or the mob's state changed materially, the stale path is discarded and (if still
   wanted) re-submitted. Then `PathNavigation.moveTo(path)` is applied on-thread as usual.

**Synchronous fast-path (the compat spine).** The async route is used **only** for the internal
navigation recompute loop. Any of the following compute the path **synchronously, inline**, exactly
as today, so no observable contract changes:
- **Plugin API calls** — `org.bukkit.entity.Mob#getPathfinder()` → `Pathfinder#findPath(...)` and
  `moveTo(...)` must return/act on a real `Path` immediately. These never defer.
- **Any internal caller that consumes the returned `Path` in the same tick** (rather than driving
  navigation over subsequent ticks).
- **Compat-listed entities** (real-entity NPCs — see below), **queue overflow**
  (`async-pathfinding.max-queued` exceeded), and **pool unavailable / shutting down**.

This means async pathfinding is *strictly* a latency relocation of the internal recompute: same A\*,
same snapshot, same resulting `Path`, applied ≤1 tick later under normal load. It introduces **no**
new API and cannot return a different path than sync would for the same inputs.

**Pool.** A dedicated `ForkJoinPool`/`ThreadPoolExecutor` sized `threads: auto`
(≈ `min(4, ceil(logicalCores * 0.25))`, reserving cores for the tick and chunk pools), bounded queue
`max-queued`, `keep-alive-seconds` idle reaping. Threads named `victus-pathfinder-*` for profiling.

### 3. Async AI goal processing (`optimizations/entities/`, EXPERIMENTAL)

Leaf-style `AsyncGoalExecutor`: run `GoalSelector#tick` for eligible mobs on a worker pool. This is
**materially riskier** than async pathfinding because goals read/mutate live entity and world state
and plugins assume main-thread execution. It is therefore **off by default and not part of the
on-by-default compat promise**. When enabled it only offloads goals that touch a captured snapshot
(target selection, wander) and hard-excludes goals that fire Bukkit events or mutate blocks, which
stay on-thread. Kept in the spec as a gated seam; DAB + async pathfinding deliver the bulk of the win
at far lower risk.

### 4. Event-driven AI & path caching (`optimizations/entities/ai-cache/`)

**Sensor / POI query cache (Lithium-style).** The per-mob sensors that dominate villager/piglin/raid
cost — nearest-living-entities scans and point-of-interest lookups — are the same query repeated by
many co-located mobs each tick. We add:
- a short-TTL **result cache** keyed by (query type, section/POI cell, filter) so a cluster of mobs
  in the same chunk section reuses one nearest-entities / POI result instead of each rescanning;
- **event-driven invalidation**: the cache entry for a cell is invalidated on the relevant world
  event (block/POI change in the cell, entity enter/leave the section) rather than expiring blindly,
  so throttling never serves a stale-but-important result across a real change.
This is where the Lithium **~16–22× POI-query** figure comes from; it is a throughput multiplier on
the *POI-query portion* only, not whole-tick.

**Path cache (TTL).** A per-world LRU of recently computed paths keyed by
(quantized start cell, quantized target cell, mob path-type/params). Within `path-cache.ttl-ticks`, a
mob requesting a materially identical route reuses the cached `Path` (cloned, so per-mob progress
state is independent) instead of scheduling a fresh A\*. TTL + LRU bound staleness and memory; cache
is invalidated on block changes intersecting a cached path's bounding box. This compounds with async
pathfinding (a cache hit skips the search entirely) and with DAB (throttled mobs re-path less often).

### Lag-doctor integration

Reuses the per-subsystem entity-AI MSPT timer (no new diagnosis code). Adds three remediation ids
mirroring the redstone pattern: `dab-enable` (writes `optimizations.entities.dab.enabled: true`),
`async-pathfinding-enable`, and their symmetric `*-disable` so a technical operator who was
auto-migrated can revert in one click. `/victus doctor` reports, e.g., "48% entities: 6.2k mobs, 71%
of AI cost in pathfinding in chunk 47,-12" and points at the matching toggle.

## victus.yml keys

The two headline keys already exist in `VICTUS-CONFIG.md` as booleans
(`optimizations.entities.dab: true`, `optimizations.entities.async-pathfinding: true`). Each accepts
either the **boolean shorthand** (`true` ⇒ the tuned defaults below; `false` ⇒ fully off / vanilla
cadence) **or** an expanded map to override individual knobs. The boolean form in the top-level schema
is exactly equivalent to `enabled:` with defaults.

```yaml
optimizations:
  entities:
    # Distance-bucketed throttling of mob brain / goals / sensors.
    # `true` == { enabled: true } with the defaults shown here.
    dab:
      enabled: true
      start-distance: 12          # blocks; within this, brains tick every tick (interval 1)
      activation-dist-mod: 8      # bucket width in blocks per +1 interval step
      max-tick-interval: 20       # farthest bucket: run AI at most once per N ticks
      throttle-navigation-travel: false  # false = keep path-following per-tick (smoother)
      exempt-named: false         # true = never throttle name-tagged mobs
      adaptive: false             # true = widen intervals while MSPT > hosting.limits.max-mspt
      blacklist:                  # entity types NEVER throttled (full-rate AI always)
        - minecraft:ender_dragon
        - minecraft:wither
        - minecraft:elder_guardian
        - minecraft:warden

    # Compute A* off the main thread; apply the finished path on the main thread next tick.
    # `true` == { enabled: true } with the defaults shown here.
    async-pathfinding:
      enabled: true
      threads: auto               # auto ~= min(4, 25% logical cores)
      max-queued: 1000            # in-flight request cap; overflow -> synchronous fallback
      keep-alive-seconds: 60
      compat-mode:
        sync-for-real-entity-npcs: true   # Citizens & other real-entity NPCs path SYNCHRONOUSLY
        sync-entity-types: []             # operator force-sync list, e.g. [minecraft:villager]
        sync-plugin-entities: []          # force-sync entities owned by these plugins

    # EXPERIMENTAL, off by default; not covered by the on-by-default compat promise.
    async-goal-processing: false

    # Event-driven AI: TTL + event-invalidated caches for sensor/POI queries and paths.
    ai-cache:
      sensor-poi-cache: true      # reuse nearest-entity / POI query results across co-located mobs
      path-cache:
        enabled: true
        ttl-ticks: 40             # reuse a materially-identical path within this window
        max-entries: 256          # per-world LRU bound
```

- **Related keys used, not introduced:** `engine.profile` (supplies per-profile defaults),
  `hosting.limits.max-mspt` (drives optional `dab.adaptive`),
  `optimizations.entities.activation-range-tuning` and `.per-player-mob-spawns` (complementary Spigot/
  Paper controls tuned elsewhere), `compatibility.migrate-config-on-boot` (imports pre-existing
  Paper `tick-rates` / activation-range settings once).

**Profile overlays** (overlays, not locks — any explicit key you set still wins):

| Profile | `dab` | `async-pathfinding` | `ai-cache` | Rationale |
| --- | --- | --- | --- | --- |
| `smp` | on (defaults) | on | on | mob-heavy farms & crowds; AI timing not contraption-critical |
| `minigames` | on | on | on | throughput over mob-timing fidelity |
| `modded` | on | on | on | modded mob counts are high; behavior tolerant |
| `network` | on | on | on | hub mobs are trivial; favor MSPT |
| `technical` | **off** | on | `path-cache` **off**, `sensor-poi-cache` on | preserve exact farm/aggro/pathing timing; async is result-identical so it stays on |

`technical` disables DAB (its distance-throttling shifts mob-decision timing, which farm rates can
depend on) and disables the path cache (a reused path can differ by a tick from a fresh search), but
keeps async pathfinding on because it is **result-identical** to sync — only the apply latency moves,
and even that is bounded and reverts to sync under load.

## Vanilla-parity & plugin-compat risks

- **No new plugin API; the API stays synchronous.** DAB and async pathfinding add zero
  Bukkit/Spigot/Paper API surface. The one API that touches pathing —
  `Mob#getPathfinder()`/`Pathfinder#findPath` — is served by the **synchronous fast-path** and is
  unaffected. This is the load-bearing compat guarantee: async is internal-recompute-only.
- **Async pathfinding is result-parity by construction.** Same A\* over the same
  `PathNavigationRegion` snapshot ⇒ the same `Path`. Only *when* it is applied changes (≤1 tick under
  normal load; falls back to inline-sync on queue overflow). Because the snapshot is taken on the main
  thread, workers never read live world state, so there is no data race and no thread-visibility
  concern for plugins.
- **Real-entity NPC visual glitches (Citizens) — mitigated by default.** Player-facing NPCs backed by
  real entities can rubber-band if their path applies a tick late.
  `compat-mode.sync-for-real-entity-npcs: true` (default) forces these to path synchronously.
  Detection: (a) if Citizens is present, via its NPC registry / the `NPC` metadata+scoreboard tag it
  stamps on its entities; (b) generic "plugin-persistent / plugin-owned real entity" heuristic; (c)
  explicit `sync-entity-types` / `sync-plugin-entities` operator lists. `TODO(verify)` the current
  Citizens NPC marker in the 26.1-era plugin build.
- **DAB changes mob *reaction timing*, not plugin contracts.** A throttled far mob decides/re-paths
  every `N` ticks, so it can react a few ticks later than vanilla. This is imperceptible near players
  (interval is 1 within `start-distance`) and never affects the plugin API. It **can** shift
  time-sensitive farm/aggro behavior — hence auto-exclusions (ridden/leashed/targeting/targeted/named/
  boss), skipped-tick accounting so time-integrating goals aren't starved, and `technical` ⇒ DAB off.
  DAB is on by default for other profiles for the same reason Paper's activation ranges are: the
  behavioral delta is negligible for those audiences and the key is one flip away.
- **Sensor/POI cache correctness.** A cached sensor/POI result served across throttled ticks could go
  stale; event-driven invalidation (block/POI/entity change in the cell) bounds staleness to "no
  relevant change happened," and TTL caps the worst case. Villager work/breeding and raid targeting
  are in the parity test corpus.
- **Async goal processing is opt-in and excluded from the promise.** Left `false` by default; goals
  that fire events or mutate the world stay main-thread even when enabled. Documented as experimental.
- **Determinism for technical builds.** With `dab: off` and `path-cache: off` (the `technical`
  overlay), entity AI is tick-for-tick vanilla-equivalent aside from async pathfinding's
  result-identical latency shift, which itself is disable-able.

## Tests & verification

- **Farm / contraption parity corpus.** Fixed schematics on identical seeds with recorded inputs:
  iron farm, gold/piglin bartering farm, guardian farm, mob-switch, villager breeder, trading hall,
  enderman farm, spawner-based farms, pillager outpost/raid. Run each under (a) everything off
  (baseline), (b) full defaults, (c) `technical` overlay; assert **spawn/kill/trade throughput** is
  within tolerance for defaults and **tick-for-tick identical** for the `technical` overlay. Catalogue
  any default-profile divergence (honest "what DAB changes" doc), mirroring the redstone approach.
- **Async-path result-parity test.** For a corpus of (start, target, world) cases, assert the `Path`
  returned by the async route equals the sync route byte-for-byte; assert the API path
  (`Mob#getPathfinder().findPath`) is always synchronous and correct; assert queue-overflow and
  compat-list cases fall back to sync.
- **Thread-safety / race test.** Stress many concurrent path requests while mutating the world on the
  main thread; assert workers only ever read the immutable snapshot (instrument the region), no CME /
  torn reads, and completion-drain applies strictly on the main thread.
- **Citizens visual-compat test.** With Citizens installed, drive NPC walk paths and assert no
  rubber-banding with `sync-for-real-entity-npcs: true`; confirm the NPC-detection heuristic tags
  them; confirm force-sync lists work.
- **DAB behavior test.** Assert interval bucketing (near = interval 1, far = capped at
  `max-tick-interval`), entityId staggering spreads expensive ticks across ticks, auto-exclusions hold
  (ridden/leashed/targeting/boss never throttled), and time-integrating goals advance correctly via
  the skipped-tick delta (breeding/eating/tempt timers).
- **Cache correctness.** Assert event-driven invalidation fires on block/POI/entity change in a cell
  and that a stale POI/path is never served across a real change; assert TTL + LRU bounds.
- **Config resolution tests.** Boolean⇄map coercion (`dab: true` == `{enabled: true}`), profile
  overlays (`technical` ⇒ dab off + path-cache off), precedence (explicit key > profile > default),
  and resolved-value logging at boot.
- **Benchmark harness (Phase 1 discipline, see ROADMAP & CONTRIBUTING).** A mob-heavy world (large
  active mob populations at varied player distances + a farm suite) on identical hardware, ≥150 bots,
  real workload. Isolate the **entity-AI + pathfinding portion** of MSPT via the per-subsystem timer
  and report the **MSPT distribution** (not idle TPS) with each toggle on/off, and specifically the
  p95/p99 flattening from DAB's entityId staggering and from moving A\* off-thread.
- **Top-50 plugin suite.** Mob/AI-adjacent plugins (Citizens, MythicMobs, EliteMobs, guard/pet
  plugins, anti-cheat) load and behave identically in the default path — zero API regressions, per the
  product promise.

## Honest expected gain

**Illustrative targets, to be proven by the Phase 1 benchmark harness — not measured facts.** All
figures are for the **entity-AI + pathfinding portion of the tick**; whole-server MSPT improvement
scales with how mob-bound the workload is and is **near-zero on low-mob servers**.

- **DAB:** roughly **~20–40%** off the entity-tick portion on mob-heavy worlds where many mobs sit
  outside `start-distance` (loaded farms, dense mob areas). Approaches **~0** where most mobs are
  close to players (nothing to throttle). The p95/p99 win from entityId staggering can exceed the
  mean win.
- **Async pathfinding:** **relieves main-thread MSPT wherever pathfinding dominates** the AI cost
  (many actively-repathing mobs — raids, aggro swarms, large hostile populations) by moving that CPU
  onto worker cores. **~0** on low-mob servers and when pathfinding isn't a hotspot; it trades main-
  thread time for worker time, not total CPU, and adds ≤1 tick of path-apply latency.
- **Event-driven AI / caches:** the sensor/POI cache targets the Lithium-class **~16–22×** on the
  **POI-query portion only** (villager/piglin/raid heavy); the path cache turns repeat-route requests
  into cache hits (searches avoided). Both are multipliers on sub-portions, not whole-tick.

These ranges are grounded in the upstream projects' own claims and the ARCHITECTURE §3 entity row
(~15–40% entity MSPT on mob-heavy worlds); they are **targets for the Phase 1 harness to confirm on
Victus hardware**, not benchmarks we have run.

## Prior art / references

- **Pufferfish — DAB (Dynamic Activation of Brain).** The distance-bucketed brain/goal/sensor
  throttler this section adapts; source of the interval-by-distance approach and the entity blacklist
  concept.
- **Purpur.** Ships Pufferfish's DAB and related entity-AI toggles; reference for config ergonomics
  and the exempt/blacklist surface.
- **Leaf — async pathfinding & `AsyncGoalExecutor`.** The off-main-thread A\* model (snapshot →
  worker → apply on main thread) and the (riskier) async goal-selector executor kept here as an
  experimental seam.
- **Gale / Petal.** Additional forks with async/optimized pathfinding and entity-AI work; cross-check
  for the snapshot-safety and sync-fallback edge cases.
- **Lithium (CaffeineMC).** Event-driven AI and point-of-interest optimizations; source of the
  ~16–22× POI-query figure and the event-invalidation-over-polling design for the sensor/POI cache.
- **PaperMC.** Upstream `tick-rates.sensor.*` / `tick-rates.behavior.*`, Spigot activation ranges,
  `per-player-mob-spawns`, and the synchronous `PathFinder` / `PathNavigationRegion` this delta builds
  on.
- **Citizens.** The canonical real-entity NPC plugin whose visual-glitch risk motivates
  `compat-mode.sync-for-real-entity-npcs`; its NPC metadata/registry marker is the detection hook.
- **Mojang `PathFinder` / `Brain` / `GoalSelector` / `Sensor`.** The vanilla baselines these
  optimizations wrap and are measured against.
