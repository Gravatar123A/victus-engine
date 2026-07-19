# Victus Engine — build status

_Living log of what's real vs. planned. Newest first._

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
