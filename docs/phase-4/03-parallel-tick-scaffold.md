# Phase 4 — `PARALLEL` tick engine: scaffold shipped (EXPERIMENTAL, off by default)

**Status:** structural scaffold built + wired, **default OFF**. This is the first increment of ROADMAP
Milestone 3 Step A. It is **NOT production-safe** and must not be enabled on a real server yet — see
§Known-unsafe below. Shipped off-by-default in the same spirit as async-pathfinding / async-chunk-send
(land the scaffold, harden + soak before flipping it on).

## What was built
- **`cloud.victus.core.threading.ParallelTickExecutor`** (victus-core, dependency-free, unit-tested —
  `ParallelTickSelfTest` 12/0): a barrier-synchronized "run these units, then join" primitive.
  - Barrier: `runAll(units)` returns only after every unit finished (via `ThreadPoolExecutor.invokeAll`).
  - First-exception propagation to the caller thread (the server's crash path still fires on a tick error).
  - Serial fallback when disabled / no pool / a single unit — behaviourally identical to the classic loop.
- **`VictusEngine.parallelWorldTicking()` / `tickWorldsParallel(levels, tickFn)`** + a lazily-built executor
  created **only** when `threading.mode=parallel`; `SINGLE`/`PARALLEL(off)` → always serial.
- **`MinecraftServer.tickChildren` world loop** wrapped: `if (parallelWorldTicking()) { serial pre-pass for
  plugin-visible/static per-world state, then tick worlds in parallel } else { <original serial loop, byte-
  identical> }`. The default path is unchanged, so `SINGLE` servers see zero behaviour change.

## Why it's OFF (the honest, unsolved parts)
Parallel world ticking is only correct if worlds are truly independent for the whole tick. They are not,
and these are the blockers to making `parallel` production-safe:
1. **Plugin events dispatch on worker threads.** `ServerLevel.tick` fires Bukkit events; under parallel
   ticking those handlers run off the main thread, concurrently — violating the single-main-thread
   contract nearly every plugin assumes. **This alone breaks the 100%-compat promise** and is why the
   tier must stay opt-in + why `compat-mode` (force event dispatch back onto one owner thread at
   safepoints) is required before it can be a real default.
2. **Cross-world entity movement mid-tick.** Portals / end-travel / `/tp` across dimensions move entities
   between worlds *during* the tick — two world-tick threads can touch the same entity/chunk. Needs a
   "hop to the target world's owner" rule (Folia's model), not yet implemented.
3. **Static per-world state.** e.g. `HopperBlockEntity.skipHopperEvents` is a single JVM-wide static set
   per world in the serial loop; under parallel ticking it can't be per-world. The scaffold sets it once
   conservatively (fire hopper events unless nobody listens; per-world `hopper.disableMoveEvent` ignored).
4. **Shared server systems** — scoreboard, bossbars, player list, weather/time sync, command dispatch —
   touched from multiple world ticks without synchronization.

## Empirical validation (2026-07-23) — proves both the safety of the default and the danger of the tier
- **`SINGLE` (default): zero regression.** The parallel-tick jar deployed to e5aa1c05 boots clean,
  `threading=SINGLE`, 0 errors, no experimental warning — the serial path is byte-identical.
- **`PARALLEL` enabled: boots, then dies within ~10 s of ticking.** A throwaway `threading.mode=parallel`
  boot reached `Done (16.9s)` but crashed on the first parallel ticks at **Moonrise's tick-thread guard**:
  `TickThread.ensureTickThread` → *"Cannot tick player chunk loader async"* (a `Victus Parallel Tick Worker`
  thread running `ServerChunkCache.tick → RegionizedPlayerChunkLoader.tick`).
- **What this means (important for the design):** Moonrise **already enforces tick-thread affinity** and
  **fails fast** (a thrown assertion, not silent world corruption — good, safe design). So the naive
  "tick worlds on a generic pool" approach is rejected by the chunk system itself. Real `PARALLEL`/
  `REGIONIZED` ticking must **register each region's worker as a recognized tick thread within Moonrise's
  model** (per-region ownership) — which is precisely Folia's architecture. This **reinforces doc-01's
  approaches** (merge Folia / reimplement on Moonrise / Folia-backed lobby type) over any ad-hoc pool, and
  confirms the scaffold must stay OFF until that Moonrise/region integration is built.

## Hardening roadmap (to make `parallel` real)
1. **`compat-mode` event marshalling:** route all Bukkit event dispatch + API mutations onto a single owner
   thread (main) at safepoints; only vetted internal phases run on workers. Advertise "compat-safe parallel."
2. **Cross-world hand-off:** entity/chunk operations targeting another world hop to that world's tick owner.
3. **Serialize the shared-static / global systems** (or make them thread-safe / per-world).
4. **Fault-injection + full plugin-regression soak** on a multi-world box before the default is ever
   considered; measure with the spread-player bench (doc 01 §5).

## Measurement
`PARALLEL` targets **multi-world** parallelism first (survival + nether + end + minigame worlds ticking
concurrently); a single spread world needs the later coarse-region step (doc 01 §Step A). Bench: multi-world
box MSPT with `parallel` vs `single` on the same box, once §Hardening 1–3 land. Until then the tier exists
only as a gated scaffold and reports nothing.

## Config
`threading.mode: single` (default) | `parallel` (experimental — logs a loud warning when enabled) |
`regionized` (future). Enabling `parallel` today is for controlled testing only.
