# Victus NeoForge 26.2 merge machinery

**Status: in progress, fail-closed, not a supported capability.** This module pins NeoForge `26.2.0.57`, its installer-selected FML `11.0.16` runtime, the explicitly locked FML `11.0.17` validation override/fixture API, NeoForm `26.2-2`, the installer processors and binary patch, Minecraft `26.2`, and the NeoForm source-machine tools. No upstream binary or Minecraft source is committed.

## Reproducible tasks

```bash
./gradlew :hybrid-neoforge:neoforgeValidateLock
./gradlew :hybrid-neoforge:neoforgeResolveLockedRuntime
./gradlew :hybrid-neoforge:neoforgeReconstructPatchedGame
./gradlew :hybrid-neoforge:neoforgeReconstructSources
```

All downloads and generated game/source layers stay below `hybrid-neoforge/build/neoforge/`. The binary task parses `install_profile.json` and `version.json`, executes the locked installer processors, and verifies the patched server digest. The source task runs the locked NeoForm Runtime against the locked userdev/NeoForm data and records hashes/provenance beside its output.

The installer is authoritative for the stock runtime assembly and currently selects FML `11.0.16`, despite the requested `11.0.17`. That mismatch is recorded rather than rewriting installer provenance. The `11.0.17` loader/earlydisplay override artifacts are separately pinned and hydrated for validation, and fixture source compiles against `11.0.17`; using the override for a final distribution still requires a passing bounded gate.

## Paper/Victus merge

Run `applyAllPatches` first so the Paper/Victus patched source tree exists, then supply the vanilla source result if its path differs:

```bash
./gradlew :hybrid-neoforge:neoforgeMergeSources \
  -PneoforgeVanillaSources=/path/to/vanilla-named-sources.jar \
  -PvictusPatchedSources=victus-server/src/minecraft/java
```

The three-way comparer uses a separate NeoForm-only named source result as the base, automatically writes non-conflicting NeoForge-only and Paper/Victus-only changes to `build/neoforge/merge/overlay`, and writes `build/neoforge/merge/conflicts.json`. Any class changed differently by both sides is unresolved unless an owned replacement exists at the same relative path below `hybrid-neoforge/bridge-patches/`. Full replacements now cover the first FML/Paper boot batch: `Main`, `MinecraftServer`, `DedicatedServer`, `ReloadableServerResources`, `Connection`, `PacketEncoder`, and `DataPackConfig`. These retain Paper CLI/plugin/tick/network/resource behavior while composing NeoForge mod loading, lifecycle, data-map/datapack, reload-listener, connection-filter, and codec access hooks; nearby `Victus bridge` comments identify non-obvious joins. `Main.java.patch` remains provenance for the required FML `main(String[])` to Paper `OptionSet` entrypoint bridge. Complete source jars preserve deletions; generated Paper source directories are overlays and may declare explicit removals in `.hybrid-deletions`. The semantic merge report now contains **333 unresolved classes**, down from 339, with **7 resolved by bridge** (one previous auto-merge, `DataPackConfig`, is now explicitly owned because the composed server calls its NeoForge mutation API); distribution assembly remains fail-closed while `unresolved` is non-empty.

A clean report is content-bound to vanilla, NeoForge, Paper/Victus, and bridge inputs. Bind it to the exact merged jar with `neoforgeBindMergedServer -PneoforgeMergedServer=/path/to/merged.jar`. `neoforgeHybridDistribution` then requires the same jar through `-PvictusServerJar`; stale reports or a digest mismatch are refused. It copies the installer-generated library graph, FML class processor/Mixin services and args file; it does not place an ordinary unpatched Paper jar on a module path.

## Fixture and bounded runner

The NeoForge fixture is a real `@Mod` with injected event bus/container, a `DeferredRegister.Items`, common-setup listener, service-loaded no-op `ClassProcessor`, and no-op Mixin config plugin. `neoforgeExpectedFailIntegration` validates that artifact and writes a structured result. With the current report the expected result is `NEOFORGE_MERGE_CONFLICTS` (or `NEOFORGE_MERGED_GAME_MISSING` before a comparison exists); the runner never falls back to starting Paper untransformed.

Run the hydrated gate with `:hybrid-neoforge:neoforgeBoundedIntegration -PneoforgeMergedServer=/path/to/merged.jar`. Its process bound defaults to 90 seconds and can be changed with `-PneoforgeIntegrationTimeout`.

Once a clean merged jar is supplied, the bounded runner requires discovery/transformation/Mixin/constructor/event-bus/deferred-register/common-setup markers. It returns nonzero if any marker is absent.

The public `neoForgeBridge` capability stays false until Bukkit bridging, real mod packs, and soak testing are complete.
