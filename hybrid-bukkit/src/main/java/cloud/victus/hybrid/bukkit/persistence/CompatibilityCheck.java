// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.persistence;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.Set;

public record CompatibilityCheck(Status status, Set<NamespacedIdentifier> missingContent, String detail) {
    public enum Status { COMPATIBLE, FINGERPRINT_MISMATCH, CONTENT_MISSING }

    public CompatibilityCheck {
        missingContent = Set.copyOf(missingContent == null ? Set.of() : missingContent);
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("detail must not be blank");
    }

    public boolean compatible() {
        return status == Status.COMPATIBLE;
    }
}
