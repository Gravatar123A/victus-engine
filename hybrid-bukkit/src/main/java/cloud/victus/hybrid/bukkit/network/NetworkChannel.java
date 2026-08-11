// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.network;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.Objects;

public record NetworkChannel(NamespacedIdentifier id, String version, boolean required) {
    public NetworkChannel {
        Objects.requireNonNull(id, "id");
        version = Objects.requireNonNull(version, "version").trim();
        if (version.isEmpty()) throw new IllegalArgumentException("version must not be empty");
    }
}
