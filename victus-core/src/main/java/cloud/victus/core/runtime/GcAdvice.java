// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

import java.util.List;

/**
 * The immutable result of a boot-time GC check (see {@link GcAdvisor}): what collector is actually
 * running, what {@code optimizations.gc.profile} expected, and any one-shot warnings to surface at
 * boot and in {@code /victus doctor}.
 *
 * <p>An empty {@link #warnings()} list means "all good, stay silent" — the advisor is informational
 * only and never blocks a boot.
 *
 * @param detected           the collector actually running (from {@link GcDetector})
 * @param configuredProfile  the raw {@code optimizations.gc.profile} value (may be {@code auto})
 * @param warnings           zero or more operator-facing advisories (defensively copied)
 */
public record GcAdvice(GcProfile detected, String configuredProfile, List<String> warnings) {

    public GcAdvice {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** {@code true} if the advisor produced at least one warning. */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
