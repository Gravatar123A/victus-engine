// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.persistence;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.Set;

public record RemovalDecision(boolean allowed, Set<NamespacedIdentifier> referencedContent, String detail) {
    public RemovalDecision {
        referencedContent = Set.copyOf(referencedContent == null ? Set.of() : referencedContent);
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("detail must not be blank");
        if (allowed && !referencedContent.isEmpty()) throw new IllegalArgumentException("Allowed removal has references");
    }

    public static RemovalDecision permit() {
        return new RemovalDecision(true, Set.of(), "No persisted world content blocks removal");
    }

    public static RemovalDecision refused(Set<NamespacedIdentifier> references, String detail) {
        return new RemovalDecision(false, references, detail);
    }
}
