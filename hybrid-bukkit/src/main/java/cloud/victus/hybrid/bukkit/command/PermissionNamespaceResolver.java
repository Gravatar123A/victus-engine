// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.command;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Allocates permission nodes without allowing one owner to silently capture another owner's node. */
public final class PermissionNamespaceResolver {
    private final Map<String, NamespacedIdentifier> claims = new HashMap<>();

    public synchronized String claim(NamespacedIdentifier owner, String requestedNode) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(requestedNode, "requestedNode");
        String requested = requestedNode.trim().toLowerCase(Locale.ROOT);
        if (!requested.matches("[a-z0-9._-]+")) throw new IllegalArgumentException("Invalid permission " + requestedNode);
        NamespacedIdentifier claimant = claims.putIfAbsent(requested, owner);
        if (claimant == null || claimant.equals(owner)) return requested;

        String qualified = "hybrid." + owner.namespace() + '.' + owner.path().replace('/', '.') + '.' + requested;
        NamespacedIdentifier qualifiedClaimant = claims.putIfAbsent(qualified, owner);
        if (qualifiedClaimant != null && !qualifiedClaimant.equals(owner)) {
            throw new IllegalStateException("Permission collision even after namespacing: " + qualified);
        }
        return qualified;
    }
}
