# Victus Engine — build status

_Living log of what's real vs. planned. Newest first._

## ⭐ 2026-07-20 — PERF ROOT-CAUSE FOUND + FIXED: the fork was heavier than Paper by its own JVM config (ZGC), not its code

Owner reported "RAM/CPU/chunk as bad or worse than Paper." A 6-angle deep research workflow + **direct
A/B measurement on DE-1** (elastic heap, PSS via `/proc/PID/smaps_rollup`, NMT) found the real cause:
**Victus was the only process on the node running Generational ZGC; every Paper peer runs G1+Aikar.**

Measured, near-idle, same jar/world:

| GC config (elastic heap) | PSS | NMT committed |
| --- | --- | --- |
| Generational ZGC (old default) | **1.96 GB** | 1.86 GB |
| G1+Aikar | **1.03 GB** | 0.90 GB |
| G1+Aikar + CompactObjectHeaders | **1.00 GB** | 0.87 GB |

→ **G1 uses ~48% less RAM** than ZGC here (ZGC's colored-pointer multi-mapping ~doubles the footprint at
idle and needs spare cores the single tick thread lacks). *Caveat learned:* a first A/B with `Xms==Xmx`
hid this (full-commit) — you must use an **elastic** heap so RSS tracks the working set.

**Fixes shipped:**
- `e5aa1c05` startup PATCHed (Application API) ZGC → **G1+Aikar + `-XX:+UseCompactObjectHeaders`
  + `-XX:+UseStringDeduplication` + native-trim, elastic `-Xms1024M -Xmx4608M` (safe headroom, not 95%),
  no AlwaysPreTouch** — boot-verified. This ~halves your server's RAM on next Start.
- `victus-core RecommendedFlags` now **heap-aware** (`recommendedGcFlags`: G1+Aikar default; Gen-ZGC only
  ≥16 GB **dedicated**; COH/StringDedup/native-trim additive; AlwaysPreTouch dedicated-only). Self-test **64/64**.
- `VictusEngine` GC boot-advice made heap-aware (it used to hardcode "switch to ZGC" — the regression's source).
- `VictusTickTimings` 10s log spam gated behind `-Dvictus.ticktiming.log=true` (default off; metrics still publish).

**Research verdict (saves weeks — do NOT build these):** FerriteCore (Moonrise already dedups blockstates →
~0 MB server; the famous 600 MB is a modded-*client* number), C2ME (Moonrise *is* the equivalent + is
incompatible), Lithium ports (~85-90% banked in Moonrise). The genuinely additive wins over *tuned* Paper:
**CompactObjectHeaders** (~15-20% heap, a JDK-25 flag Paper's source can't set), uncapping Moonrise
chunk-threads on dedicated nodes, async chunk send (port Leaf), and Folia regionized as an opt-in premium tier.
Full roadmap: `docs/phase-3/` (perf research). Measure everything A/B with spark + Chunky + PSS/NMT — idle-TPS is a vanity metric.

## 🚧 2026-07-20 — async pathfinding: DESIGNED + config foundation landed (core is the next focused build)

Off-thread A* path computation (à la Petal/Leaf). **Fully designed** — a 5-agent research workflow
produced a source-verified, reference-validated brief (`docs/phase-3/01-async-pathfinding-design.md`):
the promise-`AsyncPath` model (return a lazy `Path`; any accessor that needs it before the worker
finishes computes inline synchronously — a missed gate degrades to vanilla-sync, never a crash),
plus Victus safety deltas (hoist mob callbacks to main, null off-thread `getBlockEntity`,
try/catch→sync fallback, daemon threads + shutdown hook, `CALLER_RUNS` saturation).

**Landed this increment:** the config layer (`asyncPathfinding` + max-threads / queue-size /
keepalive / reject-policy / per-navigation ground·flying·water knobs), **default OFF**, resolver
self-test **43/43**.

**Honest status — why the core isn't rushed:** unlike DAB/tick-timing, async pathfinding is
concurrency whose correctness its **own** verification plan says needs a **48-hour gameplay soak**
(zero off-thread writes, door/brain/goal parity, MSPT win) — a headless boot can only confirm
compile/link/executor-lifecycle/kill-switch, not races or stuck mobs (no players headless → no mob
pathing). So the core (`AsyncPath` + `PathFinder`/`PathNavigation` refactor + executor) will be
implemented **default-OFF/opt-in**, gated behind the adversarial-review pass + a soak on `e5aa1c05`
before it can be trusted default-on. Design + config are the verified foundation for that.

## ⭐ 2026-07-20 — DAB (distance-throttled mob AI) — first deep-perf patch, workflow-designed + adversarially reviewed

**DAB = Dynamic Activation of Brain**: inside the patched `Mob.serverAiStep()`, a mob far from every
player ticks its goal/target selectors + LOS sensing + navigation only every Nth tick (N grows with
squared distance to the nearest player, capped). Movement controls **and** the Brain
(`customServerAiStep`) always tick — so v1 does **not** throttle the Brain (zero memory-TTL risk) and
nothing freezes mid-air. Runs only on Paper's EAR-active path, so it layers on top of activation-range
and can never resurrect an inactive mob.

- **Config** (`victus-core`): `optimizations.entities.dab` + `dab-start-distance` / `dab-max-tick-interval`
  / `dab-activation-dist-mod` / `dab-blacklist`, per-profile (SMP start=16, MINIGAMES/NETWORK aggressive,
  MODDED conservative, TECHNICAL **off**). Resolver self-test **33/33**.
- **Helper** `cloud.victus.engine.dab.VictusDab`: `computeInterval` (nearest-player distance → interval,
  cached on `Mob.dabInterval`, recomputed every 16 ticks, id-offset) + `mustFullTick` exclusion predicate
  (combat / bosses / passengers / leashed / fire / drowning / potion-effects / operator blacklist).
- **Process**: designed by a 5-agent research **workflow** (source-verified against MC 26.2), then an
  adversarial review **workflow** (4 lenses → per-finding verify) surfaced **5 real issues** — all fixed:
  runtime `/victus reload` now re-pushes DAB (`VictusEngine.reload()`), default `victus.yml` no longer
  masks the TECHNICAL profile, allocation-free blacklist, id-offset recompute, start-distance overflow clamp.
- **Node-verified**: builds (JDK25/ZGC), boots clean, `[Victus] DAB enabled (start-distance=16 …)`, no errors.
  Runtime *throttle behaviour* needs a connected client (EAR skips mob AI entirely with nobody online) —
  that's the live gameplay check: spawn far mobs, watch `entities=` fall via the per-subsystem tick timing,
  confirm mobs don't freeze and combat stays full-rate.

## ⭐ 2026-07-20 — per-subsystem tick timing (first src/minecraft patches — deep layer unblocked)

The first **decompiled-Minecraft** patches land, which unblocks the entire deep-perf patch layer
(DAB, async pathfinding, threading — all live here).
- New `cloud.victus.engine.VictusTickTimings` (paper-server) accumulates per-phase tick time on the
  main thread; hooks in `MinecraftServer.tickServer` (begin/end) and `ServerLevel.tick`
  (entities + block-entities phases) via `minecraft-patches/sources/`.
- Every ~10s (200t) the engine logs `tick timings (avg/200t): total=… entities=… (n%)
  block-entities=… (n%) other=…` and publishes `avgTotalMs/avgEntitiesMs/avgBlockEntitiesMs`
  for the metrics exporter / `/victus doctor`. Engine-only — a plugin can't measure these phases.
- **Node-verified** on DE-1: `[Victus] tick timings (avg/200t): total=2.05ms entities=0.02ms (1%)
  block-entities=0.01ms (0%) other=2.02ms`, clean `Done`.
- **Reproducibility-proven**: clean re-apply from the patch files (`applyMinecraftSourcePatches
  --rerun-tasks`) → "Applied 2 patches", BUILD SUCCESSFUL, edits reappear — a from-scratch/CI build
  includes the feature. Jar rebuilt (`VictusTickTimings.class` verified in jar) + deployed to `e5aa1c05`.

### Minecraft-layer patch workflow (now proven — the key that unblocks DAB/async/threading)
`edit victus-server/src/minecraft/java/… → (in THAT git repo) git add -A →
gradlew :victus-server:fixupMinecraftSourcePatches :victus-server:rebuildMinecraftSourcePatches`.
Non-obvious gotchas: (1) edits made on Windows arrive **CRLF** — `sed -i 's/\r$//'` (LF-normalize)
before `git add`, else the whole file diffs (~6000 lines) instead of your ~4 lines. (2) set
`git config user.email/name` in the build container or the fixup commit fails 128. (3) verify with
`applyMinecraftSourcePatches --rerun-tasks` (re-applies from patch files onto the mache base).

## ⭐ 2026-07-20 — profile-driven optimizations APPLIED by the engine (node-verified)

The engine reads `victus.yml` and **applies** these per world (via the `PaperConfigurations.createWorldConfig` hook → `VictusEngine.applyWorldConfig`). All node-verified on DE-1 + deployed to `e5aa1c05`.

| Setting | smp | technical | minigames | modded | network |
| --- | --- | --- | --- | --- | --- |
| redstone engine | alternate-current | **vanilla** | alternate-current | alternate-current | alternate-current |
| per-player-mob-spawns | on | on | on | on | on |
| monster spawn cap | vanilla (-1) | vanilla (-1) | **8** | vanilla (-1) | **5** |
| projectile save cap / chunk | **16** | vanilla (off) | 16 | 16 | 16 |

`technical` = vanilla-accurate (no approximations). `victus-core` config resolver: **21/21 tests**.
Build pipeline lives on **DE-1** (java_25 container, ~1m35s incremental); deploy-to-panel + push-to-GitHub after every increment (standing rule).

### Next phase (bigger, engine-unique — beyond config knobs)
1. **Native observability in the engine** — `/victus` command + Prometheus endpoint served by the
   engine itself (main-thread tick sampler), so hosting features need no bundled plugin.
2. ~~**Per-subsystem tick timing** (src/minecraft tick hooks) → a real lag-doctor breakdown.~~ ✅ DONE (2026-07-20).
3. **Deep perf patches**: ~~DAB (distance-throttled mob AI, à la Pufferfish)~~ ✅ v1 DONE (2026-07-20); async pathfinding next.
4. Threading tiers (parallel/regionized); hybrid mod loader.
These are larger multi-step patches (src/minecraft layer) — done via the same build→node-test→
deploy→push loop, verifying on the node before each deploy.

## ⭐ 2026-07-20 — engine now APPLIES optimizations (node-verified)

The gap is closing: the engine doesn't just read `victus.yml`, it **applies** settings per world.
- Hook in `PaperConfigurations.createWorldConfig` → `VictusEngine.applyWorldConfig(cfg, world)` sets
  `misc.redstoneImplementation` and `entities.spawning.perPlayerMobSpawns` from the resolved profile.
- **Verified on the DE-1 node** (java_25 container, ZGC, jar pulled from the public release):
  `[Victus] applied to world 'minecraft:overworld' (profile SMP): redstone=ALTERNATE_CURRENT,
  per-player-mob-spawns=true` (overworld + nether + end), `Done (13.8s)!`, **zero errors**.
- I have SSH to the control VPS + DE-1 (authorized) and can boot/tail the engine on the node.
- Panel server `e5aa1c05` is ready: `victus.jar` is in its volume; clicking **Start** runs it.
- Next per-setting hooks (same `WorldConfiguration` pattern): activation ranges, mob-spawn ranges,
  view/simulation distance; then per-subsystem tick timing + throttle enforcement.

## 🚀 Panel deploy (2026-07-20)

- **Server created on the Victus panel** via Application API: `Victus Engine Test`, id 375, on **DE-1**,
  6 GB RAM / 10 GB disk. Panel: https://control.victuscloud.com/server/e5aa1c05 — connect:
  **`paid4.victuscloud.com:25573`**. **You just hit Start** (the app key can create but not power on).
- The jar is published as a public GitHub release
  (`github.com/Gravatar123A/victus-engine-builds` → `dev-26.2/victus-server.jar`, HTTP-200 verified);
  the server's custom startup accepts EULA, fetches the jar once, and runs it on Java 25 + ZGC.
  (Public build repo is reversible — delete it or switch to a Client-API/SFTP upload anytime.)

## ✅ Done & verified

| Area | State | Evidence |
| --- | --- | --- |
| **Fork builds** | Real PaperMC **26.2** hard-fork via paperweight-patcher 2.0.0-beta.21, Gradle 9.4.1, JDK 25 | `applyAllPatches` + `createPaperclipJar` → `victus-paperclip-26.2*.jar` |
| **Fork boots** | Branded server reaches `Done (16.7s)!`, logs `This server is running Victus version 26.2-DEV-…` | boot test |
| **victus-core** | Pure-JDK, Paper-independent core logic — config, metrics, JSON logging, lag-doctor, throttle-limits, GC-runtime | **411/411 offline self-tests pass** |
| **VictusEngine plugin** | Loads `victus.yml`, `/victus` command, live **Prometheus `/metrics`** endpoint | booted on the fork; `curl :9940/metrics` returns live `victus_tps/entities/heap/...` |
| **Lag-doctor (working)** | `/victus doctor` shows MSPT p95/p99/max; **`/victus doctor apply\|revert <id>`** writes victus.yml and hot-reloads | verified live: `apply dab-off` → `dab: false` on disk + in `/victus config` |
| **GC-pause metrics** | `victus_gc_pause_ms` via JMX delta approximation | in the sampler |
| **Engine reads victus.yml natively** ⭐ | Server patch (`cloud.victus.engine.VictusEngine`, hooked in `CraftServer`, victus-core bundled into the server) loads victus.yml at startup — no plugin needed | verified boot: `[Victus] engine config: profile=SMP redstone=ALTERNATE_CURRENT …` + engine wrote victus.yml itself |
| **Native GC advice** | Engine detects the running collector and nudges toward Generational ZGC | verified: `[Victus] GC: running Generational ZGC (recommended)` |
| **CI/CD** | GitHub Actions: JDK-25 build on Linux + auto-deploy to a Pterodactyl server | `.github/workflows/build.yml`, `docs/DEPLOY.md` |

### The server-patch workflow is proven & repeatable
`edit paper-server/ → ./gradlew :victus-server:createPaperclipJar (~1.5m) → boot-test → (in paper-server) git add; ../gradlew fixupPaperServerFilePatches rebuildPaperServerFilePatches → commit patches → push`.
Non-obvious: keep the Gradle daemon heap small (`-Xmx768m` in `$GRADLE_USER_HOME/gradle.properties`)
so the forked paperclip worker gets commit space (C:-bound 3.8 GB page file).

## ⏳ Next (the real "optimizations" lift)

The engine now *reads* victus.yml; the gap is making it *apply* settings. Each needs a per-setting
hook, found but not yet wired:
- **redstone** — `io.papermc.paper.configuration.WorldConfiguration.Misc.redstoneImplementation`
  (per-world; override before world load from the profile).
- activation ranges / mob caps / view-distance — same WorldConfiguration path.
- Then: per-subsystem tick timing + throttle **enforcement** moved from the plugin (advisory) into
  the engine tick loop (`src/minecraft` patch layer).

## 🔭 Planned (large, honestly not started)

- Deep perf patches: DAB, async pathfinding, native libdeflate compression.
- Threading tiers: `parallel` (barrier-sync) and `regionized` (Folia-style).
- Hybrid mod loader (Fabric/NeoForge + plugins) — the hardest, isolated module.

## ⚠️ For you in the morning

- **Panel server** is created + configured (see the deploy section above). Hit **Start** on
  https://control.victuscloud.com/server/e5aa1c05 — it fetches the latest engine jar and boots on
  Java 25 + ZGC. Connect: `paid4.victuscloud.com:25573`. I can't power it on (app key = create only)
  or read its console (Client-API only) — if you drop me a **Client API key (`ptlc_`) for that
  server**, I can start it, tail the console, and hunt runtime errors on the real node myself.
- Two public GitHub repos now exist: `victus-engine` (private, source) and `victus-engine-builds`
  (public, the jar release). Delete/flip either anytime.

## Honest framing

The jar is Paper 26.2 + the hosting/observability layer (native config load + GC advice in the
engine; metrics/`/victus`/lag-doctor via the bundled plugin). The perf features (Phase 1) *apply*
step, threading tiers, and hybrid (Phase 4) are the remaining lifts — each is deep engine surgery,
so I built the foundation + proven patch workflow rather than rush them unattended.

## Dev-box gotchas (all handled in `scripts/dev-env.sh`)

Windows-style `E:/` temp paths (JVM mangles MINGW `/e/`); `-Djavax.net.ssl.trustStoreType=Windows-ROOT`
(Avast intercepts TLS, Java doesn't trust its root otherwise); stop stale Gradle daemons before the
decompile (they exhaust the C:-bound page file). None needed on Linux/CI.
