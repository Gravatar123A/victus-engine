// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.api;

/**
 * Extension for content that has a stable namespaced identity but no native Bukkit enum value.
 * Consumers should retain the key rather than relying on implementation class identity.
 */
public interface HybridKeyed {
    NamespacedIdentifier hybridKey();
}
