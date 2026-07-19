# Phase 1 · Entity tracking, spawning & collision

## Goal

Cut the per-tick cost of the three biggest entity hot paths — **tracking** (deciding which entities
each player can see and pushing the movement/metadata packets), **natural mob spawning** (the mob-cap
census + spawn cycle), and **collision / cramming** (pushing overlapping entities apart) — on
mob-heavy and populated survival worlds, **without changing any plugin-observable outcome in the
default (`single`) path**.

Victus's delta over Paper 26.1 is a mix of *one real parallelization patch*, *two Lithium-class
behaviour-identical rewrites*, and *policy/ergonomics binding* — not a from-scratch entity system:

1. **Async entity tracker** (`optimizations.entities.async-entity-tracker`, Leaf-class) — move the
   tracker's position/visibility computation and packet assembly off the main thread onto a worker
   pool, while marshalling every plugin-observable callback back to the main thread so the API
   surface is unchanged. Requires `per-player-mob-spawns: true` (see §Design) and is force-disabled
   whenever compat is at risk.
2. **Per-player mob spawns + Pufferfish-class spawn math** (`.per-player-mob-spawns`) — bind Paper's
   existing per-player spawn accounting to a first-class victus.yml key, ship the optimized
   (cached, non-re-iterating) counting hot path, and make it the documented precondition for the
   async tracker.
3. **Activation-range + despawn-range tuning** (`.activation-range-tuning`) — a profile-aware overlay
   onto Spigot/Paper's existing granular activation-range and despawn-range keys (tight for
   `smp`/`network`, vanilla for `technical`). No new algorithm.
4. **Cuboid-optimized collision + cheap cramming** (`optimizations.entities.collision.*`,
   Lithium-class) — replace the general `VoxelShape`/stream collision-gathering path with direct
   AABB/cuboid intersection, and resolve entity cramming without the quadratic pairwise push. Both
   are **behaviour-identical**; they change *how fast*, not *what happens*.

Everything behaviour-changing is a toggle whose compat-preserving value is always reachable, and the
engine tells the operator what each setting costs via `/victus doctor`.

## Upstream baseline (what Paper 26.1 already provides)

A large fraction of this area is **already upstream**; we must not re-claim it. Honest inventory:

- **Per-player mob spawns — already in Paper, default on.** Paper ships `per-player-mob-spawns`
  (world config, `entities.spawning.per-player-mob-spawns`, default `true` in modern Paper). It
  replaces the single per-world global mob cap with a per-player cap: fairer (a player alone in far
  chunks still gets mobs) and cheaper (avoids some global rescans). `TODO(verify against Paper 26.1
  source once building online)` — exact YAML path/default across the unobfuscation cutover.
- **Entity activation ranges — already upstream (from Spigot).** `spigot.yml →
  world-settings.<world>.entity-activation-range.{animals,monsters,raiders,misc,water,villagers,
  flying-monsters,...}` plus `wake-up-inactive` tuning. Paper extends this (tick-inactive villagers,
  etc.). We tune the *values*, we don't add the mechanism.
- **Despawn ranges — already upstream (Paper).** `entities.spawning.despawn-ranges.{soft,hard}` per
  mob category in the world config. Again: we ship tuned defaults, not a new despawn engine.
- **`max-entity-collisions` — already upstream.** The per-entity cap on how many collision pushes are
  resolved each tick (Spigot's `entity-max-collisions`; Paper exposes it in the world config —
  `TODO(verify)` whether 26.1 spells it `collisions.max-entity-collisions` or keeps the Spigot path).
  Default `8`.
- **Entity-tracker range & save limits — already upstream.** Paper's `entity-tracking-range`
  (per-category tracking distances) and `entity-per-chunk-save-limit` exist. **But entity tracking
  itself still runs on the main tick thread in Paper.** That is the gap this spec's item (1) fills.
- **Moonrise chunk system + Starlight** — already baseline; the tracker interacts with it but we do
  not touch it here.

So the genuinely *new server-code* work in this spec is: **(1) the async tracker**, **(4) the
Lithium-class collision/cramming rewrites**, and **the optimized spawn-count hot path** on top of
Paper's per-player spawns. Items (2)-partial and (3) are config binding + profile overlays living in
the shared victus.yml core, not entity-specific algorithm patches.

> **Sibling keys, out of scope here.** `optimizations.entities.dab` (Dynamic Activation of Brains —
> a Pufferfish/Purpur feature, *not* in Paper) and `optimizations.entities.async-pathfinding` (also
> not in Paper) are specced separately. This document owns tracker, spawning, activation/despawn
> tuning, and collision/cramming only.

## Design / patch plan

Four pieces. One is a real concurrency patch; two are behaviour-identical rewrites; one is config.

### 1. Async entity tracker — `patches/paper-server/optimizations/entities/tracker/`

**What runs where.** Paper's per-player tracking loop (`ChunkMap.TrackedEntity#updatePlayer` /
`ServerEntity#sendChanges`, `TODO(verify)` exact classes post-unobfuscation) computes, for every
tracked entity × every candidate viewer: in-range visibility, the movement/rotation/velocity deltas,
and the metadata/passenger/mount packet set, then enqueues packets. On a mob-heavy world this is a
large, embarrassingly-parallel-per-entity chunk of the main tick.

The patch splits that loop into:

- **Off-thread (worker pool):** the *pure computation* — range checks, delta encoding, packet
  **assembly** (byte buffers), and per-player visibility set diffing. These read entity position/
  state snapshots captured at a tick barrier; they mutate no shared game state.
- **On the main thread (unchanged):** anything a plugin can observe — `PlayerTrackEntityEvent` /
  `PlayerUntrackEntityEvent` dispatch, and any Bukkit/Paper API touched during add/remove — is
  **marshalled back** and fired in the same logical order as the synchronous path. Packet *sends* go
  through the normal Netty pipeline (already thread-safe / flush-consolidated).

**Ordering guarantee.** Per player, the set and ordering of entity add/remove/move/metadata packets
produced by the async path must be **identical** to the synchronous path for the same tick snapshot.
The worker pool parallelizes *across* entities; within a player's stream the merge step restores a
deterministic order before handoff to the connection. This is the core correctness invariant (see
Tests).

**Thread-pool sizing.** `async-entity-tracker-threads: auto` reuses the shared Victus async executor
(sized against logical cores with headroom, like `chunks.worker-threads`), so a plain SMP box does
not spawn a second large pool. Explicit integer overrides the share.

**Precondition — requires `per-player-mob-spawns: true`.** At config resolution:

- If `async-entity-tracker: true` **and** `per-player-mob-spawns: false`, the engine logs a warning
  and **force-disables the async tracker** (falls back to synchronous — the compat-preserving path),
  surfacing it in `/victus doctor`. It does **not** silently flip the other key.
- Rationale (`TODO(verify)` whether this is a hard implementation requirement or a conservative
  guard): the async tracker reads entity-collection snapshots concurrently with the spawn cycle. The
  per-player spawn path keeps its counts in per-player structures (`NearbyPlayers`/per-player
  `SpawnState`, `TODO(verify)` names) that Victus makes safe for concurrent readers, whereas the
  legacy global-cap census does a shared, mutable full-entity walk during the spawn step that would
  contend/race with the tracker. Coupling them removes that hazard by construction.

**Compat safe-mode (this is how the default stays 100% compatible).**

- When `threading.parallel.compat-mode: true`, the tracker is **forced synchronous**, byte-for-byte
  the upstream path.
- **NPC / packet-manipulation auto-detection.** On boot, if a known NPC or low-level packet plugin
  is present (Citizens, ProtocolLib-based packet rewriters, and the maintained detect-list — same
  mechanism as the hybrid `safe-mode`), the engine **auto-forces synchronous tracking** and logs
  exactly which plugin triggered it. These plugins frequently inject/rewrite entity packets or call
  API from tracker context and are the one category where off-thread assembly can race.
- The engine logs the resolved tracker mode (`async` / `sync (compat-mode)` / `sync (plugin: X)`) at
  boot.

### 2. Per-player mob spawns + optimized spawn math — `patches/paper-server/optimizations/entities/spawning/`

- **Binding (config core).** Register `optimizations.entities.per-player-mob-spawns` as the
  first-class key and map it onto Paper's world-config `per-player-mob-spawns`. Precedence mirrors
  the redstone spec: explicit per-world Paper value > victus.yml > profile default > Paper's own
  default (`true`). One-time import via `compatibility.migrate-config-on-boot`.
- **Optimized counting hot path (Pufferfish-class, `TODO(verify)` how much 26.1 already merged).**
  The per-player mob census is the leverage point. Cache the per-category mob counts per player
  across the spawn attempt rather than re-walking nearby entities for each category, and reuse the
  `NearbyPlayers` structure Moonrise/Paper already maintains instead of an ad-hoc radius scan. This
  is the change that makes per-player spawns *cheaper* than the global cap rather than merely fairer,
  and it is what unblocks the tracker's concurrent reads (§1).
- **Behaviour note.** Per-player spawns changes spawn *distribution* vs a strict global cap, but this
  is **Paper's long-standing default**, so relative to our Paper 26.1 base there is no regression;
  the count math change is a pure speed optimization with identical spawn decisions.

### 3. Activation-range + despawn-range tuning — profile overlay (config core, no algorithm patch)

`activation-range-tuning: true` applies a **profile-aware overlay** onto Spigot's
`entity-activation-range.*` and Paper's `entities.spawning.despawn-ranges.*` — it does not introduce
a new range engine. Any explicit per-world Spigot/Paper value the operator set **wins** (the granular
keys remain the escape hatch), exactly like the redstone binding.

| Profile | Activation ranges | Despawn ranges | Rationale |
| --- | --- | --- | --- |
| `smp` | tightened (monsters/animals/misc pulled in) | tightened soft/hard | most mobs off-screen; big MSPT, no felt gameplay change |
| `network` | tightest | tightest | hub/utility mobs are cosmetic |
| `minigames` | tightened | tightened | throughput over far-mob fidelity |
| `modded` | moderate | moderate | mod mobs vary; stay conservative |
| `technical` | **vanilla** | **vanilla** | farm rates / mob timing parity is the point |

`activation-range-tuning: false` (or the `technical` profile) leaves Spigot/Paper defaults untouched.
`TODO(verify)` the concrete tuned numbers against a farm-rate corpus before publishing the presets —
over-tightening despawn/activation ranges silently nerfs mob-farm rates, which technical/SMP players
notice. The overlay ships *values*, and every value is individually overridable.

### 4. Cuboid-optimized collision + cheap cramming — `patches/paper-server/optimizations/entities/collision/`

Both are **behaviour-identical** Lithium-class rewrites of the *math*, gated on but defaulting to on
because they change nothing observable.

- **`cuboid-collision` — cheap AABB/cuboid intersection.** Vanilla/Paper gather collision shapes
  through general `VoxelShape` operations with stream/iterator allocation on the hot path
  (`Entity#collide`, `CollisionContext`, `Shapes.*`; `TODO(verify)` names). For the common case —
  entities and full-cube/simple blocks — replace this with direct `AABB` cuboid intersection: early
  bounding-box reject, no stream allocation, fall back to the full `VoxelShape` path for genuinely
  non-cuboid shapes (stairs, fences, etc.) so results are bit-identical. This mirrors Lithium's
  `entity_collisions` / `block_shapes` optimizations.
- **`cheap-cramming` — non-quadratic push resolution.** Vanilla resolves entity cramming/pushing by
  pairwise iteration bounded by `max-entity-collisions`; in dense clusters (mob farms, packed pens)
  the neighbor query dominates. Optimize the neighbor lookup (reuse the section/chunk entity index
  instead of re-scanning an AABB per entity) and short-circuit once the per-entity collision cap is
  hit — **same final positions, same cramming damage, same cap semantics**, less work.
- **`max-entity-collisions`** is surfaced as a first-class victus.yml key bound to Paper/Spigot's
  per-world value (default `8`, matching upstream). Profiles may lower it slightly for
  `network`/`minigames`; `technical`/`smp` keep `8`. Explicit per-world value wins.

### Lag-doctor integration

Entities are already a first-class `/victus doctor` category (ARCHITECTURE §5 example: "62%
entities: 9.4k mobs in chunk 47,-12 from PluginX"). This spec wires four thin remediation ids to the
per-subsystem entity timers — no new diagnosis code:

- `per-player-mob-spawns` → writes `optimizations.entities.per-player-mob-spawns: true`.
- `async-entity-tracker` → writes `async-entity-tracker: true` (only offered if the precondition and
  no NPC-plugin conflict are satisfied; otherwise the suggestion explains why it's withheld).
- `activation-range-tuning` → writes `activation-range-tuning: true` (with the farm-rate caveat in
  the remediation text).
- `entity-collision` → writes `optimizations.entities.collision.cuboid-collision: true` /
  lowers `max-entity-collisions` on cramming-dominated ticks.

Each remediation states its cost, and a symmetric revert exists for the `technical` operator, per the
redstone precedent.

## victus.yml keys

```yaml
optimizations:
  entities:
    # Move tracker position/visibility computation + packet assembly off the main thread.
    # Plugin-observable callbacks are marshalled back to main; per-player packet order is preserved.
    # Requires per-player-mob-spawns: true. Force-disabled by compat-mode and by NPC/packet plugins.
    async-entity-tracker: true
    async-entity-tracker-threads: auto   # auto = share the Victus async executor; or an integer

    # Per-player mob caps (Paper feature, bound here) + Pufferfish-class optimized count math.
    # Precondition for async-entity-tracker.
    per-player-mob-spawns: true

    # Profile-aware overlay onto Spigot entity-activation-range.* and Paper despawn-ranges.*.
    # Explicit per-world Spigot/Paper values still win. technical profile overlays vanilla ranges.
    activation-range-tuning: true

    collision:
      cuboid-collision: true             # Lithium-class cheap AABB/cuboid intersection. Behaviour-identical.
      cheap-cramming: true               # non-quadratic cramming/push resolution. Behaviour-identical.
      max-entity-collisions: 8           # surfaces Paper/Spigot per-entity collision-push cap (upstream default 8)
```

- **Defaults & profiles.** All four toggles default `true` (the collision pair and the tracker are
  compat-preserving by construction — see risks). The `technical` profile overlays
  `activation-range-tuning`'s *values* to vanilla and keeps `max-entity-collisions: 8`; it leaves the
  tracker/collision/spawn toggles on (they don't change outcomes). Any explicit key beats the
  overlay, per `VICTUS-CONFIG.md`.
- **Consistent with the top-level schema.** `async-entity-tracker`, `per-player-mob-spawns`, and
  `activation-range-tuning` already exist in `VICTUS-CONFIG.md`; this spec pins their semantics,
  precondition, profile behaviour, and adds the `async-entity-tracker-threads` and
  `collision.*` keys.
- **Related keys used, not introduced:** `engine.profile` (supplies defaults),
  `threading.parallel.compat-mode` (forces synchronous tracking),
  `compatibility.migrate-config-on-boot` (one-time import of existing Paper spawn/range/collision
  values), `hosting.limits.entity-hard-cap` (hard spawn refusal, orthogonal) and
  `hosting.limits.max-mspt` (soft cap that can throttle the spawn cycle).

## Vanilla-parity & plugin-compat risks

- **Collision + cramming are behaviour-identical by construction.** `cuboid-collision` falls back to
  the full `VoxelShape` path for any non-cuboid shape, so results are bit-identical; `cheap-cramming`
  preserves final positions, cramming damage, and `max-entity-collisions` semantics. They earn
  default-on status because they change speed, not outcomes. Residual risk is a math edge case →
  covered by the collision-parity corpus (Tests).
- **Async tracker — the real compat surface, mitigated to default-safe.** The API-observable
  behaviour (which packets a player receives, `PlayerTrack/UntrackEntityEvent` order) is identical
  because callbacks are marshalled to main and per-player packet order is restored. The genuine
  residual risk is plugins that manipulate the tracker via internals/NMS or intercept/rewrite entity
  packets, or assume tracking runs on the main thread. Mitigation, layered:
  1. **NPC/packet-plugin auto safe-mode** force-syncs when Citizens / ProtocolLib-class packet
     plugins are detected;
  2. **`threading.parallel.compat-mode: true`** force-syncs everything;
  3. setting `async-entity-tracker: false` gives the byte-for-byte upstream synchronous path.
  Honest caveat: Leaf-lineage async trackers have had reported edge cases; a deployment that cannot
  tolerate *any* concurrency risk should run compat-mode. `TODO(verify)` the exact tracker classes
  and event-dispatch points against 26.1 source.
- **Per-player spawns changes spawn distribution vs a global cap — but matches Paper's default.**
  Relative to our Paper 26.1 base there is no regression; relative to strict vanilla global caps it's
  a long-standing, well-understood Paper behaviour. The count-math optimization makes **identical
  spawn decisions**, just faster. `per-player-mob-spawns: false` restores the global-cap path (and
  disables the async tracker, per §1).
- **Activation/despawn tuning can nerf farm rates if over-tightened.** This is the sharpest gameplay
  risk in the spec. Mitigation: `technical` profile = vanilla ranges; every value individually
  overridable; farm-rate corpus gates the published presets; the lag-doctor remediation states the
  cost. `activation-range-tuning: false` restores Spigot/Paper defaults.
- **No new plugin API surface.** We add config bindings + internal optimizations, not new
  events/hooks (beyond firing existing track/untrack events from the marshalled path). No
  Bukkit/Spigot/Paper API breakage beyond what upstream already ships.

## Tests & verification

- **Tracker packet-parity (the core invariant).** For a fixed populated scenario (recorded input
  trace, identical seed), capture every per-player entity add/remove/move/rotate/velocity/metadata/
  passenger packet under `sync` and `async`. Assert the **set and per-player order are identical**.
  Any divergence fails the build.
- **Tracker event-parity.** Assert `PlayerTrackEntityEvent` / `PlayerUntrackEntityEvent` fire the
  same number of times, in the same order, on the main thread under both modes.
- **Tracker concurrency stress.** High entity counts (≥5k), churn (rapid spawn/despawn, teleporting
  players) under thread-sanitizer-style assertions / concurrency checks; assert zero
  `ConcurrentModificationException`, no lost/dup packets, no cross-player leakage.
- **NPC-plugin compat suite.** Citizens + ProtocolLib-based packet plugins load and behave
  identically; assert auto safe-mode force-syncs the tracker and logs the triggering plugin; assert
  `compat-mode` force-syncs.
- **Precondition guard test.** `async-entity-tracker: true` + `per-player-mob-spawns: false` ⇒
  tracker force-disabled, warning logged, `/victus doctor` surfaces it; other key not silently
  flipped.
- **Spawn parity.** Same seed + trace ⇒ identical spawn/despawn decisions with the optimized count
  math vs the naive path (per-player-mob-spawns on); catalogue the distribution difference vs the
  global cap so it's documented, not silently accepted.
- **Collision/cramming parity corpus.** Entities driven into blocks (cuboid and non-cuboid: stairs,
  fences, walls, slabs), packed clusters, boats/minecarts, cramming-damage thresholds — assert
  **identical final positions and identical cramming damage/events** with `cuboid-collision` and
  `cheap-cramming` on vs off. Include the shapes most likely to expose an AABB-fastpath bug.
- **Activation/despawn farm-rate corpus.** Standard mob-farm schematics (general mob farm, iron farm,
  guardian/raid farms) run for fixed durations under each profile; assert `technical` matches vanilla
  rates and catalogue the rate delta the tuned SMP/network presets introduce.
- **Benchmark harness (Phase 1 discipline, see ROADMAP).** Entity-stress world (700+ monsters + a
  mob-farm suite) on identical hardware, ≥150 bots for whole-server context. Isolate the
  **tracker / spawn / collision portions** of MSPT via the per-subsystem timers and report the
  **MSPT distribution** (not idle TPS) for: baseline Paper, tracker-only, +per-player spawns,
  +activation tuning, +collision — so each subsystem's contribution is attributable.
- **Top-50 plugin suite.** Entity-adjacent plugins (mob stackers, spawners, anti-cheat, NPC, custom
  mobs, mythic-style) load and behave identically in the default path — zero regressions, per the
  product promise.

## Honest expected gain

**Illustrative targets, to be proven by the Phase 1 benchmark harness — not measured facts.** All
figures are **workload-qualified**; on a low-entity world every number here is **near-zero**. Whole-
server MSPT improvement scales with how entity-bound the workload is, and the bundle overlaps with
the DAB / async-pathfinding siblings (don't sum them naively).

- **Async entity tracker:** target **~15%** whole-server MSPT reduction on entity-heavy servers.
  Illustrative reported figure from prior art: **~4.5 vs ~8.1 mspt at 700 monsters** (the
  *tracker-portion* improvement, larger than the whole-server headline). Gain rises with player ×
  entity count (more viewers × more tracked entities = more parallelizable work); ~0 on small
  populations.
- **Per-player mob spawns + optimized count math:** **one of the highest-leverage SMP wins.** The
  fairness change is qualitative; the count-math optimization removes a recurring per-spawn-cycle
  cost that grows with entity count. Meaningful reduction of the spawn-cycle MSPT portion on
  populated survival; near-zero on empty or entity-sparse worlds.
- **Activation/despawn tuning:** meaningful entity-tick MSPT reduction on worlds with many idle
  off-screen mobs (most of an entity-heavy SMP's mobs are inactive at any instant); ~0 where mobs are
  few or players are dense. Trades a small, tunable amount of far-mob fidelity for it.
- **Cuboid collision + cheap cramming:** meaningful reduction of the **collision-tick portion** on
  cramming-dominated workloads (mob farms, packed pens, entity-crushers); near-zero where few
  entities overlap. Behaviour-identical, so it's a free win where it applies.
- **Whole entity bundle (this spec + siblings), per ARCHITECTURE §3:** **~15–40% entity MSPT** on
  mob-heavy worlds.

These ranges are grounded in the upstream projects' own reported figures and the ARCHITECTURE §3
table; they are **targets for the Phase 1 harness to confirm on Victus hardware**, not benchmarks we
have run.

## Prior art / references

- **Leaf — MultithreadedTracker.** The async/off-main-thread entity tracker this spec's item (1) is
  modelled on (lineage shared with Petal/Gale tracker work).
- **Pufferfish.** Optimized per-player mob-spawn math (cached counts, reduced re-iteration) and DAB;
  the spawn-count hot-path optimization here follows its approach.
- **Lithium (CaffeineMC).** `entity_collisions` / `block_shapes` cuboid/AABB collision optimizations
  and entity-cramming/push optimization — the model for item (4). Behaviour-identical by design.
- **PaperMC.** Upstream `per-player-mob-spawns`, `entity-activation-range`/`entity-tracking-range`,
  `despawn-ranges`, and `max-entity-collisions` — the options Victus binds, tunes, and defaults per
  profile; and the Moonrise chunk system the tracker rides on.
- **Spigot.** Origin of entity activation ranges and `entity-max-collisions` (`max-entity-collisions`).
- **Purpur.** Related per-entity tuning toggles and DAB lineage (context for the sibling `dab` key).
- **Mojang / vanilla `NaturalSpawner`, `ChunkMap.TrackedEntity`, `Entity#collide`.** The baselines
  these optimizations preserve outcomes against and are measured relative to.
