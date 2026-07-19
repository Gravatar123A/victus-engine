# victus-core

**Paper-independent, dependency-free engine logic** — the parts of Victus Engine that need no
Minecraft/Paper code, so they can be built and tested **offline with just the JDK**. The paperweight
patches wire this library into the server; keeping as much logic here as possible maximizes what we
can develop and verify without a full online build.

## What's here

- `cloud.victus.core.config` — the `victus.yml` resolution core:
  - typed enums (`Profile`, `ThreadingMode`, `RedstoneImpl`, `CompressionBackend`)
  - `ConfigResolver` — precedence (explicit Paper per-world &gt; victus.yml &gt; profile overlay &gt; hard
    default) + profile overlays + validation + the "boolean-or-map" coercion
  - `CompressionBackend` encodes the correctness rule that **`zstd` is invalid for packet
    compression** (would break stock clients) — fails fast with a helpful message
  - `ConfigSelfTest` — a dependency-free self-test (no JUnit needed offline)

## Run the self-test offline (no internet, no Gradle needed)

```bash
cd victus-core
javac -d out $(find src -name '*.java')
java -cp out cloud.victus.core.config.ConfigSelfTest
# -> 13 passed, 0 failed
```

## Building online

Once the repo hydrates (internet), this becomes a normal Gradle subproject: add `include("victus-core")`
to `settings.gradle.kts` and depend on it from the server module. Replace `ConfigSelfTest` with JUnit
(`org.junit.jupiter:junit-jupiter`).

## Roadmap for this module

More Paper-independent logic will land here so it stays offline-testable:
- Prometheus text-exposition writer (Phase 2 · 01)
- lag-doctor remediation catalog + report model (Phase 2 · 02)
- per-instance throttle-ladder state machine + hysteresis (Phase 2 · 04)
- JSON log record model (Phase 2 · 01)
