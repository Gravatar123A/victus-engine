# Phase 2 — Hosting-first control plane (the moat)

The differentiator. Phase 1 makes Victus Engine *fast*; Phase 2 makes it the only server built
**for hosts** — per-instance safety, always-on observability, one-command lag diagnosis, and control
hooks the Victus panel/Wings drive. No other Paper fork ships this because no other fork is built by
a hosting company. Phases 1 + 2 together are the pilotable MVP.

## Spec index

| # | Sub-module | Spec | Core value | Key `victus.yml` |
| --- | --- | --- | --- | --- |
| 01 | Metrics &amp; observability | [`01-metrics-observability.md`](01-metrics-observability.md) | shared timing core + Prometheus + JSON logs | `hosting.metrics.*`, `hosting.logging.*` |
| 02 | Lag-doctor | [`02-lag-doctor.md`](02-lag-doctor.md) | `/victus doctor` → ranked, located, one-click fixes | `hosting.lag-doctor.*` |
| 03 | Plugin attribution | [`03-plugin-attribution.md`](03-plugin-attribution.md) | trustworthy "which plugin lags?" | `hosting.attribution.*` |
| 04 | Per-instance limits | [`04-per-instance-limits.md`](04-per-instance-limits.md) | one tenant can't kill a node (graceful throttle) | `hosting.limits.*` |
| 05 | Control hooks + panel | [`05-control-hooks-panel.md`](05-control-hooks-panel.md) | drain-restart, rollback, backup, panel/Wings wiring | `hosting.control.*` |

## Build order

1. **01 Metrics** — the shared per-subsystem timing core + exporter + JSON logs. Everything else
   consumes it. (Same instrumentation as the Phase 1 harness `08` — build once.)
2. **03 Attribution** — per-plugin timing; feeds the doctor's plugin line.
3. **02 Lag-doctor** — the diagnosis pipeline + remediation catalog on top of 01/03.
4. **04 Limits** — the MSPT-driven throttle ladder + cgroup awareness (emits via 01).
5. **05 Control hooks** — drain/rollback/backup entrypoints + the localhost control endpoint + panel
   button ↔ Wings action ↔ exit-code mapping.

## Cross-cutting rules

- **Observability must never degrade the tick.** Phase-level timers, sampling defaults, off-thread
  export; every collector is toggleable and overhead-budgeted (<~1% p99).
- **Nothing behavior-changing happens silently.** Throttles log structured events; remediations are
  one-click and snapshotted; the default under-budget path is 100% plugin-compatible.
- **Two loopback surfaces only:** metrics exporter `127.0.0.1:9940`, control endpoint
  `127.0.0.1:9941` (token-authed). Never public.
- **Cooperate with cgroups, don't pretend to replace them** — the engine degrades gracefully so the
  kernel rarely has to throttle/kill.
- Reuses Victus's existing panel, Wings, Velocity proxy (for drain-transfer), and Prometheus/JSON
  scraping — this bolts onto infrastructure that already exists.

## Definition of done (Phase 2 exit criteria)

- [ ] Prometheus endpoint + JSON logs scraped by the panel; per-tenant dashboards live.
- [ ] `/victus doctor` produces a correct ranked breakdown on synthetic lag scenarios and applies +
      reverts a fix with an auto-snapshot and structured event.
- [ ] Plugin attribution matches spark within tolerance; `sampled` mode <~1% MSPT overhead.
- [ ] Overload sim: the throttle ladder keeps a tenant under budget and relaxes on recovery without
      flapping; cgroup limits read + exported; graceful fallback when unreadable.
- [ ] Panel-initiated safe-restart drains players to the hub via the proxy (no drops) and backups are
      world-consistent (save-off/flush bracket).
- [ ] **Piloted on the DE-1 free nodes**, panel showing live metrics + one-click doctor fixes.

Once green, Victus Engine (Phases 0→2) is a faster-than-Paper, fully-compatible, self-instrumenting,
multi-tenant-safe server you can put in front of real customers — before taking on the harder Phase 3
(regionized threading) and Phase 4 (hybrid).
