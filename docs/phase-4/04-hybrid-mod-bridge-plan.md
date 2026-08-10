# Phase 4 — pre-main hybrid runtime foundation

**Status (2026-08-10): FOUNDATION, FAIL-CLOSED, NOT SUPPORTED.** Task #19 establishes an
Arclight-class architecture boundary; it does not claim that Paper and either loader merge successfully.
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
- **fabric:** isolates the adapter, requires the locked runtime classpath, sets Fabric's explicit server game
  jar/version/mapping/mod-folder properties, and enters the real
  `net.fabricmc.loader.impl.launch.knot.KnotServer`. Preflight requires a patched server jar containing
  `net.minecraft.server.Main`; Paperclip is not a valid transforming target.
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
The Fabric fixture compiles against Loader 0.19.3's real `ModInitializer` and has a no-op Mixin configuration
marker. The NeoForge fixture compiles against FML 11.0.17's real `@Mod` plus a class-processor marker. The
Bukkit fixture compiles against Paper 26.2's real `JavaPlugin` lifecycle. These are marker fixtures, not proof
that the combined runtime has reached their callbacks.

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

1. **Task #20 — Fabric:** hydrate the locked Fabric runtime, adapt the Minecraft game provider to the patched
   Paper artifact where stock classification/entrypoint patching rejects it, reach Knot/Mixin and fixture
   lifecycle markers, and preserve disabled isolation.
2. **Task #21 — NeoForge:** generate the complete locked module/class path, reconcile FML game-content
   location/class processors with patched Paper, bind the real NeoForge fixture, and reach FML lifecycle markers.
3. **Task #22 — shared bridge:** reconcile registries/events/world/entity/item/command surfaces and compile the
   Bukkit fixture against the real API.
4. Add curated compatibility fingerprints, conflict policy, gameplay/soak tests, and only then consider
   changing support flags. Unknown combinations must continue to fail closed.
