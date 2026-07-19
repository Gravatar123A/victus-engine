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
            case "doctor" -> doctor(sender, args);
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

    private void doctor(CommandSender sender, String[] args) {
        RemediationCatalog cat = RemediationCatalog.defaultCatalog();

        // /victus doctor apply|revert <id>
        if (args.length >= 3 && (args[1].equalsIgnoreCase("apply") || args[1].equalsIgnoreCase("revert"))) {
            boolean revert = args[1].equalsIgnoreCase("revert");
            Remediation chosen = cat.get(args[2]);
            if (chosen == null) {
                sender.sendMessage("[Victus] unknown fix id: " + args[2]);
                return;
            }
            Remediation target = revert ? cat.revertOf(chosen) : chosen;
            try {
                String desc = new ConfigApplier(plugin.getServer().getWorldContainer()).apply(target.writes());
                plugin.reloadVictusConfig();
                sender.sendMessage("[Victus] applied [" + target.id() + "] " + target.title() + " -> " + desc);
                sender.sendMessage("[Victus] revert with: /victus doctor revert " + chosen.id());
            } catch (Exception e) {
                sender.sendMessage("[Victus] apply failed: " + e.getMessage());
            }
            return;
        }

        Server server = plugin.getServer();
        double mspt = server.getAverageTickTime();
        double[] tps = server.getTPS();
        int players = server.getOnlinePlayers().size();
        long entities = 0, chunks = 0;
        for (World w : server.getWorlds()) {
            entities += w.getEntities().size();
            chunks += w.getLoadedChunks().length;
        }
        var msptSnap = plugin.registry().summary(MetricCatalog.MSPT).snapshot();
        sender.sendMessage("=== /victus doctor ===");
        sender.sendMessage("TPS=" + fmt(tps.length > 0 ? tps[0] : 20.0) + "  MSPT now=" + fmt(mspt)
                + "ms  p95=" + fmt(msptSnap.p95()) + "  p99=" + fmt(msptSnap.p99()) + "  max=" + fmt(msptSnap.max()));
        sender.sendMessage("players=" + players + "  entities=" + entities + "  chunks=" + chunks);

        boolean overBudget = mspt > plugin.config().maxMspt;
        if (overBudget || entities > 2000) {
            sender.sendMessage(overBudget
                    ? "MSPT over budget — fixes (apply with /victus doctor apply <id>):"
                    : "High entity count — fixes (apply with /victus doctor apply <id>):");
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
