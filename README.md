# Victus Engine

A high-performance Minecraft: Java Edition server, forked from [PaperMC](https://papermc.io).
**Every Spigot / Bukkit / Paper plugin works unchanged** — that is the non-negotiable promise.
On top of full compatibility we stack aggressive, honest performance work and a
**hosting-first control plane** that no other fork ships, because no other fork is built by a
hosting company.

> Standalone repository. Not part of the main Victus Cloud web / panel / billing repos.

---

## What this actually is (no hype)

Minecraft's tick loop is fundamentally serial, so **"100× per core" is not real** — see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the grounded numbers. What Victus Engine
*does* deliver:

| Goal | Realistic target vs Paper |
| --- | --- |
| Per-core MSPT headroom (survival) | **3–10×** (stacked optimizations) |
| Players / box, spread-out survival | **5–8×** (opt-in regionized threading) |
| Players / box, minigames / lobby | **thousands** (companion Minestom engine) |
| Network total | **effectively unlimited** (Velocity sharding) |
| Find & fix lag | **one command** (`/victus doctor`) |

## Design pillars

1. **Compatibility first** — full Bukkit/Spigot/Paper API. Regionized threading and the mod
   bridge are *opt-in per instance*; a plain SMP pays zero cost for features it doesn't use.
2. **Honest, layered performance** — every optimization preserves vanilla behavior (or is a
   toggle back to it). No idle-MSPT or startup-time vanity metrics.
3. **Hosting-first moat** — per-instance CPU/RAM/tick limits, Prometheus metrics, JSON logs,
   the lag-doctor, safe-restart/rollback hooks, config auto-migration.
4. **Hybrid from day one (isolated)** — a Fabric/NeoForge mod loader bridge lives behind a
   per-instance toggle and can never drag down the pure-plugin core.

See [`docs/ROADMAP.md`](docs/ROADMAP.md) for phases and [`docs/VICTUS-CONFIG.md`](docs/VICTUS-CONFIG.md)
for the full `victus.yml` schema (the concrete feature seams).

---

## Status

**Phase 0 — scaffold.** This repo is the fork skeleton. The actual Paper source is *not* vendored;
paperweight downloads and patches it at build time (see below). Nothing here builds until you run
the hydrate step **with internet access**.

## Build (requires internet + a git clone)

Base: **Paper `26.2`** (unobfuscated). Toolchain: paperweight-patcher `2.0.0-beta.21`,
Gradle `9.4.1`, **JDK 25** (auto-provisioned by Gradle's foojay resolver on first build).

```bash
# 1. dev env — keeps temp/caches off a full C: and trusts the Windows cert store (Avast TLS)
source scripts/dev-env.sh

# 2. pull + decompile + patch Paper, then build the server jar
./scripts/build.sh          # wraps ./gradlew applyAllPatches build

# 3. run it (Java 25, Generational ZGC)
java -XX:+UseZGC -XX:+ZGenerational -jar victus-server/build/libs/victus-*.jar --nogui
```

> The build config is verified working (paperweight configures + checks out Paper). On Windows the
> two gotchas are handled by `scripts/dev-env.sh`: Windows-style temp paths and the `Windows-ROOT`
> Java truststore (so Gradle's TLS survives Avast interception). On Linux/CI neither is needed.

## Layout

```
patches/paper-server/   feature + fix patches applied to Paper's server (GPL-3.0)
patches/paper-api/      patches to the Paper API (MIT, mirroring Paper)
build-data/             paperweight fork metadata (dev-imports, mappings notes)
docs/                   ARCHITECTURE, ROADMAP, VICTUS-CONFIG, CONTRIBUTING
design/modules/         per-module design notes (the Victus-specific seams)
scripts/                dev-env + build helpers (C:-full ENOSPC workaround)
.github/workflows/      CI
```

## License

GPL-3.0-only (server), MIT (API patches) — mirroring PaperMC. See [`LICENSE`](LICENSE) and
[`docs/LICENSING.md`](docs/LICENSING.md). You **must** vendor the full GPL-3.0 text before publishing.
