// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.api;

import cloud.victus.hybrid.bukkit.registry.RegistryKind;
import cloud.victus.hybrid.bukkit.registry.RegistryValue;
import java.util.Collection;
import java.util.Optional;

/**
 * Immutable view of arbitrary namespaced registry values unavailable through Bukkit enums.
 * Contract version is {@link HybridApi#VERSION}; additions within major version 1 are compatible.
 */
public interface HybridRegistryView {
    long generation();

    Optional<RegistryValue> find(RegistryKind kind, NamespacedIdentifier key);

    Collection<RegistryValue> values(RegistryKind kind);
}
