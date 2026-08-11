// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.command;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.Locale;
import java.util.Objects;

/** Loader-neutral command declaration. Qualified labels always use owner namespace plus command label. */
public record CommandDefinition(
        NamespacedIdentifier owner,
        String label,
        CommandOrigin origin,
        String permission
) {
    public CommandDefinition {
        Objects.requireNonNull(owner, "owner");
        label = normalizeLabel(label);
        Objects.requireNonNull(origin, "origin");
        permission = permission == null ? "" : permission.trim().toLowerCase(Locale.ROOT);
    }

    public String qualifiedLabel() {
        return owner.namespace() + ':' + label;
    }

    private static String normalizeLabel(String value) {
        Objects.requireNonNull(value, "label");
        String result = value.trim().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9._-]+")) throw new IllegalArgumentException("Invalid command label " + value);
        return result;
    }
}
