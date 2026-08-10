# Victus Engine

Victus Engine is a Paper-based Minecraft: Java Edition server fork. The repository currently contains one real source line: **Minecraft 26.2**, based on the exact Paper commit pinned in [`gradle.properties`](gradle.properties). Multi-version support is a catalogued porting program, not a set of finished builds.

> Standalone repository. It is not part of the Victus Cloud web, panel, or billing repositories.

## Version status

The machine-readable source of truth is [`versions/versions.json`](versions/versions.json).

| Matrix state | Count | Meaning |
| --- | ---: | --- |
| Implemented Victus source line | **1** | 26.2 on `main`; builds from this repository |
| Exact Paper baselines planned for ports | **52** | Paper shipped the exact Mojang version, but no Victus source branch/build exists yet |
| Exact versions unavailable | **21** | Paper never shipped that exact Mojang baseline |
| Official Mojang releases covered | **74** | Every Java release from 1.8 through 26.2 |

The 21 exact Paper gaps are `1.8`, `1.8.1`–`1.8.7`, `1.8.9`, `1.9`–`1.9.3`, `1.10`, `1.10.1`, `1.11`, `1.11.1`, `1.16`, `1.20.3`, `1.21.2`, and `26.1`. They are deliberately non-downloadable; Victus does not relabel a different server version or claim protocol translation is a native build.

All 74 catalog entries currently have `publicationEligible: false`. Only 26.2 has a `sourceBranch`; no catalog entry pretends that the other 52 planned ports or their artifacts already exist.

## 26.2 capability status

Implemented and wired on the current source line include the native `victus.yml` configuration foundation, profile-driven settings, DAB, async chunk-send controls/watchdog, metrics, and `/victus doctor`. The compact native Victus Cloud banner is attached to the genuine server-ready lifecycle: Paper's `Done` message is logged first, then the banner and `https://victuscloud.com` are logged once.

The following are **not supported claims**:

- parallel/regionized ticking is experimental foundation, not production support;
- Fabric/NeoForge hybrid operation is foundation/planned and is not advertised as working;
- a capability on 26.2 does not imply that it has been ported to any other catalog entry.

See [`docs/STATUS.md`](docs/STATUS.md) for the current evidence boundaries, [`docs/DEPLOY.md`](docs/DEPLOY.md) for the non-publishing CI/manual-test policy, and [`docs/ROADMAP.md`](docs/ROADMAP.md) for future work.

## Catalog tooling

Catalog validation and generation are offline by default and use the checked-in verified snapshot:

```bash
python scripts/validate-version-catalog.py
python scripts/generate-version-catalog.py --check
python scripts/test-banner-contract.py
python scripts/hybrid/validate-runtime-lock.py
./gradlew hybridCheck
```

The hybrid gate proves launcher parsing, disabled delegation/isolation, adapter preflight, lock/fingerprint/report
contracts, and owned fixtures. Fabric runs Loader 0.19.3/Knot with a Victus GameProvider and proves real mod
discovery, main entrypoint, Mixin transformation, and Knot-owned target definition against an owned class-bearing
target. `resolveFabricRuntime`, `:hybrid-fabric:fabricDistribution`, and the bounded integration script assemble
and verify the same plan for the rebuilt `victus-server` jar (never Paperclip/bundler). NeoForge additionally has
installer/NeoForm reconstruction, source-merge conflict reporting, and a real event-bus/deferred-register/class-
processor/Mixin fixture. Public bridge flags remain false until the rebuilt Victus target proof and later
bridge/profile/soak gates pass; enabled profiles fail closed.

An explicit optional refresh contacts Mojang and Paper, updates [`versions/upstream-snapshot.json`](versions/upstream-snapshot.json), and regenerates the catalog:

```bash
python scripts/generate-version-catalog.py --refresh
```

The semantic validator rejects duplicate/missing/out-of-order versions, invalid Java tiers, fabricated source/build settings, Paper gaps marked downloadable, mutable Paper URLs, missing checksums/provenance, unsupported capabilities, and publication eligibility. The JSON contract is defined in [`versions/versions.schema.json`](versions/versions.schema.json).

## Build 26.2

Building requires a Git clone, network access for paperweight dependencies/upstream hydration, Gradle 9.4.1, and JDK 25 (the toolchain resolver can provision it).

```bash
source scripts/dev-env.sh       # optional local Windows/TLS environment helper
./scripts/build.sh              # ./gradlew applyAllPatches build
java -jar victus-server/build/libs/victus-*.jar --nogui
```

The catalog-driven manual workflow refuses entries without implemented source. The regular CI builds only 26.2 and uploads a short-lived CI artifact; neither workflow publishes a release or deploys a server.

## Layout

```text
versions/                  frozen 74-version catalog, schema, verified upstream snapshot
scripts/                   catalog validation/generation, build selection, banner assertions
victus-server/             current 26.2 Paper file/source patches
victus-core/               Paper-independent configuration/metrics/runtime logic
victus-plugin/             plugin-side command and metrics integration
hybrid-common/             loader-neutral contracts, lifecycle, reports, policy
hybrid-launcher/           dependency-light pre-main disabled|fabric|neoforge dispatcher
hybrid-fabric/             Fabric/Knot adapter, Victus GameProvider, lifecycle/Mixin proof
hybrid-neoforge/           isolated NeoForge/FML adapter foundation
hybrid-fixtures/           owned Fabric, NeoForge, and Bukkit marker fixtures
hybrid/                     runtime lock and launcher configuration example
docs/                      architecture, status, roadmap, configuration, design records
.github/workflows/          catalog validation and honest selected-line build foundations
```

## License

GPL-3.0-only for server changes and MIT-compatible API patch treatment, mirroring PaperMC. See [`LICENSE`](LICENSE) and [`docs/LICENSING.md`](docs/LICENSING.md).
