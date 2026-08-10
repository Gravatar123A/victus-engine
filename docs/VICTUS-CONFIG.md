# `victus.yml` — configuration schema

This is the concrete contract for every Victus Engine feature seam. It sits alongside
`server.properties`, `bukkit.yml`, `spigot.yml`, `paper.yml`. Sensible defaults ship per profile
(see `engine.profile`); everything below is the fully-expanded form.

```yaml
# ============================================================================================
# Victus Engine — per-server configuration
# ============================================================================================
engine:
  # Applies a bundle of tuned defaults for all sections below.
  #   smp | technical | minigames | modded | network
  profile: smp

threading:
  # single     — one tick thread + async offload. 100% plugin compat. (default)
  # parallel   — barrier-synchronized parallel world/region ticking. Keeps plugin compat.
  # regionized — Folia-style. Massive concurrency for SPREAD-OUT players. Folia-aware plugins only.
  mode: single
  parallel:
    world-ticking: true         # tick Overworld/Nether/End on separate threads
    region-ticking: true        # barrier-sync parallel chunk-region groups
    compat-mode: false          # force synchronous execution for full plugin safety
  regionized:
    threads: auto               # auto ≈ 80% of logical cores (reserve headroom)
    grid-exponent: 4            # region size = 2^n chunks per side (Folia default 4 = 16×16)

optimizations:
  redstone: alternate-current   # vanilla | alternate-current | eigencraft
  entities:
    dab: true                   # dynamic activation of (mob) brains — distance-throttled AI
    async-pathfinding: true
    async-entity-tracker: true  # ~15% on entity-heavy servers
    per-player-mob-spawns: true # required for the tracker gain; fairer mob caps
    activation-range-tuning: true
  network:
    native-transport: true      # Linux epoll / io_uring
    compression: libdeflate     # zlib | libdeflate  — WIRE-COMPATIBLE ONLY.
                                # NOT zstd: MC packet compression is fixed to zlib/DEFLATE; zstd
                                # would break stock clients. zstd is for region/disk storage only.
    compression-threshold: 256  # bytes
    flush-consolidation: true
    low-alloc-handlers: true    # Krypton-class low-alloc encode/decode + viewable-packet grouping
    tcp-nodelay: true
  chunks:
    worker-threads: auto
    io-threads: auto
    max-generate-rate: 8        # chunks/sec/player, throttles gen spikes (0 = unlimited)
    async-send: false           # experimental Java-client chunk serialization offload
                                # Geyser/Floodgate Bedrock players always use synchronous send:
                                # deferred FIFO packets can stall server-authoritative movement
  memory:
    dedup: true                 # block-state/model + string deduplication
  gc:
    profile: zgc-generational   # INFORMATIONAL — actual flags live in the start script
                                # (engine warns if the running GC != this profile)

hosting:
  limits:
    max-mspt: 45                # soft cap → throttle heavy subsystems before TPS collapses
    max-heap-mb: 0              # 0 = inherit -Xmx
    cpu-quota-pct: 0            # 0 = node/cgroup enforced
    entity-hard-cap: 0          # 0 = off; else refuse spawns past N per world
  metrics:
    prometheus:
      enabled: true
      bind: 127.0.0.1
      port: 9940
  logging:
    format: json                # text | json (panel scrapes json)
  lag-doctor:
    enabled: true
    auto-suggest: true          # surface fixes in /victus doctor
    auto-apply: false           # one-click apply stays MANUAL by default
  control:
    safe-restart-hook: true     # exposes a graceful-restart entrypoint for Victus Wings
    rollback-hook: true

hybrid:
  enabled: false                # late discovery diagnostics only; never starts a loader
  loader: neoforge              # diagnostic preference only in victus.yml
  safe-mode: true               # reserved compatibility-policy input
  tested-only: true             # planned; public bridges remain unsupported

compatibility:
  migrate-config-on-boot: true  # import paper.yml / spigot.yml / purpur.yml settings once
  purpur-nbt-tolerance: true    # don't spam "unknown NBT tag" when migrating from Purpur

eula:
  no-pay-to-win-guard: true     # warn on clear pay-to-win patterns (banned). NOT priority queues —
                                # those are a contested gray area, not a flat ban (see LICENSING.md)
  warn-standalone-queue-skip: false  # optionally flag standalone "skip the queue" SKUs (risky)
```

## Notes

- **`gc.profile` is informational.** The JVM's collector is set by launch flags, not by the
  server at runtime; the engine reads the active GC and warns on mismatch. See
  `scripts/dev-env.sh` for the Generational-ZGC flag set.
- **Hybrid runtime selection is pre-main.** The `hybrid.*` block above cannot safely initialize a loader
  after Paper has started. Use `VictusHybridLauncher` and `hybrid/config/hybrid-launcher.properties.example`;
  enabled profiles are fail-closed foundation work, not supported bridges. See `docs/phase-4/04-hybrid-mod-bridge-plan.md`.
- **Profiles are overlays, not locks.** `engine.profile: technical` sets `redstone: vanilla`,
  disables approximate AI throttling, etc., but any explicit key you set wins.
- **`threading.mode: regionized`** rejects plugins lacking a Folia-aware marker unless
  `parallel.compat-mode`-style shims are in place; the engine logs exactly which plugins were
  refused and why.
