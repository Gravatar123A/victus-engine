# Contributing to Victus Engine

## Toolchain

- JDK **21+** (Paper 26.1 may require newer — bump `build.gradle.kts` toolchain if `applyPatches`
  complains). `java -version` locally reports 21.0.3.
- Git 2.4+, internet access (paperweight fetches Paper source).
- Dev machine note: **C: drive is full** — always `source scripts/dev-env.sh` first to redirect
  temp/Gradle caches onto E:.

## Working with patches

This is a paperweight-patcher fork; you don't edit Paper source directly. Typical loop:

```bash
source scripts/dev-env.sh
./gradlew applyPatches        # materialize Paper source + our patches into paper-server/ paper-api/
# ... edit code in paper-server/ ...
./gradlew rebuildPatches      # regenerate patches/ from your edits
./gradlew build               # produce the server jar
```

- Server changes → `patches/paper-server` (GPL-3.0). API changes → `patches/paper-api` (MIT).
- New engine features live behind a `victus.yml` toggle (see `docs/VICTUS-CONFIG.md`) and default
  **off** unless they're compat-preserving always-on optimizations.
- Add `// SPDX-License-Identifier: GPL-3.0-only` headers to new files.

## Rules of the road

1. **Never break plugin compat in the default (`single`) path.** That's the product promise.
2. **No vanity metrics.** Benchmarks use ≥150 players, real workloads, MSPT distribution — never
   idle TPS or startup time.
3. **Isolate risky modules** (hybrid, regionized) so they can't affect the pure-plugin core.
4. **Never commit vendored Minecraft/Paper source** (`.gitignore` enforces; EULA requires it).
