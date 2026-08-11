// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.api;

import cloud.victus.hybrid.bukkit.event.EventTranslationResult;
import cloud.victus.hybrid.bukkit.event.HybridEvent;

/**
 * Extension point for mod events that cannot be represented by an existing Bukkit/Paper event.
 * Translation results always state whether semantics are exact, partial, or unsupported.
 */
public interface HybridEventView {
    EventTranslationResult translate(HybridEvent event);
}
