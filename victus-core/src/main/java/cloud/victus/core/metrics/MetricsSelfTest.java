// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.metrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Dependency-free self-test for the metrics module (no JUnit — runs offline with just the JDK):
 * <pre>
 *   javac -d out src/main/java/cloud/victus/core/config/*.java src/main/java/cloud/victus/core/metrics/*.java
 *   java -cp out cloud.victus.core.metrics.MetricsSelfTest
 * </pre>
 * Covers gauges/summaries + labels, Prometheus exposition well-formedness, exact and reservoir-sampled
 * quantiles, the cardinality cap, escaping, value formatting, and the metric catalog. Mirrors the style
 * of {@code cloud.victus.core.config.ConfigSelfTest}.
 */
public final class MetricsSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        testGaugesAndExposition();
        testExactQuantiles();
        testQuantileEdges();
        testEmptySummaryExposition();
        testSummaryWithLabels();
        testReservoirEviction();
        testCardinalityGuard();
        testCardinalityViaRegistry();
        testTypeConflict();
        testLabelEscaping();
        testValueFormatting();
        testLabelCanonicalisation();
        testCatalog();
        testFullExpositionWellFormed();

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // 1. gauges (labelled + unlabelled) scrape to well-formed exposition
    private static void testGaugesAndExposition() {
        MetricRegistry reg = new MetricRegistry();
        MetricCatalog.registerDefaults(reg);
        reg.gauge(MetricCatalog.TPS).set(19.97);
        reg.gauge(MetricCatalog.PLAYERS).set(42L);
        reg.gauge(MetricCatalog.ENTITIES, Labels.of("world", "overworld", "type", "zombie")).set(120L);
        reg.gauge(MetricCatalog.ENTITIES, Labels.of("world", "overworld", "type", "cow")).set(30L);

        String body = PrometheusExposition.write(reg);
        check("gauge TYPE line present", body.contains("# TYPE victus_tps gauge"));
        check("gauge HELP line present", body.contains("# HELP victus_tps Server ticks per second."));
        check("unlabelled gauge value", body.contains("\nvictus_players 42\n"));
        check("fractional gauge value", body.contains("\nvictus_tps 19.97\n"));
        check("labelled gauge value (zombie)",
                body.contains("victus_entities{type=\"zombie\",world=\"overworld\"} 120"));
        check("labels are key-sorted (type before world)",
                PrometheusExposition.renderLabels(Labels.of("world", "overworld", "type", "zombie"))
                        .equals("{type=\"zombie\",world=\"overworld\"}"));
        check("described-but-unused metric is not emitted", !body.contains("victus_cgroup_cpu_quota"));
        check("gauge exposition is well-formed", isWellFormed(body));
    }

    // 2. exact quantiles when the whole stream fits the reservoir (1..100)
    private static void testExactQuantiles() {
        Summary s = new Summary(1024);
        for (int i = 1; i <= 100; i++) s.record(i);
        SummarySnapshot snap = s.snapshot();
        check("exact p50 == 50", snap.p50() == 50.0);
        check("exact p95 == 95", snap.p95() == 95.0);
        check("exact p99 == 99", snap.p99() == 99.0);
        check("exact max == 100", snap.max() == 100.0);
        check("count == 100", snap.count() == 100);
        check("sum == 5050", snap.sum() == 5050.0);
        check("retained == 100", snap.retained() == 100);
    }

    // 3. quantile boundary behaviour
    private static void testQuantileEdges() {
        Summary s = new Summary(1024);
        for (int i = 1; i <= 100; i++) s.record(i);
        SummarySnapshot snap = s.snapshot();
        check("quantile(1.0) == max", snap.quantile(1.0) == 100.0);
        check("quantile(0.0) == min", snap.quantile(0.0) == 1.0);
        check("quantile clamps above 1.0 to max", snap.quantile(2.0) == 100.0);

        SummarySnapshot empty = new Summary().snapshot();
        check("empty quantile is NaN", Double.isNaN(empty.quantile(0.5)));
        check("empty max is NaN", Double.isNaN(empty.max()));
        check("empty count is 0", empty.count() == 0);
    }

    // 4. an empty summary still emits _count 0, _sum 0 and NaN quantiles
    private static void testEmptySummaryExposition() {
        MetricRegistry reg = new MetricRegistry();
        reg.summary("victus_test_summary");
        String body = PrometheusExposition.write(reg);
        check("empty summary TYPE", body.contains("# TYPE victus_test_summary summary"));
        check("empty summary _count 0", body.contains("\nvictus_test_summary_count 0\n"));
        check("empty summary _sum 0", body.contains("\nvictus_test_summary_sum 0\n"));
        check("empty summary quantile is NaN",
                body.contains("victus_test_summary{quantile=\"0.5\"} NaN"));
        check("empty summary is well-formed", isWellFormed(body));
    }

    // 5. labelled summary emits quantile/_count/_sum with merged, sorted labels
    private static void testSummaryWithLabels() {
        MetricRegistry reg = new MetricRegistry();
        MetricCatalog.registerDefaults(reg);
        Summary sum = reg.summary(MetricCatalog.MSPT_BY_SUBSYSTEM,
                Labels.of(MetricCatalog.LABEL_SUBSYSTEM, "entities"));
        for (int i = 1; i <= 10; i++) sum.record(i);
        String body = PrometheusExposition.write(reg);

        check("summary quantile line with merged labels (quantile before subsystem)",
                body.contains("victus_mspt_by_subsystem{quantile=\"0.5\",subsystem=\"entities\"} 5"));
        check("summary _count with base labels only",
                body.contains("victus_mspt_by_subsystem_count{subsystem=\"entities\"} 10"));
        check("summary _sum with base labels only",
                body.contains("victus_mspt_by_subsystem_sum{subsystem=\"entities\"} 55"));

        long quantileLines = body.lines()
                .filter(l -> l.startsWith("victus_mspt_by_subsystem{"))
                .count();
        check("exactly 4 quantile lines (0.5/0.95/0.99/1.0)", quantileLines == 4);
        check("labelled summary is well-formed", isWellFormed(body));
    }

    // 6. bounded reservoir: eviction keeps count/sum/max exact, quantiles approximate
    private static void testReservoirEviction() {
        Summary s = new Summary(128, new Random(42));
        long trueSum = 0;
        for (int i = 1; i <= 10_000; i++) { s.record(i); trueSum += i; }
        SummarySnapshot snap = s.snapshot();
        check("reservoir retained is capped at capacity", snap.retained() == 128);
        check("count is exact after eviction", snap.count() == 10_000);
        check("sum is exact after eviction", snap.sum() == (double) trueSum);
        check("max is exact after eviction (reservoir-independent)", snap.max() == 10_000.0);
        double p50 = snap.p50();
        check("sampled p50 near true median 5000 (+/-2000)", Math.abs(p50 - 5000.0) < 2000.0);
    }

    // 7. cardinality guard caps distinct label-sets and folds the rest into an overflow bucket
    private static void testCardinalityGuard() {
        CardinalityGuard guard = new CardinalityGuard(3);
        String metric = "victus_entities";
        Labels a = Labels.of("world", "w", "type", "a");
        Labels b = Labels.of("world", "w", "type", "b");
        Labels c = Labels.of("world", "w", "type", "c");
        Labels d = Labels.of("world", "w", "type", "d");
        Labels e = Labels.of("world", "w", "type", "e");

        check("1st admitted as-is", guard.admit(metric, a) == a);
        check("2nd admitted as-is", guard.admit(metric, b) == b);
        check("3rd admitted as-is", guard.admit(metric, c) == c);
        Labels od = guard.admit(metric, d);
        check("4th folded to overflow (type)", "other".equals(od.get("type")));
        check("4th folded to overflow (world)", "other".equals(od.get("world")));
        Labels oe = guard.admit(metric, e);
        check("5th folds to the same overflow bucket", od.equals(oe));
        check("distinctCount bounded at limit+1", guard.distinctCount(metric) == 4);
        check("re-admitting an admitted set is stable", guard.admit(metric, a) == a);
        check("isOverflowing true for a fresh set past the cap",
                guard.isOverflowing(metric, Labels.of("world", "w", "type", "z")));
        check("isOverflowing false for an admitted set", !guard.isOverflowing(metric, a));

        CardinalityGuard unlimited = new CardinalityGuard(0);
        check("limit<=0 disables capping", unlimited.admit(metric, d) == d);
        check("empty labels never overflow",
                guard.admit(metric, Labels.EMPTY) == Labels.EMPTY);
    }

    // 8. the cap is enforced through the registry + shows up in exposition
    private static void testCardinalityViaRegistry() {
        MetricRegistry reg = new MetricRegistry(2);
        MetricCatalog.registerDefaults(reg);
        String[] types = {"zombie", "cow", "pig", "sheep", "creeper"};
        for (int i = 0; i < types.length; i++) {
            reg.gauge(MetricCatalog.ENTITIES, Labels.of("world", "overworld", "type", types[i])).set(i + 1);
        }
        String body = PrometheusExposition.write(reg);
        long entitySeries = body.lines().filter(l -> l.startsWith("victus_entities{")).count();
        check("registry caps entity series at limit+1 (2+overflow)", entitySeries == 3);
        check("overflow bucket present in exposition",
                body.contains("victus_entities{type=\"other\",world=\"other\"}"));
        check("capped exposition still well-formed", isWellFormed(body));
    }

    // 9. reusing a name with a different type is rejected
    private static void testTypeConflict() {
        MetricRegistry reg = new MetricRegistry();
        reg.gauge("victus_dual");
        check("gauge-then-summary conflict throws",
                throwsISE(() -> reg.summary("victus_dual")));
        MetricRegistry reg2 = new MetricRegistry();
        reg2.summary("victus_dual2");
        check("summary-then-gauge conflict throws",
                throwsISE(() -> reg2.gauge("victus_dual2")));
        check("invalid metric name rejected",
                throwsIAE(() -> reg.gauge("1bad-name")));
        check("invalid label name rejected",
                throwsIAE(() -> Labels.of("bad-label", "x")));
    }

    // 10. label values with special characters are escaped
    private static void testLabelEscaping() {
        MetricRegistry reg = new MetricRegistry();
        String raw = "a\"b\\c\nd"; // quote, backslash, newline
        reg.gauge("victus_escape", Labels.of("note", raw)).set(1);
        String body = PrometheusExposition.write(reg);
        check("label value is escaped", body.contains("victus_escape{note=\"a\\\"b\\\\c\\nd\"} 1"));
        check("escaped exposition is well-formed", isWellFormed(body));
        // direct escaper unit checks
        check("escapeLabelValue quote", PrometheusExposition.escapeLabelValue("x\"y").equals("x\\\"y"));
        check("escapeHelp keeps quotes literal", PrometheusExposition.escapeHelp("a\"b").equals("a\"b"));
    }

    // 11. numeric formatting: integral -> no ".0"; NaN/Inf tokens
    private static void testValueFormatting() {
        check("integral formats without decimal", PrometheusExposition.formatValue(42.0).equals("42"));
        check("large integral (heap bytes) stays plain",
                PrometheusExposition.formatValue(12_345_678_901.0).equals("12345678901"));
        check("fractional keeps decimal", PrometheusExposition.formatValue(3.5).equals("3.5"));
        check("negative integral", PrometheusExposition.formatValue(-7.0).equals("-7"));
        check("NaN token", PrometheusExposition.formatValue(Double.NaN).equals("NaN"));
        check("+Inf token", PrometheusExposition.formatValue(Double.POSITIVE_INFINITY).equals("+Inf"));
        check("-Inf token", PrometheusExposition.formatValue(Double.NEGATIVE_INFINITY).equals("-Inf"));
        check("gauge stores double bits exactly", roundTrip(6.02e23) && roundTrip(-0.0) && roundTrip(1.5));
    }

    // 12. Labels canonicalisation: order-independent identity + rendering
    private static void testLabelCanonicalisation() {
        Labels l1 = Labels.of("b", "2", "a", "1");
        Labels l2 = Labels.of("a", "1", "b", "2");
        check("Labels equal regardless of construction order", l1.equals(l2));
        check("Labels hashCode matches", l1.hashCode() == l2.hashCode());
        check("Labels render key-sorted", PrometheusExposition.renderLabels(l1).equals("{a=\"1\",b=\"2\"}"));
        check("empty Labels render empty", PrometheusExposition.renderLabels(Labels.EMPTY).isEmpty());
        check("Labels.with rejects duplicate key",
                throwsIAE(() -> Labels.of("quantile", "x").with("quantile", "y")));
        check("Labels.of(map) round-trips", Labels.of(Map.of("k", "v")).get("k").equals("v"));
    }

    // 13. catalog constants match the spec exactly
    private static void testCatalog() {
        check("victus_tps", MetricCatalog.TPS.equals("victus_tps"));
        check("victus_mspt", MetricCatalog.MSPT.equals("victus_mspt"));
        check("victus_mspt_by_subsystem", MetricCatalog.MSPT_BY_SUBSYSTEM.equals("victus_mspt_by_subsystem"));
        check("victus_entities", MetricCatalog.ENTITIES.equals("victus_entities"));
        check("victus_block_entities", MetricCatalog.BLOCK_ENTITIES.equals("victus_block_entities"));
        check("victus_chunks_loaded", MetricCatalog.CHUNKS_LOADED.equals("victus_chunks_loaded"));
        check("victus_players", MetricCatalog.PLAYERS.equals("victus_players"));
        check("victus_heap_bytes", MetricCatalog.HEAP_BYTES.equals("victus_heap_bytes"));
        check("victus_heap_max_bytes", MetricCatalog.HEAP_MAX_BYTES.equals("victus_heap_max_bytes"));
        check("victus_gc_pause_ms", MetricCatalog.GC_PAUSE_MS.equals("victus_gc_pause_ms"));
        check("victus_throttle_active", MetricCatalog.THROTTLE_ACTIVE.equals("victus_throttle_active"));
        check("victus_plugin_time_ms", MetricCatalog.PLUGIN_TIME_MS.equals("victus_plugin_time_ms"));
        check("victus_netloop_cpu_ratio", MetricCatalog.NETLOOP_CPU_RATIO.equals("victus_netloop_cpu_ratio"));
        check("victus_cgroup_cpu_quota", MetricCatalog.CGROUP_CPU_QUOTA.equals("victus_cgroup_cpu_quota"));
        check("victus_cgroup_mem_limit_bytes", MetricCatalog.CGROUP_MEM_LIMIT_BYTES.equals("victus_cgroup_mem_limit_bytes"));
        check("8 canonical subsystems", MetricCatalog.SUBSYSTEMS.length == 8);

        MetricRegistry reg = new MetricRegistry();
        MetricCatalog.registerDefaults(reg);
        check("registerDefaults sets gauge type", reg.typeOf(MetricCatalog.TPS) == MetricType.GAUGE);
        check("registerDefaults sets summary type", reg.typeOf(MetricCatalog.MSPT) == MetricType.SUMMARY);
        check("registerDefaults sets help", reg.helpFor(MetricCatalog.TPS) != null);
    }

    // 14. a fully-populated registry produces valid exposition
    private static void testFullExpositionWellFormed() {
        MetricRegistry reg = new MetricRegistry();
        MetricCatalog.registerDefaults(reg);
        reg.gauge(MetricCatalog.TPS).set(20.0);
        reg.gauge(MetricCatalog.PLAYERS).set(7);
        reg.gauge(MetricCatalog.HEAP_BYTES).set(4_200_000_000L);
        reg.gauge(MetricCatalog.CHUNKS_LOADED, Labels.of("world", "overworld")).set(841);
        reg.gauge(MetricCatalog.CHUNKS_LOADED, Labels.of("world", "nether")).set(120);
        for (int i = 1; i <= 50; i++) reg.summary(MetricCatalog.MSPT).record(i * 0.7);
        for (String subsystem : MetricCatalog.SUBSYSTEMS) {
            Summary s = reg.summary(MetricCatalog.MSPT_BY_SUBSYSTEM,
                    Labels.of(MetricCatalog.LABEL_SUBSYSTEM, subsystem));
            for (int i = 1; i <= 20; i++) s.record(i * 0.1);
        }
        reg.summary(MetricCatalog.GC_PAUSE_MS, Labels.of(MetricCatalog.LABEL_COLLECTOR, "G1 Young Generation")).record(3.2);

        String body = PrometheusExposition.write(reg);
        check("full exposition ends with newline", body.endsWith("\n"));
        check("full exposition is well-formed", isWellFormed(body));
        check("no duplicate series lines", noDuplicateSeries(body));
        check("output is byte-stable across scrapes", body.equals(PrometheusExposition.write(reg)));
    }

    // ---- exposition validity checker (a tiny Prometheus-ish parser) ----

    /**
     * Validates: each family has &le;1 HELP and exactly 1 TYPE; every sample belongs to a declared
     * family; every value parses; series names are valid identifiers.
     */
    private static boolean isWellFormed(String body) {
        Map<String, Integer> helpCount = new HashMap<>();
        Map<String, Integer> typeCount = new HashMap<>();
        Map<String, String> declaredType = new HashMap<>();
        List<String> sampleNames = new ArrayList<>();

        for (String line : body.split("\n", -1)) {
            if (line.isEmpty()) continue;
            if (line.startsWith("# HELP ")) {
                String rest = line.substring(7);
                String name = rest.contains(" ") ? rest.substring(0, rest.indexOf(' ')) : rest;
                helpCount.merge(name, 1, Integer::sum);
            } else if (line.startsWith("# TYPE ")) {
                String rest = line.substring(7);
                int sp = rest.indexOf(' ');
                if (sp < 0) return false;
                String name = rest.substring(0, sp);
                String type = rest.substring(sp + 1);
                if (!type.equals("gauge") && !type.equals("summary")) return false;
                typeCount.merge(name, 1, Integer::sum);
                declaredType.put(name, type);
            } else if (line.startsWith("#")) {
                // other comment — ignore
            } else {
                int sp = line.lastIndexOf(' ');
                if (sp < 0) return false;
                String series = line.substring(0, sp);
                String value = line.substring(sp + 1);
                if (!valueParses(value)) return false;
                String metricName = series.contains("{") ? series.substring(0, series.indexOf('{')) : series;
                if (!Labels.isValidName(metricName)) return false;
                sampleNames.add(metricName);
            }
        }
        // every family with a TYPE must have exactly one TYPE and at most one HELP
        for (String name : typeCount.keySet()) {
            if (typeCount.get(name) != 1) return false;
            if (helpCount.getOrDefault(name, 0) > 1) return false;
        }
        // every sample must map to a declared family (allowing _count/_sum on summaries)
        for (String sample : sampleNames) {
            if (familyOf(sample, declaredType) == null) return false;
        }
        return true;
    }

    private static String familyOf(String sampleName, Map<String, String> declaredType) {
        if (declaredType.containsKey(sampleName)) return sampleName;
        if (sampleName.endsWith("_count")) {
            String base = sampleName.substring(0, sampleName.length() - "_count".length());
            if ("summary".equals(declaredType.get(base))) return base;
        }
        if (sampleName.endsWith("_sum")) {
            String base = sampleName.substring(0, sampleName.length() - "_sum".length());
            if ("summary".equals(declaredType.get(base))) return base;
        }
        return null;
    }

    private static boolean valueParses(String v) {
        if (v.equals("NaN") || v.equals("+Inf") || v.equals("-Inf")) return true;
        try {
            Double.parseDouble(v);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static boolean noDuplicateSeries(String body) {
        Set<String> seen = new HashSet<>();
        for (String line : body.split("\n", -1)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            int sp = line.lastIndexOf(' ');
            String series = line.substring(0, sp);
            if (!seen.add(series)) return false;
        }
        return true;
    }

    private static boolean roundTrip(double v) {
        Gauge g = new Gauge();
        g.set(v);
        return Double.compare(g.get(), v) == 0;
    }

    // ---- tiny test harness (mirrors ConfigSelfTest) ----

    interface Thrower { void run(); }

    static boolean throwsIAE(Thrower t) {
        try { t.run(); return false; }
        catch (IllegalArgumentException e) { return true; }
    }

    static boolean throwsISE(Thrower t) {
        try { t.run(); return false; }
        catch (IllegalStateException e) { return true; }
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }
}
