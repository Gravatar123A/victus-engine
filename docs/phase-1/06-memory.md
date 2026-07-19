# Phase 1 · Memory footprint

## Goal

Shrink the JVM heap a Victus instance holds at rest and under load — chiefly the **permanent live
set** (block-state metadata, collision/occlusion shapes) plus the **repetitive immutable strings**
that scale with loaded chunks and entities — **without changing a single gameplay outcome or any
plugin-observable value in the default (`single`) path.** This is a pure memory optimization: it
costs a bounded one-time CPU pass at data-load and buys back RAM (node density / oversell headroom)
and a smaller live set for the garbage collector to scan.

Victus's delta over Paper 26.1 is a **FerriteCore-class deduplication patch** (real new server
code), plus one **informational JVM-flag check**, all behind one already-schema'd toggle
`optimizations.memory.dedup` (default `true`):

1. **Block-state metadata dedup** — canonicalize the per-`BlockState` internal tables (the
   property→value maps and the neighbour/transition table that backs `setValue`/`cycle`) so
   structurally-identical tables share one instance instead of every state carrying its own.
2. **VoxelShape dedup** — canonicalize the cached collision/occlusion shapes and their internal
   `IndexMerger`/`DoubleList` axis caches, which are highly repetitive across blocks (every full
   cube, slab, stair orientation, fence node, etc. recomputes the same shapes).
3. **Load-time string canonicalization** — intern the hot, fixed set of immutable strings created at
   registry build (`ResourceLocation` namespaces/paths, block-state property names, tag/registry
   keys) so identical strings share one object.
4. **JVM string-dedup awareness** — the *runtime* string backing-array dedup (repetitive NBT keys
   across chunks/entities, team/scoreboard names, etc.) is left to the JVM's own
   `-XX:+UseStringDeduplication` (a background GC-thread job, zero tick cost). Like `gc.profile`,
   this is a **launch-flag** concern; the engine only **checks for it and warns on mismatch**, it
   never sets JVM flags at runtime.

The honesty up front, because this area is the easiest to over-sell:

- **Model / baked-quad dedup is client-only.** FerriteCore's largest single win — deduplicating
  baked models and vertex/quad data — lives entirely on the *client*. A headless Victus server bakes
  no models, so Victus **does not** implement model dedup server-side; it would be a no-op. The
  `dedup` comment in `VICTUS-CONFIG.md` reads "block-state/model" for lineage, but on the server
  "model" contributes ~0. We say so rather than claim it.
- **FerriteCore's famous number is modded.** FerriteCore cutting a large Fabric modpack from
  **1792 MB → 984 MB** is a *modded-client* figure, driven by modpacks registering tens of thousands
  of extra block states. A **vanilla Paper server** has a fixed, comparatively small block-state
  census, so the *absolute* block-state/shape savings on pure SMP are far more modest. The ~2× ceiling
  is a **heavy-case** target (heavy datapacks, and — later — `hybrid`/modded worlds), not a vanilla
  SMP promise. See Honest expected gain.
- **Paper already deduplicates some data.** Vanilla makes block states singletons and interns some
  strings; the target here is the *internal* memory each singleton still carries. That means the
  remaining headroom Victus can reclaim is smaller than the raw FerriteCore-on-old-versions figures.

Everything here is behaviour-neutral by construction; the compat-preserving value (`dedup: false`,
i.e. Paper's exact representation) is always reachable and is what `/victus doctor` reverts to.

## Upstream baseline (what Paper 26.1 already provides)

**We must not re-claim vanilla's own deduplication.** Honest inventory:

- **Block states are already singletons — Mojang.** Each distinct block state is a single shared
  `BlockBehaviour$BlockStateBase` instance obtained from the block's `StateDefinition`; nothing in
  Victus changes that, and `Block.defaultBlockState()` / state identity (`==`) is preserved. What
  vanilla does **not** deduplicate is the *internal* per-state data: each state instance still holds
  its own property→value map and its own neighbour/transition table (the structure that makes
  `setValue`/`cycle` O(1) by precomputing the resulting sibling state). Those tables are large in
  aggregate and are structurally identical across many states — that redundancy is item (1) here.
  `TODO(verify against Paper 26.1 source once building online)` — the unobf field names/shape of the
  neighbour table and the property-map type (`ImmutableMap` vs a `Reference2ObjectArrayMap`-style
  map) have drifted across versions and again across the unobfuscation cutover.
- **VoxelShapes are cached but not canonicalized — Mojang.** `Block`/`BlockBehaviour` cache computed
  collision/occlusion shapes, and `Shapes`/`IndexMerger` cache the axis `DoubleList`s. But identical
  shapes computed for different blocks are **distinct objects**; vanilla does not intern them. That is
  item (2). `TODO(verify)` the 26.1 class/field names for the shape cache, `DiscreteVoxelShape` /
  `BitSetDiscreteVoxelShape`, and whether `VoxelShape` carries a correct content-based
  `equals`/`hashCode` (FerriteCore historically had to *add* one to make interning safe — see risks).
- **String interning is partial — JDK + Mojang.** The JVM interns compile-time string literals;
  Mojang interns some registry keys. But the bulk of repetitive strings on a running server —
  `ResourceLocation` path/namespace strings, block-state property names, and especially **NBT
  compound-tag key names** repeated across every stored entity/chunk/block-entity — are **not**
  interned. Items (3) (load-time, application-level) and (4) (runtime, JVM-level) target these.
- **`-XX:+UseStringDeduplication` exists in the JDK, off by default.** Originally G1-only, the JVM's
  string-dedup was **decoupled from G1 and made available to all collectors (including ZGC and
  Shenandoah) in JDK 18+**, so it is usable alongside Victus's default Generational ZGC (`gc.profile:
  zgc-generational`, Java 21+). It dedups the backing `byte[]`/`char[]` of equal `String`s on a
  background GC thread; it does **not** collapse the `String` object headers themselves (that is what
  our load-time interning does for the fixed set). `TODO(verify)` the exact flag support + behaviour
  on the target JDK build. The engine does not enable this — it is a start-script flag — but item (4)
  makes the engine *aware* of it.
- **Paper's own memory tweaks — Paper.** Paper carries assorted allocation/footprint reductions
  (palette handling, some cache tuning) and has upstreamed or mirrored a few FerriteCore-adjacent
  ideas over the years. Paper does **not** ship the FerriteCore block-state/shape/string dedup passes.
  `TODO(verify)` precisely which FerriteCore ideas reached vanilla/Paper by 26.1 — every one that did
  shrinks the remaining headroom Victus reclaims, and this spec must not double-count them.

So the genuinely-new server code in this spec is **the dedup passes (1)–(3)**, an always-on
compat-preserving optimization patch. Item (4) is a boot-time capability check (a few lines, wired
into the same warning surface as `gc.profile`). There is **no upstream Paper option to merely bind**
here — unlike the redstone/chunks specs — because Paper ships no equivalent knob.

## Design / patch plan

One patch group, `patches/paper-server/optimizations/memory/` (GPL-3.0), all gated on
`optimizations.memory.dedup` and its per-technique sub-flags. The passes run **once**, at the point
the game data is fully built and about to be frozen (registry freeze / `Bootstrap.bootStrap()` /
server data-load, *before* any world ticks), so the interned structures are immutable for the JVM
lifetime and there is no concurrent-mutation surface. `TODO(verify)` the exact 26.1 lifecycle hook
where block registries + state definitions + shape caches are fully populated but the server has not
yet begun ticking.

### 1. Block-state metadata dedup (`dedup-block-states`)

Canonicalize the internal tables every `BlockBehaviour$BlockStateBase` holds:

- **Property→value maps.** After the `StateDefinition` for every block is built, walk all states and
  replace each state's property-value map with a canonical instance drawn from a content-keyed
  interner (Guava `Interners`-style, or a purpose-built `HashMap<Map,Map>`), so that all states
  sharing the same `{property: value, …}` shape point at one map object. `getValue`/`getValues`
  return identical results — only the backing object identity is shared.
- **Neighbour / transition table.** The larger target: the precomputed table that maps
  `(property, target-value) → resulting sibling state` (what `setValue`/`cycle` read). FerriteCore's
  approach is to (a) deduplicate identical sub-tables across states and/or (b) replace the map/table
  with a compact **array-backed** lookup indexed by property ordinal, eliminating per-state map
  overhead. Victus adopts (a) first (lower forward-port risk — pure interning, no algorithm change),
  and treats (b) as a follow-up if the harness shows the array-backed form is worth the rebase cost.
  `setValue(prop, val)` / `cycle(prop)` must return the **exact same** `BlockState` singleton as
  before (verified in Tests) — we change how the table is *stored*, never what it resolves to.
- **Predefined value tables.** Intern the small immutable value arrays/collections
  (`Property#getPossibleValues` results and similar) shared across states of the same property.

Approach: a single locality-friendly pass over `Block.BLOCK_STATE_REGISTRY` / every block's
`getStateDefinition().getPossibleStates()`, using a **strong** interner that is dropped after the
pass (the survivors are referenced by the states themselves, so the interner map can be GC'd once the
pass completes — no permanent interner overhead). `TODO(verify)` unobf names for the state registry,
`getPossibleStates`, and the neighbour-table field.

### 2. VoxelShape dedup (`dedup-voxel-shapes`)

Canonicalize collision/occlusion shapes after they are computed:

- Intern the `VoxelShape` objects produced for block collision/occlusion/support/interaction shapes
  so structurally-identical shapes (the overwhelmingly common full-cube, plus repeated slab/stair/
  fence/wall/pane geometries across hundreds of blocks) share one instance.
- Intern the internal `IndexMerger`/`DoubleList` axis caches and the `DiscreteVoxelShape` bitsets,
  which are even more repetitive than the top-level shapes.
- **Correctness precondition:** interning requires a correct content-based `equals`/`hashCode` on the
  shape types. If 26.1's `VoxelShape`/`DiscreteVoxelShape` lack one (they historically did),
  Victus adds a content-based equality **used only by the interner** (a wrapper key or an added
  method), never altering the identity semantics Mojang code relies on elsewhere. `TODO(verify)`.

This pass hooks the shape-cache population (lazy in vanilla) at the same boot-time point, or wraps the
cache-fill path so shapes are canonicalized as they are first computed. Because collision math reads
the *same* shape geometry, collision results are bit-identical (verified in Tests).

### 3. Load-time string canonicalization (`dedup-strings`)

An application-level interner run over the **fixed, load-time** string set only — deliberately **not**
the per-deserialize hot path, so the promise "one-time load-time CPU cost, no gameplay change" holds:

- `ResourceLocation` namespace + path strings across all registries.
- Block-state **property names** and enum value names.
- Tag / registry key strings frozen at bootstrap.

Use a `WeakInterner` (or strong-interner-then-drop, as in §1) at registry freeze. This collapses the
`String` *objects* for the fixed set — complementary to the JVM's `UseStringDeduplication`, which only
collapses backing arrays and runs continuously in the background.

Deliberately **out of scope for the default path:** interning NBT `CompoundTag` key names on the chunk/
entity **deserialize** path. That would be an *ongoing* per-key cost on every load, violating the
"one-time load-time" contract, and the JVM's `UseStringDeduplication` already covers those backing
arrays for free on the GC thread. We rely on item (4) for that class of string instead. (An opt-in
`dedup-nbt-keys` runtime pass is noted as a possible Phase 2+ escape hatch, default off — not shipped
here.)

### 4. JVM string-dedup awareness (`warn-if-no-jvm-string-dedup`)

Mirror the `gc.profile` pattern exactly: at boot, read the active JVM flags
(`ManagementFactory` / `HotSpotDiagnosticMXBean` / `Runtime`) and, if `UseStringDeduplication` is
**not** enabled, emit **one** startup warning pointing at `scripts/dev-env.sh` / the launch-flag docs.
The engine **never** sets the flag itself (it cannot be toggled at runtime). Silent when the flag is
present. This is informational only, exactly like the `gc.profile` mismatch warning.

### 5. Profiles — no per-profile variation (deliberate)

Unlike redstone (`technical` → `vanilla`) and chunks (per-profile `max-generate-rate`), memory dedup
is **behaviour-neutral**, so **every profile defaults `dedup: true`** — it is the rare optimization
that is universally safe to leave on, including `technical`, because it changes *no* observable
behaviour and costs only a bounded one-time boot pass. The `modded` profile (and later `hybrid`
worlds) benefit *most* in absolute terms because their block-state census is far larger; no profile
turns it off. The sub-flags exist for A/B benchmarking and for the fail-safe path, not for profile
tuning. `TODO` confirm no profile has a reason to disable — none is currently known.

### Lag-doctor integration

Memory is a `/victus doctor` concern via the existing heap/GC metrics (ARCHITECTURE §5). This spec
wires thin, non-diagnostic hooks — no new analysis code:

- `memory-dedup` — if `dedup` is `false` and the instance is under memory pressure (retained set near
  `hosting.limits.max-heap-mb` / frequent GC), suggest enabling it, stating the one-time boot cost and
  the honest "savings are modest on vanilla, larger on modded/heavy-datapack" caveat.
- `jvm-string-dedup` — surface the same warning as item (4) as a doctor remediation ("add
  `-XX:+UseStringDeduplication` to the start script"), since it is a launch-flag change Wings applies,
  not a runtime toggle.
- Both report **estimated / measured retained-heap breakdown by category** (block-states, shapes,
  strings) when a heap sample is available, so the operator sees where the RAM actually went.

## victus.yml keys

The headline key `optimizations.memory.dedup` already exists in `VICTUS-CONFIG.md`; this spec pins
its semantics and **adds** per-technique sub-flags (each inheriting `dedup` when unset) for A/B
benchmarking and fail-safe control, plus the JVM-awareness flag — the same additive pattern the
chunks spec used for `chunks.pregen.*`.

```yaml
optimizations:
  memory:
    # Master switch for load-time, application-level deduplication of immutable game data.
    # One-time CPU pass at data-load; ZERO gameplay change; shrinks the live set → less GC.
    # false = Paper's exact representation (the compat-preserving value).
    dedup: true

    # Fine-grained overrides — inherit `dedup` when unset. For benchmarking + fail-safe escape hatches.
    dedup-block-states: true    # per-BlockState property maps + neighbour/transition tables (FerriteCore-class)
    dedup-voxel-shapes: true    # cached collision/occlusion VoxelShapes + IndexMerger/DoubleList axis caches
    dedup-strings: true         # load-time interning of ResourceLocation / property-name / registry-key strings
                                # (NOTE: does NOT touch the per-deserialize NBT-key path — that is left to the JVM,
                                #  see warn-if-no-jvm-string-dedup — so the "one-time load cost" promise holds)

    # INFORMATIONAL only (like optimizations.gc.profile): warn once at boot if the running JVM lacks
    # -XX:+UseStringDeduplication. The engine NEVER sets JVM flags at runtime; this is a launch-flag concern.
    warn-if-no-jvm-string-dedup: true
```

- **Defaults & profiles.** `dedup: true` on **every** profile (§5); all sub-flags inherit it.
  `warn-if-no-jvm-string-dedup: true` everywhere. Any explicit key beats the profile overlay, per
  `VICTUS-CONFIG.md`.
- **Consistent with the top-level schema.** `optimizations.memory.dedup` already appears in
  `VICTUS-CONFIG.md`; this spec pins it and **adds** the `dedup-block-states` / `dedup-voxel-shapes`
  / `dedup-strings` / `warn-if-no-jvm-string-dedup` sub-keys.
- **Related keys used, not introduced:** `engine.profile` (supplies the default),
  `optimizations.gc.profile` (Generational ZGC — the collector that benefits most from a smaller live
  set; `UseStringDeduplication` rides the same launch flags), `hosting.limits.max-heap-mb` (the
  pressure signal the `memory-dedup` doctor hook reads), and `hybrid.enabled` / `hybrid.safe-mode`
  (dedup stays on for modded worlds but is guarded — see risks).

## Vanilla-parity & plugin-compat risks

- **Behaviour-neutral by construction — the default path is 100% compatible.** Dedup changes only the
  *object identity / storage* of immutable data, never any value, event, timing, or generated output.
  Block states remain the same singletons (`==` identity preserved), `setValue`/`cycle`/`getValues`
  resolve to the exact same states, collision/occlusion math reads the same geometry, and every
  string compares equal (interned or not, `.equals` is unchanged; `==` only ever becomes *more* true).
  `dedup: false` restores Paper's representation exactly and is what `/victus doctor` reverts to.
- **Interner equals/hashCode hazard (the one real correctness risk).** Interning is only safe if the
  interned type has a correct content-based `equals`/`hashCode`. Property maps do; `VoxelShape` /
  `DiscreteVoxelShape` historically did **not**, so §2 adds content-equality **for the interner's use
  only**, never replacing the identity semantics Mojang relies on. If that equality can't be made
  provably correct against 26.1, `dedup-voxel-shapes` fails safe to off (shapes un-interned) rather
  than risk a mis-merge that would corrupt collisions. `TODO(verify)` 26.1 shape equality.
- **Making more objects `==` is safe; assuming inequality is not.** Some plugins compare `VoxelShape`
  or `BlockData` internals; interning can only turn two previously-`!=` equal objects into `==`
  (never the reverse). Code that treats two *equal* shapes as distinct via `==` would be relying on a
  vanilla implementation accident; this is extremely unlikely and would already be fragile. Noted as
  low risk; the corpus test (below) exercises WorldEdit/FAWE which touch enormous block-state volumes.
- **No new plugin API surface.** No new/changed Bukkit/Spigot/Paper events, no changed
  `BlockData`/`BlockState`/`VoxelShape` API semantics. `BlockData` still round-trips
  (`getAsString`/`createBlockData`), property get/set is unchanged.
- **Thread-safety.** All passes run once at bootstrap before any world ticks; the results are
  immutable for the JVM lifetime, so there is no concurrent-mutation surface and no per-tick cost.
- **Startup cost.** Adds a bounded one-time boot pass (target well under ~1 s on vanilla data;
  `TODO` measure and set a hard budget). It does not touch the tick loop. On a very large modded/
  datapack state census the pass is proportionally longer — acceptable, and paid once at boot.
- **Hybrid/modded guard.** Modded worlds are where block-state dedup pays the most, but mods can
  register states/shapes in nonstandard ways. Under `hybrid.enabled`, dedup stays on but is wrapped so
  `hybrid.safe-mode` can disable it if a specific mod's state/shape handling misbehaves — the module
  isolation rule (ARCHITECTURE §6) applies.
- **Forward-port risk.** The unobf class/field names for the neighbour table, the shape cache, and
  `StateDefinition` may have shifted (`TODO(verify)` throughout). If a pass can't hook safely, that
  sub-flag degrades to a **no-op / disabled**, never to a broken state — fail-safe like the chunks
  spec. Worst case is Paper's original footprint, not corruption.

## Tests & verification

- **State-equivalence golden test (the core invariant).** With `dedup: true` vs `false`, iterate
  **every** block state in the registry and assert: identical property values (`getValues`), identical
  `setValue`/`cycle` results (same `BlockState` singleton), and byte-identical serialized
  `BlockData` strings. Assert every block's collision/occlusion/support `VoxelShape` produces
  bit-identical intersection/collision results against a fixed AABB probe set. This proves interning
  changed nothing observable.
- **Save/load determinism.** Save a fixed, block-diverse world with `dedup` on and off; assert
  **byte-identical region files / chunk NBT** both ways — interning cannot corrupt persisted data.
- **Footprint test (the payoff).** Boot the *same* world + heap with `dedup: true` vs `false`, force a
  full GC, capture the **retained live set** (`jmap -histo:live` / heap dump / `GarbageCollectorMXBean`
  after `System.gc()`), and report absolute MB **and** a per-category breakdown (block-state tables,
  VoxelShapes, strings). Run on (a) vanilla SMP data — expect modest savings; (b) a **heavy-block-count
  datapack** — expect the larger delta that shows where the ~2× ceiling lives; honestly label which is
  which.
- **Startup-cost bench.** Measure the added boot time of each pass; assert under the budget and that
  it is one-time (no per-tick regression in the MSPT harness below).
- **GC-impact bench (ties to the GC spec).** Under the ≥150-bot real workload on identical hardware,
  compare **GC frequency, allocation rate, and retained-set floor** with `dedup` on vs off, under the
  default Generational ZGC. Expect fewer / less-frequent collections at a given heap and a lower
  steady-state floor — the smaller-live-set benefit — with **no** meaningful MSPT change either way
  (this optimization buys RAM + GC smoothness, not tick time).
- **JVM string-dedup awareness test.** Assert the engine warns exactly once when
  `-XX:+UseStringDeduplication` is absent and stays silent when present; assert it never attempts to
  set the flag at runtime.
- **Fail-safe test.** Simulate an un-hookable shape/state layout (or force the safe-off path) and
  assert the affected sub-flag disables cleanly, the server boots, and behaviour is identical to
  `dedup: false` — no crash, no partial dedup.
- **Top-50 plugin suite.** Block/NBT-heavy plugins — **WorldEdit / FAWE** (enormous block-state
  churn), WorldGuard, schematic/structure plugins, custom-block plugins — load and behave identically
  in the default path with `dedup: true`; zero regressions, per the product promise. FAWE is the
  headline stress case because it touches millions of block states.

## Honest expected gain

**Illustrative targets, to be proven by the Phase 1 benchmark harness — not measured facts.** This
subsystem's payoff is **RAM footprint** (→ node density / oversell headroom on Victus nodes) and a
smaller GC live set, **not** MSPT: gameplay CPU cost change is ~0 by design.

- **Heavy cases — up to ~2× lower footprint (ceiling, illustrative).** Reached only where the
  block-state census is large (heavy datapacks now; `hybrid`/modded worlds in Phase 4) and/or where
  loaded chunks/entities carry lots of repetitive string data. FerriteCore's oft-cited
  **1792 MB → 984 MB** is a **modded Fabric client** figure and is quoted here only as lineage — it is
  **not** a claim about a headless Paper server and must not be presented as one.
- **Vanilla Paper SMP — modest, honest.** Block-state and shape dedup shrink a **fixed floor** (order
  tens of MB, independent of player count; `TODO` quantify on the harness), and load-time string
  interning collapses a fixed string set. Expect a **single-digit to low-double-digit % heap
  reduction** on typical vanilla SMP — real and worth having for node density, but nowhere near 2×.
  The ~2× only appears in the heavy/modded cases above.
- **String data that scales with live state** (repetitive NBT keys across chunks/entities) is handled
  by the **JVM's** `-XX:+UseStringDeduplication` on the GC thread (item 4), so its benefit scales with
  loaded content but is a launch-flag win the engine only *surfaces*, not one Victus's code produces.
- **Indirect GC win.** A smaller permanent live set means each GC cycle scans less and the collector
  triggers less often at a given heap — most valuable under the default Generational ZGC, and
  complementary to the GC spec. Expect lower GC frequency / lower steady-state floor, not a tick-time
  change.
- **~0 gameplay / MSPT impact and ~0 downside** beyond a bounded one-time boot pass — which is exactly
  why `dedup` defaults on for every profile.

These are **targets for the Phase 1 harness to confirm on Victus hardware**, grounded in the
ARCHITECTURE §3 memory row ("~ up to 2× lower footprint") and FerriteCore's (modded) prior art — not
benchmarks we have run.

## Prior art / references

- **FerriteCore (malte0811).** The Fabric/Forge mod that deduplicates block-state metadata (property
  maps, neighbour tables, predefined value tables), VoxelShapes, and strings, plus client-side model/
  quad data. Victus adapts the **server-relevant** parts (block-state tables, VoxelShapes, load-time
  strings) and deliberately omits the **client-only** model/quad dedup, which is a no-op headless.
  Origin of the ~2× / 1792→984 MB figures — which are **modded-client** numbers, cited as lineage only.
- **JVM `-XX:+UseStringDeduplication`.** The HotSpot GC-thread string backing-array deduplicator;
  originally G1-only, **decoupled to all collectors (ZGC/Shenandoah included) in JDK 18+**, so it pairs
  with Victus's default Generational ZGC. Handles the runtime/scaling string class the engine's
  load-time interning does not. `TODO(verify)` exact support on the target JDK.
- **Guava `Interners` (weak/strong interner).** The canonicalization primitive for the load-time dedup
  passes (block-state maps, shapes, strings).
- **Mojang / vanilla `BlockBehaviour$BlockStateBase`, `StateDefinition`, `StateHolder`, `VoxelShape` /
  `Shapes` / `DiscreteVoxelShape` / `IndexMerger`, `ResourceLocation`, `CompoundTag`.** The structures
  whose internal redundancy is deduplicated and whose outcomes these passes preserve exactly; also the
  baseline whose existing (partial) singleton/interning behaviour Victus must not re-claim.
- **Hydrogen (CaffeineMC).** CaffeineMC's (now-retired) client memory-usage mod — named for context as
  another memory-footprint effort; **client-scoped**, not a source of server-side technique here.
- **PaperMC.** Upstream memory tweaks and any FerriteCore-adjacent ideas already merged by 26.1 — the
  baseline Victus builds on and must not double-count (`TODO(verify)` which reached 26.1).
