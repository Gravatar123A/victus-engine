# Phase 4 — Hybrid mod bridge (Fabric/NeoForge + Bukkit) — plan & foundation status

**Status:** **FOUNDATION / EXPERIMENTAL — NOT SUPPORTED.** The 26.2 source contains an opt-in,
off-by-default proof-of-concept that can discover mod metadata and invoke a narrow class of direct,
Mojang-mapped, loader-API-light Fabric entrypoints. It is not a Fabric Loader or NeoForge runtime, does
not provide Mixin, remapping, registries, `fabric-api`, or a Bukkit↔mod compatibility bridge, and must
not be described as general hybrid/mod support. The remaining work is the hardest, highest-maintenance
part of this project (ARCHITECTURE §6, ROADMAP Phase 4) and requires curated compatibility and soak gates.

## Proof-of-concept currently present in source
`VictusHybrid.executeFabricServerEntrypoints` reads each `mods/*.jar`'s `fabric.mod.json` (gson), builds a
child `URLClassLoader` (parent = the server/app loader, so mod classes bind our Mojang-mapped Minecraft
directly), and reflectively instantiates + invokes each declared `main`/`server` entrypoint — honoring the
Fabric entrypoint contract without standing up the full loader. Firing is **deferred until the Bukkit
server is live** (console/services ready) and happens **exactly once** per JVM (`MODS_STARTED` CAS), since
`VictusEngine.init()` runs multiple times during boot. Never throws into boot; each entrypoint is
individually try/caught with the real cause unwrapped. **Scope:** Mojang-mapped, loader-API-light,
mixin-free mods. Intermediary linkage / `fabric-api` / Mixin → H2 (below).

## What "fully works" actually requires (why it's not one session)
Running a Fabric/NeoForge mod on a Bukkit/Spigot/Paper server means reconciling **two runtimes that were
never meant to coexist**:
1. **Mod loader bootstrap** — embed the loader (FabricLoader's Knot, or NeoForge's ModLauncher) *inside*
   the Paperclip launch, before the server main, with the right classloader hierarchy.
2. **Mixin/ASM transformation** — mods patch Minecraft classes via Mixin at load time; the transformer
   must run over the *already-Paper-patched* classes without fighting Paper's own transformations.
3. **Mappings** — historically the killer. **Eased hugely here:** MC 26.1+ is unobfuscated/Mojang-mapped,
   so a Mojang-mapped mod can bind directly to our Mojang-mapped server — no Yarn/Intermediary remap layer.
   This is exactly why the roadmap put hybrid *after* the unobfuscation base.
4. **Entrypoints + mod lifecycle** — invoke each mod's server entrypoints at the correct server phases.
5. **Bukkit ↔ mod bridge** — the part plugins care about: events, worlds, entities, items and commands
   registered by mods must surface through the Bukkit API (and vice-versa) so plugins see a coherent world.
6. **Isolation + safe-mode** — a bad mod↔plugin interaction must degrade, not crash the server.

## What shipped now (the foundation — in the jar, off by default)
- **Config** (`victus-core`): `hybrid.enabled` (default false), `hybrid.loader` (auto|fabric|neoforge),
  `hybrid.safe-mode` (default true); resolver validates + warns loudly that it's experimental; self-tested.
- **`cloud.victus.engine.VictusHybrid`**: isolated proof-of-concept wired into `VictusEngine.init()`.
  When enabled it discovers mod jars and may directly invoke the narrow Fabric entrypoint subset above.
  Discovery or entrypoint invocation is not equivalent to loader/runtime or gameplay compatibility.
- **Default `mods/`-aware, pure-plugin-safe:** a server with `hybrid.enabled=false` loads none of it.

## Build order to functional (each a gated milestone with its own soak)
1. **H1 — Loader embed (Fabric first).** Embed fabric-loader; bring up Knot + the mod classloader inside
   the paperclip bootstrap; get the loader to *enumerate + accept* the discovered mods (no gameplay yet).
   Gate: server still boots 100% for pure-plugin (`hybrid.enabled=false`) and with `enabled=true`+0 mods.
2. **H2 — Mixin/transform pipeline.** Run Mixin over the Paper-patched classes; resolve conflicts; verify a
   trivial no-op mixin mod applies. Gate: byte-level sanity + no plugin regressions.
3. **H3 — Entrypoints + registries.** Fire server-side mod entrypoints; register mod blocks/items/entities;
   confirm one simple content mod's registry objects exist server-side. Gate: a curated single mod runs.
4. **H4 — Bukkit bridge + safe-mode.** Surface mod content through the Bukkit API; populate the safe-mode
   blocklist from a tested-compat matrix; refuse/downgrade unknown combos. Gate: a curated modpack + a
   plugin suite run together on a soak box; unknown combos are refused, never crash.
5. **NeoForge** as a parallel track once the Fabric path is proven.

## Honesty contract
`hybrid.enabled` stays **experimental + off by default**, and the `/engine` marketing copy for "run mods
and plugins together" must track reality: it may describe hosting-level hybrid that's live today, but the
**native engine bridge is not advertised as done until H4 passes its soak**. No milestone flips "on" in a
public build until it genuinely runs.
