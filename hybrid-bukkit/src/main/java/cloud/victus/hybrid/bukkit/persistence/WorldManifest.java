// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.persistence;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Persistent hybrid ownership and compatibility metadata stored beside a world. */
public record WorldManifest(
        int formatVersion,
        NamespacedIdentifier dimension,
        String compatibilityFingerprint,
        List<NamespacedIdentifier> requiredContent
) {
    public static final int CURRENT_FORMAT = 1;

    public WorldManifest {
        if (formatVersion != CURRENT_FORMAT) throw new IllegalArgumentException("Unsupported manifest format " + formatVersion);
        Objects.requireNonNull(dimension, "dimension");
        compatibilityFingerprint = Objects.requireNonNull(compatibilityFingerprint, "compatibilityFingerprint").trim();
        if (!compatibilityFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("compatibilityFingerprint must be lowercase SHA-256");
        }
        List<NamespacedIdentifier> sorted = new ArrayList<>(requiredContent == null ? List.of() : requiredContent);
        sorted.sort(NamespacedIdentifier::compareTo);
        if (sorted.stream().distinct().count() != sorted.size()) throw new IllegalArgumentException("Duplicate required content");
        requiredContent = List.copyOf(sorted);
    }
}
