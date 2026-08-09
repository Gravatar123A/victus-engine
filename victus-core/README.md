# victus-core

**Paper-independent, dependency-free engine logic** — the parts of Victus Engine that need no
Minecraft/Paper code, so they can be built and tested **offline with just the JDK**. The paperweight
patches wire this library into the server; keeping as much logic here as possible maximizes what we
can develop and verify without a full online build.

## What's here

- `cloud.victus.core.config` — typed `victus.yml` resolution, overlays, and validation.
- `cloud.victus.core.branding` — plain-text-safe startup banner contract and snapshot self-test.
- `cloud.victus.core.metrics`, `doctor`, `limits`, `logging`, and `runtime` — dependency-free
  observability and hosting logic.

## Run the self-test offline (no internet, no Gradle needed)

```bash
cd victus-core
javac -d out $(find src -name '*.java')
java -cp out cloud.victus.core.config.ConfigSelfTest
java -cp out cloud.victus.core.branding.StartupBannerSelfTest
```

## Building online

The root Gradle build includes this as a normal subproject. The 26.2 server source set bundles its
non-test classes into the server artifact; the dependency-free self-test mains remain available for
offline checks.

## Roadmap for this module

More Paper-independent logic will land here so it stays offline-testable:
- Prometheus text-exposition writer (Phase 2 · 01)
- lag-doctor remediation catalog + report model (Phase 2 · 02)
- per-instance throttle-ladder state machine + hysteresis (Phase 2 · 04)
- JSON log record model (Phase 2 · 01)
