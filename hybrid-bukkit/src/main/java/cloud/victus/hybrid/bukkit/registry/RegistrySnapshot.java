// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.registry;

import cloud.victus.hybrid.bukkit.api.HybridRegistryView;
import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable point-in-time registry image created after loader registration freezes. */
public final class RegistrySnapshot implements HybridRegistryView {
    private final long generation;
    private final Map<RegistryKind, Map<NamespacedIdentifier, RegistryValue>> registries;

    private RegistrySnapshot(long generation, Map<RegistryKind, Map<NamespacedIdentifier, RegistryValue>> registries) {
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        this.generation = generation;
        this.registries = registries;
    }

    public static RegistrySnapshot capture(long generation, Collection<RegistryEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        Map<RegistryKind, Map<NamespacedIdentifier, RegistryValue>> mutable = new EnumMap<>(RegistryKind.class);
        for (RegistryKind kind : RegistryKind.values()) mutable.put(kind, new LinkedHashMap<>());
        for (RegistryEntry entry : entries) {
            RegistryValue value = entry.nativeBukkitRepresentation()
                    ? new NativeRegistryValue(entry.kind(), entry.key(), entry.attributes())
                    : new UnknownRegistryValue(entry.kind(), entry.key(), entry.attributes());
            RegistryValue previous = mutable.get(entry.kind()).putIfAbsent(entry.key(), value);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate " + entry.kind() + " key " + entry.key());
            }
        }
        Map<RegistryKind, Map<NamespacedIdentifier, RegistryValue>> frozen = new EnumMap<>(RegistryKind.class);
        mutable.forEach((kind, values) -> frozen.put(kind, Map.copyOf(values)));
        return new RegistrySnapshot(generation, Map.copyOf(frozen));
    }

    public static RegistrySnapshot empty() {
        return capture(0, List.of());
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public Optional<RegistryValue> find(RegistryKind kind, NamespacedIdentifier key) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(registries.get(kind).get(key));
    }

    @Override
    public Collection<RegistryValue> values(RegistryKind kind) {
        Objects.requireNonNull(kind, "kind");
        return registries.get(kind).values();
    }
}
