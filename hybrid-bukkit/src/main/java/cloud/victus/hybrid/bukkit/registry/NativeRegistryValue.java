// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.registry;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.Map;
import java.util.Objects;

/** Namespaced reference known to have a native Bukkit/Paper representation. */
public record NativeRegistryValue(
        RegistryKind kind,
        NamespacedIdentifier hybridKey,
        Map<String, String> attributes
) implements RegistryValue {
    public NativeRegistryValue {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(hybridKey, "hybridKey");
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
