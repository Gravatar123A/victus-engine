# Phase 4 — Multi-core tick threading (`parallel` + `regionized`) — plan

**Status:** design/planning only — NO code yet. This fleshes out ROADMAP Milestone 3 and the three
`ThreadingMode` tiers (`SINGLE` / `PARALLEL` / `REGIONIZED`) whose enum already exists in victus-core
but is otherwise unimplemented. It supersedes the never-written `docs/design/modules/threading-tiers.md`
referenced by `ThreadingMode`.

## 0. The honest framing (read this first)
Minecraft's server tick is largely a **single big serial loop** (entities → block entities → redstone →
chunk/world → network flush) with pervasive shared mutable state. There is **no 100×**. Real wins come
from (a) moving work *off* the tick thread (already shipping: async chunk send, DAB, async pathfinding
scaffold, chunk-thread uncap) and (b) ticking **independent regions in parallel**. Parallelism is bounded by:
- **Amdahl** — anything that must be global (a per-tick barrier, world save, weather, scoreboard, player
  list) caps the speed-up.
- **Workload shape** — regionized ticking helps **spread-out** players (survival) a lot; it does **~nothing**
  for players concentrated in one area (spawn hubs, minigame arenas) because they share one region.
- **Coordination cost** — entities/portals/pistons/redstone/hoppers crossing region boundaries need
  synchronization that eats into the win.

Project-honest estimate (carried from memory): **~3–10×/core on a spread survival box, ~5–8×/box**, and
**not** a universal multiplier. This plan optimizes for that reality and refuses to over-promise.

## 1. The core tension — and why it's already resolved
The #1 product promise is **100% Spigot/Bukkit/Paper plugin compatibility**. Folia-style regionization
**breaks** that: plugins must use region schedulers and drop assumptions of a single main thread + global
state. These are in direct conflict.

**Resolution (already baked into the design):** threading is a **per-instance opt-in tier**, not a global
switch. Compatibility is a property of the *tier the operator chooses*:

| Tier | Concurrency | Plugin compat | Default? | Who it's for |
|---|---|---|---|---|
| `SINGLE` | 1 tick thread + async offload | **100%** | **yes** | everyone; the safe baseline |
| `PARALLEL` | worlds/regions ticked on a pool, **barrier-synchronized per tick** | compat-safe (+ `compat-mode`) | no | multi-world / moderate spread, wants cores without risk |
| `REGIONIZED` | independent per-region tick threads, **no global barrier** (Folia model) | **Folia-aware plugins only** (non-aware refused at load — already the `ConfigResolver` warning) | no | large spread-survival; accepts the plugin trade |

So the default server is unchanged and fully compatible; regionization is something a hosting customer
(or our own lobby/survival network) turns on **knowingly**. Our own hub/lobby/discovery plugins get shipped
Folia-aware so the managed network can run `regionized` end-to-end.

## 2. Two steps, in order (parallel BEFORE regionized)
`PARALLEL` is the pragmatic first milestone; `REGIONIZED` is the later extreme. Ship and soak them separately.

### Step A — `PARALLEL` (barrier-synchronized parallel ticking) — *do this first*
Tick **independent units** (start with whole **worlds**; later, coarse regions) concurrently on a bounded
pool, then **join on a barrier** each tick before the global/finish phase (network flush, save decisions,
scoreboard, player list). Plugins still observe a consistent, effectively-single-threaded view at the
tick boundary, and events that must be main-thread are dispatched from a single owner — so **compat holds**.
- **Win:** multi-world servers (survival + nether + end + minigame worlds) tick in parallel; a spread
  single world gets nothing yet (one world = one unit until coarse regions land).
- **Risk:** medium. Data races on shared globals; needs a `compat-mode` that forces main-thread for
  plugin event dispatch + a curated allowlist of parallel-safe internal phases.
- **Bounded by the barrier** (Amdahl) — the slowest unit + the serial finish set the tick time.

### Step B — `REGIONIZED` (Folia model) — *opt-in, later*
Split each world into independently-ticking **regions** (connected chunk groups), each owning its own
tick thread; regions **morph** (split/merge) as players move. No global per-tick barrier → true concurrency.
Cross-region actions (entity crossing, portal, piston/redstone at a border) hop onto the target region's
scheduler. This is the Folia architecture.

**Three ways to get there (honest trade-offs):**
1. **Merge Folia's upstream patch set into the fork.** Fastest to working regionization *if* a Folia
   patch set for the exact Paper/MC base exists. Cost: Folia is a large, fast-moving separate Paper fork;
   merging it into a Paper-26.2 hard-fork is a heavy, **ongoing** rebase burden, and it must be reconciled
   with our own patches (async chunk send, DAB, tuning). Highest capability, highest maintenance.
2. **Reimplement regionization on Paper 26.2 + Moonrise.** Moonrise (already in-tree — it's what our
   chunk-thread work touches) provides a threaded chunk system and region-ish primitives, so this is
   *less* from-scratch than it sounds, but still a very large, correctness-critical effort with a long soak.
   Best long-term fit + lowest merge burden; highest up-front cost.
3. **Run Folia as a separate server *type*, not in the main jar.** The managed **network/lobby** profile
   boots a Folia build; **managed survival/creative stay Paper** (`SINGLE`/`PARALLEL`). Sidesteps merging
   Folia into our jar entirely; the "combine all niches" promise is met at the *fleet* level (Velocity
   routes players to the right backend type) rather than per-jar.

**Recommendation:** ship **Step A (`PARALLEL`)** on the Paper+Moonrise base first (real win for the common
multi-world case, compat-safe). For `REGIONIZED`, start with **approach (3)** — a Folia-backed lobby/network
type behind Velocity — because it delivers the spread-scaling win to the exact workload that benefits
(stateless lobbies, spread survival) **without** betting the whole engine on a permanent Folia merge, and
our own plugins are already the ones that must be Folia-aware there. Revisit **approach (2)** (native
regionization in the main jar) only once `PARALLEL` is proven and there's a measured demand the lobby-type
split can't serve.

## 3. Plugin-compatibility strategy (the promise, kept)
- `SINGLE` (default): untouched, 100% compat. No user is exposed to any of this unless they opt in.
- `PARALLEL`: a `threading.compat-mode` (default on) forces plugin event dispatch + Bukkit API mutations
  onto a single owner thread at safepoints; only vetted internal phases run parallel. Advertised as
  "compat-safe parallel."
- `REGIONIZED`: **refuse to load** non-Folia-aware plugins with a clear message (the `ConfigResolver`
  warning already anticipates this), and provide a `folia-aware-allowlist`. Ship Victus hub/lobby/
  discovery/waiter plugins Folia-aware so the managed network runs regionized cleanly.
- A **`/victus threading`** command + a boot line that states the active tier and, for `REGIONIZED`, lists
  which installed plugins were refused and why.

## 4. Correctness hazards to design against (regionized)
Entities crossing regions; portals/dimension changes; pistons/observers/redstone/hoppers straddling a
region border; explosions; pathfinding targets in another region; world save vs a live region; global
systems (weather, time, scoreboard, boss bars, player list, `/tp` across regions). Each needs an explicit
"hop to owning region's scheduler" rule. **Every one is a potential dupe/desync/crash** — this is why
regionized needs the longest soak of anything in the project and why approach (3) (isolate it to a
server type) de-risks the blast radius.

## 5. Measurement strategy (measure, don't claim)
Success metric (from ROADMAP): **a spread-survival box holds ≥3× the Paper player count at ≥19 TPS.**
- Extend `bench/` with a **spread-player load harness** (S-tier): N mineflayer bots spawned at *spread*
  coordinates (e.g. a grid ≥512 blocks apart so they occupy distinct regions), each doing light survival
  activity; ramp N until TPS drops below 19; compare Victus `parallel`/`regionized` vs well-tuned stock
  Paper on the **same box + world + seed**.
- Also run a **concentrated** control (all bots at spawn) to *prove the honest limitation* — regionized
  should show little/no gain there, and saying so out loud is the point.
- Report TPS-vs-players curves + MSPT percentiles + per-region tick times; PSS/NMT for RAM as today.
- New metrics (extending this session's Prometheus work): `victus_tick_regions`, `victus_region_tick_ms`
  (summary), `victus_threading_mode`.

## 6. Phased roadmap + gates
1. **P4.1 — `PARALLEL`, multi-world only.** Tick worlds on a pool + per-tick barrier + `compat-mode`.
   Gate: multi-world box shows a real MSPT drop; full plugin regression suite green; a week's soak.
2. **P4.2 — `PARALLEL`, coarse intra-world regions.** Barrier-synced coarse regions in one world.
   Gate: spread-survival bench shows gain; no dupes across region seams in a fault-injection soak.
3. **P4.3 — `REGIONIZED` via a Folia-backed lobby/network type (approach 3)** behind Velocity, with our
   plugins Folia-aware. Gate: lobby box holds the target concurrency; managed Paper servers untouched.
4. **P4.4 (stretch) — native regionization in the main jar (approach 2)** only if P4.3 proves insufficient.

## 7. Open questions to resolve before P4.1 code
- Does a Folia build track Paper/MC 26.2 closely enough for approach (1)/(3) today, or is there a lag?
- What is Paper 26.2 + Moonrise's *current* region/thread primitive surface (how much of P4.2 is already
  scaffolded by Moonrise)? — a focused code+upstream audit is the first task of P4.1.
- `compat-mode` allowlist: which internal tick phases are provably free of plugin-visible shared state?
