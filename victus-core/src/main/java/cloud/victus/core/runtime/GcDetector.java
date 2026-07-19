// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Detects the <b>active</b> garbage collector at runtime from the names exposed by
 * {@link ManagementFactory#getGarbageCollectorMXBeans()}, per
 * <a href="file:docs/phase-1/07-gc.md">docs/phase-1/07-gc.md</a> §2.
 *
 * <p>This is <b>read-only</b>. The collector is fixed by JVM launch flags and cannot be changed once
 * the JVM has started; this class never attempts to set a flag. It exists so the boot-time advisor
 * (see {@link GcAdvisor}) can warn on a mismatch with {@code optimizations.gc.profile}.
 *
 * <p>Classification is a pure function of the MXBean names, so it is unit-testable offline with mocked
 * name sets (the exact bean names have shifted across JDK releases — especially the Generational-ZGC
 * major/minor split — so the heuristics are substring-based and case-insensitive):
 * <ul>
 *   <li><b>Generational ZGC</b> → both a {@code "ZGC ... Major ..."} and a {@code "ZGC ... Minor ..."}
 *       bean (e.g. {@code "ZGC Major Cycles"} / {@code "ZGC Minor Cycles"}).</li>
 *   <li><b>Non-generational ZGC</b> → ZGC beans with no major/minor split
 *       (e.g. {@code "ZGC Cycles"} / {@code "ZGC Pauses"}).</li>
 *   <li><b>G1</b> → {@code "G1 Young Generation"} / {@code "G1 Old Generation"} (+ {@code "G1 Concurrent GC"}).</li>
 *   <li><b>Shenandoah</b> → {@code "Shenandoah Cycles"} / {@code "Shenandoah Pauses"}.</li>
 *   <li><b>Parallel</b> → {@code "PS Scavenge"} / {@code "PS MarkSweep"}.</li>
 *   <li><b>Serial</b> → {@code "Copy"} / {@code "MarkSweepCompact"}.</li>
 *   <li>anything else → {@link GcProfile#UNKNOWN} (fail-safe — the advisor stays silent).</li>
 * </ul>
 */
public final class GcDetector {

    private GcDetector() {
    }

    /** Classify the collector active in <em>this</em> JVM. Never throws; returns {@link GcProfile#UNKNOWN} if unsure. */
    public static GcProfile detect() {
        return classify(activeBeanNames());
    }

    /** The live {@link GarbageCollectorMXBean} names in this JVM, for diagnostics / {@code /victus doctor}. */
    public static List<String> activeBeanNames() {
        List<String> names = new ArrayList<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean != null && bean.getName() != null) {
                names.add(bean.getName());
            }
        }
        return List.copyOf(names);
    }

    /**
     * Classify a collector from a set of GC MXBean names. Pure and side-effect-free so the advisor
     * can be tested against every collector's bean set without launching a JVM under each one.
     *
     * @param beanNames the {@code GarbageCollectorMXBean} names (order-independent, case-insensitive)
     * @return the detected {@link GcProfile}, or {@link GcProfile#UNKNOWN} if none of the heuristics match
     */
    public static GcProfile classify(Collection<String> beanNames) {
        if (beanNames == null || beanNames.isEmpty()) {
            return GcProfile.UNKNOWN;
        }

        // ZGC first: it is the only collector whose beans carry "ZGC". The generational form additionally
        // splits into major/minor cycle/pause beans; the single-generation form does not.
        if (anyContains(beanNames, "ZGC")) {
            boolean major = anyContainsBoth(beanNames, "ZGC", "MAJOR");
            boolean minor = anyContainsBoth(beanNames, "ZGC", "MINOR");
            return (major && minor) ? GcProfile.ZGC_GENERATIONAL : GcProfile.ZGC;
        }
        if (anyContains(beanNames, "SHENANDOAH")) {
            return GcProfile.SHENANDOAH;
        }
        if (anyContains(beanNames, "G1")) {
            return GcProfile.G1;
        }
        // Parallel old is "PS MarkSweep"; check the "PS" prefix so it is not confused with Serial's
        // "MarkSweepCompact".
        if (anyContains(beanNames, "PS SCAVENGE") || anyContains(beanNames, "PS MARKSWEEP")
                || anyContains(beanNames, "PARALLEL")) {
            return GcProfile.PARALLEL;
        }
        if (anyContains(beanNames, "MARKSWEEPCOMPACT") || anyEquals(beanNames, "COPY")
                || anyContains(beanNames, "SERIAL")) {
            return GcProfile.SERIAL;
        }
        return GcProfile.UNKNOWN;
    }

    private static boolean anyContains(Collection<String> names, String upperNeedle) {
        for (String n : names) {
            if (n != null && n.toUpperCase(Locale.ROOT).contains(upperNeedle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyContainsBoth(Collection<String> names, String a, String b) {
        for (String n : names) {
            if (n == null) {
                continue;
            }
            String u = n.toUpperCase(Locale.ROOT);
            if (u.contains(a) && u.contains(b)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyEquals(Collection<String> names, String upper) {
        for (String n : names) {
            if (n != null && n.trim().toUpperCase(Locale.ROOT).equals(upper)) {
                return true;
            }
        }
        return false;
    }
}
