// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.registry;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.Map;
import java.util.Objects;

/**
 * Safe wrapper for content Bukkit cannot represent. It deliberately does not impersonate or mutate an enum.
 */
public record UnknownRegistryValue(
        RegistryKind kind,
        NamespacedIdentifier hybridKey,
        Map<String, String> attributes
) implements RegistryValue {
    public UnknownRegistryValue {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(hybridKey, "hybridKey");
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
