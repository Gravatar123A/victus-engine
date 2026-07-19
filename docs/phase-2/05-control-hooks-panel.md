# Phase 2 · Control hooks + panel/Wings integration

## Goal

Give the Victus panel and [Victus Wings](../../design/modules/hosting-limits.md) **first-class engine
entrypoints** for the lifecycle operations a host actually runs: a *graceful* safe-restart (drain
without dumping players on the floor), consistent backups, safe/reversible rollback, and visible
config-migration status. Today those panel buttons drive Wings' generic process controls — "send the
stop command, wait, `SIGTERM`, `SIGKILL`" — which are correct for any jar but blind to Minecraft:
they kick every online player, they can archive a world mid-chunk-write, and they can't tell the
panel whether a first-boot config migration succeeded. This spec turns each of those into a
**coordinated, Minecraft-aware operation** the engine owns, exposed over a **localhost-only, token-
authed control surface** (plus console commands + signals), and wired into the Prometheus/JSON
channel the panel already scrapes.

This is squarely the [ARCHITECTURE §5](../ARCHITECTURE.md) moat: *"Safe-restart / rollback / backup
hooks callable by Victus Wings."* No non-host fork can prioritize this, because no non-host fork owns
the panel, the Wings daemon, and the [Velocity proxy (#342)](../ARCHITECTURE.md) the drain transfers
players across.

**Non-negotiables carried from the repo:** the default (plugin) tick path pays nothing for this;
observability never degrades the tick; behavior-changing remediations stay opt-in / one-click, never
silent; and the control surface is **never** reachable off `127.0.0.1` without a token.

## Upstream baseline (what Paper 26.1 / Wings / existing tools already provide, and why it's not enough for a host)

Honest inventory — we coordinate these primitives, we do not reimplement them:

- **Paper's shutdown path.** Paper (and CraftBukkit) install a JVM shutdown hook that runs
  `save-all`, disables plugins, and closes worlds on a clean stop. `SecureRandom`/console `stop`/`end`
  and `SIGTERM` all route into `MinecraftServer#halt`/`stopServer`. `TODO(verify)` the exact
  `MinecraftServer` / `DedicatedServer` shutdown method names + the console-command dispatch class on
  Paper 26.1 (post-unobfuscation these are cleartext but may have moved). **Gap:** the stop path does
  *not* drain — it saves and exits with everyone still connected, so a restart kicks the whole server.
  There is no "deny new logins, move players to the hub, then flush" sequence.
- **`save-off` / `save-on` / `save-all flush`.** Vanilla/Paper commands to quiesce autosave and force
  a synchronous flush — exactly what a consistent backup needs. **Gap:** nothing calls them around a
  Wings archive. Wings tars the volume with the server still writing chunks, so a backup can capture a
  torn region file. Consistency is left to the operator remembering to `save-off` by hand.
- **Pterodactyl Wings power controls.** Wings exposes `start` / `stop` / `restart` / `kill`. The stop
  action is per-egg: a command written to **stdin** (for Paper, `stop`) or a signal. Wings sends it,
  waits a bounded time, then escalates `SIGTERM` → `SIGKILL`. It also has **crash detection** (restart
  on unexpected exit) and native **backups** (local `tar.gz` / S3) + **restore** (wipe + extract).
  `TODO(verify)` the exact Wings stop-timeout default and the `SIGTERM`→`SIGKILL` interval on the
  Victus Wings build (`/usr/local/bin/wings`, the rebrand target). **Gaps:** (a) Wings treats the jar
  as an opaque process — its "restart" is stop+start with no drain; (b) its backup/restore fire **no
  pre/post hooks**, so the engine never gets a chance to quiesce or to re-run config migration after a
  restore; (c) restart-on-exit-code is not a native mapping (only crash detection is), so an
  *engine-initiated* restart has no clean signalling path.
- **Velocity proxy.** Velocity can move a player between backends (server connect via the
  BungeeCord/Velocity plugin-messaging channel) and, since 1.20.5, the client **Transfer** packet can
  hand a client to a different address entirely — the same rail [server discovery + VictusWaiter
  switch-wake](../ARCHITECTURE.md) already use to connect players across nodes. **Gap:** nothing ties
  "this backend is draining for a restart" to "proxy, please move its players to the hub first."
- **spark / metrics.** spark and the Phase 1 metrics work already sample MSPT and health. This spec
  **consumes** the shared per-subsystem timing + Prometheus registry from
  [`08-benchmark-harness.md`](../phase-1/08-benchmark-harness.md) and
  [hosting-limits](../../design/modules/hosting-limits.md) for any health it reports — it adds **no new
  timing instrumentation** and no per-tick sampling of its own.
- **Config migrator.** [`config-migrator.md`](../../design/modules/config-migrator.md) already writes
  `victus-migration-report.txt` + a `.victus-migrated` marker on first boot. **Gap:** that report only
  lives on disk; the panel has no structured signal to render "migrated from Paper — view report" or
  to flag a *failed* migration.

Net: every primitive exists; **nothing orchestrates them into host-grade lifecycle operations, and
nothing exposes them to the panel/Wings on a safe, authed surface.** That orchestration is this spec.

## Design / implementation plan

Patch group: `patches/paper-server/hosting/control/`. One module, off the tick, driven entirely by
lifecycle events + the control surface. It has three collaborators: the **control endpoint**
(localhost HTTP), the **console/signal front-ends**, and the **operations** (drain, backup-quiesce,
rollback/snapshot, migration-status) they invoke. Everything reports through the **existing**
JSON-log + Prometheus channel; nothing here starts a new scrape surface for metrics.

### 1. The control surface (how it's invoked)

Three equivalent front-ends, so integration is robust to whatever Wings can reach:

**(a) Localhost HTTP control endpoint — the primary panel/Wings path.**
`VictusControlServer` binds `hosting.control.bind:port` (**default `127.0.0.1:9941`** — a *separate*
port from the `9940` Prometheus exporter so the privileged control surface has its own auth domain).
It runs on its own Netty listener (its own boss/worker threads), **never on the main tick thread**;
request handlers marshal the minimal Bukkit work (deny logins, transfer players, `save-off`) onto the
main thread via the scheduler and return a job handle immediately. When
`hosting.control.enabled: false`, the listener is never created — zero footprint.

- **Bind guard:** on boot the module refuses to start the listener if `bind` is not a loopback
  address *unless* a token is configured **and** an explicit `allow-nonloopback` escape hatch is set
  (which we do not document as supported for production). A non-loopback bind without a token is a
  hard boot error, logged loudly. This is the "never public" invariant enforced in code, not docs.
- **Auth:** every request needs `Authorization: Bearer <token>`. The token is resolved in priority
  order **`token-env` → `token-file` → inline `token`**; the env var is preferred so the secret is
  not sitting in a world-readable `victus.yml` inside the container (Wings injects
  `VICTUS_CONTROL_TOKEN` at start, alongside the egg's other env). Comparison is constant-time.
  Missing/blank token ⇒ the endpoint still binds but **rejects every request 401** (fail-closed).
  Setting the inline `token` logs a "prefer token-env/token-file" warning.
- **Scope:** the endpoint can stop, roll back, and drain a server — it is *root-equivalent for that
  instance*. It is therefore treated exactly like the node↔Wings link: loopback + token, never
  proxied to the internet, and rate-limited (a small fixed bucket) so a bug in a caller can't spin it.

**(b) Console commands (stdin) — zero-new-surface fallback + operator use.**
`hosting.control.console-commands: true` registers a `victus` command family dispatched from the
server console (Wings already owns stdin, so Wings — or an operator in the panel console — can drive
every operation without opening a socket). The privileged verbs (`safe-restart`, `drain`, `rollback`,
`backup`) are **console/op-gated only**; they are not granted to normal players. (`/victus doctor`
from [lag-doctor](../../design/modules/lag-doctor.md) is the same command root — this spec adds the
lifecycle verbs beside it.)

**(c) Signals — the last-resort graceful path.**
The module installs a JVM shutdown hook and a `SIGTERM`/`SIGINT` handler that, if a drain is not
already running, kicks off a **time-boxed** graceful drain (bounded by `drain.timeout-seconds`) and
then falls through to Paper's normal `save-all` + shutdown. Because Wings sends `SIGTERM` after its
stop-command timeout, this guarantees that even a panel "Stop" that bypassed the drain command still
gets a best-effort drain before the process dies. `SIGKILL` (Wings "Kill") is uncatchable by design —
that path relies on Paper's last periodic save, and we document it as the non-graceful button.

**Intercepting the Wings stop command (the "every restart is graceful" trick).**
The cleanest integration needs *no* Wings change: the module intercepts the egg's configured stop
command (`stop`/`end`) so that a normal panel **Restart** or **Stop** runs the drain first, then falls
into Paper's real shutdown. `TODO(verify)` whether to hook the console `stop`/`end` dispatch directly
or to register a higher-priority `victus:stop` and change the egg's "stop command" to it (the latter
is more explicit and avoids fighting Paper's own handler — likely the shipping choice).

### 2. Safe-restart / drain (`hosting.control.safe-restart-hook`)

The drain state machine, invoked by `POST /control/safe-restart`, `victus safe-restart`, an
intercepted stop, or `SIGTERM`:

1. **Enter draining.** Set an internal `draining` flag; emit `control.drain.start`
   (JSON log) and flip `victus_control_drain_active` to 1. Idempotent — a second trigger joins the
   in-flight drain.
2. **Deny new logins.** Register a high-priority `PlayerLoginEvent`/`AsyncPlayerPreLoginEvent`
   handler that rejects with `drain.login-deny-message`. This uses the *public Bukkit API* — exactly
   what a plugin would do — so it is fully compat-safe and visible to other plugins.
3. **Broadcast + move players.** Broadcast `drain.broadcast-message`, then move players off according
   to `drain.transfer-mode`, **staggered by `per-player-delay-ms`** so the proxy/hub isn't thundering-
   herded:
   - **`proxy-connect` (default):** send the Velocity/BungeeCord `Connect` plugin message per player
     to `drain.target` (a proxy server name; `hub` resolves to the proxy's configured fallback).
     Keeps the player's proxy session — the smoothest UX, and the normal case since the fleet runs a
     proxy. Cross-refs [`04-network-ping.md`](../phase-1/04-network-ping.md) (the proxy/networking
     layer) and the discovery/proxy rail.
   - **`transfer-packet`:** send the 1.20.5+ client **Transfer** packet to `drain.target`
     (`host:port`) — the cross-node rail VictusWaiter/discovery already use, for when the destination
     is on another node/network rather than behind this proxy.
   - **`kick`:** last resort for a standalone server with no proxy — kick with the drain message.
     Never hang waiting for a transfer that can't happen.
   Emit `control.drain.player_moved` per player.
4. **Wait for empty (bounded).** Poll until player count hits 0 **or** `drain.timeout-seconds`
   elapses. The timeout **must be shorter than the Wings stop timeout** (see the mapping table) so the
   engine finishes on its own terms before `SIGKILL`.
5. **Flush + save.** If `drain.flush-save`, run `save-off` + `save-all flush` (synchronous), so the
   world on disk is clean before exit. Take a config snapshot if configured (§4).
6. **Exit with a Wings-interpretable code.** Emit `control.drain.done`
   (with duration + players-moved), then exit:
   - **drained stop / restart via the panel:** exit `drain.exit-code` (**0** = clean). Wings sees a
     clean stop and, if the action was **Restart**, starts it again. This is the common path and needs
     no Wings change.
   - **engine-initiated restart** (e.g. a scheduled restart or a lag-doctor remediation that requires
     a bounce): exit `drain.restart-exit-code` (**default 10**). *Native* Wings has no "restart on
     exit code N" mapping, so the primary mechanism stays **panel-initiated** (the panel scheduler
     issues the Restart power action, then the drain runs on the stop leg). The `restart-exit-code` is
     the hook for an **optional Victus-Wings enhancement** that maps a distinguished exit code to a
     restart, so an in-engine trigger doesn't require a round-trip to the panel API.
     `TODO(verify)` whether the Victus Wings build should carry this exit-code→restart map, or whether
     to route engine-initiated restarts through the existing panel Wings state listener
     (the `Server\Crashed`-style queued-listener path already in the panel) instead.

### 3. Backup quiesce hooks (`hosting.control.rollback-hook`, backup side)

The panel's existing **Backup** button archives the volume via Wings. To make that archive
world-consistent, the panel/Wings brackets it with two calls:

- `POST /control/backup/quiesce` (or `victus backup quiesce`) → `save-off` + `save-all flush`,
  bounded by `backup.quiesce-timeout-seconds`; returns 200 once the world is flushed and autosave is
  paused. Emit `control.backup.quiesce`.
- Wings performs its normal archive.
- `POST /control/backup/resume` (or `victus backup resume`) → `save-on`. Emit `control.backup.resume`.

**Safety net:** if `backup.auto-resume` is true, the engine re-enables autosave automatically after
`backup.resume-timeout-seconds` even if the post-hook never arrives (a crashed/aborted backup can
otherwise leave autosave off indefinitely). Both calls are idempotent.

Wiring: since Wings fires no native pre/post-backup hooks, integration is either **panel-orchestrated**
(panel calls quiesce → triggers the Wings backup → calls resume — no Wings change) or via a small
**Victus-Wings pre/post-backup hook** that calls the endpoint itself. `TODO(verify)` which the Victus
Wings build adopts; the panel-orchestrated path ships first because it needs no daemon change.

### 4. Rollback + snapshots (`hosting.control.rollback-hook`, restore side)

Two granularities, both reversible:

- **Wings restore (full backup).** The panel's **Restore** button drains+stops the server (§2), lets
  Wings wipe+extract the archive, then starts it. The rollback-hook's job on the *start* leg is to
  **re-run/verify config migration** against the restored files and re-surface migration status (§5),
  so a restore of a pre-migration backup doesn't silently leave stale config.
- **In-engine snapshots (fast, fine-grained).** `hosting.control.snapshot.*` provides a lightweight
  ring buffer (`snapshot.keep`, default 5) of **config-only** snapshots by default (`victus.yml` +
  the migrated Paper/Spigot/Purpur files), stored under `snapshot.dir`. `include-world: false` by
  default because world snapshots are large; opt-in for small/critical worlds. Snapshots are taken
  automatically before reversible actions when `snapshot.auto-before.*` is set:
  - `config-migration: true` — snapshot before [config auto-migration](../../design/modules/config-migrator.md)
    rewrites `victus.yml`, so a migration is a one-command undo.
  - `doctor-apply: true` — snapshot before a [lag-doctor](../../design/modules/lag-doctor.md)
    `--apply`/one-click fix, complementing its existing `doctor.applied` reversibility.
  `POST /control/rollback` (`victus rollback <snapshot-id|pre-migration> [--scope config|world|both]`)
  restores a snapshot. **World rollback requires the server stopped** (or does `save-off` + an atomic
  world-dir swap staged for next boot); it **never overwrites a live world**, and it always takes a
  *pre-rollback* snapshot first so a rollback is itself reversible. Emits `control.rollback.start` /
  `control.rollback.done`.

### 5. Config-migration status surfacing (`hosting.control.migration-status.expose`)

Parse the migrator's outcome (report path + imported/no-equivalent/differing-default counts + the
`.victus-migrated` marker + a failure flag) and expose it three ways so the panel can render a card
without reading files off the container disk:

- **Metric:** `victus_config_migration_status{state="done|pending|failed|skipped"} 1` (gauge, one
  active label), plus `victus_config_migration_imported_keys`.
- **JSON log:** a `config.migration` event with the structured counts + report path.
- **Endpoint:** `GET /control/migration/status` → the parsed report as JSON (below).

This is **read-only**; it never re-triggers migration (that stays the migrator's first-boot job).

### 6. Reuse of shared infra (no duplication)

- **Timing/health:** `GET /control/status` and any health the endpoint reports read the **shared
  per-subsystem sampler + Prometheus registry** from
  [`08-benchmark-harness.md`](../phase-1/08-benchmark-harness.md) /
  [hosting-limits](../../design/modules/hosting-limits.md). This module adds **no per-tick timing**.
- **Metrics channel:** all `victus_control_*` series register into the *same* exporter on `9940` —
  the control **command** surface (`9941`) is authed and separate, but its **telemetry** rides the
  existing scrape endpoint the panel already reads.
- **Logging:** all events honor `hosting.logging.format: json` and drop into the panel's existing
  scraper, exactly like the throttle/doctor events in [hosting-limits](../../design/modules/hosting-limits.md).

## victus.yml keys

Expands the existing `hosting.control` block in [`VICTUS-CONFIG.md`](../VICTUS-CONFIG.md) (which today
carries only `safe-restart-hook` / `rollback-hook`). **Additive and back-compatible** — the two
existing booleans keep their meaning; everything else is new with safe defaults. Defaults are
identical across every `engine.profile` (lifecycle behavior is host policy, not a perf profile), and
any explicit key beats the profile overlay per `VICTUS-CONFIG.md`.

```yaml
hosting:
  control:
    enabled: true                 # master switch for the whole control surface (endpoint + commands)
    bind: 127.0.0.1               # LOOPBACK ONLY. Non-loopback bind w/o a token = hard boot error.
    port: 9941                    # separate from metrics (9940) — privileged, own auth domain
    # Auth token, resolved in this order (env preferred so it isn't in a world-readable victus.yml):
    token-env: VICTUS_CONTROL_TOKEN   # env var Wings injects at start (recommended)
    token-file: ""                # optional path to a 0600 secret file
    token: ""                     # last-resort inline token (discouraged; warns if set). Blank = 401 all.
    console-commands: true        # also accept every op as `victus …` console/stdin commands

    safe-restart-hook: true       # (existing) expose the graceful drain+restart entrypoint
    rollback-hook: true           # (existing) expose rollback/restore + backup quiesce hooks

    drain:
      deny-logins: true           # reject new logins during a drain
      login-deny-message: "This server is restarting - please reconnect in a moment."
      broadcast-message: "Server restarting - moving you to the hub..."
      transfer-mode: proxy-connect   # proxy-connect | transfer-packet | kick
      target: hub                 # proxy server name (proxy-connect) or host:port (transfer-packet)
      per-player-delay-ms: 50     # stagger transfers so the proxy/hub isn't thundering-herded
      timeout-seconds: 30         # MUST be < the Wings stop timeout; then force flush+exit
      flush-save: true            # save-off + save-all flush before exit (clean world on disk)
      exit-code: 0                # code handed to Wings on a drained stop (0 = clean)
      restart-exit-code: 10       # code for an ENGINE-initiated restart (see Interfaces mapping)

    backup:
      quiesce: true               # on the pre-backup hook: save-off + save-all flush for a consistent archive
      quiesce-timeout-seconds: 15
      auto-resume: true           # re-enable autosave after the post hook — or after the timeout below
      resume-timeout-seconds: 120 # safety: save-on even if the post-backup hook never arrives

    snapshot:
      enabled: true               # lightweight in-engine config/world snapshots for fast rollback
      dir: snapshots              # under the server data dir
      keep: 5                     # ring buffer of recent snapshots
      include-world: false        # config-only by default (fast); world snapshots are opt-in (large)
      auto-before:
        config-migration: true    # snapshot before compatibility.migrate-config-on-boot rewrites config
        doctor-apply: true        # snapshot before a lag-doctor one-click apply

    migration-status:
      expose: true                # surface config-migrator status to the panel (metric + endpoint + log)
```

**Related keys used, not introduced:** `hosting.metrics.prometheus.*` (the `victus_control_*` series
ride this exporter), `hosting.logging.format: json` (the event stream), `hosting.metrics.bench.*` /
the shared subsystem sampler ([`08`](../phase-1/08-benchmark-harness.md)) for any health the status
endpoint reports, `compatibility.migrate-config-on-boot` (the migrator whose status §5 surfaces), and
[`lag-doctor`](../../design/modules/lag-doctor.md)'s apply path (which snapshots before applying).

## Interfaces / API

### Control endpoint (HTTP, `127.0.0.1:9941`, `Authorization: Bearer <token>`)

| Method + path | Purpose | Success | Notes |
| --- | --- | --- | --- |
| `POST /control/safe-restart` | drain → flush → exit for restart | `202 {job}` | body may override `target`/`timeout`/`transfer-mode` |
| `POST /control/drain` | drain → flush → stop (no restart) | `202 {job}` | exits `drain.exit-code` |
| `POST /control/backup/quiesce` | `save-off` + flush for a consistent archive | `200` | idempotent; auto-resumes after timeout |
| `POST /control/backup/resume` | `save-on` | `200` | idempotent |
| `POST /control/rollback` | restore a snapshot | `202 {plan}` | body `{target, scope}`; world scope needs stop |
| `POST /control/snapshot` | create a snapshot now | `201 {id}` | ring-buffered per `snapshot.keep` |
| `GET  /control/snapshots` | list snapshots | `200 [ ]` | id, ts, scope, size |
| `GET  /control/migration/status` | config-migrator outcome | `200 {…}` | read-only |
| `GET  /control/status` | drain/backup/snapshot state + health | `200 {…}` | health from the shared sampler |

Auth/guard responses: `401` (missing/blank/bad token), `429` (rate-limited), boot-time hard error on
a non-loopback `bind` without a token. All bodies are JSON.

```jsonc
// GET /control/status
{ "draining": false, "backup_quiesced": false, "players_online": 42,
  "last_drain": { "at": "2026-07-19T10:00:00Z", "duration_s": 6.1, "players_moved": 40, "mode": "proxy-connect" },
  "migration": { "state": "done", "imported_keys": 37 },
  "snapshots": 3, "health": { "tps": 20.0, "mspt_p99_ms": 11.4 } }   // health read from the shared metrics module

// GET /control/migration/status  (from config-migrator's report)
{ "state": "done", "report_path": "victus-migration-report.txt", "migrated_at": "2026-07-19T09:12:00Z",
  "sources": ["server.properties","spigot.yml","paper-global.yml"],
  "counts": { "imported": 37, "no_victus_equivalent": 4, "default_differs": 6 }, "failed": false }
```

### Console commands (stdin / op-gated)

```
victus safe-restart [--in <duration>] [--target <server>] [--mode proxy-connect|transfer-packet|kick]
victus drain        [--target <server>] [--mode …]
victus backup       quiesce | resume
victus rollback     <snapshot-id | pre-migration> [--scope config|world|both]
victus snapshot     [create | list]
victus migrate      status
```

### Signals

| Signal | Source | Engine behavior |
| --- | --- | --- |
| `SIGTERM` / `SIGINT` | Wings stop-timeout escalation; dev Ctrl-C | time-boxed drain (`drain.timeout-seconds`) → flush → Paper shutdown |
| `SIGKILL` | Wings "Kill" | uncatchable; relies on Paper's last periodic save (documented non-graceful) |

### Wings lifecycle ⇄ panel button mapping

| Panel button | Wings action | Engine participation | Exit code |
| --- | --- | --- | --- |
| **Stop** | send stop cmd (`stop`) → wait → `SIGTERM` → `SIGKILL` | intercept stop → drain → flush → exit | `drain.exit-code` (0) |
| **Restart** | stop-then-start | drain on the stop leg; Wings restarts | 0 |
| **Safe restart** (Victus) | `POST /control/safe-restart` | drain → flush → exit; panel issues start (or exit-code map) | 0 / `restart-exit-code` (10) |
| **Kill** | `SIGKILL` | none (forced) | 137 |
| **Backup** | quiesce → Wings archive → resume | `save-off`/flush around the archive; server stays up | n/a |
| **Restore / Rollback** | drain+stop → Wings restore → start | safe stop + post-restore migration re-check | 0 |

`TODO(verify)` the Victus Wings stop-timeout default so `drain.timeout-seconds` is provably shorter,
and whether the Victus Wings build maps `restart-exit-code` → restart or engine-initiated restarts
route through the panel's existing Wings state listener.

### Metrics (Prometheus, on the existing `9940` exporter)

`victus_control_drain_active` (0/1), `victus_control_drain_players_remaining`,
`victus_control_last_drain_duration_seconds`, `victus_control_backup_quiesced` (0/1),
`victus_control_snapshot_count`, `victus_control_last_snapshot_timestamp_seconds`,
`victus_config_migration_status{state="done|pending|failed|skipped"}`,
`victus_config_migration_imported_keys`.

### JSON log events (`hosting.logging.format: json`)

`control.drain.start` · `control.drain.player_moved` · `control.drain.done` ·
`control.safe_restart.requested` · `control.backup.quiesce` · `control.backup.resume` ·
`control.rollback.start` · `control.rollback.done` · `control.snapshot.created` ·
`config.migration` — each with structured fields (job id, counts, durations, target, mode).

## Safety, overhead & compat risks

- **Default (plugin) path is untouched.** The drain uses public Bukkit APIs (`PlayerLoginEvent`
  deny, proxy plugin-messaging / `player.transfer`, `save-off`/`save-all`) — the same calls any
  plugin makes — so plugins, anti-cheats, and ProtocolLib see nothing unusual, and no new packet type
  exists on the default path. When `hosting.control.enabled: false`, the listener is never created and
  no command/handler is registered: **zero footprint**.
- **Observability never degrades the tick.** The control endpoint is an idle localhost listener on
  its own threads; it does **no per-tick work**. Lifecycle handlers hop onto the main thread only for
  the minimal, staggered Bukkit calls (deny-login registration, per-player transfer, `save-off`), so a
  drain can't produce a tick spike. Health it reports is *read* from the shared sampler — no new
  timing. Overhead is validated by the null test (Tests).
- **Never public, always authed.** Loopback bind is enforced in code (hard boot error otherwise);
  token is env/file-first (not in world-readable `victus.yml`), constant-time compared, fail-closed
  (blank token ⇒ 401 everything), rate-limited. The surface is root-equivalent for the instance and is
  treated like the Wings↔node link.
- **Remediation is coordinated, not silent.** A drain announces itself (broadcast + deny message +
  `control.drain.*` events); rollback always snapshots *before* overwriting and never touches a live
  world; backup-quiesce auto-resumes so a failed backup can't strand autosave off. Nothing
  behavior-changing happens without a log line, matching the [lag-doctor](../../design/modules/lag-doctor.md)
  / [hosting-limits](../../design/modules/hosting-limits.md) posture.
- **No-proxy degradation.** If `transfer-mode` is `proxy-connect`/`transfer-packet` but no proxy /
  target is reachable, the drain falls back to a kick with the drain message rather than hanging — the
  server still flushes and exits within the timeout.
- **Timeout coordination.** `drain.timeout-seconds` **must** be shorter than the Wings stop timeout,
  or Wings `SIGKILL`s mid-drain and the graceful path is wasted. Flagged as a deploy check;
  `TODO(verify)` the exact Wings default.
- **Snapshot disk usage.** Config-only + ring-buffered by default; world snapshots opt-in. The
  endpoint reports snapshot size so the panel can warn before a large `include-world` snapshot.
- **Restore ↔ migration coherence.** Restoring a pre-migration backup could leave stale config; the
  rollback-hook re-verifies migration on the start leg and re-surfaces status so the panel shows the
  true state.

## Tests & verification

- **Drain e2e (with proxy).** Velocity + 150 bots ([`08`](../phase-1/08-benchmark-harness.md) swarm);
  trigger `safe-restart`: assert new logins denied, all players moved to `hub` within
  `timeout-seconds`, `save-all flush` ran, exit code == `drain.exit-code`, and Wings restarted on a
  Restart action. Assert **no MSPT spike** during the staggered transfer.
- **No-proxy fallback.** Standalone server drain kicks with the message, flushes, exits — no hang.
- **Backup consistency.** quiesce → archive → resume: assert zero chunk writes hit disk during the
  archive window (autosave off) and autosave is re-enabled after; drop the resume call and assert
  `resume-timeout-seconds` re-enables it anyway.
- **Rollback.** Snapshot → mutate `victus.yml` + a world region → rollback: assert config/world
  restored and a *pre-rollback* snapshot now exists; assert world rollback refuses while the server is
  running (or stages a clean boot-time swap).
- **Auth / bind guards.** Request without token → 401; blank token ⇒ all 401; wrong token → 401;
  `token-env` overrides `token-file`/inline; non-loopback `bind` without a token ⇒ hard boot error.
- **Signals.** `SIGTERM` mid-play drains within budget then flushes+exits; `SIGKILL` still leaves a
  recoverable world (Paper's last save).
- **Migration status.** First boot ⇒ endpoint/metric/log show `done` + correct counts; a forced
  migration failure ⇒ `state="failed"` surfaces (panel can alert).
- **Overhead null test.** control `enabled` vs `disabled` idle *and* under the
  [`08`](../phase-1/08-benchmark-harness.md) load harness ⇒ MSPT delta within the noise floor.
- **Pilot on DE-1 free nodes** (ROADMAP Phase 2 exit): every panel button — Stop / Restart / Safe
  restart / Backup / Restore — maps to the right engine op end-to-end against real Victus Wings.
- `TODO(verify)` Wings stop-timeout/`SIGTERM`→`SIGKILL` sequence and exit-code→restart mapping on the
  Victus Wings build before wiring the engine-initiated restart path.

## Value delivered

Operational value for the **host** and the **customer** — not a TPS number:

- **Restarts stop dropping players.** A panel restart drains to the hub and (with proxy transfer)
  players hop back after — the difference between "the server went down" and "the server blinked." Big
  retention + fewer angry tickets on scheduled restarts and auto-remediation bounces.
- **Backups are actually consistent.** `save-off`/flush around the Wings archive removes torn-region
  restores — the quiet class of "my backup won't load" tickets — and makes automated backup schedules
  trustworthy.
- **Safe, reversible rollback.** One-click restore that re-verifies migration, plus fast in-engine
  snapshots before every risky action (config migration, lag-doctor apply) ⇒ operators and customers
  change things without fear, the core adoption lever from
  [config-migrator](../../design/modules/config-migrator.md).
- **The panel finally sees the engine.** Migration status, drain/backup state, and lifecycle events
  land in the Prometheus/JSON channel the panel already scrapes, so buttons reflect real engine state
  instead of opaque process control.
- **The moat, made concrete.** These are the entrypoints that only a host-built fork can offer
  ([ARCHITECTURE §5](../ARCHITECTURE.md)); they turn "Victus runs your server" into "Victus's server
  and panel are one product."

## Prior art / references

- **Pterodactyl / Wings** — power actions (`start`/`stop`/`restart`/`kill`), the stdin stop command +
  `SIGTERM`→`SIGKILL` escalation, crash detection, and native backup/restore. The lifecycle this spec
  hooks. (Victus Wings = the `/usr/local/bin/wings` rebrand target.)
- **Velocity** — server-connect via BungeeCord/Velocity plugin messaging and the 1.20.5+ client
  **Transfer** packet: the drain's player-move rail, shared with server discovery / VictusWaiter
  switch-wake. Cross-ref [`04-network-ping.md`](../phase-1/04-network-ping.md).
- **Paper / CraftBukkit shutdown path** — `save-off`/`save-on`/`save-all flush`, the JVM shutdown
  hook, and `MinecraftServer` stop handling the drain flushes through (`TODO(verify)` 26.1 names).
- **spark + the shared metrics/subsystem sampler** ([`08-benchmark-harness.md`](../phase-1/08-benchmark-harness.md),
  [hosting-limits](../../design/modules/hosting-limits.md)) — the health/timing this module *consumes*
  rather than re-implements.
- **Sibling modules** — [`config-migrator.md`](../../design/modules/config-migrator.md) (status
  surfaced here), [`lag-doctor.md`](../../design/modules/lag-doctor.md) (snapshots before apply; shares
  the `victus` command root), [`hosting-limits.md`](../../design/modules/hosting-limits.md) (the
  Prometheus/JSON channel), and [`ROADMAP.md`](../ROADMAP.md) Phase 2 exit criteria (piloted on DE-1,
  panel shows live metrics + one-click fixes).
