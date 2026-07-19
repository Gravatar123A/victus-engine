# Phase 1 · Networking &amp; player ping

## Goal

Lower the CPU the network layer spends per tick, cut the *time between a tick finishing and packets
reaching the client* (the felt-latency win), and shrink bandwidth — **without** changing the wire
protocol or breaking a single stock client. This is the layer players experience as "ping/lag,"
distinct from MSPT (though felt responsiveness is bounded by both — see cross-refs).

Victus's delta over Paper 26.1 is **defaults + a faster (wire-compatible) compression backend +
a lower-allocation handler path**, not a protocol change.

## Upstream baseline (what Paper 26.1 already provides)

Paper already does a lot here — we tune and extend, we don't reinvent:

- **Netty native transport (epoll/io_uring on Linux, kqueue on macOS)** via `use-native-transport`
  in `paper-global.yml`, default enabled where available. Lower GC, better fd scaling than NIO.
- **Flush consolidation** — Paper batches socket flushes instead of flushing per packet, already on.
- **Pooled/off-heap `ByteBuf`s** and Velocity-derived networking improvements are partially upstream.
- **Compression** — the vanilla protocol compresses packets with **zlib (raw DEFLATE)** once a
  packet exceeds `network-compression-threshold` (default 256 bytes). This is **fixed by the
  protocol**: the client always expects zlib. Paper exposes the threshold, not the algorithm.
- `TODO(verify against Paper 26.1 source once building online)`: exact config keys/locations for
  native transport + compression threshold, and whether io_uring is exposed separately from epoll.

## Design / patch plan

Patch group: `patches/paper-server/optimizations/network/`. Three pieces.

### 1. Wire-compatible compression backend (the headline delta)

Expose `optimizations.network.compression` = `zlib | libdeflate` (default **`libdeflate`**).

- **libdeflate is a drop-in, wire-compatible replacement for zlib** — it emits the identical
  zlib/DEFLATE stream a vanilla client already decodes, just far faster to produce. Swap the JNI
  binding used by the compression `ChannelHandler` (compress) and decompression handler
  (decompress) from `java.util.zip.Deflater/Inflater` to a libdeflate binding.
- **`zstd` is NOT a valid option here.** Minecraft's packet compression is negotiated as zlib only;
  sending zstd-framed packets would break every stock client. **zstd belongs to disk/region-file
  storage** (Anvil/Linear region compression — see [`05-chunks.md`](05-chunks.md)) or to *inter-node
  / proxy* links where Victus controls both ends, **never** to client packet compression. This spec
  is the authoritative statement of that split; the top-level schema was corrected to match.
- Also expose `network-compression-threshold` through `optimizations.network.compression-threshold`
  (default 256; profile overlay may raise it for `network`/`minigames` hubs where tiny packets
  dominate and per-packet compression overhead isn't worth it — a raised threshold trades bandwidth
  for CPU).

### 2. Low-allocation / consolidated handler path (Krypton-class)

- Reduce per-packet allocations in the hot encode/decode path (reuse buffers, avoid intermediate
  copies), mirroring the Velocity/Krypton handler techniques Paper hasn't fully absorbed.
- **Viewable-packet grouping/caching**: when the same packet (e.g. an entity movement/spawn) is sent
  to N nearby players, serialize once and fan the shared buffer out, instead of re-encoding per
  recipient. Biggest win in dense areas (spawn, PvP, lobbies) — exactly the clustered case where
  regionized threading gives nothing, so this is complementary.
- Keep this behind `optimizations.network.low-alloc-handlers` (default true). Purely internal; no
  observable behavior change.

### 3. Hosting-aware transport tuning

- `optimizations.network.native-transport` (default true) — surface Paper's setting in victus.yml
  for consistency + let the profile/hosting layer reason about it.
- **`epollWait` busy-spin caveat (hosting-critical):** a known Netty/JDK interaction can make epoll
  event loops busy-spin, burning a core, and it compounds when **many server instances are
  co-located on one node** — i.e. exactly the Victus multi-tenant node layout. Spec a mitigation:
  detect abnormal event-loop CPU with zero throughput and apply the known workaround (bounded
  select / `-Dio.netty.…` tuning); expose loop CPU as a metric (ties into the hosting module) so a
  spinning instance is visible per-tenant. `TODO(verify)` the precise trigger + workaround on the
  26.1 Netty version.
- Ensure `TCP_NODELAY` is set (Nagle off) so small movement/interaction packets aren't delayed —
  the single most direct "felt ping" knob at the socket level.

## victus.yml keys

```yaml
optimizations:
  network:
    native-transport: true        # Linux epoll/io_uring, macOS kqueue (falls back to NIO)
    flush-consolidation: true      # batch socket flushes (upstream; surfaced here)
    low-alloc-handlers: true       # Krypton-class low-allocation encode/decode + viewable grouping
    compression: libdeflate        # zlib | libdeflate  (WIRE-COMPATIBLE ONLY — never zstd for packets)
    compression-threshold: 256     # bytes; raise on hub/minigame profiles to trade bandwidth for CPU
    tcp-nodelay: true              # disable Nagle; lowers felt latency on small packets
```

- **Defaults keep behavior identical**; `libdeflate` produces byte-identical zlib output, so clients
  and packet-capturing plugins see no difference — just lower CPU.
- Profile overlays: `network`/`minigames` may raise `compression-threshold`; everything else uses
  defaults. `technical`/`smp` unchanged.

## Vanilla-parity &amp; plugin-compat risks

- **No protocol change, no API surface change** → the default path is 100% plugin-compatible by
  construction. libdeflate is wire-identical to zlib; ProtocolLib/packet-manipulation plugins,
  anti-cheats, and proxies (Velocity/ViaVersion) are unaffected.
- **libdeflate native library must be present for the target platform.** If the JNI lib is missing
  or the arch is unsupported, the backend **must fall back to zlib automatically** and log a warning
  — never fail a connection. Ship libdeflate bindings for linux-x86_64/aarch64 (the fleet) + a pure
  fallback.
- **`zstd`-for-packets is explicitly forbidden** (would break stock clients). Enforced by the enum
  only accepting `zlib | libdeflate`; a stray `zstd` value is rejected at config load with a message
  pointing at region-file storage.
- **Raising `compression-threshold` too high** wastes bandwidth (large packets uncompressed);
  lowering it too far wastes CPU compressing tiny packets. Documented; `/victus doctor` can flag a
  pathological setting.
- **epollWait mitigation must not drop events** — verify against the connection-heavy benchmark
  profile before enabling by default; behind a flag until proven.

## Tests &amp; verification

- **Wire-identity test.** Capture the compressed packet stream for a fixed session under `zlib` vs
  `libdeflate`; assert byte-identical DEFLATE output (or at minimum, a stock client + ViaVersion
  decode both streams identically). Zero client-visible difference is the pass bar.
- **Fallback test.** Remove/deny the libdeflate native lib → server boots, logs the warning, serves
  clients on zlib. No connection failures.
- **Throughput/latency bench (Phase 1 discipline, see [`08-benchmark-harness.md`](08-benchmark-harness.md)).**
  Identical hardware, Victus vs stock Paper 26.1: measure (a) network-thread CPU, (b) compression
  CPU per MB, (c) **flush→client wire latency distribution** under a dense-area profile (many nearby
  entities), and (d) bandwidth per player. Report distributions (p50/p95/p99), not averages.
- **Dense-area viewable-grouping bench.** 150+ bots clustered in one region with high entity churn;
  assert lower per-packet encode CPU vs Paper with identical client-received packets.
- **Co-located busy-spin test.** Launch many small instances on one node; assert no event-loop
  core-burn and that per-instance loop CPU is exposed as a metric.
- **Top-50 plugin suite** incl. ProtocolLib, an anti-cheat, and a Velocity proxy hop: zero
  regressions.

## Honest expected gain

**Illustrative targets for the Phase 1 harness — not measured facts.** Network wins are real but
mostly show up as **lower CPU + lower felt latency**, not TPS; per-packet compression speedups are
Amdahl-bounded on whole-tick time.

- **libdeflate:** ~**3×** faster compression / ~**2×** faster decompression vs `java.util.zip` zlib
  (illustrative, upstream libdeflate claims) — frees network-thread CPU and shortens the
  encode→flush window. Bandwidth **unchanged** (same DEFLATE ratio).
- **Flush consolidation + low-alloc + viewable grouping:** lower per-packet CPU and GC, most
  visible in dense areas; felt-latency improvement from tighter flush timing + `TCP_NODELAY`.
- **Raised `compression-threshold` (hub profiles):** less CPU on tiny packets at a bandwidth cost —
  a tuning trade, not a free win.
- **Whole-server MSPT:** small (network is a minority of the tick on most servers); the value is
  headroom, tenant density (more instances/node), and responsiveness — all of which matter to a
  *host*.

> Felt "ping" also depends on MSPT (a slow tick delays every outbound packet) and on the client's
> real RTT (routing/geography — see the Victus SG-1→Mumbai routing work). This spec only addresses
> the server-side network layer.

## Prior art / references

- **Krypton** (Fabric) — Velocity-derived Netty handlers, flush consolidation, low-allocation
  serialization; the technique source. Paper already includes much of this natively.
- **Velocity** — the proxy whose networking handlers set the low-allocation bar.
- **libdeflate** — the wire-compatible, faster DEFLATE implementation behind the `libdeflate` backend.
- **paper-zstd / Linear region format** — zstd used correctly, i.e. for **region-file/disk**
  compression, not packets (see the chunks spec).
- **Netty native transports** (epoll/io_uring/kqueue) — the transport layer; also the `epollWait`
  busy-spin caveat relevant to co-located instances.
