# Victus Engine — Roadmap

Phases 1 + 2 are the real MVP: a faster-than-Paper, hosting-instrumented server with full plugin
compat and the lag-doctor — shippable and pilotable on Victus free nodes before the two hard/risky
pieces (regionized threading, hybrid).

| Phase | Deliverable | Exit criteria |
| --- | --- | --- |
| **0 — Foundation** *(this scaffold)* | Fork skeleton, paperweight build/CI, branding, module seams, `victus.yml` schema | `applyPatches build` produces a booting jar online |
| **1 — Perf core** ⭐ | All always-on optimizations (ARCHITECTURE §3); benchmark harness vs Paper on real entity/redstone workloads; publish tables | Measurable MSPT win at 150+ players; 0 plugin regressions on a top-50 plugin suite |
| **2 — Hosting moat** ⭐ | Per-instance limits, Prometheus/JSON logs, `/victus doctor`, safe-restart/rollback, config auto-migration; panel integration | Piloted on DE-1 free nodes; panel shows live metrics + one-click doctor fixes |
| **3 — Parallel ticking** | `parallel` tier (compat-safe, `compat-mode`), then opt-in `regionized` Folia mode | Spread-survival box holds ≥3× Paper players at ≥19 TPS |
| **4 — Hybrid** | Fabric/NeoForge bridge, tested-compat lists, safe-mode | Curated modpack + plugin suite runs; unknown combos refused |
| **5 — Lobby engine** | Minestom-based Victus Lobby on the discovery/proxy protocol | Lobby backend holds thousands at 20 TPS |
| **6 — Profiles & LTS** | Profile presets, guided config, LTS branch, migration docs, benchmark publishing, support tier | Public beta |

⭐ MVP.

## Benchmark discipline (non-negotiable)

- Test on **identical hardware**, realistic workloads (populated survival + a redstone farm suite),
  **≥150 concurrent** (bots), report **MSPT distribution** not idle TPS or startup time.
- Publish Paper vs Victus Engine tables with the exact plugin set + hardware. No recycled
  host-blog percentages — those are how the rest of the fork scene lies.

## Known risks (tracked honestly)

1. **Upstream churn** — Mojang changes the protocol ~monthly; the Via stack + our patches need
   continual rebasing. Unobfuscation (26.1) eases this but doesn't remove it.
2. **Hybrid maintenance** — Arclight/Mohist-class bridges are perpetually buggy/version-lagged.
   Mitigation: strict module isolation + tested-only default.
3. **Regionized plugin compat** — Folia mode breaks non-aware plugins. Mitigation: opt-in only;
   ship our own hub/lobby plugins as Folia-aware.
4. **ZGC default switch** — validate against current Aikar/G1 fleet tuning on a pilot node.
