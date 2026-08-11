// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.registry;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.Map;
import java.util.Objects;

/** Immutable loader-supplied registry entry; attributes must be stable scalar strings. */
public record RegistryEntry(
        RegistryKind kind,
        NamespacedIdentifier key,
        boolean nativeBukkitRepresentation,
        Map<String, String> attributes
) {
    public RegistryEntry {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
