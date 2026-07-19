// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.plugin;

import cloud.victus.core.config.ConfigResolver;
import cloud.victus.core.config.ResolvedConfig;
import cloud.victus.core.metrics.MetricCatalog;
import cloud.victus.core.metrics.MetricRegistry;
import cloud.victus.core.runtime.GcDetector;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Victus Engine hosting/observability layer, delivered as a Paper plugin backed by victus-core.
 * Loads victus.yml, exposes the {@code /victus} command, and serves a Prometheus metrics endpoint.
 *
 * <p>This is the fast-iterating, deployable integration: observability + config + advisory. The
 * deep per-subsystem tick instrumentation and throttle ENFORCEMENT move into server patches later.
 */
public final class VictusPlugin extends JavaPlugin {

    private ResolvedConfig config;
    private final MetricRegistry registry = new MetricRegistry();
    private MetricsHttpEndpoint endpoint;
    private int samplerTask = -1;
    private File victusYml;

    @Override
    public void onEnable() {
        MetricCatalog.registerDefaults(registry);
        victusYml = new File(getServer().getWorldContainer(), "victus.yml");
        reloadVictusConfig();

        getLogger().info("Victus Engine loaded — profile=" + config.profile + ", threading=" + config.threadingMode
                + ", redstone=" + config.redstone + ", compression=" + config.compression
                + ", GC(detected)=" + GcDetector.detect().displayName());
        for (String w : config.warnings) {
            getLogger().warning("victus.yml: " + w);
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(victusYml);
        if (yaml.getBoolean("hosting.metrics.prometheus.enabled", true)) {
            String bind = yaml.getString("hosting.metrics.prometheus.bind", "127.0.0.1");
            int port = yaml.getInt("hosting.metrics.prometheus.port", 9940);
            try {
                endpoint = new MetricsHttpEndpoint(registry, bind, port);
                endpoint.start();
                getLogger().info("Prometheus metrics endpoint: " + endpoint.url());
            } catch (IOException e) {
                getLogger().warning("Could not start metrics endpoint on " + bind + ":" + port + " — " + e.getMessage());
            }
        }

        samplerTask = getServer().getScheduler()
                .runTaskTimer(this, new MetricsSampler(getServer(), registry), 20L, 20L).getTaskId();

        if (getCommand("victus") != null) {
            getCommand("victus").setExecutor(new VictusCommand(this));
        }
    }

    @Override
    public void onDisable() {
        if (samplerTask != -1) {
            getServer().getScheduler().cancelTask(samplerTask);
        }
        if (endpoint != null) {
            endpoint.stop();
        }
    }

    ResolvedConfig config() {
        return config;
    }

    MetricRegistry registry() {
        return registry;
    }

    void reloadVictusConfig() {
        if (!victusYml.exists()) {
            writeDefaultVictusYml();
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(victusYml);
        Map<String, Object> map = YamlMaps.toNestedMap(yaml);
        this.config = new ConfigResolver(map).resolve(null);
    }

    private void writeDefaultVictusYml() {
        String def = ""
                + "# Victus Engine per-server config. See docs/VICTUS-CONFIG.md for the full schema.\n"
                + "engine:\n"
                + "  profile: smp            # smp | technical | minigames | modded | network\n"
                + "threading:\n"
                + "  mode: single            # single | parallel | regionized\n"
                + "optimizations:\n"
                + "  redstone: alternate-current   # vanilla | alternate-current | eigencraft\n"
                + "  entities:\n"
                + "    dab: true\n"
                + "    async-pathfinding: true\n"
                + "    per-player-mob-spawns: true\n"
                + "  network:\n"
                + "    compression: libdeflate     # zlib | libdeflate (NOT zstd — breaks clients)\n"
                + "    compression-threshold: 256\n"
                + "hosting:\n"
                + "  limits:\n"
                + "    max-mspt: 45\n"
                + "  metrics:\n"
                + "    prometheus:\n"
                + "      enabled: true\n"
                + "      bind: 127.0.0.1\n"
                + "      port: 9940\n"
                + "  logging:\n"
                + "    format: text            # text | json\n"
                + "  lag-doctor:\n"
                + "    enabled: true\n";
        try {
            Files.writeString(victusYml.toPath(), def, StandardCharsets.UTF_8);
            getLogger().info("Wrote a default victus.yml to the server directory.");
        } catch (IOException e) {
            getLogger().warning("Could not write default victus.yml: " + e.getMessage());
        }
    }
}
