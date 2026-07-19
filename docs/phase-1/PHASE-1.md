# Phase 1 — Perf core (the MVP)

Phase 1 delivers the first shippable Victus Engine: **stock Paper 26.1 + a stack of
compat-preserving optimizations + the benchmark harness that proves them.** No threading rewrite,
no hybrid — just "Paper, but measurably faster, with every plugin still working." Combined with
Phase 2 (the hosting moat) this is the pilotable product.

## Spec index

| # | Area | Spec | Headline (illustrative) gain | Key `victus.yml` |
| --- | --- | --- | --- | --- |
| 01 | Redstone dust | [`01-redstone.md`](01-redstone.md) | ~10–30× dust-tick MSPT (wire-heavy only) | `optimizations.redstone` |
| 02 | Mob AI &amp; pathfinding | [`02-entity-ai-pathfinding.md`](02-entity-ai-pathfinding.md) | DAB ~20–40% off entity tick (mob-heavy) | `optimizations.entities.dab`, `.async-pathfinding` |
| 03 | Entity tracking / spawn / collision | [`03-entity-tracking-spawning-collision.md`](03-entity-tracking-spawning-collision.md) | async tracker ~15%; per-player spawns = big SMP win | `optimizations.entities.async-entity-tracker`, `.per-player-mob-spawns` |
| 04 | Network / ping | [`04-network-ping.md`](04-network-ping.md) | libdeflate ~3× compress CPU; lower felt latency | `optimizations.network.*` |
| 05 | Chunks | [`05-chunks.md`](05-chunks.md) | removes gen/IO stalls from the tick | `optimizations.chunks.*` |
| 06 | Memory | [`06-memory.md`](06-memory.md) | up to ~2× lower footprint (heavy cases) | `optimizations.memory.dedup` |
| 07 | GC | [`07-gc.md`](07-gc.md) | sub-ms pauses vs G1 ~35 ms avg | launch flags + `optimizations.gc.profile` |
| 08 | Benchmark harness | [`08-benchmark-harness.md`](08-benchmark-harness.md) | *proves* all of the above | — (CI gate) |

**All gain figures are illustrative targets grounded in prior-art claims — to be confirmed by
spec 08 on Victus hardware, not measurements.** Every figure is near-zero when its workload is
absent (redstone gains need dust; entity gains need mobs; etc.).

## Build order

1. **Phase 0 config loader + profile overlay** (prerequisite for every spec's `victus.yml` keys and
   the boolean-or-map coercion specs 02/03 rely on).
2. **Spec 08 — benchmark harness *first*.** Non-negotiable: stand up the harness before the patches
   so every optimization lands with a before/after on identical hardware. This is what keeps us
   honest and out of the recycled-blog-percentage trap.
3. **GC (07)** — a launch-flag change, near-zero code, immediate pause-time win; validate on a pilot
   node vs the current Aikar/G1 fleet tuning.
4. **Chunks (05)** and **Memory (06)** — mostly tuning + dedup of what Paper/Moonrise already ship;
   low risk, broad benefit.
5. **Network (04)** — libdeflate backend + handler path; wire-identical, low risk.
6. **Redstone (01)** — config binding + profile defaults (algorithm already upstream).
7. **Entities (02, 03)** — the largest code delta and the biggest wins; land behind toggles, prove
   each with the harness, watch the NPC/anti-cheat compat edges.

## Cross-cutting rules

- **The default (`threading.mode: single`) path stays 100% plugin-compatible.** Anything
  behavior-changing is a toggle defaulting to the compat-preserving value; the `technical` profile
  turns off approximations (vanilla redstone, no DAB, no path cache) for tick-exact parity.
- **Every optimization is individually toggleable and individually benchmarked.** No "bundle of
  magic" — each patch group can be A/B'd and reverted.
- **Per-subsystem timing is shared infrastructure.** The MSPT-by-subsystem timers specs 01–06 lean
  on are the same ones that feed `/victus doctor` and the Prometheus exporter (Phase 2) — build them
  once, in the harness (08).
- **`TODO(verify)` markers** flag every place internal Paper/Mojang class names may have shifted
  across the 26.1 unobfuscation cutover; resolve them on the first online build.

## Definition of done (Phase 1 exit criteria)

- [ ] Benchmark harness (08) runs reproducibly, Victus vs stock Paper 26.1, ≥150 bots, spread **and**
      clustered profiles, real workloads (survival + redstone suite + entity suite + exploration).
- [ ] Reports **MSPT distribution** (mean/p95/p99/max) + GC pauses + per-subsystem breakdown — never
      idle TPS or startup time.
- [ ] Each optimization shows a measurable win on its target workload **and zero regression** on the
      others; published as committed result tables.
- [ ] **Zero plugin regressions** on a top-50 plugin suite in the default path.
- [ ] CI regression gate: fail the build if MSPT p99 regresses beyond threshold.
- [ ] GC default migration validated on a pilot node before any fleet rollout.

Once green, this is the jar you pilot on the DE-1 free nodes — then Phase 2 bolts the hosting
control plane on top.
