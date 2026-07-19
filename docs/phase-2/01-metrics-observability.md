# Phase 2 · Metrics &amp; observability backbone

## Goal

Provide the **always-on, low-overhead, scrape-friendly, multi-tenant** telemetry that the rest of
the hosting control plane stands on: a shared per-subsystem tick-timing core, a Prometheus exporter,
and structured JSON logging that the Victus panel already knows how to scrape. This module is the
foundation the lag-doctor (02), plugin attribution (03), and per-instance limits (04) all consume —
built once, here.

## Upstream baseline (what exists, and why it isn't enough for a host)

- **spark** (lucko) is excellent for *humans doing ad-hoc investigation* — sampling profiler, `/spark
  tps`, `/spark health`. But it's pull-on-demand, sampling-based, and not designed to be scraped
  continuously across hundreds of tenants; leaving a sampler running everywhere has overhead and no
  stable metric contract.
- **Paper** exposes `/tps`, `/mspt`, basic health, and JMX beans — coarse, not labelled by
  subsystem/world/type, not Prometheus-native, no structured event stream.
- **Paper Timings v2** was retired upstream; there is no built-in always-on per-subsystem breakdown.
- A host needs: a stable metric catalog, per-instance isolation, cheap always-on collection, and
  machine-parseable event logs — none of which the above give out of the box.

## Design / implementation plan

Patch group: `patches/paper-server/hosting/metrics/`.

### 1. Shared per-subsystem tick timing (the core)
- Instrument the tick loop at phase boundaries — `entities`, `block-entities` (hoppers/furnaces),
  `redstone`, `chunk-gen`, `chunk-io`, `network`, `plugins` (from module 03), `other` — using
  `System.nanoTime()` deltas at **phase granularity, not per-entity** (a handful of timers per tick,
  negligible cost). `TODO(verify)` the Paper 26.1 tick phase call sites.
- Feed deltas into fixed-size **HdrHistogram**s (or t-digest) per subsystem for cheap streaming
  quantiles (p50/p95/p99/max) without storing raw samples.
- **This is the exact same instrumentation the Phase 1 benchmark harness
  ([`../phase-1/08-benchmark-harness.md`](../phase-1/08-benchmark-harness.md)) builds** — one
  implementation, consumed by both. The harness reads it offline; this module exposes it live.

### 2. Prometheus exporter
- A tiny embedded HTTP server (reuse a lightweight handler; or a dedicated Netty channel) serving
  `GET /metrics` in text exposition format, bound to `hosting.metrics.prometheus.bind:port`
  (default `127.0.0.1:9940`, loopback so only the node/panel scrapes it).
- Sampled on a background thread from the histograms + live gauges every `interval` (default 10s);
  **never computed on the tick thread**.

### 3. Structured JSON logging
- A log4j2 layout/appender toggled by `hosting.logging.format: text | json`. In `json` mode each
  record is one JSON object: `ts, level, logger, thread, msg`, plus structured fields for
  control-plane events (`event: throttle|doctor.applied|limit|drain|migration`, with typed payloads).
- Drop-in for the panel's existing JSON scrapers; text mode stays the human default.

### 4. Cardinality control
- Per-`{world,type}` label sets are capped (`cardinality-limit`, default 200) with an `…_other`
  bucket, and an allowlist for entity types, so a pathological world can't explode Prometheus
  cardinality or memory.

## victus.yml keys

```yaml
hosting:
  metrics:
    prometheus:
      enabled: true
      bind: 127.0.0.1        # loopback only — scraped by the node/panel, never public
      port: 9940
    interval-seconds: 10
    per-subsystem-timing: true    # the shared tick-timing core (also used by the harness)
    cardinality-limit: 200        # max distinct {world,type} label combos before bucketing to _other
  logging:
    format: json                  # text | json
    include-fields: [event, world, plugin, subsystem]   # structured fields emitted in json mode
```

## Interfaces / API

**Metric catalog** (`victus_` prefix):

| Metric | Type | Labels |
| --- | --- | --- |
| `victus_tps` | gauge | — |
| `victus_mspt` | summary | `quantile` |
| `victus_mspt_by_subsystem` | summary | `subsystem`, `quantile` |
| `victus_entities` | gauge | `world`, `type` |
| `victus_block_entities` | gauge | `world`, `type` |
| `victus_chunks_loaded` | gauge | `world` |
| `victus_players` | gauge | — |
| `victus_heap_bytes` / `victus_heap_max_bytes` | gauge | — |
| `victus_gc_pause_ms` | summary | `collector`, `quantile` |
| `victus_throttle_active` | gauge | `subsystem` (from module 04) |
| `victus_plugin_time_ms` | summary | `plugin`, `phase` (from module 03) |
| `victus_netloop_cpu_ratio` | gauge | `loop` (from spec 04-network) |
| `victus_cgroup_cpu_quota` / `victus_cgroup_mem_limit_bytes` | gauge | — (from module 04) |

**JSON log event shape** (example):
```json
{"ts":"2026-07-19T10:11:12.000Z","level":"INFO","logger":"victus.limits","event":"throttle",
 "subsystem":"entities","action":"reduce-ai-radius","from":48,"to":32,"mspt_p95":58.2}
```

## Safety, overhead &amp; compat risks

- **Zero plugin-API change** — pure internal instrumentation + an exporter; the default tick path is
  untouched functionally.
- **Overhead budget:** phase-level timers + periodic background sampling must add well under ~1% MSPT;
  `per-subsystem-timing: false` disables the core entirely. Never compute exposition on the tick.
- **Exporter is loopback + off-thread**; a slow scrape can't stall the server.
- **Cardinality cap is mandatory** — without it a mob-farm world could mint thousands of series.

## Tests &amp; verification

- **Overhead bench** (via harness 08): MSPT distribution with instrumentation on vs off — assert
  <1% p99 delta; assert exporter scrape under load doesn't perturb MSPT.
- **Exposition validity:** `/metrics` parses with a Prometheus client; label sets stable across
  scrapes; cardinality cap kicks in and buckets to `_other`.
- **JSON log validity:** every record parses; control-plane events carry their typed fields.
- **Quantile accuracy:** histogram p99 within tolerance of a recorded reference distribution.

## Value delivered

Panel dashboards, alerting, and capacity planning per tenant; the data spine for one-click
lag-doctor fixes and automatic limit throttling; honest before/after for every Phase 1 optimization.
Operational visibility no other fork ships — and it plugs straight into the monitoring Victus
already runs.

## Prior art / references

- **spark** (lucko) — the human-facing profiler this complements (not replaces).
- **Paper Timings v2** (retired) — the per-subsystem breakdown gap we fill always-on.
- **Prometheus** text exposition + client model; **HdrHistogram / t-digest** for streaming quantiles.
- **log4j2 JSON layout**; community `minecraft-prometheus-exporter` plugins (external, per-server) as
  the pattern we make first-class and multi-tenant.
