// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

import java.util.List;

/**
 * Dependency-free self-test (no JUnit needed, so it runs offline with just the JDK):
 * <pre>
 *   javac -d out src/main/java/cloud/victus/core/config/*.java src/main/java/cloud/victus/core/runtime/*.java
 *   java -cp out cloud.victus.core.runtime.RuntimeSelfTest
 * </pre>
 * Covers the flag builder + heap math, the collector detector (mocked bean sets + the live JVM), and
 * the mismatch / non-generational advisor (including the {@code auto} escape hatch and the UNKNOWN
 * fail-safe). Replaced by proper JUnit tests once the repo builds online.
 */
public final class RuntimeSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // ---- 1. RecommendedFlags: exact GC flag list, in order ----
        check("gc flags = exact Generational-ZGC set in order",
                RecommendedFlags.gcFlags().equals(List.of(
                        "-XX:+UseZGC", "-XX:+ZGenerational",
                        "-XX:+AlwaysPreTouch", "-XX:+PerfDisableSharedMem")));
        check("gc flags list is immutable", isImmutable(RecommendedFlags.gcFlags()));

        // ---- 2. Heap flags: -Xms == -Xmx, MB suffixed ----
        check("heapFlags(4096) -> -Xms4096M/-Xmx4096M",
                RecommendedFlags.heapFlags(4096).equals(List.of("-Xms4096M", "-Xmx4096M")));
        check("heapFlags rejects <= 0", throwsIAE(() -> RecommendedFlags.heapFlags(0)));

        // ---- 3. Heap-sizing math + OS reserve ----
        check("recommendedHeapMb(8192) leaves 1.5GB -> 6656",
                RecommendedFlags.recommendedHeapMb(8192) == 8192 - 1536);
        check("recommendedHeapMb(8192, 1024) -> 7168",
                RecommendedFlags.recommendedHeapMb(8192, 1024) == 7168);
        check("recommendedHeapMb rejects reserve >= container",
                throwsIAE(() -> RecommendedFlags.recommendedHeapMb(1024, 1536)));
        check("recommendedHeapMb rejects container <= 0",
                throwsIAE(() -> RecommendedFlags.recommendedHeapMb(0)));
        check("recommendedHeapMb rejects negative reserve",
                throwsIAE(() -> RecommendedFlags.recommendedHeapMb(4096, -1)));
        check("osReserveNote mentions the 1-1.5GB headroom rule",
                RecommendedFlags.osReserveNote().contains("1024-1536 MB")
                        && RecommendedFlags.osReserveNote().toLowerCase().contains("off-heap"));

        // ---- 4. Full launch command ----
        List<String> cmd = RecommendedFlags.launchCommand("victus-engine.jar", 6656, true);
        check("launchCommand starts with java + fixed heap",
                cmd.get(0).equals("java") && cmd.contains("-Xms6656M") && cmd.contains("-Xmx6656M"));
        check("launchCommand contains all GC flags", cmd.containsAll(RecommendedFlags.GC_FLAGS));
        check("launchCommand includes string dedup when asked", cmd.contains("-XX:+UseStringDeduplication"));
        check("launchCommand carries the aikars-flags=false marker",
                cmd.contains("-Dusing.aikars.flags=false"));
        check("launchCommand ends with -jar <jar> --nogui",
                cmd.get(cmd.size() - 3).equals("-jar")
                        && cmd.get(cmd.size() - 2).equals("victus-engine.jar")
                        && cmd.get(cmd.size() - 1).equals("--nogui"));
        check("launchCommand omits string dedup when not asked",
                !RecommendedFlags.launchCommand(null, 4096, false).contains("-XX:+UseStringDeduplication"));
        check("launchCommand defaults blank jar to victus-engine.jar",
                RecommendedFlags.launchCommand("  ", 4096, false).contains("victus-engine.jar"));

        // ---- 5. GcDetector.classify: mocked bean sets for every collector ----
        check("classify Generational ZGC",
                GcDetector.classify(List.of("ZGC Major Cycles", "ZGC Minor Cycles",
                        "ZGC Major Pauses", "ZGC Minor Pauses")) == GcProfile.ZGC_GENERATIONAL);
        check("classify non-generational ZGC",
                GcDetector.classify(List.of("ZGC Cycles", "ZGC Pauses")) == GcProfile.ZGC);
        check("classify G1",
                GcDetector.classify(List.of("G1 Young Generation", "G1 Old Generation",
                        "G1 Concurrent GC")) == GcProfile.G1);
        check("classify Shenandoah",
                GcDetector.classify(List.of("Shenandoah Cycles", "Shenandoah Pauses")) == GcProfile.SHENANDOAH);
        check("classify Parallel",
                GcDetector.classify(List.of("PS Scavenge", "PS MarkSweep")) == GcProfile.PARALLEL);
        check("classify Serial",
                GcDetector.classify(List.of("Copy", "MarkSweepCompact")) == GcProfile.SERIAL);
        check("classify Serial not mistaken for Parallel (MarkSweepCompact vs PS MarkSweep)",
                GcDetector.classify(List.of("Copy", "MarkSweepCompact")) != GcProfile.PARALLEL);
        check("classify unknown -> UNKNOWN",
                GcDetector.classify(List.of("Some Future Collector")) == GcProfile.UNKNOWN);
        check("classify empty -> UNKNOWN", GcDetector.classify(List.of()) == GcProfile.UNKNOWN);
        check("classify null -> UNKNOWN", GcDetector.classify(null) == GcProfile.UNKNOWN);
        check("classify is case-insensitive",
                GcDetector.classify(List.of("zgc major cycles", "zgc minor cycles")) == GcProfile.ZGC_GENERATIONAL);

        // ---- 6. Detector on the live test JVM must resolve to a real collector ----
        GcProfile live = GcDetector.detect();
        check("live JVM detector returns a non-UNKNOWN collector (was " + live
                + ", beans=" + GcDetector.activeBeanNames() + ")", live != GcProfile.UNKNOWN);

        // ---- 7. Mismatch advisor ----
        check("mismatch fires: G1 running, zgc-generational expected",
                nonNullContains(GcAdvisor.mismatchWarning(GcProfile.G1, "zgc-generational", true),
                        "mismatch"));
        check("mismatch silent when collector matches",
                GcAdvisor.mismatchWarning(GcProfile.ZGC_GENERATIONAL, "zgc-generational", true) == null);
        check("mismatch silent under profile: auto even if different",
                GcAdvisor.mismatchWarning(GcProfile.G1, "auto", true) == null);
        check("mismatch silent when warn-on-mismatch=false",
                GcAdvisor.mismatchWarning(GcProfile.G1, "zgc-generational", false) == null);
        check("mismatch silent (fail-safe) when detected UNKNOWN",
                GcAdvisor.mismatchWarning(GcProfile.UNKNOWN, "zgc-generational", true) == null);
        check("mismatch silent when configured profile null",
                GcAdvisor.mismatchWarning(GcProfile.G1, null, true) == null);
        check("mismatch names both expected and running collectors",
                nonNullContains(GcAdvisor.mismatchWarning(GcProfile.G1, "zgc-generational", true), "zgc-generational")
                        && GcAdvisor.mismatchWarning(GcProfile.G1, "zgc-generational", true).contains("g1"));

        // ---- 8. Non-generational advisor ----
        check("non-generational warning fires for plain ZGC",
                nonNullContains(GcAdvisor.nonGenerationalWarning(GcProfile.ZGC, true), "ZGenerational"));
        check("non-generational silent for Generational ZGC",
                GcAdvisor.nonGenerationalWarning(GcProfile.ZGC_GENERATIONAL, true) == null);
        check("non-generational silent for non-ZGC collector",
                GcAdvisor.nonGenerationalWarning(GcProfile.G1, true) == null);
        check("non-generational silent when flag off",
                GcAdvisor.nonGenerationalWarning(GcProfile.ZGC, false) == null);

        // ---- 9. advise() bundles both advisories ----
        GcAdvice both = GcAdvisor.advise(GcProfile.ZGC, "zgc-generational", true, true);
        check("advise: plain ZGC vs generational expected -> 2 warnings", both.warnings().size() == 2);
        check("advise: hasWarnings() true", both.hasWarnings());
        GcAdvice clean = GcAdvisor.advise(GcProfile.ZGC_GENERATIONAL, "zgc-generational", true, true);
        check("advise: correct setup -> 0 warnings", !clean.hasWarnings());
        check("advise: GcAdvice.warnings is immutable", isImmutable(both.warnings()));

        // ---- 10. GcProfile parsing + helpers ----
        check("fromConfig zgc-generational", GcProfile.fromConfig("ZGC-Generational") == GcProfile.ZGC_GENERATIONAL);
        check("fromConfig g1", GcProfile.fromConfig(" g1 ") == GcProfile.G1);
        check("fromConfig shenandoah", GcProfile.fromConfig("shenandoah") == GcProfile.SHENANDOAH);
        check("fromConfig zgc", GcProfile.fromConfig("zgc") == GcProfile.ZGC);
        check("fromConfig rejects unknown", throwsIAE(() -> GcProfile.fromConfig("banana")));
        check("fromConfig rejects null", throwsIAE(() -> GcProfile.fromConfig(null)));
        check("fromConfig rejects auto (it is a directive, not a collector)",
                throwsIAE(() -> GcProfile.fromConfig("auto")));
        check("isAuto true for auto/AUTO/ padded", GcProfile.isAuto("auto") && GcProfile.isAuto("  AUTO "));
        check("isAuto false for a collector", !GcProfile.isAuto("g1") && !GcProfile.isAuto(null));
        check("isZgc covers both ZGC forms",
                GcProfile.ZGC.isZgc() && GcProfile.ZGC_GENERATIONAL.isZgc() && !GcProfile.G1.isZgc());
        check("isGenerationalZgc only for generational",
                GcProfile.ZGC_GENERATIONAL.isGenerationalZgc() && !GcProfile.ZGC.isGenerationalZgc());
        check("configName round-trips through fromConfig",
                GcProfile.fromConfig(GcProfile.G1.configName()) == GcProfile.G1
                        && GcProfile.fromConfig(GcProfile.ZGC_GENERATIONAL.configName()) == GcProfile.ZGC_GENERATIONAL);
        check("UNKNOWN.configName is 'unknown'", GcProfile.UNKNOWN.configName().equals("unknown"));

        // ---- 11. Heap-aware GC policy (2026-07-20, measured: ZGC ~2x RAM of G1 at idle) ----
        List<String> small = RecommendedFlags.recommendedGcFlags(6144, false);   // 6GB shared/oversold
        check("small/shared heap -> G1 (not ZGC)",
                small.contains("-XX:+UseG1GC") && !small.contains("-XX:+UseZGC"));
        check("shared node -> NO AlwaysPreTouch (oversell-safe)", !small.contains("-XX:+AlwaysPreTouch"));
        check("any tier -> CompactObjectHeaders additive win",
                small.contains("-XX:+UseCompactObjectHeaders"));
        check("any tier -> StringDedup + native trim",
                small.contains("-XX:+UseStringDeduplication") && small.contains("-XX:TrimNativeHeapInterval=5000"));
        List<String> bigShared = RecommendedFlags.recommendedGcFlags(32768, false); // big but shared
        check("big but SHARED heap -> still G1 (ZGC only when dedicated)",
                bigShared.contains("-XX:+UseG1GC") && !bigShared.contains("-XX:+UseZGC"));
        List<String> bigDedicated = RecommendedFlags.recommendedGcFlags(32768, true); // big + dedicated
        check("big + DEDICATED heap -> Generational ZGC + pretouch",
                bigDedicated.contains("-XX:+UseZGC") && bigDedicated.contains("-XX:+ZGenerational")
                        && bigDedicated.contains("-XX:+AlwaysPreTouch"));
        check("recommendedGcFlags list immutable", isImmutable(small));

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("Live collector detected: " + live.displayName()
                + " -> advise(profile=zgc-generational): "
                + GcAdvisor.advise(live, "zgc-generational", true, true).warnings());
        if (failed > 0) System.exit(1);
    }

    // ---- tiny test harness ----

    interface Thrower { void run(); }

    static boolean throwsIAE(Thrower t) {
        try { t.run(); return false; }
        catch (IllegalArgumentException e) { return true; }
    }

    static boolean nonNullContains(String s, String needle) {
        return s != null && s.contains(needle);
    }

    static boolean isImmutable(List<String> list) {
        try { list.add("x"); return false; }
        catch (UnsupportedOperationException e) { return true; }
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }
}
