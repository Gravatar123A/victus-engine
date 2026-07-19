# Phase 1 · Redstone dust engine

## Goal

Make redstone-dust propagation cheap on wire-heavy servers **without** changing game outcomes in
the default path, and without forcing a global trade-off onto players who run update-order-sensitive
technical contraptions.

Concretely, Victus's delta over Paper 26.1 is *policy and ergonomics*, not a new algorithm:

1. Expose Paper's existing redstone-implementation selector through **one** first-class
   `victus.yml` key — `optimizations.redstone` — instead of a per-world Paper-config enum most
   operators never find.
2. Ship **profile-aware defaults**: `alternate-current` for `smp` / `minigames` / `modded` /
   `network`, but `vanilla` for the `technical` profile so locational/directional contraption
   parity is preserved for the people who actually depend on it.
3. Keep the whole thing a pure toggle: the compat-preserving value is always reachable, and the
   engine tells the operator exactly what the setting costs them (via `/victus doctor`).

## Upstream baseline (what Paper 26.1 already provides)

Paper already does the hard part. **We do not reimplement redstone.**

- Paper bundles a `redstone-implementation` option with three values: `VANILLA`,
  `ALTERNATE_CURRENT`, and `EIGENCRAFT`. It is a **per-world** setting, living in the world config
  (`paper-world-defaults.yml` → `misc.redstone-implementation`, overridable per world under
  `config/<world>/paper-world.yml`). `TODO(verify against Paper 26.1 source once building online)` —
  the exact YAML path/enum spelling has drifted across Paper versions and again across the
  unobfuscation cutover.
- **Paper's default is `VANILLA`** — i.e. out of the box Paper ships the slow, recursive
  `RedstoneWireBlock` update algorithm for full parity. The fast paths are opt-in.
- `ALTERNATE_CURRENT` is SpaceWalkerRS's Alternate Current algorithm, already integrated upstream.
- `EIGENCRAFT` is theosib's redstone rewrite (a.k.a. `RedstoneWireTurbo`), also already integrated
  upstream.
- Paper fires the standard `BlockRedstoneEvent` / block-physics updates from whichever
  implementation is active, so plugin surface is upstream-defined for all three.

So there is **no dust-algorithm patch to write** in the common case. The Victus delta is the
victus.yml binding + profile overlay + honest surfacing, plus a re-verification/forward-port task
against the 26.1 source in case the unobfuscation cutover disturbed the bundled AC/Eigencraft code.

## Design / patch plan

Two small pieces of new work, one contingent.

### 1. victus.yml → Paper world-config binding (always-on)

`optimizations.redstone` is authoritative. At world load, the Victus config layer maps the
victus.yml value onto each world's Paper `redstone-implementation` before the world's redstone
handler is constructed:

| `optimizations.redstone` | Paper `redstone-implementation` |
| --- | --- |
| `vanilla` | `VANILLA` |
| `alternate-current` | `ALTERNATE_CURRENT` |
| `eigencraft` | `EIGENCRAFT` |

- **Precedence:** an *explicit* per-world Paper `redstone-implementation` set by the operator wins
  (so power users keep granular control); otherwise victus.yml wins; otherwise the profile default;
  otherwise `vanilla`. The one-time import of any pre-existing Paper value is handled by the
  existing `compatibility.migrate-config-on-boot` path, after which victus.yml is the source of
  truth. The engine logs the resolved value per world at boot.
- **Where it lives:** the resolution logic sits in the shared `victus.yml` loader / profile-overlay
  core introduced in Phase 0 (not a redstone-specific patch). This spec only registers the
  `optimizations.redstone` key, its enum coercion, and the profile-default entries.
  `TODO(verify)` the concrete Paper class that reads `redstone-implementation` and constructs the
  wire handler (historically a factory selected in the world/level config bootstrap) so the mapping
  is applied at the correct lifecycle point.

### 2. Profile overlay entries

Add redstone defaults to the profile presets (overlays, not locks — any explicit key the operator
sets still wins, per `VICTUS-CONFIG.md`):

| Profile | `optimizations.redstone` default | Rationale |
| --- | --- | --- |
| `smp` | `alternate-current` | wire-heavy farms, no dependence on exact update order |
| `minigames` | `alternate-current` | throughput over contraption parity |
| `modded` | `alternate-current` | mod redstone is rarely order-locked |
| `network` | `alternate-current` | hub/utility redstone is trivial; favor MSPT |
| `technical` | `vanilla` | locational/directional/0-tick contraption parity is the point |

### 3. AC/Eigencraft forward-port re-verification (contingent patch, `optimizations/redstone/`)

If — and only if — the bundled Alternate Current / Eigencraft code fails to apply cleanly or
regresses against the 26.1 unobfuscated source, land a fix under
`patches/paper-server/optimizations/redstone/`. This is the sole place we would touch the dust
algorithm itself, and the goal is a *no-op forward-port*, not a behavior change. Expected to be
empty; tracked as a risk, not planned work.

### The Alternate Current algorithm (high level, for reviewer context)

Vanilla `RedstoneWireBlock` propagates power **recursively**: changing one wire re-evaluates and
re-updates its neighbors, which recurse into theirs, so a large connected wire mass can be visited
and re-notified many times — the classic super-linear "dust is laggy" blowup.

Alternate Current is **non-recursive and network-based**:

1. **Build the network.** From the changed wire, iteratively flood the *connected wire network*
   (all directly/diagonally-connected redstone dust reachable through valid connections) into a
   flat list — no recursion, no repeated node expansion.
2. **Find external power.** Collect power entering the network from **non-wire** sources (strong/weak
   block power, powered components, directional wire-into-block reads). Each wire reads its
   non-wire surroundings **at most once**.
3. **Spread power once.** Propagate levels across the network in a single ordered relaxation pass so
   each wire **writes its own power level exactly once**, then emits the resulting block updates.

Net effect: work becomes ~linear in network size with a single read/write per node instead of the
recursive re-visitation vanilla performs. Eigencraft (`RedstoneWireTurbo`) attacks the same problem
more conservatively — it reproduces vanilla's update *order* while eliminating redundant work — so
it is slower than AC but closer to vanilla-identical.

### Update-order parity caveat (why `technical` stays vanilla)

AC computes the **same steady-state power levels** as vanilla for the overwhelming majority of
circuits, but it does **not** reproduce vanilla's exact *sequence and shape* of intermediate block
updates. A class of contraptions depends on that emergent order:

- **Directional / update-order-dependent** machines (behavior differs by which side updates first).
- **Locational** contraptions (behavior depends on absolute position / iteration order).
- **0-tick and "instant wire"** tricks and some observer/comparator timing chains.

For these, AC's outcome can diverge from vanilla. That is exactly why the `technical` profile
defaults to `vanilla`, and why `eigencraft` exists as a middle ground: faster than vanilla, with
markedly higher update-order fidelity than AC (see risks). The choice is always the operator's.

### Lag-doctor integration

The existing `ac-redstone` remediation id (see `design/modules/lag-doctor.md`) already offers
"switch to Alternate Current (up to ~30× dust; may break locational contraptions)". This spec makes
that id a thin write of `optimizations.redstone: alternate-current`, and adds a symmetric
`vanilla-redstone` id so a technical operator who was auto-migrated can revert in one click. No new
diagnosis code — it reuses the per-subsystem redstone MSPT timer.

## victus.yml keys

```yaml
optimizations:
  # vanilla           — recursive vanilla RedstoneWireBlock. Full parity. Slowest.
  # alternate-current — SpaceWalkerRS network algorithm. Fastest. Minor update-order differences.
  # eigencraft        — theosib RedstoneWireTurbo. Faster than vanilla, high vanilla-order fidelity.
  redstone: alternate-current
```

- **Default:** `alternate-current`, **except** `engine.profile: technical`, which overlays
  `vanilla`. Any explicit `optimizations.redstone` you set beats the profile overlay.
- Consistent with the top-level schema in `VICTUS-CONFIG.md` (the key already exists there); this
  spec pins its semantics, per-profile defaults, and the mapping onto Paper's per-world enum.
- **Related keys used, not introduced:** `engine.profile` (supplies the default), and
  `compatibility.migrate-config-on-boot` (one-time import of any pre-existing Paper
  `redstone-implementation`).

## Vanilla-parity & plugin-compat risks

- **Default path is 100% compatible by construction.** The compat-preserving value (`vanilla`) is
  always reachable, is the value the `technical` profile selects, and is what `/victus doctor` will
  revert to. Non-technical profiles default to `alternate-current` because its game outcomes match
  vanilla for the circuits those audiences actually build; anyone who needs bit-exact update order
  flips one key (or picks the `technical` profile).
- **AC is outcome-parity, not trace-parity.** Steady-state power levels match vanilla; the
  *ordering* of intermediate block updates does not. Update-order-sensitive contraptions (0-tick,
  certain directional/locational machines) can diverge. Mitigation: `technical` → `vanilla` by
  default; `eigencraft` offered as a higher-fidelity fast option; the caveat is stated in-config and
  in the lag-doctor remediation text.
- **`BlockRedstoneEvent` fire-count differs under AC.** Because AC settles a network in one pass
  rather than stepping through intermediate levels, a plugin that *counts* `BlockRedstoneEvent`
  invocations (rather than reading final `oldCurrent`/`newCurrent`) may observe fewer fires for the
  same physical change. Final current values are correct. This is upstream Paper behavior, not
  introduced by Victus, but the config docs and `/victus doctor` must call it out. Plugins that read
  values (the normal case) are unaffected. `TODO(verify)` exact event-emission points against 26.1.
- **No new plugin API surface.** We add a config binding, not new events or hooks, so there is no
  Bukkit/Spigot/Paper API breakage to reason about beyond what upstream already ships.
- **Forward-port risk.** The unobfuscation cutover could have disturbed the bundled AC/Eigencraft
  code; §3 exists to catch that. If it fails to apply, the fallback is `vanilla` everywhere until
  fixed — never a broken redstone tick.

## Tests & verification

- **Contraption parity corpus.** A fixed library of schematics — locational, directional, 0-tick,
  instant-wire, comparator/observer clocks, note-block music, sorting systems, flying machines —
  loaded on identical seeds and driven with recorded inputs. For each, run `vanilla`,
  `alternate-current`, `eigencraft` and assert final block/redstone state. Expected results:
  `eigencraft` matches `vanilla` everywhere; `alternate-current` matches on the common set and is
  **catalogued** (not silently accepted) where it diverges. This catalogue becomes the honest
  "what AC changes" doc.
- **Event-parity test.** Assert `BlockRedstoneEvent` reports correct final `oldCurrent`/`newCurrent`
  under all three, and record the fire-count delta AC introduces so it can be documented.
- **Determinism regression.** Same seed + input trace ⇒ identical redstone outcome under `vanilla`
  and `eigencraft`; identical *final* state under `alternate-current`.
- **Benchmark harness (Phase 1 discipline, see ROADMAP).** A redstone-stress world (large dust
  grids + a wire-heavy farm suite) on identical hardware. Isolate the **dust-tick portion** of MSPT
  via the per-subsystem metrics timer / lag-doctor breakdown and report the **MSPT distribution**
  (not idle TPS) for all three implementations. Redstone MSPT is meaningful sub-150-player, but the
  published run still follows the ≥150-bot, real-workload rule for whole-server context.
- **Config resolution tests.** Assert precedence (explicit per-world Paper value > victus.yml >
  profile default > vanilla), profile overlays (`technical` ⇒ vanilla; others ⇒ alternate-current),
  and the one-time migration import path. Assert the resolved per-world value is logged at boot.
- **Top-50 plugin suite.** Redstone-adjacent plugins (machine/automation, anti-cheat, custom
  crafting) load and behave identically in the default path — zero regressions, per the product
  promise.

## Honest expected gain

**Illustrative targets, to be proven by the Phase 1 benchmark harness — not measured facts.**
All figures are for the **redstone-dust portion of the tick only**; whole-server MSPT improvement
scales with how redstone-bound the workload is, and is **near-zero on servers with little dust**.

- **Alternate Current:** up to **~30×** lower dust-tick MSPT on pathological, wire-heavy circuits;
  **commonly ~10×** on typical wire-heavy circuits.
- **Eigencraft:** roughly **~2–10×**, trading some of AC's ceiling for closer vanilla update-order
  fidelity.
- **Vanilla:** baseline (0×) — the parity reference.

These ranges are grounded in the upstream projects' own claims and the ARCHITECTURE §3 table; they
are **targets for the Phase 1 harness to confirm on Victus hardware**, not benchmarks we have run.

## Prior art / references

- **SpaceWalkerRS — Alternate Current.** The non-recursive, network-based dust algorithm bundled
  upstream and selected by `alternate-current`.
- **theosib — Eigencraft redstone / `RedstoneWireTurbo`.** The vanilla-order-preserving fast
  rewrite bundled upstream and selected by `eigencraft`.
- **PaperMC.** Upstream `redstone-implementation` option that already exposes VANILLA /
  ALTERNATE_CURRENT / EIGENCRAFT; the thing Victus binds and defaults per profile.
- **Mojang `RedstoneWireBlock`.** The recursive vanilla baseline these algorithms replace and are
  measured against.
