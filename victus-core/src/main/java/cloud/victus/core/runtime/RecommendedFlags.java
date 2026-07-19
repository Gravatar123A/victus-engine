// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces the exact recommended Generational-ZGC launch flags from
 * <a href="file:docs/phase-1/07-gc.md">docs/phase-1/07-gc.md</a> §1, plus heap-sizing helpers.
 *
 * <p>This is <b>advisory only</b>: victus-core never launches a JVM. These flags are consumed by the
 * launch layer — {@code scripts/dev-env.sh} (dev reference) and the Victus Wings start-script template
 * (production) — which is the only place that can pick a collector. This class exists so that flag set
 * is pinned in one testable place and cannot silently drift.
 *
 * <p>The rules encoded here (all from the spec):
 * <ul>
 *   <li>Collector: {@code -XX:+UseZGC -XX:+ZGenerational} (generational must be on for JDK 21/22).</li>
 *   <li>Latency: {@code -XX:+AlwaysPreTouch} (no first-touch page-fault stalls mid-tick) and
 *       {@code -XX:+PerfDisableSharedMem} (no {@code /tmp/hsperfdata} safepoint hiccups).</li>
 *   <li>Fixed heap: {@code -Xms == -Xmx} to avoid resize churn and pair with {@code AlwaysPreTouch}.</li>
 *   <li>Headroom: leave ~1&ndash;1.5&nbsp;GB of container RAM for OS + off-heap — see {@link #osReserveNote()}.</li>
 *   <li>Never overlay old G1/Aikar flags — the migration is a clean replacement, not an overlay.</li>
 * </ul>
 */
public final class RecommendedFlags {

    /**
     * The exact recommended Generational-ZGC flag list (collector + latency flags), in order.
     * {@code -XX:+UseStringDeduplication} and the {@code -Dusing.aikars.flags=false} marker ride the
     * same launch line but are owned elsewhere; see {@link #launchCommand(String, int, boolean)}.
     */
    public static final List<String> GC_FLAGS = List.of(
            "-XX:+UseZGC",
            "-XX:+ZGenerational",
            "-XX:+AlwaysPreTouch",
            "-XX:+PerfDisableSharedMem");

    /** Minimum OS + off-heap headroom to reserve outside {@code -Xmx} (spec: ~1&nbsp;GB). */
    public static final int OS_RESERVE_MIN_MB = 1024;
    /** Maximum / conservative OS + off-heap headroom to reserve outside {@code -Xmx} (spec: ~1.5&nbsp;GB). */
    public static final int OS_RESERVE_MAX_MB = 1536;
    /** Default headroom applied by {@link #recommendedHeapMb(int)} — the conservative 1.5&nbsp;GB end. */
    public static final int DEFAULT_OS_RESERVE_MB = OS_RESERVE_MAX_MB;

    private RecommendedFlags() {
    }

    /** The recommended Generational-ZGC GC/latency flags. Immutable. */
    public static List<String> gcFlags() {
        return GC_FLAGS;
    }

    /**
     * Heap-sizing flags for a fixed {@code heapMb}-megabyte heap, with {@code -Xms == -Xmx} per the spec.
     *
     * @param heapMb the heap size in MB (must be &gt; 0)
     * @return {@code ["-Xms<heapMb>M", "-Xmx<heapMb>M"]}
     */
    public static List<String> heapFlags(int heapMb) {
        if (heapMb <= 0) {
            throw new IllegalArgumentException("heapMb must be > 0, got " + heapMb);
        }
        String size = heapMb + "M";
        return List.of("-Xms" + size, "-Xmx" + size);
    }

    /**
     * Recommended {@code -Xmx} (== {@code -Xms}) for a container, using the default ~1.5&nbsp;GB OS reserve.
     *
     * @param containerRamMb the total RAM available to the instance/container, in MB
     * @return the recommended heap size in MB
     */
    public static int recommendedHeapMb(int containerRamMb) {
        return recommendedHeapMb(containerRamMb, DEFAULT_OS_RESERVE_MB);
    }

    /**
     * Recommended {@code -Xmx} (== {@code -Xms}) for a container, leaving {@code osReserveMb} for the OS
     * and off-heap memory. Under ZGC + {@code AlwaysPreTouch} the process commits the full {@code -Xmx}
     * up front, so sizing to the whole box risks native-OOM / the OOM-killer — see {@link #osReserveNote()}.
     *
     * @param containerRamMb the total RAM available to the instance/container, in MB (must be &gt; 0)
     * @param osReserveMb    RAM to leave for OS + off-heap, in MB (must be &ge; 0 and &lt; {@code containerRamMb})
     * @return the recommended heap size in MB ({@code containerRamMb - osReserveMb})
     */
    public static int recommendedHeapMb(int containerRamMb, int osReserveMb) {
        if (containerRamMb <= 0) {
            throw new IllegalArgumentException("containerRamMb must be > 0, got " + containerRamMb);
        }
        if (osReserveMb < 0) {
            throw new IllegalArgumentException("osReserveMb must be >= 0, got " + osReserveMb);
        }
        int heap = containerRamMb - osReserveMb;
        if (heap <= 0) {
            throw new IllegalArgumentException("container RAM " + containerRamMb + "MB minus OS reserve "
                    + osReserveMb + "MB leaves no heap; give the instance more RAM or lower the reserve");
        }
        return heap;
    }

    /** The operator-facing explanation of the OS/off-heap headroom rule. */
    public static String osReserveNote() {
        return "Leave ~1-1.5 GB (" + OS_RESERVE_MIN_MB + "-" + OS_RESERVE_MAX_MB + " MB) of container RAM "
                + "for the OS and off-heap memory (Metaspace, thread stacks, Netty direct buffers, and ZGC's "
                + "mapped/off-heap overhead). Size -Xmx at roughly (container RAM - 1 to 1.5 GB), never the "
                + "full box: under ZGC + AlwaysPreTouch the process commits the full -Xmx in RSS up front, so "
                + "an oversized heap risks native-OOM / the OS OOM-killer, which no GC flag can fix.";
    }

    /**
     * The full recommended launch command line for a Victus instance, matching the spec's example:
     * {@code java -Xms<N>M -Xmx<N>M} + {@link #GC_FLAGS} [+ {@code -XX:+UseStringDeduplication}]
     * {@code -Dusing.aikars.flags=false -jar <jar> --nogui}.
     *
     * @param jarName          the server jar (defaults to {@code victus-engine.jar} if null/blank)
     * @param heapMb           the fixed heap size in MB
     * @param includeStringDedup include {@code -XX:+UseStringDeduplication} (owned by the memory spec,
     *                           rides the same line)
     */
    public static List<String> launchCommand(String jarName, int heapMb, boolean includeStringDedup) {
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.addAll(heapFlags(heapMb));
        cmd.addAll(GC_FLAGS);
        if (includeStringDedup) {
            cmd.add("-XX:+UseStringDeduplication");
        }
        cmd.add("-Dusing.aikars.flags=false");
        cmd.add("-jar");
        cmd.add(jarName == null || jarName.isBlank() ? "victus-engine.jar" : jarName);
        cmd.add("--nogui");
        return List.copyOf(cmd);
    }
}
