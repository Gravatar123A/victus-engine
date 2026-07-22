# Engine-Core Adversarial Review — Findings & Fix List (2026-07-22, wf_7bf02510)

17 agents reviewed DAB, init/reload lifecycle, GC/flags, and config across the real source/patch
files. **10 confirmed real defects** (6 medium, 4 low; 3 false alarms correctly rejected). None are
crashes/data-loss — all are correctness / behavior-vs-intent / config-trap issues. Fix them in the
next build cycle (the `victus-core` ones can ship independently of the blocked decompile).

## Fixable NOW in `victus-core` (light build, no Paper decompile — not blocked)

**#4 (med) — Two sources of GC truth.** `RecommendedFlags.GC_FLAGS`/`gcFlags()`/`launchCommand()` still
emit unconditional `-XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch` for every heap/node, contradicting
the measured `recommendedGcFlags(heapMb, dedicated)` policy (G1 default; ZGC only ≥16 GB dedicated;
AlwaysPreTouch only dedicated; + ADDITIVE_FLAGS). *Fix:* route `launchCommand`/`gcFlags` through
`recommendedGcFlags(heapMb, dedicated)` (add a `dedicated` param); update `RuntimeSelfTest` so it no
longer pins the ZGC-always set as canonical.

**#7 (med) — `dab-activation-dist-mod` unbounded.** `ConfigResolver.java:52` floors at 1 but has no
upper clamp; used as a right-shift exponent on squared distance, so ≥~19–21 makes it evaluate to 0 →
DAB becomes a silent no-op while logging "DAB enabled". *Fix:* `Math.min(16, Math.max(1, …))` + warn if
it would zero the shift; add a ConfigSelfTest upper-clamp case.

**#8 (low) — Nested `dab:` map keys ignored.** Resolver reads only hyphenated sibling keys and only
`enabled` from a map; `throttle-navigation-travel`/`exempt-named` are unimplemented. *Fix:* read the
nested map (warn on unknown sub-keys) OR remove the nested example from docs + `boolVal` comment.

**#5 (low) — `recommendedInitialHeapMb` can exceed Xmx.** `Math.min(512, Math.max(256, xmxMb/8))` isn't
clamped to `xmxMb`, so xmx<256 → Xms>Xmx → `elasticHeapFlags` throws. Latent (unreachable at real heap
sizes). *Fix:* `Math.min(xmxMb, …)`.

## Fixable in the full build (paper/minecraft layer — needs the decompile/page-file unblock)

**#1 (med) — reload re-arms the async pool.** `init()` unconditionally calls `configureAsync(...)` and
`reload()` re-runs `init()` on the main thread → up to 2 s `awaitTermination` tick stall + dropped
`shutdownNow()` tasks strand chunk shells. (Same root as async-send review #1/#3.) *Fix:* first-call
guard / skip-when-unchanged; drain off-thread; guarantee `setReady` on drop. Ties into watchdog Edits 5/6.

**#2 (med) — one bad key nukes the whole config.** `init()`'s `catch(Throwable){ config = new
ResolvedConfig(); }` + a non-total resolver (`intVal`/enum parsers throw) means a single malformed key
silently discards the ENTIRE file and runs hard defaults with one warning. *Fix:* resolve into a local,
publish to the static only on success; on reload keep last-good; treat validation errors as per-key
warnings (keep other valid settings).

**#3 (med) — victus.yml path divergence.** Engine uses CWD-relative `Path.of("victus.yml")`; plugin uses
`worldContainer/victus.yml`. They diverge on non-default `--universe`, and the two DEFAULT_YML templates
differ. *Fix:* one canonical absolute path shared by both; one template owner.

**#6 (med) — default template force-enables async-pathfinding.** Both DEFAULT_YML templates emit
uncommented `async-pathfinding: true`, contradicting the resolver/ResolvedConfig/self-test OFF-by-default
contract. Latent (no consumer yet) but every fresh install would silently enable the un-soaked feature
once the async-path patch lands. *Fix:* comment it out in both templates; add a self-test that resolves
the actual DEFAULT_YML and asserts `asyncPathfinding == false`.

**#9 (low) — `dab-start-distance` inert in normal range.** `computeInterval`'s `(best >> distMod) <= 1`
guard subsumes the start-distance check below ~sqrt(2^(distMod+1)) (~22.6 blocks at default distMod=8),
so the per-profile start-distance values produce no behavior change. *Fix:* derive the interval from
distance BEYOND the start radius: `interval = clamp(1, maxInterval, (best - startDistanceSq) >> distMod)`.

**#10 (low) — stale DAB interval on sudden approach.** Interval recomputed only every 16 ticks and
`mustFullTick` has no proximity check → up to ~15-tick delayed sensing/aggro when a mob's nearest-player
distance collapses (ender pearl/teleport) without a combat trigger. *Fix:* proximity short-circuit in
`mustFullTick`, or reset interval on teleport/dimension-change/mount.

## Rejected (false alarms — verified NOT bugs)
Sound/particle packet reordering (Paper's `canSendImmediate` excludes them), missing server-stop hook
(fallback path doesn't strand), and worker main-thread-fallback reentrancy (`srv.execute` is not
reentrant here).
