# Victus Engine — build status

_Living log of what's real vs. planned. Newest first._

## ✅ Done & verified

| Area | State | Evidence |
| --- | --- | --- |
| **Fork builds** | Real PaperMC **26.2** hard-fork via paperweight-patcher 2.0.0-beta.21, Gradle 9.4.1, JDK 25 | `applyAllPatches` + `createPaperclipJar` → `victus-paperclip-26.2*.jar` |
| **Fork boots** | Branded server reaches `Done (16.7s)!`, logs `This server is running Victus version 26.2-DEV-…` | boot test |
| **victus-core** | Pure-JDK, Paper-independent core logic — config, metrics, JSON logging, lag-doctor, throttle-limits, GC-runtime | **411/411 offline self-tests pass** |
| **VictusEngine plugin** | Loads `victus.yml`, `/victus` command, live **Prometheus `/metrics`** endpoint | booted on the fork; `curl :9940/metrics` returns live `victus_tps/entities/heap/...` |
| **CI/CD** | GitHub Actions: JDK-25 build on Linux + auto-deploy to a Pterodactyl server | `.github/workflows/build.yml`, `docs/DEPLOY.md` |

## ⏳ In progress / next

- **Engine-integrated hosting** — move per-subsystem tick timing + throttle **enforcement** from the
  plugin (advisory only today) into server patches.
- **Config-driven optimizations** — apply profile-based redstone / activation-range / GC / network
  defaults from `victus.yml` inside the server.

## 🔭 Planned (large, honestly not started)

- Deep perf patches: DAB, async pathfinding, native libdeflate compression.
- Threading tiers: `parallel` (barrier-sync) and `regionized` (Folia-style).
- Hybrid mod loader (Fabric/NeoForge + plugins) — the hardest, isolated module.

## ⚠️ Blocked on you

- **Panel deploy**: you chose "create via Application API" but I need the `ptla_` key + node +
  Java-25 egg (SSH was declined). Drop me those and I'll create the server + hand you the link.

## Honest framing

The jar is functionally Paper 26.2 + the hosting/observability layer (config, metrics, `/victus`).
The performance features (Phase 1) and hybrid (Phase 4) are still specs/design — the foundation,
build pipeline, and hosting brains are real and tested; the deep-engine perf work is the next lift.

## Dev-box gotchas (all handled in `scripts/dev-env.sh`)

Windows-style `E:/` temp paths (JVM mangles MINGW `/e/`); `-Djavax.net.ssl.trustStoreType=Windows-ROOT`
(Avast intercepts TLS, Java doesn't trust its root otherwise); stop stale Gradle daemons before the
decompile (they exhaust the C:-bound page file). None needed on Linux/CI.
