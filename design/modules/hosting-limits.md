# Module: hosting limits + metrics (the moat)

Config: `hosting.*`. Server-enforced, so one tenant can't take down a node — impossible for a
non-host to prioritize this the way Victus can.

## Per-instance limits
- **`max-mspt` (soft)**: when a rolling MSPT average exceeds the cap, progressively throttle the
  cheapest-to-shed heavy subsystems first (mob AI radius → mob caps → non-player chunk tick range),
  logging each throttle as a structured event. Never silently drop player-visible behavior without
  a log line.
- **`max-heap-mb` / `cpu-quota-pct`**: advisory to the engine; real enforcement is the node cgroup.
  Engine reads its own cgroup limits and exposes them as metrics so the panel shows true headroom.
- **`entity-hard-cap`**: last-resort spawn refusal per world (off by default).

## Metrics — Prometheus exporter
Endpoint `hosting.metrics.prometheus.bind:port` (default 127.0.0.1:9940). Series:
`victus_tps`, `victus_mspt{quantile}`, `victus_mspt_by_subsystem{subsystem=...}`,
`victus_entities{world,type}`, `victus_chunks_loaded{world}`, `victus_players`,
`victus_heap_bytes`, `victus_gc_pause_ms{quantile}`, `victus_throttle_active{subsystem}`.

## Logging
`hosting.logging.format: json` emits one JSON object per log record (ts, level, logger, msg,
+ structured fields on throttle/doctor/limit events) — drop-in for the panel's existing scrapers.

## Control hooks
`safe-restart-hook` / `rollback-hook`: named entrypoints Victus Wings calls for graceful drain +
restart and world/config rollback, so the panel's buttons map to first-class engine operations.
