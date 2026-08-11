// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.event;

import java.util.Map;
import java.util.Objects;

/** Bukkit-facing event description emitted by a translator and bound later by the Paper host. */
public record TranslatedEvent(String bukkitEventType, boolean cancellable, Map<String, String> properties) {
    public TranslatedEvent {
        Objects.requireNonNull(bukkitEventType, "bukkitEventType");
        if (bukkitEventType.isBlank()) throw new IllegalArgumentException("bukkitEventType must not be blank");
        properties = Map.copyOf(properties == null ? Map.of() : properties);
    }
}
