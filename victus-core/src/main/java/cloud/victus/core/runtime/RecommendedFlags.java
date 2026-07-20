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

    // === Heap-aware GC policy (2026-07-20) — MEASURED on DE-1, not assumed ==================
    // A/B on the node (elastic heap, PSS via smaps_rollup): a near-idle server used
    //   Generational ZGC = ~1.96 GB PSS   vs   G1 = ~1.03 GB PSS   (~48% LESS on G1).
    // ZGC's colored-pointer multi-mapping roughly DOUBLES the footprint at idle on small/oversold
    // nodes and needs spare cores the single tick thread doesn't have. Every well-tuned Paper peer
    // on the box runs G1+Aikar — that is why Victus (the one ZGC process) looked "heavier than Paper".
    // Policy: G1+Aikar is the DEFAULT; Generational ZGC only pays off on big, dedicated, many-core
    // heaps where its pause-time win matters more than its RAM/CPU cost.
    /** Heap size (MB) at/above which Generational ZGC may be worth its overhead — and only if dedicated. */
    public static final int ZGC_MIN_HEAP_MB = 16384;

    /** G1 + Aikar tuning — the default collector for typical (&lt;16 GB / shared / oversold) instances. */
    public static final List<String> G1_AIKAR_FLAGS = List.of(
            "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions", "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200", "-XX:+DisableExplicitGC",
            "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M",
            "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32",
            "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
            // Periodic idle GC → returns committed heap to the OS on oversold/shared nodes (lower idle RAM).
            "-XX:G1PeriodicGCInterval=180000", "-XX:-G1PeriodicGCInvokesConcurrent",
            "-XX:G1PeriodicGCSystemLoadThreshold=0");

    /**
     * Universal additive wins layered on ANY collector (measured / spec'd, not marketing):
     * <ul>
     *   <li>{@code -XX:+UseCompactObjectHeaders} (JEP 519, JDK 25) — ~15-20% smaller object footprint;
     *       something Paper's source cannot do. Measured ~38% fewer heap bytes for the same boot.</li>
     *   <li>{@code -XX:+UseStringDeduplication} — a few % heap.</li>
     *   <li>{@code -XX:TrimNativeHeapInterval=5000} — returns freed native/Netty arenas to the OS.</li>
     * </ul>
     */
    public static final List<String> ADDITIVE_FLAGS = List.of(
            "-XX:+UseCompactObjectHeaders",
            "-XX:+UseStringDeduplication",
            "-XX:TrimNativeHeapInterval=5000");

    /**
     * The recommended GC/latency flags for a given heap size and node type — the heap-aware policy.
     * G1+Aikar by default; Generational ZGC only for large ({@code >= ZGC_MIN_HEAP_MB}) dedicated heaps.
     * {@code AlwaysPreTouch} is included ONLY for dedicated nodes (on oversold/shared nodes it pins RSS
     * at boot and defeats the oversell model — a measured regression).
     *
     * @param heapMb    the heap size in MB
     * @param dedicated true for a dedicated box (may pretouch / use ZGC on big heaps); false = shared/oversold
     */
    public static List<String> recommendedGcFlags(int heapMb, boolean dedicated) {
        List<String> flags = new ArrayList<>();
        if (dedicated && heapMb >= ZGC_MIN_HEAP_MB) {
            flags.add("-XX:+UseZGC");
            flags.add("-XX:+ZGenerational");
            flags.add("-XX:+PerfDisableSharedMem");
        } else {
            flags.addAll(G1_AIKAR_FLAGS);
        }
        if (dedicated) {
            flags.add("-XX:+AlwaysPreTouch"); // only pretouch on dedicated boxes (never oversold/shared)
        }
        flags.addAll(ADDITIVE_FLAGS);
        return List.copyOf(flags);
    }

    /** Minimum OS + off-heap headroom to reserve outside {@code -Xmx} (spec: ~1&nbsp;GB). */
    public static final int OS_RESERVE_MIN_MB = 1024;
    /** Maximum / conservative OS + off-heap headroom to reserve outside {@code -Xmx} (spec: ~1.5&nbsp;GB). */
    public static final int OS_RESERVE_MAX_MB = 1536;
    /** Default headroom applied by {@link #recommendedHeapMb(int)} — the conservative 1.5&nbsp;GB end. */
    public static final int DEFAULT_OS_RESERVE_MB = OS_RESERVE_MAX_MB;

    private RecommendedFlags() {
    }

    // === Moonrise chunk-system thread advisory (2026-07-20) ================================
    // Paper's Moonrise DELIBERATELY throttles chunk-gen/IO threads for shared hosting
    // (MoonriseCommon.adjustWorkerThreads): default worker threads = totalCores/2, then HALVED
    // again (→ ~cores/4), and on <=6-core boxes it uses just ONE worker thread. Great for
    // packed shared nodes; a big, needless cap on a DEDICATED box. Uncapping it is the roadmap's
    // top chunk-loading win (Q5) — but ONLY on dedicated hardware: raising it on a shared/oversold
    // node steals cores from co-located servers, so the default recommendation is "leave it".
    // Apply via config/paper-global.yml: chunk-system.worker-threads / io-threads (or the JVM
    // system property "<brand>.WorkerThreadCount"). -1 = Moonrise's conservative default.

    /**
     * Recommended {@code chunk-system.worker-threads} for a node. Returns {@code -1} (= keep Moonrise's
     * safe shared-hosting default) UNLESS this is a dedicated box, in which case it sizes to leave the
     * main tick thread + GC headroom while devoting the rest to parallel chunk gen.
     *
     * @param cores     physical cores available to this instance
     * @param dedicated true only for a dedicated (not shared/oversold) node
     * @return the value for {@code chunk-system.worker-threads}, or {@code -1} to keep the default
     */
    public static int recommendedChunkWorkerThreads(int cores, boolean dedicated) {
        if (!dedicated || cores <= 6) {
            return -1; // shared/oversold or tiny box → never steal cores from neighbours; keep default
        }
        // dedicated: use most cores for chunk gen but reserve ~2 for the main tick + GC.
        return Math.max(4, cores - 2);
    }

    /** Recommended {@code chunk-system.io-threads} (region file read/write). 1 is plenty unless on fast NVMe + dedicated. */
    public static int recommendedChunkIoThreads(boolean dedicated, boolean nvme) {
        return dedicated && nvme ? 3 : -1; // -1 = Moonrise default (1)
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
