# Phase 2 · In-engine per-instance limits

## Goal

Protect the **node** from any single tenant: keep one server's runaway load (mob farm, dupe storm,
bad plugin) from collapsing the neighbours sharing the box. The engine cooperatively **throttles its
own work** when it exceeds a budget, reads its real container limits, and exposes true headroom —
the multi-tenant safety a host needs and only a host would build.

## Upstream baseline (what exists, and why it isn't enough)

- **cgroups / Wings** cap CPU and RAM at the container level — but a CPU-throttled or OOM-killed
  instance is a *bad* outcome (lag spiral, then a hard kill that drops players). The kernel enforces,
  it doesn't *degrade gracefully*.
- **Paper** has scattered anti-abuse knobs (entity limits, `max-entity-collisions`, chunk-load rate)
  but no unified, MSPT-driven "shed load before you fall over" mechanism, and no awareness of its own
  cgroup limits.
- Missing: an engine that notices it's over budget and **sheds the cheapest-impact work first, with a
  log line**, instead of melting down. That's this module.

## Design / implementation plan

Patch group: `patches/paper-server/hosting/limits/`.

### 1. Soft MSPT cap + throttle ladder
- `hosting.limits.max-mspt` (default 45; soft). Read the rolling MSPT (module 01). When the average
  over a short window exceeds the cap, climb a **throttle ladder**, one rung at a time, re-checking
  each window (with **hysteresis** — only escalate after sustained breach, relax after sustained
  recovery — to avoid flapping):
  1. reduce mob-AI activation radius (cheapest, least visible)
  2. tighten per-player mob caps
  3. reduce non-player chunk-tick (random-tick) range
  4. (optional final rung) throttle chunk-generation rate
- **Every rung emits a structured `throttle` event** (module 01 JSON + `victus_throttle_active{
  subsystem}` metric) and is shown in `/victus doctor`. **Never silently drop player-visible behavior
  without a log line** — transparency is a hard rule.
- Ladder is data-driven/config-ordered so operators can reorder or cap the depth.

### 2. cgroup v2 awareness
- On boot (and periodically) read the instance's own cgroup v2 limits — `cpu.max`, `memory.max`,
  `memory.current` (typical paths under `/sys/fs/cgroup/…`; `TODO(verify)` exact paths in the
  Wings/container layout) — and expose `victus_cgroup_cpu_quota`, `victus_cgroup_mem_limit_bytes`,
  `victus_cgroup_mem_current_bytes`. Report **true per-instance headroom**, not node-wide figures the
  JVM would otherwise see.
- Use memory pressure (approaching `memory.max`) as an additional throttle trigger to avoid the OOM
  killer dropping players.

### 3. Heap &amp; entity caps
- `hosting.limits.max-heap-mb` is **informational** vs the real `-Xmx` (the JVM heap is fixed at
  launch); the engine warns on mismatch and surfaces heap vs cgroup memory.
- `hosting.limits.entity-hard-cap` (default 0 = off): last-resort refusal of new spawns past N per
  world, with a logged reason — a blunt backstop for spawn storms, below the graceful ladder.

### 4. What it does NOT claim
- The engine **cannot** truly enforce CPU/RAM — that's the cgroup's job. It **cooperates**: degrades
  gracefully to stay under budget so the cgroup rarely has to throttle/kill. This honest boundary is
  stated in-config and in docs.

## victus.yml keys

```yaml
hosting:
  limits:
    max-mspt: 45              # soft cap → climb the throttle ladder when exceeded (0 = off)
    max-mspt-window-seconds: 10
    throttle-ladder:          # ordered; engine climbs/descends with hysteresis
      - reduce-ai-radius
      - tighten-mob-caps
      - reduce-random-tick-range
      - throttle-chunk-gen
    hysteresis-seconds: 15
    max-heap-mb: 0            # 0 = inherit -Xmx (informational; warns on mismatch)
    cpu-quota-pct: 0          # 0 = cgroup-enforced (informational)
    entity-hard-cap: 0        # 0 = off; last-resort per-world spawn refusal
    memory-pressure-throttle: true   # also throttle as cgroup memory.current nears memory.max
```

## Interfaces / API

- Metrics: `victus_throttle_active{subsystem}`, `victus_throttle_level`, `victus_cgroup_*`,
  `victus_mspt` (module 01).
- Events: `{event:"throttle", subsystem, action, from, to, mspt_p95}` and a symmetric `throttle`
  relax event on recovery.
- Surfaced in `/victus doctor` (module 02) and the panel per-tenant view.
- No new plugin-facing API.

## Safety, overhead &amp; compat risks

- **Throttling changes gameplay feel** (slower distant mobs, tighter caps) — but only under sustained
  overload, always logged, always reversible when load drops, and ladder depth is operator-capped.
  Default path under budget is untouched → 100% compatible.
- **Hysteresis is essential** — without it the ladder oscillates and feels worse than the lag.
- **cgroup path assumptions** vary by container runtime — must fail safe (limits simply unknown →
  MSPT-only mode) if the files aren't readable.
- Risk of masking a real problem: throttling buys stability but the `/victus doctor` finding must
  still point the operator at the *root* cause (the farm/plugin), not just quietly absorb it.

## Tests &amp; verification

- **Overload sim:** spawn a mob/dust storm to push MSPT over `max-mspt`; assert the ladder climbs in
  order, each rung logs an event, MSPT recovers, and rungs relax on recovery without flapping.
- **cgroup read:** in a constrained container, assert true quota/limit/current are read and exported;
  assert graceful fallback when unreadable.
- **Memory-pressure trigger:** approach `memory.max` → assert throttle engages before OOM.
- **Compat:** under-budget server shows zero throttling and zero plugin regressions (top-50 suite).

## Value delivered

One bad tenant can no longer take down a node — the core multi-tenant safety promise. Graceful
degradation instead of CPU-throttle lag spirals or OOM kills that drop players; true per-instance
headroom for the panel and for oversell decisions. This is the moat feature only a host prioritizes.

## Prior art / references

- **cgroups v2 / Pterodactyl Wings** — the container enforcement this cooperates with.
- **Paper** entity/collision/chunk-rate knobs — the levers the ladder pulls.
- **Pufferfish DAB / activation ranges** — the mechanisms behind the cheapest ladder rungs.
