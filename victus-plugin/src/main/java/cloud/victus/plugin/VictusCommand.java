// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.plugin;

import cloud.victus.core.config.ResolvedConfig;
import cloud.victus.core.doctor.Remediation;
import cloud.victus.core.doctor.RemediationCatalog;
import cloud.victus.core.doctor.Subsystem;
import cloud.victus.core.metrics.MetricCatalog;
import cloud.victus.core.runtime.GcDetector;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Locale;

/** {@code /victus <version|config|reload|metrics|doctor>}. */
public final class VictusCommand implements CommandExecutor {

    private final VictusPlugin plugin;

    VictusCommand(VictusPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "version" -> sender.sendMessage("Victus Engine plugin " + plugin.getPluginMeta().getVersion()
                    + " on " + plugin.getServer().getVersion());
            case "config" -> showConfig(sender);
            case "reload" -> {
                plugin.reloadVictusConfig();
                sender.sendMessage("[Victus] reloaded victus.yml (profile=" + plugin.config().profile + ").");
            }
            case "metrics" -> {
                double tps = plugin.registry().gauge(MetricCatalog.TPS).get();
                int players = (int) plugin.registry().gauge(MetricCatalog.PLAYERS).get();
                sender.sendMessage("[Victus] TPS=" + fmt(tps) + " players=" + players
                        + " — Prometheus endpoint configured under hosting.metrics.prometheus in victus.yml");
            }
            case "doctor" -> doctor(sender);
            default -> sender.sendMessage("[Victus] /victus <version|config|reload|metrics|doctor>");
        }
        return true;
    }

    private void showConfig(CommandSender sender) {
        ResolvedConfig c = plugin.config();
        sender.sendMessage("=== Victus config ===");
        sender.sendMessage("profile=" + c.profile + "  threading=" + c.threadingMode);
        sender.sendMessage("redstone=" + c.redstone + "  compression=" + c.compression + " (threshold " + c.compressionThreshold + ")");
        sender.sendMessage("dab=" + c.dab + "  async-pathfinding=" + c.asyncPathfinding + "  per-player-spawns=" + c.perPlayerMobSpawns);
        sender.sendMessage("max-mspt=" + c.maxMspt + "  GC(detected)=" + GcDetector.detect().displayName());
        for (String w : c.warnings) sender.sendMessage("warn: " + w);
    }

    private void doctor(CommandSender sender) {
        Server server = plugin.getServer();
        double mspt = server.getAverageTickTime();
        double[] tps = server.getTPS();
        int players = server.getOnlinePlayers().size();
        long entities = 0, chunks = 0;
        for (World w : server.getWorlds()) {
            entities += w.getEntities().size();
            chunks += w.getLoadedChunks().length;
        }
        sender.sendMessage("=== /victus doctor ===");
        sender.sendMessage("TPS=" + fmt(tps.length > 0 ? tps[0] : 20.0) + "  MSPT=" + fmt(mspt) + "ms  players="
                + players + "  entities=" + entities + "  chunks=" + chunks);

        RemediationCatalog cat = RemediationCatalog.defaultCatalog();
        boolean overBudget = mspt > plugin.config().maxMspt;
        if (overBudget || entities > 2000) {
            sender.sendMessage(overBudget ? "MSPT over budget — suggestions:" : "High entity count — suggestions:");
            for (Remediation r : cat.forSubsystem(Subsystem.ENTITIES)) {
                sender.sendMessage("  [" + r.id() + "] " + r.title() + " — " + r.expectedGain()
                        + " (caveat: " + r.behaviorCaveat() + ")");
            }
        } else {
            sender.sendMessage("No lag detected (MSPT under the " + plugin.config().maxMspt + "ms budget).");
        }
        sender.sendMessage("(Full per-subsystem lag attribution ships with the engine-integrated build.)");
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
