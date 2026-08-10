// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

import java.util.Locale;

/** Runtime selected before any Minecraft, Paper, Fabric, or NeoForge class is referenced. */
public enum LoaderProfile {
    DISABLED,
    FABRIC,
    NEOFORGE;

    public static LoaderProfile parse(String value) {
        if (value == null || value.isBlank()) {
            return DISABLED;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "disabled", "off", "false" -> DISABLED;
            case "fabric" -> FABRIC;
            case "neoforge" -> NEOFORGE;
            default -> throw new IllegalArgumentException(
                    "Unknown hybrid profile '" + value + "' (expected disabled|fabric|neoforge)");
        };
    }
}
