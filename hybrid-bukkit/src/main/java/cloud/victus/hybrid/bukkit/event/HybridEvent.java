// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.event;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.Map;
import java.util.Objects;

/** Loader-neutral event envelope. Payload values are stable scalar strings only. */
public record HybridEvent(NamespacedIdentifier type, boolean cancellable, Map<String, String> payload) {
    public HybridEvent {
        Objects.requireNonNull(type, "type");
        payload = Map.copyOf(payload == null ? Map.of() : payload);
    }
}
