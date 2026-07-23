// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.plugin;

import cloud.victus.core.metrics.Labels;
import cloud.victus.core.metrics.MetricCatalog;
import cloud.victus.core.metrics.MetricRegistry;
import org.bukkit.Server;
import org.bukkit.World;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs on the main thread once per second and pushes live server stats into the {@link MetricRegistry}.
 * Reading world/entity state must be on the main thread; the HTTP endpoint reads the registry
 * (thread-safe) separately. Kept cheap: a handful of gauges + one summary sample per tick-second.
 */
final class MetricsSampler implements Runnable {
    private final Server server;
    private final MetricRegistry registry;
    /** collector name -> [cumulative count, cumulative time-ms] from the previous sample. */
    private final Map<String, long[]> gcPrev = new HashMap<>();
    /** Reflectively-resolved cloud.victus.engine.VictusEngine.observabilitySnapshot() — server internals
     *  the plugin cannot see at compile time; null when running as a pure plugin without the native engine. */
    private java.lang.reflect.Method obsSnapshot;
    private boolean obsResolved;

    MetricsSampler(Server server, MetricRegistry registry) {
        this.server = server;
        this.registry = registry;
    }

    @Override
    public void run() {
        double[] tps = server.getTPS();
        if (tps != null && tps.length > 0) {
            registry.gauge(MetricCatalog.TPS).set(Math.min(tps[0], 20.0));
        }
        registry.summary(MetricCatalog.MSPT).record(server.getAverageTickTime());
        registry.gauge(MetricCatalog.PLAYERS).set(server.getOnlinePlayers().size());

        Runtime rt = Runtime.getRuntime();
        registry.gauge(MetricCatalog.HEAP_BYTES).set((double) (rt.totalMemory() - rt.freeMemory()));
        registry.gauge(MetricCatalog.HEAP_MAX_BYTES).set((double) rt.maxMemory());

        for (World w : server.getWorlds()) {
            String name = w.getName();
            registry.gauge(MetricCatalog.ENTITIES,
                    Labels.of(MetricCatalog.LABEL_WORLD, name, MetricCatalog.LABEL_TYPE, "all"))
                    .set(w.getEntities().size());
            registry.gauge(MetricCatalog.CHUNKS_LOADED, Labels.of(MetricCatalog.LABEL_WORLD, name))
                    .set(w.getLoadedChunks().length);
        }

        sampleGc();
        sampleEngine();
    }

    /** Reflectively pull the native engine's async-chunk-send + chunk-thread counters into the registry.
     *  The plugin compiles against the API only, so server internals are reached via VictusEngine. */
    private void sampleEngine() {
        try {
            if (!obsResolved) {
                obsResolved = true;
                try {
                    obsSnapshot = Class.forName("cloud.victus.engine.VictusEngine").getMethod("observabilitySnapshot");
                } catch (Throwable notPresent) {
                    obsSnapshot = null; // pure-plugin mode (no native engine) — skip these metrics
                }
            }
            if (obsSnapshot == null) return;
            long[] s = (long[]) obsSnapshot.invoke(null);
            if (s == null || s.length < 5) return;
            registry.gauge(MetricCatalog.ASYNC_CHUNK_SEND_ENABLED).set(s[0]);
            registry.gauge(MetricCatalog.ASYNC_CHUNK_SEND_DISPATCHED).set(s[1]);
            registry.gauge(MetricCatalog.ASYNC_CHUNK_SEND_WATCHDOG_FIRES).set(s[2]);
            if (s[3] >= 0) registry.gauge(MetricCatalog.CHUNK_WORKER_THREADS).set(s[3]);
            if (s[4] >= 0) registry.gauge(MetricCatalog.CHUNK_IO_THREADS).set(s[4]);
        } catch (Throwable ignored) {
            // engine internals unavailable this tick — skip these metrics silently
        }
    }

    /** Records the average pause of any GC that occurred since the last sample, per collector. JMX
     *  only exposes cumulative count/time, so we approximate per-pause as delta-time / delta-count. */
    private void sampleGc() {
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count < 0 || time < 0) continue;
            long[] prev = gcPrev.put(gc.getName(), new long[]{count, time});
            if (prev == null) continue;
            long dc = count - prev[0];
            long dt = time - prev[1];
            if (dc > 0) {
                double avgPauseMs = (double) dt / dc;
                var summary = registry.summary(MetricCatalog.GC_PAUSE_MS,
                        Labels.of(MetricCatalog.LABEL_COLLECTOR, gc.getName()));
                for (long i = 0; i < dc && i < 50; i++) { // cap to bound work if a huge burst occurred
                    summary.record(avgPauseMs);
                }
            }
        }
    }
}
