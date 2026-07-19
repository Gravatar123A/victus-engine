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
    native-transport: true      # Linux epoll
    compression: zstd           # zlib | libdeflate | zstd
    flush-consolidation: true
  chunks:
    worker-threads: auto
    io-threads: auto
    max-generate-rate: 8        # chunks/sec/player, throttles gen spikes (0 = unlimited)
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
  enabled: false                # Fabric/NeoForge mod loader bridge (isolated module)
  loader: neoforge              # fabric | neoforge
  safe-mode: true               # auto-disable known-bad mod↔plugin interactions
  tested-only: true             # refuse to load unverified plugin+mod combinations

compatibility:
  migrate-config-on-boot: true  # import paper.yml / spigot.yml / purpur.yml settings once
  purpur-nbt-tolerance: true    # don't spam "unknown NBT tag" when migrating from Purpur

eula:
  no-pay-to-win-guard: true     # warn on pay-gated queue / P2W patterns (2023 EULA bans both)
```

## Notes

- **`gc.profile` is informational.** The JVM's collector is set by launch flags, not by the
  server at runtime; the engine reads the active GC and warns on mismatch. See
  `scripts/dev-env.sh` for the Generational-ZGC flag set.
- **Profiles are overlays, not locks.** `engine.profile: technical` sets `redstone: vanilla`,
  disables approximate AI throttling, etc., but any explicit key you set wins.
- **`threading.mode: regionized`** rejects plugins lacking a Folia-aware marker unless
  `parallel.compat-mode`-style shims are in place; the engine logs exactly which plugins were
  refused and why.
