// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.api;

/**
 * Version marker for the loader-neutral hybrid extensions.
 *
 * <p>These contracts supplement Bukkit/Paper only where their types cannot represent arbitrary
 * namespaced mod content. They are not replacements for Bukkit registries, events, or commands.
 */
public final class HybridApi {
    public static final int MAJOR_VERSION = 1;
    public static final String VERSION = "1.0";

    private HybridApi() {
    }
}
