# Module: hybrid mod bridge (isolated)

Config: `hybrid.*`. Day-one architectural seam (per product decision), but an **isolated module,
off by default**. Runs Fabric/NeoForge mods alongside Bukkit/Spigot/Paper plugins.

> ⚠ Highest ongoing-maintenance risk in the project. Arclight/Mohist-class bridges are perpetually
> buggy and version-lagged. The module boundary is non-negotiable: it must never affect the
> pure-plugin core, and `hybrid.enabled=false` instances must not load any of it.

## Approach
- Inject a mod loader (`hybrid.loader: neoforge | fabric`) into the server the way Arclight/Mohist
  do — **substantially easier on the 26.1 unobfuscated base** (no mapping bridging between Mojang
  and loader intermediary names).
- Bridge the two event/registry models where they overlap (blocks, items, entities, world events).

## Safety rails
- `safe-mode: true` — auto-disable known-bad mod↔plugin interactions from a maintained list.
- `tested-only: true` — refuse to start with plugin+mod combinations not on the tested-compat list;
  log exactly what was refused and link the compat entry.
- Per-instance toggle; pure-plugin instances never load the loader.

## Phasing
Land basic mod+plugin coexistence in Phase 4 (after the perf core + hosting moat exist). The seam
(module layout, config, loader abstraction) is defined now so nothing else has to be reworked to
add it. Publish head-to-head benchmarks vs Arclight/Mohist on common modpacks.
