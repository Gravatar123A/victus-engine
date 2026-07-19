# Module: threading tiers

Config: `threading.mode = single | parallel | regionized` (see VICTUS-CONFIG.md).

## single (default, Phase 1)
Unmodified Paper tick model + async offload of pathfinding, entity tracking, mob-spawn math
(Leaf/Pufferfish approach). Plugin-visible contract identical to Paper. **100% compat.**

## parallel (Phase 3a)
Barrier-synchronized parallelism (DivineMC/Canvas RCT model):
- **Parallel world ticking**: Overworld/Nether/End each on a thread, per-tick barrier.
- **Region-group ticking**: each tick, recompute active chunks into non-adjacent groups (≥1 chunk
  gap), tick groups on a pool, join before advancing.
- `compat-mode: true` forces synchronous execution for full plugin safety.
- Keeps plugin compat because cross-group interaction within a tick is spatially impossible.
- Trade-off vs Folia: no per-region fault isolation (a slow group delays the whole tick).

## regionized (Phase 3b)
Folia model: no main thread; dynamic merge/split regions each with their own 20 TPS loop.
- Plugins must be **Folia-aware** (RegionScheduler/EntityScheduler/AsyncScheduler, teleportAsync,
  no global mutable state). Engine refuses non-aware plugins and logs which + why.
- `threads: auto` ≈ 80% of logical cores; recommend ≥16 physical cores + pre-generated world.
- **Only helps spread-out players** — clustered crowds collapse to one region/thread.
- Our own hub/lobby/discovery plugins ship Folia-aware to be first-class here.

## Implementation order
`single` async offload → `parallel` world ticking → `parallel` region groups → `regionized`.
Each gated behind config; each must not alter behavior in the tiers below it.
