# victus-plugin — VictusEngine

The Victus Engine **hosting/observability layer**, delivered as a drop-in Paper plugin backed by
[`victus-core`](../victus-core). Works on the Victus Engine fork jar (or any Paper 26.2 server).

## What it does (verified working)

- Loads **`victus.yml`** (writes a documented default on first run) and resolves it via victus-core.
- **`/victus`** command: `version`, `config`, `reload`, `metrics`, `doctor`.
- Serves a **Prometheus metrics endpoint** (default `http://127.0.0.1:9940/metrics`) off the tick
  thread — live `victus_tps`, `victus_mspt`, `victus_players`, `victus_entities{world,type}`,
  `victus_chunks_loaded{world}`, `victus_heap_bytes`, and more.

> This is the fast-iterating, deployable integration (observability + config + advisory). The deep
> per-subsystem tick instrumentation and throttle **enforcement** move into server patches later;
> `/victus doctor` says so where it can't yet attribute per-subsystem lag from the API alone.

## Build

**Gradle (canonical, CI-friendly):**
```bash
cd victus-plugin
gradle jar        # -> build/libs/VictusEngine-0.1.0.jar   (bundles victus-core)
```

**Offline fallback** (no network; reuses the fork's dependency cache — see `build-offline.sh`):
compiles with the provisioned JDK 25 against the built `victus-api` jar + the gradle module cache.

## Deploy

Drop `VictusEngine-0.1.0.jar` into a server's `plugins/` folder and start it (Java 25). Edit the
generated `victus.yml` and `/victus reload`.
