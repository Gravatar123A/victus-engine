# Victus Engine — Architecture

## 0. The honest performance model (grounded in 2026 research)

Minecraft's authoritative tick runs (mostly) on a single thread every 50 ms. That serial core is
an **Amdahl's-Law ceiling**: no amount of per-core cleverness gives "100×". What is real:

| Dimension | Realistic vs Paper | Mechanism |
| --- | --- | --- |
| Per-core MSPT, vanilla survival | **3–10×** stacked | redstone + entity + chunk + GC opts |
| Players/box, **spread-out** survival | **5–8×** (Paper ~150 → ~1000) | regionized threading — *only when players disperse* |
| Players/box, **clustered** (spawn/PvP/lobby) | ~1× | one region = one thread; no gain |
| Players/box, minigames/lobby | **thousands** | stripped engine (companion Victus Lobby) |
| Network total | unbounded | Velocity sharding = buying cores |
| Idle RAM | ~2× (JVM) | dedup + ZGC (native's ~10-18× costs plugin compat) |

The takeaway that shapes the whole design: **"more players" is two problems** — spread survival
(regionized threading) and packed lobbies (a separate stripped engine) — plus horizontal sharding
for the network total. One fork can't be all three at max; it *dispatches* to the right mode.

## 1. One fork, modular, profile-driven

A single Paper hard-fork. Every non-baseline capability is a **module that is off by default**, so
a plain SMP instance pays zero cost for threading / hybrid / minigame features it never loads.

```
Victus Engine (Paper 26.1+ fork)
├── Bukkit / Spigot / Paper API ............ full compat — the promise
├── [always-on] optimization stack ......... §3 (compat-preserving, vanilla-accurate)
├── [per-instance] threading tier .......... §4  single → parallel → regionized
├── [per-instance] hybrid mod bridge ....... §6  Fabric/NeoForge, isolated, safe-mode
├── [per-instance] profile ................. smp | technical | minigames | modded | network
└── [always-on] hosting control plane ...... §5  the moat — limits, metrics, lag-doctor
        │
        ├─▶ Velocity proxy (existing #342): sharding + cross-shard transfer = network scale
        └─▶ Victus Lobby (companion, Minestom): thousands/box for hub/minigames/discovery
```

All of it is driven by `victus.yml` — see [`VICTUS-CONFIG.md`](VICTUS-CONFIG.md).

## 2. Base & build system

- **Base:** Paper `26.1`+. Chosen because 26.1 is the **first unobfuscated** Java Edition release
  (post-1.21.11) — Yarn/Intermediary are retired, so maintaining a fork *and* a mod bridge is far
  cheaper. Paper's Moonrise chunk system (async load/gen/I/O + Starlight lighting) is already
  baseline; we tune it, we don't reimplement it.
- **Build:** paperweight-patcher. Paper source is fetched + decompiled + patched at build time and
  never vendored. Our changes live as patches in `patches/paper-server` (GPL-3.0) and
  `patches/paper-api` (MIT). *The exact patcher DSL drifted across Paper's hard fork — the
  `settings.gradle.kts` config block is a `TODO(verify)` against current docs.*

## 3. Always-on optimization stack (compat-preserving)

Every item preserves vanilla behavior or is a toggle back to it. Honest gains, workload-dependent
(near-zero below ~100 players; real at 150+ and on entity/redstone/chunk-heavy loads).

| Area | Technique | Honest gain |
| --- | --- | --- |
| **Ping** | Krypton-style Netty + native epoll + flush consolidation; **libdeflate** (~3× faster, wire-compatible DEFLATE) + viewable-packet grouping | lower felt latency, less tick contention |
| **Redstone** | Alternate-Current dust engine (toggle: vanilla/AC/eigencraft) | up to ~30× (typ. ~10×) lower dust MSPT |
| **Entities** | DAB distance-throttled AI + async pathfinding + async entity tracker + per-player mob caps + activation ranges | ~15–40% entity MSPT on mob-heavy worlds |
| **Chunks** | Tune Moonrise worker/IO pools + pre-gen tooling + zstd/Linear region-file storage | removes gen stalls from tick; smaller/faster disk |
| **Memory** | FerriteCore-style block-state/model + string dedup | ~ up to 2× lower footprint |
| **GC** | Default **Generational ZGC** (Java 21+) | sub-ms pauses vs G1 ~35 ms avg / ~180 ms p99 |

> Migrating the fleet default from Aikar/G1 flags to Generational ZGC is a deliberate change vs
> the current Victus JVM tuning — validate on a pilot node first.

## 4. Threading tiers (per instance)

This is how "hella optimized + all plugins work" coexist. `threading.mode` in `victus.yml`:

1. **`single` (default)** — one tick thread, everything safe offloaded async. **100% plugin compat.**
   Ships first (Phase 1).
2. **`parallel`** — DivineMC/Canvas-style **barrier-synchronized** parallel world/region ticking.
   Uses multiple cores, **keeps plugin compat** (with `compat-mode` sync fallback). Lower ceiling
   than Folia but safe. The sweet spot for typical multi-core boxes.
3. **`regionized`** — true Folia model. Massive-concurrency tier for **spread-out** populations.
   Opt-in; **Folia-aware plugins only** (includes our own hub/lobby/discovery plugins). Gains
   vanish if players cluster.

## 5. Hosting control plane — the moat

The one thing no other fork can copy, because no other fork is built by a host.

- **Per-instance limits** enforced *in-engine*: soft MSPT cap → throttle heavy subsystems; heap /
  CPU-quota awareness so one bad tenant can't take a node down.
- **`/victus doctor`** — one command → ranked lag breakdown ("62% entities: 9.4k mobs in chunk
  47,-12 from PluginX") + auto-remediation suggestion + optional one-click apply.
- **Prometheus** metrics endpoint (TPS, MSPT, mem, chunk/entity counts) + **JSON structured logs**
  the panel already scrapes.
- **Safe-restart / rollback / backup hooks** callable by Victus Wings.
- **Config auto-migration** from `server.properties`/`spigot.yml`/`paper.yml`/`purpur.yml` on first
  boot → switching from Paper feels safe and reversible.
- **EULA guard** — warn on pay-to-win patterns (clearly banned). Priority/queue monetization is a
  contested gray area, *not* a flat ban (Hypixel runs paid queues) — see docs/LICENSING.md.

## 6. Hybrid mod bridge (day-one seam, isolated)

Architected from the first build to host a Fabric/NeoForge loader (Arclight/Mohist approach, much
easier post-unobfuscation). But it is an **isolated module, off by default**:
`hybrid.enabled=false`. When on: tested-compat lists, `safe-mode` auto-disabling known-bad
mod↔plugin interactions, per-instance toggle. Pure-plugin instances never load a byte of it.
**Highest ongoing-maintenance risk in the project** — the module boundary exists so it can never
sink the fast core.

## 7. Companion: Victus Lobby (later phase)

Minestom-based engine (no Bukkit API, no vanilla sim) for hubs / minigames / the server-discovery
browser — genuinely thousands of players per box. Speaks the same proxy + discovery protocol the
Victus network already uses, so it slots in as another backend type.

## Module seams (design notes)

- [`../design/modules/threading-tiers.md`](../design/modules/threading-tiers.md)
- [`../design/modules/hosting-limits.md`](../design/modules/hosting-limits.md)
- [`../design/modules/lag-doctor.md`](../design/modules/lag-doctor.md)
- [`../design/modules/config-migrator.md`](../design/modules/config-migrator.md)
- [`../design/modules/hybrid-bridge.md`](../design/modules/hybrid-bridge.md)
