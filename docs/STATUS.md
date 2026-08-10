# Victus Engine build status

_Last reconciled with the machine-readable catalog: 2026-08-09._

## Current implementation

Victus Engine has **one implemented source line: Minecraft 26.2 on `main`**. It is a Paper hard fork using paperweight-patcher 2.0.0-beta.21, Gradle 9.4.1, JDK 25, and the exact upstream Paper source commit `75c0b485bf038c175d6f3e6efc67519cd5cd524d` (Paper build 62 lineage). The pin remains in `gradle.properties`; the newer Paper build recorded in the frozen catalog is upstream discovery data, not a silent rebase.

Implemented evidence in this repository includes:

- the patch/apply build configuration for 26.2;
- a tracked five-module pre-main hybrid foundation (`hybrid-common`, `hybrid-launcher`, isolated Fabric and
  NeoForge adapters, and owned fixtures), with locked upstream inputs and fail-closed preflight; this is
  architecture/test evidence only, not a supported Fabric/NeoForge bridge;
- `victus-core` configuration, runtime, metrics, lag-doctor, and limits logic;
- native `victus.yml` bootstrap and profile application;
- DAB and async chunk-send configuration/watchdog paths;
- plugin-side `/victus doctor` and metrics integration;
- a native startup banner contract attached immediately after Paper's genuine `Done` ready marker and guarded to log once per process.

The banner source snapshot and lifecycle ordering are asserted by `scripts/test-banner-contract.py`. This local task did not produce or publish a JAR, and the catalog therefore keeps `tests.boot`, `tests.bannerOrdering`, and `publicationEligible` false until an actual built JAR is boot-tested and its log order is captured.

## Multi-version matrix

[`versions/versions.json`](../versions/versions.json) covers all **74 official Mojang Java releases from 1.8 through 26.2**:

| State | Count | Truthful interpretation |
| --- | ---: | --- |
| `implemented` | 1 | 26.2 has source and build configuration in this repository |
| `planned` with Paper baseline | 52 | Exact upstream baseline exists; Victus port branch/build does not |
| `unavailable` | 21 | Paper never shipped the exact baseline |

The unavailable exact versions are `1.8`, `1.8.1`–`1.8.7`, `1.8.9`, `1.9`–`1.9.3`, `1.10`, `1.10.1`, `1.11`, `1.11.1`, `1.16`, `1.20.3`, `1.21.2`, and `26.1`.

The catalog contains Mojang release metadata and Java tiers for every entry. For the 53 exact Paper baselines it records a frozen latest build, channel, timestamp, checksum, immutable content-addressed URL, and upstream commit where Paper supplies one. The implemented 26.2 entry separately records the actual source ref used by this fork.

No planned line has a fake `sourceBranch`, build task, artifact path, supported capability, or publication eligibility. Every entry currently has `publicationEligible: false`.

## Support and capability boundaries

For 26.2, supported catalog flags describe the core/config, DAB, async chunk-send, lag-doctor, metrics, and plugin API integration that exist in source. They do not claim every feature has completed performance or long-running gameplay qualification.

The following remain false in the support matrix:

- parallel ticking: experimental scaffold with known thread-safety caveats;
- regionized threading: planned;
- Fabric bridge: Victus GameProvider plus real Loader/Knot/Mixin owned-target proof passes; rebuilt Victus target
  integration, bridge reconciliation, profile tests, and soak are still required;
- NeoForge bridge: installer/NeoForm reconstruction and source-merge/conflict machinery is tracked, with a real
  event-bus/deferred-register/class-processor/Mixin fixture; the source comparison currently reports 753
  unresolved classes and the bounded runner remains expected-fail at `NEOFORGE_MERGE_CONFLICTS`, so real
  Paper+FML lifecycle is not yet passing.

The old late `VictusHybrid` direct-entrypoint invoker is disabled and deprecated; it performs discovery-only
migration diagnostics. Loader profiles must enter through `VictusHybridLauncher` before server classes load.

Capabilities for the 52 future source ports are all false until each port exists and passes its own compile, unit, boot, and behavior gates. ViaVersion or another protocol bridge will never be represented as a native old-version server.

## Validation and CI

Offline, deterministic checks:

```bash
python scripts/validate-version-catalog.py
python scripts/generate-version-catalog.py --check
python scripts/test-banner-contract.py
python scripts/select-version-builds.py --versions implemented
python scripts/hybrid/validate-runtime-lock.py
./gradlew hybridCheck
```

Optional network refresh:

```bash
python scripts/generate-version-catalog.py --refresh
```

CI foundations:

- `.github/workflows/catalog.yml` validates the catalog, deterministic generation, banner source contract, and rejection of unimplemented build selections;
- `.github/workflows/build.yml` validates the catalog/selection guard and builds only the real 26.2 source line;
- `.github/workflows/build-version-lines.yml` creates a matrix only from catalog entries with `sourceImplemented: true` and has no release/deploy job.

CI uploads are short-lived workflow artifacts and are not represented as public Victus releases. [`docs/DEPLOY.md`](DEPLOY.md) documents retrieval and isolated manual-test handling; no workflow currently deploys or restarts a server.

## Required gates before any version becomes publishable

A future port must gain an actual source branch and pinned upstream source ref, then pass clean patch application, unit/self-tests, runnable JAR inspection, isolated boot to the true ready marker, exact-version checks, clean stop, and capability-specific tests. Startup evidence must show the ready marker first and the Victus banner/promotion exactly once. Only immutable release assets with checksum and source provenance may later become publication eligible.

## Historical notes

Older project notes and phase design records document experiments and previous node observations. They are not a substitute for the current catalog. In particular, previous hybrid or parallel experiments do not make those capabilities supported, and previous external artifacts do not establish that the 53-version port matrix exists.
