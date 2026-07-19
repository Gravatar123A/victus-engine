// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Boot-time GC advisor per <a href="file:docs/phase-1/07-gc.md">docs/phase-1/07-gc.md</a> §2.
 *
 * <p>It is <b>informational only</b> — it reads the active collector and compares it against
 * {@code optimizations.gc.profile}, emitting at most one mismatch warning and at most one
 * "generational is off" warning. It <b>never</b> sets or changes a JVM flag (it cannot: the collector
 * is fixed by launch flags before the JVM starts), and it never blocks a boot. This mirrors the
 * {@code warn-if-no-jvm-string-dedup} advisor pattern in {@code 06-memory.md}.
 *
 * <p>Fail-safe rules baked in:
 * <ul>
 *   <li>{@code optimizations.gc.profile: auto} suppresses the mismatch warning entirely (accept
 *       whatever is running) — the escape hatch for operators deliberately running a non-default
 *       collector (e.g. G1 on a tiny/oversold plan).</li>
 *   <li>If the running collector cannot be classified ({@link GcProfile#UNKNOWN}), the advisor stays
 *       silent rather than raising a false alarm on an unexpected JDK — worst case is a missing
 *       warning, never a broken server.</li>
 * </ul>
 */
public final class GcAdvisor {

    private GcAdvisor() {
    }

    /**
     * Run the full boot check and bundle every advisory into a {@link GcAdvice}.
     *
     * @param detected             the collector actually running (typically {@link GcDetector#detect()})
     * @param configuredProfile    the raw {@code optimizations.gc.profile} value (may be {@code auto})
     * @param warnOnMismatch       {@code optimizations.gc.warn-on-mismatch}
     * @param warnIfNonGenerational {@code optimizations.gc.warn-if-non-generational}
     */
    public static GcAdvice advise(GcProfile detected, String configuredProfile,
                                  boolean warnOnMismatch, boolean warnIfNonGenerational) {
        List<String> warnings = new ArrayList<>();
        String mismatch = mismatchWarning(detected, configuredProfile, warnOnMismatch);
        if (mismatch != null) {
            warnings.add(mismatch);
        }
        String nonGen = nonGenerationalWarning(detected, warnIfNonGenerational);
        if (nonGen != null) {
            warnings.add(nonGen);
        }
        return new GcAdvice(detected, configuredProfile, warnings);
    }

    /**
     * The mismatch advisory: fires when the running collector differs from {@code optimizations.gc.profile}.
     *
     * @return the warning string, or {@code null} to stay silent (matching collector, {@code auto},
     *         {@code warn-on-mismatch: false}, or undetectable collector)
     */
    public static String mismatchWarning(GcProfile detected, String configuredProfile, boolean warnOnMismatch) {
        if (!warnOnMismatch) {
            return null;
        }
        if (configuredProfile == null || GcProfile.isAuto(configuredProfile)) {
            return null; // auto (or unset) accepts whatever is running
        }
        if (detected == null || detected == GcProfile.UNKNOWN) {
            return null; // fail-safe: never false-alarm on an unrecognised collector
        }
        GcProfile expected = GcProfile.fromConfig(configuredProfile);
        if (detected == expected) {
            return null; // silent when they match
        }
        return "[victus/gc] GC mismatch: optimizations.gc.profile expects " + expected.configName()
                + " (" + expected.displayName() + ") but the JVM is running " + detected.configName()
                + " (" + detected.displayName() + "). The collector is set by JVM LAUNCH FLAGS, not at "
                + "runtime - update the start script (scripts/dev-env.sh / the Victus Wings start-script "
                + "template) to the recommended Generational ZGC flags and restart. Set "
                + "optimizations.gc.profile: auto to silence this if the current collector is intentional.";
    }

    /**
     * The "generational is off" advisory: fires when ZGC is active but generational mode is off, which
     * quietly loses throughput on JDK 21/22 when {@code -XX:+ZGenerational} was omitted.
     *
     * @return the warning string, or {@code null} to stay silent (generational ZGC, a non-ZGC
     *         collector, or {@code warn-if-non-generational: false})
     */
    public static String nonGenerationalWarning(GcProfile detected, boolean warnIfNonGenerational) {
        if (!warnIfNonGenerational || detected != GcProfile.ZGC) {
            return null;
        }
        return "[victus/gc] ZGC is active but GENERATIONAL mode is OFF, leaving throughput on the table "
                + "vs Generational ZGC. Add -XX:+ZGenerational to the start flags (JDK 21/22; on JDK 23+ "
                + "generational is the default and the flag is unnecessary), then restart.";
    }
}
