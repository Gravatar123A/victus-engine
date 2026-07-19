# Module: lag-doctor (`/victus doctor`)

The headline hosting feature: turn "my server lags" into a ranked, actionable diagnosis in one
command — no spark install, no flame-graph reading.

## Command
`/victus doctor [--window 60s] [--apply <fix-id>]`

Output (also available as JSON via the metrics/logging channel):
1. **Rolling MSPT breakdown by subsystem** (entities, block entities/hoppers, redstone, chunk
   gen/IO, plugins, network) with % of tick.
2. **Top offenders** drilled to location + owner: entity type × chunk × source plugin; per-plugin
   event-handler latency; hottest tile-entity clusters.
3. **Ranked remediations**, each with an id, expected gain, and behavior caveat, e.g.:
   - `dab-on` — enable dynamic mob-brain throttling (~20-40% entity MSPT; distant mobs less responsive)
   - `per-player-spawns` — fairer mob caps (helps farm-heavy servers)
   - `ac-redstone` — switch to Alternate Current (up to ~30× dust; may break locational contraptions)
   - `activation-range` — tune per-type ranges
   - `view-distance` / `simulation-distance` reductions with the player-facing trade-off stated

## Auto-apply
`lag-doctor.auto-apply` defaults **false**. `--apply <id>` (or panel one-click) makes the change,
writes it to `victus.yml`, and records a structured `doctor.applied` event so it's reversible.
Never auto-applies behavior-changing fixes without explicit action.

## Data sources
Reuses the metrics module's per-subsystem timing + entity/chunk indexes; the plugin-attribution
piece wraps event dispatch + scheduler tasks with cheap per-plugin timers (togglable).
