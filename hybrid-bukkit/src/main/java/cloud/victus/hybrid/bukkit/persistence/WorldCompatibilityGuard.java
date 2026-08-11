// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.persistence;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Fails closed when world ownership, content, or compatibility metadata does not match runtime. */
public final class WorldCompatibilityGuard {
    public CompatibilityCheck check(WorldManifest manifest, String runtimeFingerprint,
                                    Set<NamespacedIdentifier> availableContent) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(runtimeFingerprint, "runtimeFingerprint");
        Objects.requireNonNull(availableContent, "availableContent");
        if (!manifest.compatibilityFingerprint().equals(runtimeFingerprint)) {
            return new CompatibilityCheck(CompatibilityCheck.Status.FINGERPRINT_MISMATCH, Set.of(),
                    "World fingerprint differs from active hybrid runtime");
        }
        Set<NamespacedIdentifier> missing = new HashSet<>(manifest.requiredContent());
        missing.removeAll(availableContent);
        if (!missing.isEmpty()) {
            return new CompatibilityCheck(CompatibilityCheck.Status.CONTENT_MISSING, missing,
                    "Required modded world content is unavailable");
        }
        return new CompatibilityCheck(CompatibilityCheck.Status.COMPATIBLE, Set.of(), "World manifest is compatible");
    }

    public RemovalDecision removalDecision(WorldManifest manifest, Set<NamespacedIdentifier> contentBeingRemoved) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(contentBeingRemoved, "contentBeingRemoved");
        Set<NamespacedIdentifier> referenced = new HashSet<>(manifest.requiredContent());
        referenced.retainAll(contentBeingRemoved);
        return referenced.isEmpty()
                ? RemovalDecision.permit()
                : RemovalDecision.refused(referenced, "World manifest still references content selected for removal");
    }
}
