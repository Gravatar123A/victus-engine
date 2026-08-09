# Contributing to Victus Engine

## Toolchain

- JDK **25** for the implemented Paper 26.2 source line (the Gradle toolchain resolver can provision it).
- Git 2.4+ and internet access for paperweight source/dependency hydration.
- `scripts/dev-env.sh` is an optional Windows/TLS environment helper; use it only when the local machine needs those overrides.

## Working with patches

This is a paperweight-patcher fork; you don't edit Paper source directly. Typical loop:

```bash
source scripts/dev-env.sh
./gradlew applyAllPatches     # materialize Paper source + all Victus patches
# ... edit generated source through the paperweight workflow ...
./gradlew rebuildAllPatches   # regenerate the tracked patch sets
./gradlew build               # produce the server jar
```

- Server file patches live under `victus-server/paper-patches`; Minecraft source patches live under `victus-server/minecraft-patches`; API patches live under `victus-api/paper-patches`.
- New engine features live behind a `victus.yml` toggle (see `docs/VICTUS-CONFIG.md`) and default
  **off** unless they're compat-preserving always-on optimizations.
- Add `// SPDX-License-Identifier: GPL-3.0-only` headers to new files.

## Rules of the road

1. **Never break plugin compat in the default (`single`) path.** That's the product promise.
2. **No vanity metrics.** Benchmarks use ≥150 players, real workloads, MSPT distribution — never
   idle TPS or startup time.
3. **Isolate risky modules** (hybrid, regionized) so they can't affect the pure-plugin core.
4. **Never commit vendored Minecraft/Paper source** (`.gitignore` enforces; EULA requires it).
