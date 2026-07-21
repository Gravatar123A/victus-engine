# Victus Engine — performance benchmark harness

Repeatable A/B measurement so **every** perf claim is proven, not asserted. Per the perf research
(`docs/phase-3/`): *idle TPS and startup time are vanity metrics — measure a real workload, and
compare against a well-tuned stock Paper, on your own hardware.*

## Honest-metric rules (baked into the scripts)
- **RAM: use PSS, not RSS.** ZGC multi-maps the heap, so `top`/RSS/panel widgets over-report it.
  `/proc/<pid>/smaps_rollup` **Pss** is the honest resident figure; **NMT**
  (`-XX:NativeMemoryTracking=summary` → `jcmd VM.native_memory summary`) is the honest *committed*
  figure and is comparable across G1/ZGC. `jcmd GC.class_histogram` proves object-footprint wins
  (e.g. CompactObjectHeaders) and that FerriteCore is redundant (Moonrise already nulls the arrays).
- **Elastic heap for RAM A/Bs.** `-Xms == -Xmx` commits the whole heap and *hides* the GC's real
  footprint. Use a low `-Xms` so RSS tracks the working set (that's how the ZGC→G1 ~48% win surfaced;
  an `Xms==Xmx` test wrongly showed G1 *heavier*).
- **Same everything but the variable:** identical jar, world, seed, plugins, and GC (unless GC *is*
  the variable). Delete region files between chunk-gen runs.
- **Run `jcmd GC.run` before sampling** so you compare post-GC live sets, not float.

## Scripts (run on the DE-1 build node inside the java_25 container; they only touch `/root`)
| Script | Measures | Notes |
| --- | --- | --- |
| `measure.sh "<label>" "<jvm-flags>"` | boots the engine in a throwaway dir, prints PSS / VmRSS / NMT-committed / heap-used / CPU% / errors, then **gracefully** stops | the core primitive; all A/Bs call it |
| `ram-gc-ab.sh` | ZGC vs G1 vs G1+CompactObjectHeaders (elastic heap) | reproduces the ~48% ZGC→G1 RAM finding |
| `measure-live.sh <pid>` | a *running* server via `/proc` (PSS/RSS/CPU/cgroup) | non-invasive; use on the real wings process |
| `chunkgen-ab.sh` | chunk-gen time at N Moonrise worker-threads | best-effort headless (forceload); a Chunky/bot harness (S6) gives cleaner chunks/s |

## Setup (one-time, on DE-1)
```bash
# put the jar to test at /root/victus-test.jar (docker only mounts /root, NOT the pterodactyl volumes path)
cp /var/lib/pterodactyl/volumes/<uuid>/victus.jar /root/victus-test.jar
# run any script inside the toolchain image:
docker run --rm --memory=2600m -u 0 -v /root:/root -w /root --entrypoint bash \
  ghcr.io/ptero-eggs/yolks:java_25 -c 'bash /root/bench/ram-gc-ab.sh'
```

## Latest baseline — harness-reproduced (2026-07-21, DE-1, near-idle, elastic 512M–2048M)
`ram-gc-ab.sh` output (0 errors, graceful stops):
| GC config | PSS | NMT committed | heap used |
| --- | --- | --- | --- |
| Generational ZGC | 2.19 GB | 2.06 GB | 370 MB |
| G1+Aikar | 1.03 GB | 0.90 GB | 250 MB |
| G1 + CompactObjectHeaders | **0.91 GB** | 0.87 GB | 227 MB |

→ **G1+COH ≈ 58% less RAM than ZGC** (G1 ~half of ZGC; CompactObjectHeaders trims another ~12% on
top, and ~10% off the raw heap: 250 MB → 227 MB). Run-to-run ZGC PSS varies ~1.96–2.19 GB (colored-
pointer mappings); G1 is stable. This is why G1+COH shipped as the default (`RecommendedFlags`).

## Idle-RAM: `-Xms` floor (2026-07-21) — lower `-Xms` = lower idle RSS (elastic heap)
The live server idled at 1.5 GB because `-Xms1024M` pins 1 GB committed even though it only *uses* ~215 MB.
A/B (G1+COH, same fresh world, `-Xmx4608M`):
| `-Xms` | idle PSS | committed | heap used |
| --- | --- | --- | --- |
| 1024M | 1.43 GB | 1.42 GB | 215 MB |
| **512M** | **0.96 GB** | 0.90 GB | 214 MB |

→ **~500 MB (~34%) less idle RAM** from lowering `-Xms` alone (G1 never commits below `-Xms`). Shipped
`-Xms512M` on `e5aa1c05`. Realistic floor for a *running* MC server with a loaded world is ~0.9–1.0 GB
(off-heap ≈ 0.4 GB: metaspace/threads/Netty/GC card tables/mmap + ~0.4–0.5 GB working-set heap) — that's
the JVM+Paper baseline, not bloat. See `RecommendedFlags.elasticHeapFlags`.

## Chunk-gen baseline — `chunkgen-ab.sh` (2026-07-21, DE-1, `--cpus=8`)
| Moonrise worker-threads | spawn-area gen | total boot |
| --- | --- | --- |
| 1 (Moonrise ≤6-core / shared default) | 13.4 s | 26.3 s |
| 8 (dedicated, uncapped) | **5.9 s** | **18.1 s** |

→ **~2.3× faster chunk generation** (and ~31% faster boot) from uncapping worker-threads, override
confirmed applied (`using 8 worker threads` in the log). Validates the dedicated-node advisory
(`RecommendedFlags.recommendedChunkWorkerThreads`) — apply on **dedicated** nodes only (on shared
nodes it steals cores from co-located servers). Bigger workloads (a Chunky/bot pregen, S6) will show
a larger absolute win; this small spawn workload already shows the direction and magnitude.
