// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.event;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;

/** A translator owns one loader-neutral event key and reports semantic fidelity for every input. */
public interface EventTranslator {
    NamespacedIdentifier eventType();

    EventTranslationResult translate(HybridEvent event);
}
