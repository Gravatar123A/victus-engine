// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.plugin;

import cloud.victus.core.metrics.Labels;
import cloud.victus.core.metrics.MetricCatalog;
import cloud.victus.core.metrics.MetricRegistry;
import org.bukkit.Server;
import org.bukkit.World;

/**
 * Runs on the main thread once per second and pushes live server stats into the {@link MetricRegistry}.
 * Reading world/entity state must be on the main thread; the HTTP endpoint reads the registry
 * (thread-safe) separately. Kept cheap: a handful of gauges + one summary sample per tick-second.
 */
final class MetricsSampler implements Runnable {
    private final Server server;
    private final MetricRegistry registry;

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
    }
}
