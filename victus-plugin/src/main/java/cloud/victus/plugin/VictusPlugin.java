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

    private ResolvedConfig config = new ResolvedConfig(); // review #2: never null — onEnable reads it immediately even if the first load fails
    private final MetricRegistry registry = new MetricRegistry();
    private MetricsHttpEndpoint endpoint;
    private int samplerTask = -1;
    private File victusYml;

    @Override
    public void onEnable() {
        MetricCatalog.registerDefaults(registry);
        // review #3: use the SAME canonical path as the native engine (VictusEngine uses Path.of("victus.yml")
        // = the server working dir), so plugin and engine never read/write two different files under a
        // non-default --universe. victus.yml is a server-global engine config, not per-world.
        victusYml = new File("victus.yml").getAbsoluteFile();
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
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(victusYml);
            Map<String, Object> map = YamlMaps.toNestedMap(yaml);
            // review #2: publish to the field only on success. A single malformed key must NOT crash
            // onEnable() (Bukkit would disable the whole plugin) or throw raw out of /victus reload —
            // keep the last-good config, exactly as the native VictusEngine.init() does.
            this.config = new ConfigResolver(map).resolve(null);
        } catch (Throwable t) {
            getLogger().warning("victus.yml: failed to load/apply; keeping the last-good config (profile="
                    + config.profile + "): " + t);
        }
        // Re-push runtime-changeable engine settings (DAB) so a reload/doctor-apply actually takes
        // effect. The engine owns victus.yml natively; call it reflectively since this plugin does
        // not compile against server internals (and must still work if run as a pure plugin).
        try {
            Class.forName("cloud.victus.engine.VictusEngine").getMethod("reload").invoke(null);
        } catch (Throwable ignored) {
            // engine not present (running as a standalone plugin) — nothing to re-push
        }
    }

    private void writeDefaultVictusYml() {
        try {
            // one canonical template, owned by victus-core and shared with the native engine (review #3/#6)
            Files.writeString(victusYml.toPath(), cloud.victus.core.config.DefaultConfig.YML, StandardCharsets.UTF_8);
            getLogger().info("Wrote a default victus.yml to the server directory.");
        } catch (IOException e) {
            getLogger().warning("Could not write default victus.yml: " + e.getMessage());
        }
    }
}
