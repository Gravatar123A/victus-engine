# Phase 4 — pre-main hybrid runtime foundation

**Status (2026-08-10): FOUNDATION, FAIL-CLOSED, NOT SUPPORTED.** Task #19 establishes an
Arclight-class architecture boundary. Task #36 adds tracked NeoForge installer/NeoForm reconstruction,
three-way source merge/conflict reporting, explicit conflict bridge patches, real lifecycle/transformation
fixtures, and an expected-fail bounded integration gate. A full Paper/NeoForge merge is not yet claimed.
The public `fabricBridge` and `neoForgeBridge` capability flags remain false.

## What now exists

The tracked Gradle build includes five independent modules:

- `hybrid-common`: loader contracts, strict lifecycle state machine, fail-closed policy, startup report,
  compatibility fingerprint, and marker contracts;
- `hybrid-launcher`: dependency-light `cloud.victus.hybrid.launcher.VictusHybridLauncher`;
- `hybrid-fabric`: isolated Fabric adapter and ServiceLoader registration;
- `hybrid-neoforge`: isolated NeoForge/FML adapter and ServiceLoader registration;
- `hybrid-fixtures`: owned Fabric mod, NeoForge mod, and Bukkit plugin marker jars.

`VictusHybridLauncher` chooses exactly `disabled`, `fabric`, or `neoforge` from strict arguments or a Java
properties config before it references Minecraft/Paper. There is deliberately no `auto` profile. Arguments
after `--` are delegated byte-for-byte as Java strings.

- **disabled:** never opens the adapter ServiceLoader and contains no Fabric/NeoForge classes on its
  production runtime classpath. It invokes the configured target main/artifact unchanged.
- **fabric:** isolates the adapter, requires the locked runtime classpath, registers
  `VictusFabricGameProvider` through Fabric's `GameProvider` service metadata, and enters the real
  `net.fabricmc.loader.impl.launch.knot.KnotServer`. The provider skips stock vanilla/bundler classification,
  exposes the class-bearing patched server jar to Knot only after Mixin initialization, invokes Fabric's main
  lifecycle, and delegates `org.bukkit.craftbukkit.Main`. Paperclip/bundler are not transforming targets.
- **neoforge:** isolates the adapter, resolves a real JPMS plan rooted at `fml_loader`, supplies the explicit
  game/FML arguments, and enters `net.neoforged.fml.startup.Server`. Preflight requires the complete locked
  module path and a patched server jar containing `net.minecraft.server.Main`.

Enabled profiles never fall back to starting Paper without transformations. A failed preflight or loader
entry writes `logs/victus-hybrid-startup.txt`, reports a stable blocker code, and exits nonzero.

## Locked upstream inputs

`hybrid/locks/runtime-lock.json` pins artifact URLs, repositories, SPDX-style license identifiers, byte sizes,
and SHA-256 checksums for direct launch requirements. Principal pins are:

| Input | Pin |
| --- | --- |
| Minecraft fixture target | `26.2` |
| Fabric Loader | `0.19.3` |
| Fabric API fixture | `0.156.0+26.2` |
| Fabric-selected Sponge Mixin | `0.17.3+mixin.0.8.7` |
| NeoForge | `26.2.0.57` |
| FML | `11.0.17` |
| FML-selected Sponge Mixin | `0.17.1+mixin.0.8.7` |

Structural validation is offline. `--resolve` downloads every listed artifact and verifies length and digest:

```bash
python scripts/hybrid/validate-runtime-lock.py
python scripts/hybrid/validate-runtime-lock.py --resolve
```

No upstream source or binary is checked into the repository.

## Fixtures and test boundary

`hybrid-fixtures:fixtureArtifacts` creates three owned jars with loader/plugin metadata and stable markers.
The Fabric fixture compiles against Loader 0.19.3 and Fabric API 0.156.0 lifecycle types, runs a real
`ModInitializer`, and applies a required Mixin to an owned marker class. `fabricProviderIntegrationTest` proves
Fabric discovery, the entrypoint, transformed marker, and Knot-owned target definition; the full rebuilt Victus
server gate is prepared/run by `fabricDistribution` and `run-fabric-integration.py`. The NeoForge fixture compiles
against FML 11.0.17's real `@Mod` plus a class-processor marker. The Bukkit fixture compiles against Paper 26.2's
real `JavaPlugin` lifecycle.

`hybridCheck` compiles all modules, builds fixtures, tests parsing/delegation/isolation, policy, reports,
fingerprints, locks/profile preflight, and runs bounded integration. At this milestone bounded integration
asserts known missing-runtime blockers; when a hydrated loader/target test environment is supplied, tasks
#20/#21 must replace those expected blockers with real loader lifecycle markers.

## Late invoker removal

The old `cloud.victus.engine.VictusHybrid` direct Fabric entrypoint invoker was architecturally misleading:
it ran after Paper classes were loaded, without dependency resolution, Mixin, remapping, or loader lifecycle.
It is now deprecated, discovery-only, and cannot invoke mod code. `hybrid.enabled` in `victus.yml` only emits
that migration diagnostic. Runtime profile selection belongs to the pre-main launcher config.

## Exact next milestones

1. **Task #35 — Fabric provider (implemented, target gate pending):** the locked runtime, Victus GameProvider,
   real Knot/Mixin fixture proof, deterministic distribution, bounded runner, and disabled isolation exist.
   Completion still requires the same proof to pass against the rebuilt class-bearing Victus server target on
   the isolated build workspace; `fabricBridge` remains false.
2. **Task #21 — NeoForge:** generate the complete locked module/class path, reconcile FML game-content
   location/class processors with patched Paper, bind the real NeoForge fixture, and reach FML lifecycle markers.
3. **Task #22 — shared bridge:** reconcile registries/events/world/entity/item/command surfaces and compile the
   Bukkit fixture against the real API.
4. Add curated compatibility fingerprints, conflict policy, gameplay/soak tests, and only then consider
   changing support flags. Unknown combinations must continue to fail closed.

## Task #36 NeoForge merge gate

NeoForge `26.2.0.57` ships an installer profile whose actual runtime selects FML `11.0.16`; the requested
FML `11.0.17` loader/earlydisplay validation override and fixture compile/API pin are locked separately. The installer profile,
version metadata, processor graph, runtime artifacts, JVM/game arguments, `data/client.lzma`, Minecraft
server input, patched-server output, NeoForm `26.2-2`, userdev archive, and NeoForm Runtime are locked in
`hybrid-neoforge/locks/neoforge-26.2.0.57-lock.json`.

`hybrid-neoforge:neoforgeReconstructPatchedGame` and `neoforgeReconstructSources` generate only under
`hybrid-neoforge/build/neoforge`. `neoforgeMergeSources` performs a named-source three-way comparison,
writes automatic non-conflicting overlay files plus `conflicts.json`, and recognizes conflict replacements
only from `hybrid-neoforge/bridge-patches`. The current Paper 26.2 comparison records 753 unresolved classes;
`neoforgeHybridDistribution` therefore refuses assembly.

The fixture now exercises real FML constructor injection, mod event bus, deferred item registration,
common setup, a service-loaded no-op class processor, and FML's Mixin service. The bounded gate currently
records `NEOFORGE_MERGE_CONFLICTS` with 753 classes as expected-fail; it cannot delegate to untransformed Paper.
