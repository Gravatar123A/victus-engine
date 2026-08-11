// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.event;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable dispatch table for explicit loader event translations. */
public final class EventTranslationRegistry {
    private final Map<NamespacedIdentifier, EventTranslator> translators;

    public EventTranslationRegistry(Collection<? extends EventTranslator> translators) {
        Objects.requireNonNull(translators, "translators");
        Map<NamespacedIdentifier, EventTranslator> indexed = new HashMap<>();
        for (EventTranslator translator : translators) {
            EventTranslator previous = indexed.putIfAbsent(translator.eventType(), translator);
            if (previous != null) throw new IllegalArgumentException("Duplicate event translator " + translator.eventType());
        }
        this.translators = Map.copyOf(indexed);
    }

    public EventTranslationResult translate(HybridEvent event) {
        Objects.requireNonNull(event, "event");
        EventTranslator translator = translators.get(event.type());
        return translator == null
                ? EventTranslationResult.unsupported("No translation contract for " + event.type())
                : translator.translate(event);
    }
}
