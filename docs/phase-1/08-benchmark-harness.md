# Phase 1 · Benchmark harness

## Goal

Build the machinery that **proves** Victus Engine is faster than stock Paper 26.1 instead of claiming
it. This is the enforcement mechanism for the two non-negotiable rules that already sit in the repo:
CONTRIBUTING §2 ("**No vanity metrics.** Benchmarks use ≥150 players, real workloads, MSPT
distribution — never idle TPS or startup time") and ROADMAP's "Benchmark discipline" ("Test on
identical hardware… ≥150 concurrent (bots)… report MSPT distribution… **No recycled host-blog
percentages — those are how the rest of the fork scene lies**").

Every other Phase 1 spec (`01`–`07`) ends with **"Illustrative targets, to be proven by the Phase 1
benchmark harness — not measured facts."** This spec is *that harness*. Its whole job is to convert
those illustrative ranges into measured, reproducible, third-party-checkable tables — or to refute
them. Concretely it delivers:

1. An **identical-hardware A/B methodology** — Victus Engine vs stock Paper 26.1, same plugin set,
   same world, same JDK, same client load — plus an **attribution matrix** so each optimization's
   win is isolated rather than lumped into one hand-wavy headline.
2. A **bot swarm** (headless client framework) that reaches **≥150 concurrent**, with a **spread-out**
   profile and a **clustered** profile — the second one exists specifically to expose regionized
   threading's real limit later (one region = one thread = ~1× when players pack).
3. **Realistic workloads**: populated survival + a standardized redstone farm/contraption suite + an
   entity/mob-farm suite + a chunk-exploration profile.
4. **MSPT distribution capture** (mean/p50/p95/p99/max + full histogram + missed-tick count) — and an
   explicit, load-bearing statement that **idle MSPT and startup time are vanity metrics** and are
   *not* deliverables.
5. **GC pause capture** (pause distribution + allocation rate + correlation to MSPT spikes).
6. **Per-subsystem timing** (entities / block-entities / redstone / chunk gen+IO / plugins / network),
   wired to the same per-subsystem sampler the lag-doctor / metrics module uses (ARCHITECTURE §5).
7. **Reproducible run scripts + result tables committed to the repo**, with a full reproducibility
   manifest (hardware, JDK, plugin versions, git SHA, `victus.yml`, seeds).
8. A **CI regression gate** that fails when MSPT p99 regresses beyond a threshold.

Crucially, this area is **almost entirely tooling under `bench/`, not a paperweight server patch.**
It is the *instrument*, not an engine feature. The only in-engine seam is a small, read-only,
off-by-default measurement export (item 6) — so the shipping default path pays nothing for it, exactly
like the GC and memory advisors in [`07-gc.md`](07-gc.md) / [`06-memory.md`](06-memory.md).

## Upstream baseline (what Paper 26.1 already provides)

**We must not claim the measurement primitives as ours.** Honest inventory of what already exists:

- **`/tps` and `/mspt` (Paper).** Paper exposes rolling **TPS** (1m/5m/15m) and **MSPT** over short
  windows. `/mspt` reports per-window figures (historically min / avg / max, colour-coded), not a
  full percentile distribution, and both are trivially gamed by reading them on an **idle** server.
  `TODO(verify against Paper 26.1)` the exact columns `/mspt` prints (they have drifted; some builds
  show a median/95%ile-flavoured triple).
- **spark (lucko) is the community-standard tick profiler.** Paper's own **Timings v2 was
  deprecated and removed**; spark is what Paper now points operators to. spark provides: a
  low-overhead **tick-loop sampling profiler** (`/spark profiler`), **`/spark tps`** and
  **`/spark health`** with **MSPT percentiles (p50/p95/p99/max)** and TPS, plus **GC pause** and
  **heap/allocation** summaries. This is the single most important primitive the harness builds on —
  we *drive and scrape spark*, we do not reimplement a profiler. `TODO(verify)` whether Paper 26.1
  **bundles** spark or expects it as a plugin install, and spark's exact command/JSON surface on 26.1.
- **The JVM provides GC data.** `-Xlog:gc*` unified logging and `GarbageCollectorMXBean` /
  `MemoryPoolMXBean` GC-notification listeners already expose pause times and allocation rate (the
  same beans [`07-gc.md`](07-gc.md)'s advisor reads). No new JVM work.
- **Paper's Prometheus story is plugin-provided.** Metrics exporters exist as plugins, but a
  first-class in-engine endpoint is Victus's (`hosting.metrics.prometheus.*`, already in
  `VICTUS-CONFIG.md`), delivered by the Phase 2 hosting moat. The harness scrapes it when present.

**What Paper does NOT provide — the Victus delta:**

- No **bot swarm** / synthetic-load generator. No way to reach ≥150 concurrent reproducibly.
- No **standardized workloads** — no shipped redstone-stress world, mob-farm suite, or exploration
  profile.
- No **A/B orchestration** (boot A, warm up, load, capture, tear down, boot B, diff) and no
  **attribution matrix** to separate GC gains from engine gains from opt-toggle gains.
- No **committed result tables**, no reproducibility manifest, no **CI regression gate**.
- No **per-subsystem attribution** at benchmark granularity beyond what spark's sampler infers; the
  crisp "62% of tick = entities in chunk 47,-12 from PluginX" breakdown is Victus's metrics/lag-doctor
  work, not upstream.

So the harness = **all the scaffolding around spark + JVM MXBeans + the Victus metrics endpoint**:
scenarios, swarm, orchestration, schema, tables, gate. Plus one thin server-side seam (item 6 below)
only where spark's granularity is insufficient for per-subsystem attribution.

## Design / patch plan

Two work streams: (A) the **harness under `bench/`** (the bulk — plain tooling, no engine risk) and
(B) a **minimal bench instrumentation seam** in the server (read-only, off by default). Neither
changes any gameplay path.

### 1. Identical-hardware A/B methodology (the core discipline)

The comparison is only honest if everything except the server jar is pinned. The harness fixes:

- **Hardware manifest.** One committed `bench/hardware/<hw-id>.yml` per rig: CPU model + core/thread
  count, pinned clock / turbo policy (turbo **disabled** or its variance documented), RAM, storage
  (NVMe model), kernel, and whether the box is bare-metal or a dedicated (not oversold) VM. Every
  result set references a `hw-id`. Numbers from different `hw-id`s are **never** compared.
- **Matched software.** Same **JDK** (build + version), same **top-50 plugin set** (pinned by version
  in `bench/plugins/PLUGINS.lock`), same **world**, same **seed**, same **view/simulation distance**,
  same `server.properties`. Anything that *must* differ (e.g. Victus's default `redstone:
  alternate-current` vs Paper's `VANILLA`) is either **matched** for the apples-to-apples arm or
  **isolated** in the attribution matrix below — and always documented in the manifest.
- **The A/B arms + attribution matrix.** A naive "Victus defaults vs Paper defaults" conflates a dozen
  changes and is exactly the recycled-blog trap. The harness runs a **matrix** per scenario:

  | Arm | Jar | GC | Opts | Purpose |
  | --- | --- | --- | --- | --- |
  | `paper-stock` | Paper 26.1 | G1 + Aikar | Paper defaults | the honest baseline |
  | `victus-g1-baseline` | Victus | G1 + Aikar | all opts **off** | prove Victus adds no regression at parity |
  | `victus-g1-opts` | Victus | G1 + Aikar | opts **on** | isolate engine opts **from** the GC switch |
  | `victus-zgc-opts` | Victus | Gen ZGC | opts **on** | the shipping default — headline number |
  | `victus-<one-opt>` | Victus | held constant | **one** opt toggled | per-feature attribution (redstone, DAB, async-tracker, dedup, …) |

  The `victus-g1-opts` vs `victus-zgc-opts` pair is exactly the GC A/B that [`07-gc.md`](07-gc.md)
  calls for; the single-opt arms feed each sibling spec's "Honest expected gain" section. Which arms
  run per scenario is declared in `scenario.yml`.
- **Run protocol.** For each arm: cold boot → **warm-up window** (JIT compile, chunk load, pool
  spin-up — discarded) → **measurement window** (fixed wall-clock, e.g. steady 10–15 min under full
  bot load) → capture → graceful teardown. **N repetitions** (default ≥5) → report **median across
  reps + inter-run variance**, never a single run. Warm-up and measurement lengths live in
  `bench/harness.yml`.
- **Isolation.** Server pinned to dedicated cores; the bot swarm runs on a **separate machine** over
  LAN so client CPU never steals from the server (with a bot-host saturation guard, see Tests). No
  other tenants on the box during a run.

### 2. Bot swarm — ≥150 concurrent, spread + clustered (`bench/bots/`)

A headless-client swarm generates the load. Real frameworks (see Prior art): **Mineflayer**
(PrismarineJS, Node.js) or **node-minecraft-protocol** for scriptable JS bots; **MCProtocolLib**
(Java) for a JVM swarm. Bots run on the separate load machine and connect over LAN.

- **Protocol compat with 26.1.** 26.1 is post-1.21.11; bot libraries lag the newest protocol. The
  swarm connects either through a bot lib updated for 26.1 **or** via **ViaVersion / ViaProxy**
  translation (the same Via stack the Victus fleet already runs — see infra memory).
  `TODO(verify)` current protocol support for 26.1 in the chosen bot lib; ViaProxy is the fallback.
- **Deterministic behaviour.** Each bot is seeded (fixed RNG seed per bot index) so the workload is
  **reproducible across arms** — the same walk paths, the same interactions, so Paper and Victus see
  an identical client load. Behaviours: random-walk / waypoint movement, chunk-loading travel, chat,
  block break/place, combat, and optional join/leave churn.
- **Two profiles (both required):**
  - **`spread`** — bots dispersed to distinct coordinates **beyond each other's view distance**, so
    each loads its own chunks and spreads entity/redstone/tick load across the map. This is the
    profile where **regionized threading** will later show its gain (Phase 3); in Phase 1 it maximizes
    chunk + entity breadth for the single-threaded core.
  - **`clustered`** — bots packed into one area (spawn / a PvP arena), all touching the same chunks.
    This is the **adversarial** profile that shows regionized threading's ceiling later (one region,
    one thread → ~1×, per ARCHITECTURE §0). In Phase 1 it stresses single-region entity/collision and
    network fan-out.
- **Scale.** Default target **≥150** concurrent (the CONTRIBUTING floor), parameterized so a big rig
  can push 300–1000 to find the knee. The swarm ramps in stages and holds at target for the
  measurement window.

### 3. Realistic workloads (`bench/scenarios/`)

Each scenario is a committed, self-describing bundle: a `scenario.yml` (arms to run, bot profile +
count, warm-up/measure windows), the world (committed region files **or** a `seed` + `build-steps`
recipe), a `victus.yml`, and the bot script. The four Phase 1 scenarios, each tied to a sibling spec:

- **`populated-survival`** — a realistic survival world (pre-built bases, farms, villagers, loaded
  chunks) with the **`spread`** swarm doing survival activity. The general-case headline workload.
- **`redstone-suite`** — **reuses the contraption parity corpus** from [`01-redstone.md`](01-redstone.md)
  (large dust grids, wire-heavy farms, clocks) driven with recorded inputs. Runs the redstone arms
  (`vanilla` / `alternate-current` / `eigencraft`) and isolates the **dust-tick portion** of MSPT via
  the per-subsystem timer.
- **`mob-farm-suite`** — mob farms, breeders, villager trading halls, dense item-entity piles →
  entity AI / tracking / spawning load. Exercises the deltas in
  [`02-entity-ai-pathfinding.md`](02-entity-ai-pathfinding.md) and
  [`03-entity-tracking-spawning-collision.md`](03-entity-tracking-spawning-collision.md) (DAB,
  async pathfinding/tracker, per-player spawns, activation ranges).
- **`chunk-exploration`** — bots travel in straight lines (elytra/boat) into ungenerated terrain to
  force sustained chunk **generation + IO**, stressing the Moonrise pools tuned in
  [`05-chunks.md`](05-chunks.md). The profile that catches gen stalls leaking into the tick.

### 4. Metric capture — MSPT distribution, not vanity (`bench/scripts/collect-metrics.sh`)

**Idle MSPT and startup time are vanity metrics and are explicitly out of scope as deliverables.**
Why, stated so no one re-adds them: an idle server does almost no per-tick work, so its MSPT/TPS says
nothing about behaviour under load; startup time is a **one-time** cost invisible to players mid-game.
Reporting either is precisely the "recycled host-blog percentage" dishonesty ROADMAP bans (and the
anti-pattern the YouHaveTrouble guide calls out). The harness therefore captures, **under sustained
bot load only**:

- **MSPT distribution** — **mean, p50, p95, p99, max**, plus the **full histogram** and the
  **missed-tick count** (ticks that overran the 50 ms budget). p99 and max are the headline; means
  hide the tail that players actually feel.
- **Sustained TPS** under load (not idle) and time-to-recover after a load spike.
- **Sources, cross-checked:** spark (`/spark tps`, `/spark health`, profiler export) **and** an
  independent per-tick `nanoTime` sampler exported through the Victus metrics endpoint (item 6) as a
  Prometheus histogram. Two independent sources must agree within tolerance (a self-check on the
  harness itself).

### 5. GC pause capture (`bench/scripts/collect-metrics.sh` + JVM logging)

- **Per arm:** enable `-Xlog:gc*:file=<run>/gc.log:tags,uptime,level` and attach a
  `GarbageCollectorMXBean` notification listener → emit **pause distribution** (p50/p95/p99/max),
  **allocation rate**, and cycle counts (major/minor for Generational ZGC).
- **Correlation:** overlay GC pause events on the per-tick MSPT series and compute how many MSPT
  spikes are **pause-correlated** — this is the exact "missed-tick correlation" headline
  [`07-gc.md`](07-gc.md) rests on (a ~180 ms G1 pause blowing ~3.6 ticks at once). This is *the*
  measurement that decides the G1-vs-ZGC arms.

### 6. Per-subsystem timing — the one server-side seam (`patches/paper-server/metrics/bench/`)

Attribution needs to know **where** the tick went, not just how long it took. The lag-doctor design
already specifies a "Rolling MSPT breakdown by subsystem (entities, block entities/hoppers, redstone,
chunk gen/IO, plugins, network)" fed by "the metrics module's per-subsystem timing" — that module is
Phase 2 (hosting moat). To avoid blocking Phase 1 on Phase 2, the harness ships a **minimal, shared
sampler**:

- A small patch group `patches/paper-server/metrics/bench/` (GPL-3.0) wraps the major tick phases with
  cheap timers keyed by subsystem, aggregating per-tick into the same schema the lag-doctor will
  consume — so Phase 2 **reuses** this, it is not throwaway.
- **Off by default.** Gated on `hosting.metrics.bench.subsystem-timing` (default **false**). The
  timers add a small but non-zero cost, so the shipping default path never pays it — only benchmark
  scenario `victus.yml`s flip it on. This mirrors lag-doctor's "togglable per-plugin timers" and the
  read-only/off-by-default posture of the GC and memory advisors.
- **Symmetric across arms.** When on, it is on for **both** Paper-comparable and Victus arms (Paper via
  its equivalent spark sampler) so instrumentation overhead cannot bias the A/B (validated by a
  sampler-on-vs-off control, see Tests).
- **Plugin attribution** reuses spark's sampler + the lag-doctor's cheap per-plugin event/scheduler
  timers where finer detail than phase-level is needed; the harness does not add new attribution code
  beyond the phase timers.

### 7. Orchestration, scripts & committed results (`bench/scripts/`, `bench/results/`)

Everything is a committed, re-runnable script (bash, matching `scripts/` conventions — `set -euo
pipefail`, `source scripts/dev-env.sh` for the C:-full workaround):

- `run-ab.sh <scenario>` — top-level: iterate the matrix arms × N reps, calling the below.
- `launch-server.sh <arm>` — boot Paper-stock or Victus with the arm's flag set (GC + opts). Owns the
  GC-flag matrix from [`07-gc.md`](07-gc.md).
- `launch-swarm.sh <profile> <count>` — start the bot swarm on the load machine.
- `collect-metrics.sh` — scrape spark + Prometheus + GC log into the raw capture files.
- `make-tables.sh` — reduce raw captures to `RESULTS.md` (human) + `results.json` (machine).
- `ci-microbench.sh` — the trimmed CI-gate runner (item 8).

Results are committed under `bench/results/<YYYY-MM-DD>-<hw-id>/`, each with a **`manifest.json`**
(git SHA of the jar, JDK, hardware id, plugin lockfile hash, seed, `victus.yml`, bot profile+count,
window lengths, rep count) so any third party can reproduce the run. This directly satisfies ROADMAP's
"publish Paper vs Victus tables with the exact plugin set + hardware."

### 8. CI regression gate (`ci-microbench.sh`, wired into `.github/workflows/`)

Honest constraint up front: **shared GitHub runners are far too noisy for the headline ≥150-bot
numbers** — variable neighbours, no core pinning, no ≥150 clients. So the gate is **two-tier**:

- **CI tier (every PR, shared `ubuntu-latest`).** A trimmed **micro-scenario** (a fixed, small
  redstone+entity load with a handful of local bots, short windows, several reps) compares the PR
  build against a **committed baseline captured on the same runner class** — a **relative,
  Victus-vs-Victus-baseline** delta, not an absolute Paper comparison. It **fails the build if MSPT
  p99 regresses beyond a threshold** (default `ci.p99-regress-fail-pct: 10` in `bench/harness.yml`),
  using the median of reps and a noise-floor guard so runner jitter alone can't red the build. This
  tier is a **coarse guardrail against catastrophic regressions**, and it is honestly labelled as
  such — not a source of publishable numbers.
- **Truth tier (self-hosted / nightly-manual, real hardware).** The full matrix × 4 scenarios × ≥150
  bots on a pinned `hw-id`, on a dedicated/self-hosted runner or run by hand, producing the committed
  `bench/results/` tables. This is where the real p99 gate and the published wins live. Extends the
  existing `.github/workflows/build.yml` with a separate `benchmark` workflow (`workflow_dispatch` +
  schedule) targeting the self-hosted runner label; `TODO` wire the self-hosted runner.

The gate threshold, warm-up/measure windows, rep counts, and noise floor all live in
`bench/harness.yml` so they are reviewable and tunable without touching scripts.

### Directory layout

```
bench/
  README.md                      # how to run; the anti-vanity-metric rules restated
  harness.yml                    # global: JDK, reps, warm-up/measure windows, ci gate threshold, noise floor
  hardware/
    <hw-id>.yml                  # one committed rig manifest per benchmark machine
  plugins/
    PLUGINS.lock                 # pinned top-50 plugin set (name + version + hash)
  scenarios/
    populated-survival/
      scenario.yml               # arms, bot profile+count, windows
      victus.yml                 # scenario config (enables bench.* instrumentation)
      world/                     # committed region files  (or:)
      world.recipe.yml           # seed + build-steps to regenerate the world
      bots.js                    # scenario-specific bot behaviour
    redstone-suite/              # reuses 01-redstone parity corpus
    mob-farm-suite/              # ties to 02/03 entity specs
    chunk-exploration/           # ties to 05 chunks
  bots/
    swarm/
      spread.js                  # spread-out profile
      clustered.js               # clustered profile (regionized-limit probe)
      lib/                       # shared bot harness (mineflayer / MCProtocolLib wrapper)
  scripts/
    run-ab.sh                    # full matrix A/B orchestration
    run-scenario.sh
    launch-server.sh             # arm -> jar + GC/opt flag set
    launch-swarm.sh              # profile + count on the load machine
    collect-metrics.sh           # spark + prometheus + gc log -> raw captures
    make-tables.sh               # raw -> RESULTS.md + results.json
    ci-microbench.sh             # CI-tier gate runner
  schema/
    metrics.schema.json          # the metrics schema (below)
    manifest.schema.json         # reproducibility manifest schema
  results/
    <YYYY-MM-DD>-<hw-id>/
      manifest.json
      <arm>.mspt.json            # distribution + histogram + missed-tick count
      <arm>.gc.json / gc.log
      <arm>.subsystems.json      # per-subsystem MSPT breakdown
      RESULTS.md                 # published human table
```

### Metrics schema (`bench/schema/metrics.schema.json`, sketch)

```jsonc
{
  "run_id": "2026-07-19-de1bench-populated-survival-victus-zgc-opts-rep3",
  "manifest_ref": "manifest.json",
  "arm": "victus-zgc-opts",
  "scenario": "populated-survival",
  "bot_profile": "spread",
  "bot_count": 150,
  "windows": { "warmup_s": 180, "measure_s": 900, "rep": 3, "reps_total": 5 },
  "mspt": {                       // milliseconds; the headline block
    "mean": 0.0, "p50": 0.0, "p95": 0.0, "p99": 0.0, "max": 0.0,
    "histogram_ms": [ /* bucketed counts */ ],
    "missed_ticks": 0,           // ticks over the 50ms budget
    "sample_source": ["spark", "victus-nanotime"]   // both, cross-checked
  },
  "tps_under_load": { "mean": 20.0, "p1_low": 20.0 },
  "gc": {
    "collector": "zgc-generational",
    "pause_ms": { "p50": 0.0, "p95": 0.0, "p99": 0.0, "max": 0.0 },
    "alloc_rate_mb_s": 0.0,
    "cycles": { "major": 0, "minor": 0 },
    "mspt_spikes_pause_correlated_pct": 0.0    // ties to 07-gc.md
  },
  "subsystems_pct": {             // % of measured tick time; ties to lag-doctor
    "entities": 0.0, "block_entities": 0.0, "redstone": 0.0,
    "chunk_gen_io": 0.0, "plugins": 0.0, "network": 0.0, "other": 0.0
  },
  "notes": "free-text: anomalies, bot-host load, matched/unmatched knobs"
}
```

**Idle MSPT and startup time have no field in this schema by design.**

## victus.yml keys

The harness is external tooling and adds **no** game-facing config. The **only** server-side surface
is the bench instrumentation seam (Design item 6), added under the existing
`hosting.metrics` block — consistent with `VICTUS-CONFIG.md`, additive, and **off by default** so the
shipping path is unchanged:

```yaml
hosting:
  metrics:
    # Benchmark-only instrumentation. OFF in the shipping default — enabled ONLY by
    # bench/scenarios/*/victus.yml during a measured run. Read-only, plugin-invisible.
    bench:
      # Export the FULL per-tick MSPT histogram (mean/p50/p95/p99/max + buckets) via the
      # metrics endpoint, on top of Paper's rolling averages. Small overhead → off by default.
      tick-histogram: false
      # Per-subsystem MSPT attribution timers (entities/block-entities/redstone/chunk-gen+io/
      # plugins/network). Same togglable timers the lag-doctor uses. Off by default.
      subsystem-timing: false
```

- **Defaults & profiles.** Both keys default **false** on **every** `engine.profile` (a benchmark run
  opts in explicitly via the scenario's `victus.yml`; no production profile turns them on). Any
  explicit key beats the profile overlay, per `VICTUS-CONFIG.md`.
- **Related keys used, not introduced:**
  - `hosting.metrics.prometheus.*` — the endpoint the harness scrapes for MSPT/GC/subsystem series.
  - `hosting.logging.format: json` — structured logs the harness parses per run.
  - `hosting.limits.max-mspt` — the harness can verify the soft-cap throttle behaves under load.
  - `engine.profile` and `optimizations.*` — flipped per arm to build the attribution matrix.
  - `optimizations.gc.profile` — the G1 vs ZGC arms ([`07-gc.md`](07-gc.md)); the advisor confirms the
    collector each arm actually ran under, recorded into `manifest.json`.
- **Non-`victus.yml` knobs** (CI threshold, windows, rep counts, noise floor, hardware id) live in
  `bench/harness.yml` and `bench/hardware/<hw-id>.yml`, **not** in `victus.yml` — they are harness
  policy, not server config.

## Vanilla-parity & plugin-compat risks

- **The harness cannot break the game path — by construction.** It is tooling under `bench/`; the sole
  in-engine code (bench instrumentation) is **read-only**, **off by default**, and **plugin-invisible**
  (it observes tick timing, changes no value, event, or outcome). Same compat posture as the GC and
  memory advisors: worst case is a missing measurement, never a broken server.
- **The harness *is* the compat gate.** ROADMAP's Phase 1 exit criterion — "0 plugin regressions on a
  top-50 plugin suite" — is executed **by this harness**: every A/B arm runs the pinned top-50 set, and
  a run is only valid if the suite loads and behaves identically on Victus and Paper. So the harness
  doesn't risk compat; it *proves* compat.
- **Measurement must not bias the measured (the subtle risk).** If instrumentation overhead differed
  between arms, the A/B would lie. Mitigations: (a) the sampler is **symmetric** — on for both arms or
  neither; (b) a **sampler-on-vs-off control** bounds its overhead (< budget) and it is applied
  equally; (c) two independent MSPT sources (spark + nanoTime) must agree, catching a biased sampler.
- **Fair-comparison risk (the honesty core).** Victus's *defaults* differ from Paper's (redstone AC,
  ZGC, dedup…). Comparing default-vs-default silently attributes all of it to "the engine." The
  **attribution matrix** (Design item 1) and the **matched-config / documented-unmatched-knob** rule
  exist precisely to prevent this recycled-blog dishonesty — every arm records exactly what differed.
- **Reproducibility risk.** Uncontrolled bot RNG, background load, or turbo-clock drift make numbers
  irreproducible. Mitigations: seeded deterministic bots, dedicated/pinned hardware, turbo policy
  fixed and recorded, N reps with reported variance, and a full committed manifest.
- **Bot-host bottleneck.** If the load machine saturates, it throttles the workload and the server
  looks artificially healthy. Mitigated by the bot-host saturation guard (Tests) that invalidates a
  run if client CPU/network is the limiter.

## Tests & verification

The harness must be tested like a product, because a biased harness is worse than none.

- **Null A/A test (the meta-test).** Run **Paper vs Paper** (identical jar, both arms) through the full
  pipeline. Expected: MSPT/GC/subsystem deltas **within the noise floor**. A non-zero A/A delta means
  the harness itself is biased — this gates every published run.
- **Repeatability / noise floor.** N reps of one arm; compute inter-run variance and a **confidence
  interval**. The noise floor must be **smaller than the smallest effect any spec claims** (e.g. can't
  credibly report a 5% entity win if the floor is ±8%) — record the floor in `RESULTS.md`.
- **Sampler-overhead control.** `subsystem-timing`/`tick-histogram` **on vs off** on the same arm;
  assert overhead within budget and that turning it on doesn't change relative arm ordering.
- **Source cross-check.** spark percentiles vs the nanoTime sampler must agree within tolerance;
  divergence flags a capture bug.
- **Bot-host saturation guard.** Assert the load machine's CPU/network stayed below saturation for the
  whole measurement window; otherwise mark the run **invalid** (workload was client-limited).
- **Scenario determinism.** Same seed + same seeded bot script ⇒ reproducible chunk/entity/redstone
  load across reps and arms (esp. the `redstone-suite`, reusing 01's deterministic input traces).
- **CI-gate self-test.** A deliberately-regressed PR (e.g. an artificial `Thread.sleep` in a hot
  phase) **must** red the `ci-microbench.sh` gate; a no-op PR must pass. Proves the gate actually bites
  and isn't just decorative.
- **Manifest completeness.** Every committed result validates against `manifest.schema.json`
  (git SHA, JDK, hw-id, plugin lockfile hash, seed, `victus.yml`, windows, reps) — a run missing any
  field is not publishable.
- **Cross-hardware sanity.** Same scenario on two `hw-id`s to confirm the *direction* of wins is
  hardware-independent (magnitudes will differ — never cross-compare magnitudes across `hw-id`s).

## Honest expected gain

**The harness has no MSPT gain of its own — it is measurement infrastructure, not an optimization.**
Its deliverable is *epistemic*: it turns the illustrative ranges in specs `01`–`07` into measured,
reproducible, third-party-checkable tables, and it is the mechanism that keeps Victus out of the
"recycled host-blog percentage" trap the whole fork scene falls into (ROADMAP; the YouHaveTrouble
guide's core point). Stated honestly:

- **What it will confirm or refute (targets, not measured facts):** the sibling-spec ranges —
  redstone dust up to **~30×** (typ. ~10×) ([`01`](01-redstone.md)); entity MSPT **~15–40%**
  ([`02`](02-entity-ai-pathfinding.md)/[`03`](03-entity-tracking-spawning-collision.md)); chunk gen
  stalls removed from the tick ([`05`](05-chunks.md)); memory footprint **~up to 2×**
  ([`06`](06-memory.md)); and the GC headline — **p99/max MSPT tail markedly reduced** by Generational
  ZGC's sub-ms pauses, with median possibly a few % either way ([`07`](07-gc.md)). These remain
  **illustrative targets for this harness to prove on Victus hardware**, per ARCHITECTURE §3.
- **Its own cost:** the bench instrumentation is **off by default → zero cost in production**; when on
  during a run it adds a small, bounded, symmetric overhead (verified by the sampler-overhead control).
- **The point restated:** the harness's entire value is that it stops us inventing numbers. Any figure
  Victus publishes must come out of `bench/results/` with a manifest, or it doesn't get published.

## Prior art / references

- **spark (lucko).** The tick-loop sampling profiler + `/spark tps` / `/spark health` MSPT percentiles
  + GC/heap reporting — the primary measurement primitive the harness drives and scrapes. The de-facto
  successor to Paper's removed Timings.
- **Paper Timings v2 (deprecated/removed).** Historical context — the in-server profiler spark
  replaced; named to explain why the harness standardizes on spark + MXBeans.
- **YouHaveTrouble "Minecraft server optimization" guide.** The reference for **why idle TPS/MSPT and
  startup time are meaningless** and load-based MSPT distribution is the only honest metric — the
  anti-vanity-metric doctrine this spec enforces.
- **Mineflayer / node-minecraft-protocol (PrismarineJS)** and **MCProtocolLib.** Headless
  client/bot frameworks for the swarm (JS and JVM respectively).
- **ViaVersion / ViaProxy.** Protocol translation so the swarm can speak to a 26.1 server if the bot
  lib lags — the same Via stack the Victus fleet already runs.
- **JMH (OpenJDK).** Not for tick-loop runs, but the reference discipline for any **isolated
  micro-benchmark** (e.g. the AC dust algorithm in a vacuum) — warm-up, forks, and statistics done
  right, so unit-level claims are as honest as the whole-server ones.
- **OpenJDK GC logging / MXBeans (`-Xlog:gc*`, `GarbageCollectorMXBean`).** The GC pause + allocation
  primitives feeding the G1-vs-ZGC arms; shared with [`07-gc.md`](07-gc.md)'s advisor.
- **Sibling specs & modules.** [`01-redstone.md`](01-redstone.md) (parity corpus reused as the
  redstone scenario), [`02`](02-entity-ai-pathfinding.md)/[`03`](03-entity-tracking-spawning-collision.md)
  (mob-farm suite), [`05-chunks.md`](05-chunks.md) (exploration profile),
  [`06-memory.md`](06-memory.md)/[`07-gc.md`](07-gc.md) (GC A/B matrix + pause capture),
  [`../../design/modules/lag-doctor.md`](../../design/modules/lag-doctor.md) (the per-subsystem timing
  source item 6 shares) and ARCHITECTURE §5 (the metrics endpoint the harness scrapes).
- **ROADMAP "Benchmark discipline (non-negotiable)" + CONTRIBUTING §2.** The in-repo rules this spec
  operationalizes: identical hardware, ≥150 bots, real workloads, MSPT distribution, published tables,
  no recycled percentages.
