# Phase 2 · Per-plugin performance attribution

## Goal

Answer "**which plugin is costing me tick time?**" reliably and cheaply, so the lag-doctor's plugin
line is trustworthy and the panel can show per-plugin cost per tenant. Attribute main-thread time to
the owning plugin across event dispatch, scheduler tasks, and command execution — transparently, with
no plugin-API or behavior change.

## Upstream baseline (what exists, and why it isn't enough)

- **Paper Timings v2** did exactly this and was **retired** — leaving a gap.
- **spark** offers per-plugin/sampler attribution on demand, but is sampling-based, human-driven, and
  not an always-available metric per tenant.
- Result: no built-in, always-on, low-overhead "cost by plugin" signal a host can scrape and a
  customer can read. That's what this module provides.

## Design / implementation plan

Patch group: `patches/paper-server/hosting/attribution/`.

### What gets wrapped (internal, transparent)
- **Event dispatch:** wrap the `RegisteredListener` invocation / event executor so each listener call
  is timed and charged to the listener's owning plugin, preserving listener **priority order** and
  exception semantics exactly. `TODO(verify)` the Paper 26.1 event-dispatch call site.
- **Scheduler tasks:** wrap Bukkit sync/async scheduler run + Paper's scheduler so each task's runtime
  is charged to its plugin; async task time is tracked separately (`phase=async`) and **not** counted
  against MSPT.
- **Commands:** time `CommandExecutor` / brigadier dispatch per plugin.
- Charge into a per-plugin, per-phase accumulator drained each tick into module 01's histograms as
  `victus_plugin_time_ms{plugin,phase}` where `phase ∈ {event, task-sync, task-async, command}`.

### Overhead management (critical — wrapping everything isn't free)
- `hosting.attribution.mode`:
  - `off` — no wrapping.
  - `sampled` (default) — time only a sampled fraction of invocations (`sample-rate`), scale up
    statistically; near-zero overhead, good enough for ranking.
  - `full` — time every invocation (used transiently by the lag-doctor for a precise `--window`).
  - `auto` — normally `sampled`; automatically switch to `full` for N seconds when MSPT exceeds the
    limit budget (module 04), then back down.
- Timers use `System.nanoTime()`; accumulation is a couple of longs per plugin — cheap.

### Attribution limits (documented honestly)
- Plugins that **spawn their own threads** and do work off the scheduler can't be fully attributed —
  best-effort (thread-name/classloader heuristics) and documented as a known limit.
- Cross-plugin call chains (A calls B's API) charge the executing frame's plugin; noted.

## victus.yml keys

```yaml
hosting:
  attribution:
    mode: sampled          # off | sampled | full | auto
    sample-rate: 0.05      # fraction timed in sampled mode
    auto-full-window-seconds: 30   # in 'auto', how long to run full when over budget
    track-async: true      # attribute async task time (reported, not charged to MSPT)
```

## Interfaces / API

- Metric: `victus_plugin_time_ms{plugin, phase, quantile}` + a per-tick `victus_plugin_time_share`
  gauge (fraction of tick).
- Consumed by `/victus doctor` (module 02) for the plugin line and by the panel per-tenant view.
- No new plugin-facing API.

## Safety, overhead &amp; compat risks

- **Must be behavior-transparent:** listener order, event cancellation, exceptions, and command
  results identical to unwrapped Paper. The wrapper only measures. This is a hard test gate.
- **Overhead is the main risk** — mitigated by `sampled`/`auto` defaults; `full` is transient only.
- **Async attribution must never be charged to MSPT** (it's off-thread) — reported separately.
- Thread-spawning plugins are a known attribution blind spot — documented, not hidden.

## Tests &amp; verification

- **Transparency suite:** a battery asserting listener priority/cancellation/exception behavior is
  byte-identical wrapped vs unwrapped across the top-50 plugin set.
- **Attribution accuracy:** a test plugin that burns a known N ms in an event/task/command → assert
  it's charged ~N ms to the right plugin+phase; sampled mode within statistical tolerance.
- **Overhead bench** (harness 08): MSPT with `off` vs `sampled` vs `full`; assert `sampled` <~1% p99.
- **Auto-escalation:** drive MSPT over budget → attribution flips to `full`, then relaxes.

## Value delivered

Makes the lag-doctor's "blame the plugin" trustworthy; lets the host/customer see exactly which
plugin to tune or remove; per-tenant cost visibility for capacity planning. Fills the Timings-v2 gap
as an always-on, scrape-friendly signal.

## Prior art / references

- **Paper Timings v2** (retired) — the capability this restores, minus the always-on tax via sampling.
- **spark** per-plugin attribution — the accuracy reference to validate against.
- **Bukkit event / scheduler internals** — the wrap points.
