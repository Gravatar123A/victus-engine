// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Server profile. Applies a bundle of tuned defaults as an OVERLAY: any key explicitly set in
 * victus.yml still wins over these (see docs/VICTUS-CONFIG.md "Profiles are overlays, not locks").
 */
public enum Profile {
    SMP,
    TECHNICAL,
    MINIGAMES,
    MODDED,
    NETWORK;

    /** Dotted-key -&gt; value overlay for this profile. Empty entries mean "use the hard default". */
    public Map<String, Object> defaults() {
        Map<String, Object> d = new HashMap<>();
        switch (this) {
            case TECHNICAL:
                // vanilla-accurate: exact redstone update order + no approximate AI throttling
                d.put("optimizations.redstone", "vanilla");
                d.put("optimizations.entities.dab", false);
                break;
            case SMP:
                d.put("optimizations.redstone", "alternate-current");
                d.put("optimizations.entities.projectile-save-limit", 16); // anti-pile (arrows/XP/etc.)
                d.put("optimizations.entities.dab-start-distance", 16);     // moderate: fewer "resume on approach" artifacts
                break;
            case MODDED:
                // unknown modded AI/goals -> throttle shallowly, big full-tick radius, gentle scaling
                d.put("optimizations.redstone", "alternate-current");
                d.put("optimizations.entities.projectile-save-limit", 16);
                d.put("optimizations.entities.dab-start-distance", 24);
                d.put("optimizations.entities.dab-max-tick-interval", 8);
                d.put("optimizations.entities.dab-activation-dist-mod", 9);
                break;
            case MINIGAMES:
                // minigames rarely need natural mobs — cap hard to save spawn/tick cost
                d.put("optimizations.redstone", "alternate-current");
                d.put("optimizations.entities.monster-spawn-cap", 8);
                d.put("optimizations.entities.projectile-save-limit", 16);
                d.put("optimizations.entities.dab-start-distance", 8);      // aggressive: arena mobs are decoration
                d.put("optimizations.entities.dab-activation-dist-mod", 7);
                break;
            case NETWORK:
                // hub/proxy-backing lobbies want minimal mobs
                d.put("optimizations.redstone", "alternate-current");
                d.put("optimizations.entities.monster-spawn-cap", 5);
                d.put("optimizations.entities.projectile-save-limit", 16);
                d.put("optimizations.entities.dab-start-distance", 12);
                break;
        }
        return Collections.unmodifiableMap(d);
    }

    public static Profile fromConfig(String s) {
        if (s == null) return SMP;
        switch (s.trim().toLowerCase()) {
            case "smp":       return SMP;
            case "technical": return TECHNICAL;
            case "minigames": return MINIGAMES;
            case "modded":    return MODDED;
            case "network":   return NETWORK;
            default:
                throw new IllegalArgumentException("unknown engine.profile: " + s
                        + " (expected smp|technical|minigames|modded|network)");
        }
    }
}
