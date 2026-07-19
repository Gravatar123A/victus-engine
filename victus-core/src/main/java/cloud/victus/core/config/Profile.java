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
            case MINIGAMES:
            case MODDED:
            case NETWORK:
                d.put("optimizations.redstone", "alternate-current");
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
