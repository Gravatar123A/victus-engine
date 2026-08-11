// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.event;

import java.util.Objects;
import java.util.Optional;

/** Result that cannot silently claim semantic equivalence. */
public record EventTranslationResult(SemanticSupport support, Optional<TranslatedEvent> translated, String detail) {
    public EventTranslationResult {
        Objects.requireNonNull(support, "support");
        translated = translated == null ? Optional.empty() : translated;
        detail = Objects.requireNonNull(detail, "detail").trim();
        if (detail.isEmpty()) throw new IllegalArgumentException("detail must not be empty");
        if (support == SemanticSupport.UNSUPPORTED && translated.isPresent()) {
            throw new IllegalArgumentException("unsupported translation cannot contain an event");
        }
        if (support != SemanticSupport.UNSUPPORTED && translated.isEmpty()) {
            throw new IllegalArgumentException("supported translation must contain an event");
        }
    }

    public static EventTranslationResult exact(TranslatedEvent event, String detail) {
        return new EventTranslationResult(SemanticSupport.EXACT, Optional.of(event), detail);
    }

    public static EventTranslationResult partial(TranslatedEvent event, String detail) {
        return new EventTranslationResult(SemanticSupport.PARTIAL, Optional.of(event), detail);
    }

    public static EventTranslationResult unsupported(String detail) {
        return new EventTranslationResult(SemanticSupport.UNSUPPORTED, Optional.empty(), detail);
    }
}
