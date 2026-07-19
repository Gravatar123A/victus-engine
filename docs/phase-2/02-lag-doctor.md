# Phase 2 · Lag-doctor (`/victus doctor`)

## Goal

Turn "my server is lagging" into a **ranked, located, actionable diagnosis in one command** — no
spark install, no flame-graph literacy. Identify *what* is eating the tick, *where* (entity type ×
chunk × owning plugin), and offer *one-click, reversible* remediations. This is the headline hosting
feature and the thing that sells the product.

## Upstream baseline (what exists, and why it isn't enough)

- **spark** produces excellent profiles but requires the operator to install it, run a sampler, and
  *interpret* a call tree — a skill most server owners don't have and shouldn't need.
- **Paper** `/tps`/`/mspt` tell you *that* you're lagging, not *why* or *what to do*.
- Nothing upstream maps lag → a specific plugin/chunk/entity cluster **and** proposes a concrete,
  reversible config fix. That translation layer is the product.

## Design / implementation plan

Patch group: `patches/paper-server/hosting/doctor/`. A read-mostly consumer of module 01's timing +
module 03's plugin attribution + live entity/chunk indexes — **it adds almost no new instrumentation.**

### Command
```
/victus doctor [--window 60s] [--apply <fix-id>] [--revert <fix-id>] [--json]
```
- op-gated (and callable from the localhost control endpoint in spec 05 for the panel).
- `--json` emits the same report as a structured object for the panel/log channel.

### Diagnosis pipeline
1. **Read** the rolling per-subsystem MSPT breakdown (module 01) over `--window`.
2. **Rank** subsystems by % of tick.
3. **Drill** the top offenders to location + owner:
   - entities: group by `type × chunk`, attribute the owning plugin where a plugin spawned/holds them;
   - block-entities: hottest tile-entity clusters (hopper chains) by chunk;
   - plugins: per-plugin event/scheduler time from module 03;
   - redstone/chunk-gen: hottest chunks / active dust networks.
4. **Emit** a human report (and JSON) — e.g. `62% entities → 9,412 zombies in chunk 47,-12
   (world_nether), spawned by FarmPlugin` — followed by ranked remediations.

### Remediation catalog
Each remediation is a record `{id, title, expectedGain, behaviorCaveat, writes: <victus.yml patch>,
revertId}`. Initial catalog:

| id | writes | expected (illustrative) | caveat |
| --- | --- | --- | --- |
| `dab-on` / `dab-off` | `optimizations.entities.dab` | ~20–40% entity tick | distant mobs react slower |
| `per-player-spawns` | `optimizations.entities.per-player-mob-spawns: true` | big SMP win | fairer caps; farm rates shift |
| `async-tracker` | `optimizations.entities.async-entity-tracker: true` | ~15% entity-heavy | needs per-player-spawns; NPC compat-mode |
| `ac-redstone` / `vanilla-redstone` | `optimizations.redstone` | ~10–30× dust | AC changes update order (contraptions) |
| `activation-range` | `optimizations.entities.activation-range-tuning: true` | cuts entities ticked | far mobs idle sooner |
| `view-distance` / `sim-distance` | `server.properties` view/sim distance | broad MSPT relief | smaller visible/active world |
| `compression-threshold` | `optimizations.network.compression-threshold` | net CPU on hubs | bandwidth trade |

Catalog is data-driven so new fixes are added without touching the pipeline.

### Auto-apply policy
- `hosting.lag-doctor.auto-apply` defaults **false**. `auto-suggest` (default true) only surfaces
  fixes.
- `--apply <id>` (or panel one-click): writes the `victus.yml` patch, takes an auto-snapshot first
  (spec 05 `snapshot.auto-before.doctor-apply`), applies live where safe (else flags "needs
  restart"), and emits a `doctor.applied` structured event. Every apply has a `revertId`.
- **Never auto-applies behavior-changing fixes without an explicit `--apply` / button press.** Even
  with `auto-apply: true`, only a conservative allowlist (non-behavior-changing) is eligible.

### Panel integration
The JSON report + apply/revert ids flow over the module-01 metrics/log channel and the spec-05
control endpoint, so the panel renders findings with one-click apply/revert buttons.

## victus.yml keys

```yaml
hosting:
  lag-doctor:
    enabled: true
    auto-suggest: true       # surface fixes in /victus doctor + panel
    auto-apply: false        # if true, ONLY a conservative non-behavior-changing allowlist auto-applies
    default-window-seconds: 60
    max-report-entries: 15
```

## Interfaces / API

- Command as above. JSON report shape: `{window, tps, mspt:{p50,p95,p99,max}, subsystems:[{name,pct,
  detail:[{key,value,owningPlugin}]}], remediations:[{id,title,expectedGain,caveat,revertId}]}`.
- Control endpoint (spec 05): `POST /doctor/run`, `POST /doctor/apply {id}`, `POST /doctor/revert {id}`.

## Safety, overhead &amp; compat risks

- **Read-mostly** → negligible added overhead; reuses modules 01/03. Attribution can be sampled/auto-
  enabled only when over MSPT budget (module 03).
- **No plugin-API change**; remediations only write config the engine already honors.
- **Reversibility is mandatory**: every apply is snapshotted + has a revert id → operators/customers
  change things without fear (the adoption lever).
- Risk: a remediation's *expected* gain won't hold on every workload — the report labels all figures
  illustrative and the doctor re-measures after apply to show the *actual* delta.

## Tests &amp; verification

- **Synthetic lag scenarios** (mob-farm world, dust grid, a deliberately slow test plugin): assert
  the doctor attributes the dominant subsystem + correct location/owner.
- **Apply/revert round-trip**: config written, snapshot taken, `doctor.applied` emitted, revert
  restores prior state; before/after MSPT captured.
- **Overhead**: doctor run under load doesn't perturb MSPT beyond threshold.
- **Attribution accuracy** cross-checked against spark on the same scenario.

## Value delivered

The feature that markets itself: customers fix lag themselves in one command; support tickets drop;
the host looks like it ships an ops team in the jar. Directly realizes ARCHITECTURE §5.

## Prior art / references

- **spark** (lucko) — the profiler whose *insight* we automate and make prescriptive.
- **Paper Timings v2** (retired) — the per-cause breakdown we reconstruct.
- **Pufferfish/Purpur** config knobs — the raw levers our remediation catalog writes on the operator's
  behalf.
