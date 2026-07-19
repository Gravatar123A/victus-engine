# Phase 1 · Chunk load / gen / IO

## Goal

Keep world streaming — chunk **loading**, **generation**, and **IO (save/serialize)** — from ever
stalling the tick, on **exploration-heavy** and multi-core servers, **without changing generated
output or any plugin-observable behavior in the default (`single`) path**.

The critical honesty up front: Paper 26.1 already ships the **Moonrise chunk system** (fully async
load/gen/IO) and **Starlight** lighting. **Victus does not reimplement any of that, and does not add
C2ME** (which is mutually exclusive with Moonrise — see baseline). Victus's delta here is *tuning +
tooling*, not a new chunk engine:

1. **First-class pool tuning** — surface Moonrise's worker/IO thread pools through two ergonomic
   `victus.yml` keys (`optimizations.chunks.worker-threads` / `.io-threads`, default `auto`),
   **cgroup-quota-aware** so a capped hosting tenant doesn't oversubscribe cores across the tick,
   chunk, tracker and pathfinder pools at once.
2. **Per-player generation-rate cap** (`optimizations.chunks.max-generate-rate`, default `8`
   chunks/sec/player) — bind Paper's existing per-player generation throttle to a first-class key and
   default it **on** so a single fast-travelling / elytra / teleporting player can't spike the whole
   server's MSPT with a burst of on-demand generation. Pacing only; generated output is identical.
3. **A world pre-generation tool/command** (`/victus pregen`, Chunky-class) — the genuinely new
   server-code in this spec. Generates a bounded region ahead of time through the *same* Moonrise
   pipeline, throttled to a live-MSPT budget so it never starves the running server, resumable across
   restarts. Pre-gen moves the one-time generation cost fully out of the live tick, cuts chunk-worker
   demand during play, and is a **prerequisite for good regionized-threading behavior later**
   (Phase 3).

Everything behavior-changing is a toggle whose compat-preserving value is always reachable, and the
engine reports the resolved values and any pre-gen activity via `/victus doctor`.

## Upstream baseline (what Paper 26.1 already provides)

Nearly the entire chunk *engine* is upstream. **We must not re-claim it.** Honest inventory:

- **Moonrise chunk system — already baseline (Spottedleaf).** Paper 26.1 ships the fully-rewritten
  async chunk system: chunk **loading, generation, and IO run off the main thread** on dedicated
  worker/IO pools with a priority scheduler; the main tick consumes finished chunks. This supersedes
  the old Tuinity-era `chunk-loading-basic`/`-advanced` machinery Paper carried for years. We *ride*
  it; we do not touch its scheduling core.
- **Starlight — already baseline (Spottedleaf).** The rewritten lighting engine is integrated; block
  and sky light are computed off the legacy `LevelLightEngine` hot path. Out of scope here; we do not
  patch lighting.
- **The pools are already configurable.** Paper exposes the Moonrise thread pools in
  `config/paper-global.yml → chunk-system.{worker-threads, io-threads}` (both default `-1` = auto),
  plus `chunk-system.gen-parallelism`. It also exposes per-player streaming throttles in
  `paper-world-defaults.yml → chunk-loading-basic.{player-max-chunk-generate-rate,
  player-max-chunk-load-rate, player-max-chunk-send-rate}` (all default `-1.0` = unlimited) and
  `chunk-loading-advanced.{player-max-concurrent-chunk-generates, player-max-concurrent-chunk-loads}`.
  `TODO(verify against Paper 26.1 source once building online)` — the exact YAML paths/keys and their
  auto-formulas have drifted across Paper versions and again across the unobfuscation cutover.
- **Paper's shipped defaults leave gen effectively unthrottled.** `worker-threads`/`io-threads` at
  `-1` (auto) and `player-max-chunk-generate-rate` at `-1.0` (unlimited) are fine for a single-tenant
  box but are the two knobs a *host* most wants tuned. That gap is exactly Victus's delta (1) and (2).
- **No built-in pre-generation.** Paper has **no** first-party world pre-gen command; operators reach
  for the Chunky plugin. That is the genuinely-new tooling this spec adds as (3).

**C2ME is the road not taken.** CaffeineMC's C2ME is an *alternative* concurrent chunk-management
engine (Fabric). It **rewrites the same subsystem Moonrise rewrites**, so the two are **mutually
exclusive and redundant** — you run one or the other, never both. Victus is a Paper fork on Moonrise;
adding C2ME is out of scope and would conflict with the baseline. We name it here only so reviewers
know the decision was deliberate.

So the genuinely-new server-code in this spec is **(3) the pre-gen tool**. Items (1) and (2) are
config binding + profile overlays living in the shared `victus.yml` core, not chunk-engine patches.

## Design / patch plan

Two config bindings (shared config core) + one real tool. The config resolution (key parsing,
profile overlays, `auto`⇄`-1` and `0`⇄`-1.0` coercion) reuses the Phase 0 `victus.yml` loader — this
spec only registers the keys, their coercion, and their profile defaults.

### 1. Chunk-system pool binding — config core (`optimizations.chunks.worker-threads` / `.io-threads`)

Map the two victus.yml keys onto Paper's global chunk-system pool config at boot, before the chunk
system is constructed:

| `victus.yml` | Paper target (`TODO(verify)` path) |
| --- | --- |
| `optimizations.chunks.worker-threads` | `paper-global.yml → chunk-system.worker-threads` |
| `optimizations.chunks.io-threads` | `paper-global.yml → chunk-system.io-threads` |

- **Coercion:** `auto` → Paper `-1` (let Moonrise pick); an explicit integer → that integer.
- **`auto` sizing (the hosting-aware part).** `auto` resolves to **≈ the number of logical CPU
  threads visible to *this instance*** — but derived from the **cgroup/container CPU quota**
  (`hosting.limits.cpu-quota-pct` when set, else the container/`Runtime.availableProcessors()`
  ceiling), **not** the physical host's core count. This is the difference that matters on a shared
  Victus node: sizing chunk workers to the whole box would oversubscribe cores against the tick
  thread, the tracker pool (§03), and the pathfinder pool (§02) running in the same JVM. Unlike those
  two pools — which are sized *small* (~25% cores) because they run *alongside* the tick every tick —
  the chunk worker pool may claim close to the full instance quota, because chunk work is the
  **bursty, off-tick** workload that benefits from parallel catch-up. `io-threads` `auto` resolves
  smaller (disk is the bottleneck, not CPU): a fraction of the worker count with a small floor.
  `TODO(verify)` the exact auto-formulas Moonrise uses in 26.1 so our `auto` matches or deliberately
  overrides them.
- **Precedence** (mirrors the redstone/entities specs): explicit `paper-global.yml` value set by the
  operator > victus.yml > profile default > Paper's own `auto`. The one-time import of a pre-existing
  Paper value goes through `compatibility.migrate-config-on-boot`, after which victus.yml is source of
  truth. The engine logs the resolved worker/IO counts at boot.
- **Pure performance knobs — zero parity/compat risk.** These change *how fast* chunks stream, never
  *what* is generated or any plugin-observable event. `gen-parallelism` is left at Paper's default and
  not surfaced as a first-class key in Phase 1 (documented escape hatch via raw paper-global.yml).

### 2. Per-player generation-rate cap — config core (`optimizations.chunks.max-generate-rate`)

Bind the victus.yml key onto Paper's per-player generation throttle and default it **on**:

| `victus.yml` | Paper target (`TODO(verify)` path) |
| --- | --- |
| `optimizations.chunks.max-generate-rate` | `paper-world-defaults.yml → chunk-loading-basic.player-max-chunk-generate-rate` |

- **Coercion & units.** victus.yml uses **chunks/sec/player**, with **`0` = unlimited**; Paper uses a
  double where **`-1.0` = unlimited**. Mapping: `0 → -1.0`, `N → (double) N`.
- **Default `8`.** Caps the rate at which each player can force **new** chunk *generation*. The target
  is the classic exploration spike: one player elytra-ing or `/tp`-ing into ungenerated terrain queues
  a burst of generation that lands on the tick as a p99/p999 MSPT stall for *everyone*. Capping the
  per-player gen rate smooths that burst across ticks. **Pacing only** — the terrain that eventually
  generates is byte-identical; a fast traveller just sees terrain stream in a little slower under
  extreme movement instead of freezing the server.
- **Not a plugin-compat risk.** Because it changes only *when* chunks generate, never *what* generates
  or which chunk events fire, this is safe to default on for **every** profile — it is the rare knob
  that helps all server types and risks nothing behavioral. (`load-rate` / `send-rate` siblings stay
  at Paper defaults in Phase 1; only generation is the spike source we default-cap.)
- **Precedence** identical to §1 (explicit per-world Paper value > victus.yml > profile default >
  Paper default). Resolved per-world value logged at boot.

### 3. Profile overlays — config core

Overlays, not locks — any explicit key the operator sets still wins (`VICTUS-CONFIG.md`):

| Profile | `worker-threads` | `io-threads` | `max-generate-rate` | Rationale |
| --- | --- | --- | --- | --- |
| `smp` | `auto` | `auto` | `8` | exploration-heavy; smooth streaming, kill gen spikes |
| `technical` | `auto` | `auto` | `0` (unlimited) | testers want instant gen; pacing is not a parity concern |
| `minigames` | `auto` | `auto` | `2` | maps are pre-gen'd & static; almost no live gen |
| `modded` | `auto` | `auto` | `4` | modded worldgen is far heavier per chunk → cap tighter |
| `network` | `auto` | `auto` | `2` | hubs are static & pre-gen'd; conserve cores across many instances |

Note `technical` sets `max-generate-rate: 0` (unlimited) not for parity reasons — output is identical
either way — but because technical/creative testers value instantaneous terrain over spike-smoothing.
`worker-threads`/`io-threads` stay `auto` everywhere; the quota-aware `auto` already does the right
thing per node, and a hosting operator can pin integers via the panel.

### 4. World pre-generation tool — `patches/paper-server/tooling/chunk-pregen/` (GPL-3.0)

The one real piece of new code. A Chunky-class pre-generator exposed through the shared `/victus`
command framework (same surface as `/victus doctor`), console- and panel-callable so Victus Wings can
drive it.

**Command surface.**

```
/victus pregen start <world> radius <n><blocks|chunks> [around <x> <z> | spawn]
/victus pregen start <world> region <minX> <minZ> <maxX> <maxZ>   # chunk coords
        [--rate <chunks/sec>] [--mspt-budget <ms>] [--pause-with-players]
/victus pregen status                # per-world: done/total, %, ETA, current rate, throttle state
/victus pregen pause | resume | cancel [<world>]
```

**Generation flow (feed Moonrise, never bypass it).**

1. **Enumerate the target chunk set** for the requested shape (radius around a center or spawn, or an
   explicit region box), in a locality-friendly order (spiral / row-major within region-file
   boundaries) so IO batches per region file.
2. **Skip already-present chunks.** Before submitting, check the region files / chunk-exists path so a
   partially-explored or partially-pre-genned world is not regenerated. Skipped chunks count as done.
3. **Submit through the chunk system's async generation API** — add a generation **ticket** at the
   full-generation status for each target chunk, bounded to at most `max-concurrent` in-flight
   requests, and await completion via the completion callback. The actual generation runs on
   Moonrise's own worker pool (§1); the driver only *schedules* requests and performs
   ticket add/remove on the main thread as the chunk-system API requires. `TODO(verify)` the exact
   26.1 ticket type + target `ChunkStatus`/`FULL` API and the supported "generate-then-release"
   entrypoint.
4. **Release + save immediately.** As each chunk finishes, drop its generation ticket so the chunk
   system can save and **unload** it. Pre-gen must **not** keep the whole region resident, or a large
   radius OOMs the instance. Memory stays bounded by `max-concurrent`, not by region size.
5. **Persist progress.** A per-world resume record (`victus-pregen/<world>.progress` — a cursor +
   completed-chunk bitset, `TODO(verify)` format) is flushed periodically so a restart or crash
   resumes exactly where it left off. `resume: true` (default) reopens it on `start`.

**Hosting-aware back-pressure (the load-bearing property).** Pre-gen is a *background* job that must
never degrade the *live* server. Before submitting each batch the driver reads the rolling live MSPT:
while it exceeds `pregen.mspt-budget` (default `40`, kept **below** `hosting.limits.max-mspt` = 45), it
**stops submitting new work** and lets in-flight requests drain; it resumes when the tick has
headroom. `--rate` additionally caps chunks/sec regardless of headroom. `pause-with-players: true`
runs pre-gen **only while no players are online** (ideal for a scheduled overnight pre-gen via Victus
Wings + `hosting.control.safe-restart-hook`). This ties pre-gen into the hosting control plane rather
than being a fire-and-forget CPU hog.

**Custom worldgen is honored.** Because pre-gen drives the *same* generation pipeline, plugin
`ChunkGenerator`s / custom biome providers / datapack worldgen produce identical results to on-demand
generation, and `ChunkLoad`/`ChunkPopulate`/`ChunkUnload` events fire as they normally would (fired
on the main thread by the chunk system, unchanged). Pre-gen adds no new generation path to keep
parity with.

### Lag-doctor integration

Chunk gen/IO is already a first-class `/victus doctor` subsystem row (ARCHITECTURE §5). This spec
wires thin remediations to the existing per-subsystem chunk timer + the exploration-spike detector —
no new diagnosis code:

- `pregen-region` — when doctor sees repeated gen-stall spikes attributable to players entering
  ungenerated terrain, suggest `/victus pregen` for the hot area (states the one-time cost).
- `chunk-generate-rate` — writes/lowers `optimizations.chunks.max-generate-rate` when a single
  player's generation burst is the spike source; symmetric raise to revert.
- `chunk-workers` — suggests raising/lowering `worker-threads`/`io-threads` when the chunk pools are
  saturated (raise) or contending with the tick on a small box (lower). Each states its cost.

## victus.yml keys

The three headline keys already exist in `VICTUS-CONFIG.md`; this spec pins their semantics,
coercion, per-profile defaults, and the Paper mappings, and adds the `pregen:` sub-block for
command defaults.

```yaml
optimizations:
  chunks:
    # Moonrise worker pool (gen/load/light compute). auto = size to this instance's CPU quota
    # (cgroup/container-aware), NOT the physical host. Explicit integer overrides. Pure perf knob.
    worker-threads: auto
    # Moonrise IO pool (chunk (de)serialization to disk). auto resolves smaller than worker-threads.
    io-threads: auto

    # Per-player NEW-chunk generation cap, chunks/sec/player. 0 = unlimited.
    # Smooths exploration/elytra/teleport gen spikes. Pacing only — generated output is identical.
    max-generate-rate: 8

    # Defaults for the `/victus pregen` background pre-generation tool (command-driven).
    pregen:
      mspt-budget: 40            # pause submitting new gen work while live MSPT exceeds this
                                 # (keep < hosting.limits.max-mspt so pre-gen never starves the tick)
      max-concurrent: auto       # in-flight gen requests; auto ~= worker-threads. Bounds memory.
      pause-with-players: false  # true = only pre-gen while no players are online
      resume: true               # persist + resume progress across restarts
```

- **Defaults & profiles.** `worker-threads`/`io-threads` = `auto` on every profile;
  `max-generate-rate` = `8` (`smp`), `0`/unlimited (`technical`), `2` (`minigames`/`network`), `4`
  (`modded`) — see §3. `pregen.*` are inert until `/victus pregen` is invoked. Any explicit key beats
  the profile overlay, per `VICTUS-CONFIG.md`.
- **Consistent with the top-level schema.** `worker-threads`, `io-threads`, and `max-generate-rate`
  already appear in `VICTUS-CONFIG.md`; this spec pins them and **adds** the `chunks.pregen.*` keys.
- **Related keys used, not introduced:** `engine.profile` (supplies defaults),
  `hosting.limits.max-mspt` (upper bound the pre-gen `mspt-budget` stays under),
  `hosting.limits.cpu-quota-pct` (feeds quota-aware `auto` pool sizing),
  `hosting.control.safe-restart-hook` (scheduled/overnight pre-gen via Victus Wings),
  `compatibility.migrate-config-on-boot` (one-time import of any pre-existing Paper
  `chunk-system.*` / `chunk-loading-basic.*` values).

## Vanilla-parity & plugin-compat risks

- **The chunk engine is untouched — Paper's Moonrise, verbatim.** We change *pool sizes* and a
  *per-player pacing cap*, and we add a *tool that feeds the existing pipeline*. None of that alters
  generated terrain, chunk NBT, lighting, or the order/identity of `ChunkLoad`/`ChunkPopulate`/
  `ChunkUnload` events. The default path is 100% compatible by construction.
- **Pool tuning is a pure perf knob.** `worker-threads`/`io-threads` change throughput only; the
  compat-preserving value (`auto`) is the default and is what `/victus doctor` reverts to. The one
  real *operational* risk is **oversubscription on shared hosts** — hence quota-aware `auto` sizing so
  a tenant can't grab the whole box's cores and starve co-tenants or its own tick. Setting the ints
  too high on a small box can *hurt* (chunk pool steals from the tick); the lag-doctor `chunk-workers`
  remediation both raises and lowers.
- **`max-generate-rate` is pacing, not outcome.** Identical terrain generates; only the *rate* of
  on-demand generation per player is capped. No plugin API observes a difference (chunk events still
  fire when a chunk actually loads/generates). Worst case of an over-tight value is *visibly slower
  terrain streaming during fast travel*, never wrong or missing terrain. `0` restores Paper's
  unlimited behavior; `technical` uses it by default.
- **Pre-gen parity.** Pre-generated chunks are byte-identical to on-demand ones because they go
  through the same pipeline (verified in Tests). Custom `ChunkGenerator` plugins and datapack worldgen
  are honored. The only pre-gen-specific hazards are **operational**, and each is guarded: OOM (bounded
  by `max-concurrent` + immediate unload), starving the live tick (bounded by `mspt-budget` /
  `pause-with-players`), and a huge job blocking shutdown (resumable + cancellable, flushes progress).
- **No new plugin API surface.** Two config bindings + a `/victus` sub-command. No new Bukkit/Spigot/
  Paper events, no changes to `World#getChunkAt` / async-chunk API semantics. `/victus pregen` uses
  the existing command framework and permission node (`victus.command.pregen`), gated to ops/console/
  panel like the rest of `/victus`.
- **Forward-port risk.** The unobfuscation cutover may have shifted Moonrise's ticket/`ChunkStatus`
  API and the paper-global/paper-world-defaults key paths (`TODO(verify)` markers throughout). If the
  pre-gen ticket entrypoint changed, pre-gen is the only thing affected — the config bindings degrade
  gracefully to Paper's own `auto`/unlimited defaults, never to a broken chunk tick.

## Tests & verification

- **Pre-gen determinism / output-parity (the core invariant).** Generate a region via `/victus pregen`
  on a fixed seed, and generate the *same* region on the same seed by normal exploration on a clean
  world; assert the resulting **region files / chunk NBT are byte-identical** (block state, biomes,
  structures, heightmaps, block entities). Repeat with a plugin `ChunkGenerator` and with a custom
  datapack dimension to prove custom worldgen is honored identically.
- **`max-generate-rate` output-parity + pacing.** Assert that varying `max-generate-rate` (`0`, `8`,
  `2`) produces **identical generated chunks** and identical `ChunkLoad`/`ChunkPopulate` event sets
  for the same exploration trace — only the *timing* (ticks-to-fully-stream) differs. Assert `0`
  reproduces Paper-unlimited behavior exactly.
- **Pool binding / resolution tests.** `auto` resolves to the expected worker/IO counts under given
  logical-core counts **and** cgroup CPU quotas (assert it follows the quota, not the host cores);
  explicit integers are honored; precedence (explicit paper-global value > victus.yml > profile >
  Paper auto) holds; migration import path works; resolved counts logged at boot.
- **Pre-gen back-pressure test.** Run pre-gen while a scripted live workload holds the server near
  `mspt-budget`; assert pre-gen throttles/pauses submission and that live MSPT is **not pushed past
  `mspt-budget` beyond tolerance**. Assert `--rate` caps chunks/sec and `pause-with-players` halts
  when a player joins and resumes on the last player leaving.
- **Pre-gen memory-bound test.** Pre-gen a large radius (e.g. 10k-block) on a small heap; assert
  resident chunk count stays bounded by `max-concurrent` (chunks are unloaded as generated) and there
  is no unbounded heap growth / OOM.
- **Pre-gen resume / crash-safety test.** Kill the server mid-pre-gen; on restart assert it resumes
  from the persisted cursor, **skips already-generated chunks** (no duplicate generation, no wasted
  IO), and eventually completes the full set. Assert `cancel` stops cleanly and flushes progress.
- **Concurrency / correctness under load.** Run pre-gen concurrently with players actively loading the
  same and adjacent chunks; assert no `ConcurrentModificationException`, no torn/duplicate chunks, no
  lost tickets (a player-loaded chunk must not be unloaded out from under them by the pre-gen release),
  and that ticket add/remove happens on the main thread where the API requires it.
- **Benchmark harness (Phase 1 discipline, see ROADMAP & CONTRIBUTING).** An **exploration** workload
  — ≥150 bots flying/elytra-ing/teleporting into ungenerated terrain on identical hardware — measuring
  the **MSPT distribution, especially p99/p999** (this subsystem's win is tail-latency, not mean).
  Report: (a) baseline Paper defaults, (b) `max-generate-rate: 8`, (c) worker/IO pool sizing sweep,
  (d) same route over a **pre-generated** world. The pre-gen job itself is separately benchmarked for
  chunks/sec vs `mspt-budget` and for its live-MSPT impact while running.
- **Top-50 plugin suite.** Chunk-adjacent plugins (WorldEdit/FAWE, WorldGuard, custom worldgen,
  claim/region plugins, map renderers like dynmap/BlueMap) load and behave identically in the default
  path, and interoperate with a running `/victus pregen` — zero regressions, per the product promise.

## Honest expected gain

**Illustrative targets, to be proven by the Phase 1 benchmark harness — not measured facts.** This
subsystem's payoff is overwhelmingly in the **MSPT distribution (tail latency)**, not steady-state
mean MSPT. On a static, already-generated world with no exploration, **every number here is
near-zero** — nothing is being generated, so there is nothing to unstall.

- **`max-generate-rate` cap:** targets **elimination of the exploration/elytra/teleport gen-stall
  spikes** — the dominant p99/p999 tail-latency source on exploration-heavy survival. Benefits
  **every server type where players explore**; ~0 where the world is pre-generated or static. Trades a
  little terrain-streaming smoothness under extreme movement for a flat tick; no mean-MSPT change.
- **Worker/IO pool tuning:** faster parallel **catch-up** when many chunks are demanded at once (mass
  join, world-border expansion, spawn-radius load), i.e. shorter and rarer stalls on multi-core boxes.
  **~0** benefit if already saturated or on a single-core/quota-of-1 instance, and can **hurt** if set
  above the instance's quota (chunk pool steals from the tick) — which quota-aware `auto` is designed
  to prevent.
- **Pre-generation:** the largest and most reliable win — it moves the **one-time generation cost
  entirely out of the live tick** for the pre-genned area, so exploration into that area produces
  **zero** gen stalls. It also reduces sustained chunk-worker demand during play and is a
  **prerequisite for good `regionized` behavior in Phase 3** (on-demand generation across region
  boundaries is a contention/coordination hazard that a pre-generated world removes). Cost is one-time
  disk + CPU, paid in the background under a live-MSPT budget.

These are **targets for the Phase 1 harness to confirm on Victus hardware**, grounded in the
ARCHITECTURE §3 chunk row ("removes gen stalls from tick") and the prior art below — not benchmarks we
have run.

## Prior art / references

- **Moonrise (Spottedleaf).** The rewritten async chunk system (load/gen/IO + integrated Starlight)
  that Paper 26.1 ships and Victus tunes rather than reimplements. Available standalone (Fabric) and
  integrated into Paper.
- **Tuinity (Spottedleaf).** The predecessor chunk-system rewrite whose async-chunk work fed
  Paper/Moonrise; context for why Paper's chunk pipeline is already off-thread.
- **Starlight (Spottedleaf).** The rewritten lighting engine, baseline in Paper; out of scope for
  this spec but part of the same chunk-streaming path.
- **C2ME (CaffeineMC).** The **alternative, incompatible** concurrent chunk-management engine — it
  rewrites the same subsystem as Moonrise and is therefore mutually exclusive with it. Named here to
  document that Victus deliberately stays on Moonrise and does **not** integrate C2ME.
- **Chunky (pop4959).** The reference world pre-generation plugin; model for `/victus pregen`'s UX
  (shape selection, per-second rate limiting, resumable progress) — reimplemented in-engine so it can
  hook the hosting control plane (MSPT budget, `pause-with-players`, Wings scheduling).
- **PaperMC.** Upstream `chunk-system.{worker-threads, io-threads}` (paper-global) and
  `chunk-loading-basic.player-max-chunk-generate-rate` (paper-world-defaults) — the options Victus
  binds to first-class keys, makes quota-aware, and defaults per profile.
- **Mojang / vanilla `ChunkMap` / `ServerChunkCache` / `ChunkStatus`.** The generation-status
  pipeline and ticket model the pre-gen tool drives; the baseline chunk behavior these optimizations
  preserve outcomes against.
