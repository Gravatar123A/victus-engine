// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free self-test (no JUnit needed, so it runs offline with just the JDK):
 * <pre>
 *   javac -d out src/main/java/cloud/victus/core/doctor/*.java
 *   java -cp out cloud.victus.core.doctor.DoctorSelfTest
 * </pre>
 * Covers the subsystem model, tick breakdown ranking, nearest-rank MSPT quantiles, the remediation
 * catalog + reversibility invariant, the diagnosis pipeline (ENTITIES-dominant scenario), and both
 * renderers. Replaced by proper JUnit tests once the repo builds online.
 */
public final class DoctorSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        testSubsystem();
        testTickBreakdown();
        testMsptSummary();
        testCatalog();
        testRemediationInvariants();
        DiagnosisReport report = testDiagnosisPipeline();
        testRenderers(report);
        testDedupAndDominance();
        testEmptyBreakdown();

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---- 1. Subsystem ---------------------------------------------------------------------------

    private static void testSubsystem() {
        check("subsystem key is hyphenated", Subsystem.BLOCK_ENTITIES.key().equals("block-entities"));
        check("subsystem label is human", Subsystem.BLOCK_ENTITIES.label().equals("Block entities"));
        check("fromKey round-trips", Subsystem.fromKey("chunk-gen") == Subsystem.CHUNK_GEN);
        check("fromKey underscore-insensitive", Subsystem.fromKey("CHUNK_IO") == Subsystem.CHUNK_IO);
        check("fromKey unknown -> OTHER", Subsystem.fromKey("wormholes") == Subsystem.OTHER);
        check("fromKey null -> OTHER", Subsystem.fromKey(null) == Subsystem.OTHER);
    }

    // ---- 2. TickBreakdown -----------------------------------------------------------------------

    private static void testTickBreakdown() {
        TickBreakdown b = new TickBreakdown(entitiesDominant());
        check("total ms summed", eq(b.totalMs(), 40.0));
        check("pct computed", eq(b.pct(Subsystem.ENTITIES), 65.0));
        check("pct sums to ~100", eq(sumPct(b), 100.0));
        check("missing subsystem -> 0 ms", eq(b.ms(Subsystem.CHUNK_GEN), 0.0));
        check("ranked top is ENTITIES", b.ranked().get(0).subsystem() == Subsystem.ENTITIES);
        check("ranked descending", b.ranked().get(0).ms() >= b.ranked().get(1).ms());
        check("ranked only present subsystems", b.ranked().size() == 5);

        // negatives clamp to 0
        Map<Subsystem, Double> neg = new EnumMap<>(Subsystem.class);
        neg.put(Subsystem.ENTITIES, -5.0);
        neg.put(Subsystem.OTHER, 10.0);
        TickBreakdown nb = new TickBreakdown(neg);
        check("negative ms clamped to 0", eq(nb.ms(Subsystem.ENTITIES), 0.0));
        check("clamp keeps others correct", eq(nb.pct(Subsystem.OTHER), 100.0));

        // zero total -> no divide-by-zero
        TickBreakdown empty = new TickBreakdown(Map.of());
        check("empty total ms is 0", eq(empty.totalMs(), 0.0));
        check("empty pct is 0 (no NaN)", eq(empty.pct(Subsystem.ENTITIES), 0.0));
        check("empty ranked is empty", empty.ranked().isEmpty());
    }

    // ---- 3. MsptSummary -------------------------------------------------------------------------

    private static void testMsptSummary() {
        double[] samples = new double[100];
        for (int i = 0; i < 100; i++) samples[i] = i + 1; // 1..100
        MsptSummary m = MsptSummary.of(samples);
        check("p50 nearest-rank", eq(m.p50(), 50.0));
        check("p95 nearest-rank", eq(m.p95(), 95.0));
        check("p99 nearest-rank", eq(m.p99(), 99.0));
        check("max is largest", eq(m.max(), 100.0));
        check("mean correct", eq(m.mean(), 50.5));
        check("sample count", m.sampleCount() == 100);

        check("empty samples -> EMPTY", MsptSummary.of(new double[0]) == MsptSummary.EMPTY);
        check("null samples -> EMPTY", MsptSummary.of((double[]) null) == MsptSummary.EMPTY);

        MsptSummary single = MsptSummary.of(42.0);
        check("single sample all percentiles equal", eq(single.p50(), 42.0) && eq(single.p99(), 42.0));

        MsptSummary boxed = MsptSummary.of(List.of(10.0, 20.0, 30.0, 40.0));
        check("collection factory works", eq(boxed.max(), 40.0) && boxed.sampleCount() == 4);
    }

    // ---- 4. RemediationCatalog ------------------------------------------------------------------

    private static void testCatalog() {
        RemediationCatalog cat = RemediationCatalog.defaultCatalog();

        // every remediation reverts to a real catalog entry
        boolean allRevert = true;
        for (Remediation r : cat.all()) {
            if (cat.revertOf(r) == null || cat.get(r.revertId()) == null) allRevert = false;
        }
        check("every remediation has a resolvable revert", allRevert);

        List<Remediation> entityFixes = cat.forSubsystem(Subsystem.ENTITIES);
        check("ENTITIES suggests dab-on", containsId(entityFixes, "dab-on"));
        check("ENTITIES suggests per-player-spawns", containsId(entityFixes, "per-player-spawns"));
        check("ENTITIES suggests activation-range", containsId(entityFixes, "activation-range"));
        check("ENTITIES suggests async-tracker", containsId(entityFixes, "async-tracker"));
        check("REDSTONE suggests ac-redstone", containsId(cat.forSubsystem(Subsystem.REDSTONE), "ac-redstone"));
        check("NETWORK suggests compression-threshold",
                containsId(cat.forSubsystem(Subsystem.NETWORK), "compression-threshold"));
        check("PLUGINS has no config lever", cat.forSubsystem(Subsystem.PLUGINS).isEmpty());

        // pairs revert to each other
        check("dab-on reverts to dab-off", cat.revertOf(cat.get("dab-on")).id().equals("dab-off"));
        check("dab-off reverts to dab-on", cat.revertOf(cat.get("dab-off")).id().equals("dab-on"));
        check("ac-redstone reverts to vanilla-redstone",
                cat.revertOf(cat.get("ac-redstone")).id().equals("vanilla-redstone"));

        // writes go to the right file/key
        check("dab-on writes victus.yml key", cat.get("dab-on").writes().key().equals("optimizations.entities.dab"));
        check("dab-on writes true", Boolean.TRUE.equals(cat.get("dab-on").writes().value()));
        check("view-distance writes server.properties",
                cat.get("view-distance").writes().file().equals(ConfigPatch.SERVER_PROPERTIES));

        // catalog validation: duplicate id rejected
        check("duplicate remediation id rejected", throwsIAE(() -> new RemediationCatalog(
                List.of(cat.get("dab-on"), cat.get("dab-on")), Map.of())));
        // catalog validation: dangling revert rejected
        check("dangling revert rejected", throwsIAE(() -> new RemediationCatalog(
                List.of(new Remediation("x", "t", "g", "c", ConfigPatch.victus("k", 1), "nope")), Map.of())));
        // catalog validation: suggestion referencing unknown id rejected
        check("unknown suggestion id rejected", throwsIAE(() -> new RemediationCatalog(
                cat.all(), Map.of(Subsystem.ENTITIES, List.of("does-not-exist")))));
    }

    // ---- 5. Remediation invariants --------------------------------------------------------------

    private static void testRemediationInvariants() {
        check("null revertId rejected", throwsNpe(() ->
                new Remediation("a", "t", "g", "c", ConfigPatch.victus("k", 1), null)));
        check("self-revert rejected", throwsIAE(() ->
                new Remediation("a", "t", "g", "c", ConfigPatch.victus("k", 1), "a")));
        check("null patch value rejected", throwsNpe(() -> ConfigPatch.victus("k", null)));
    }

    // ---- 6. Diagnosis pipeline (ENTITIES dominant) ----------------------------------------------

    private static DiagnosisReport testDiagnosisPipeline() {
        TickBreakdown breakdown = new TickBreakdown(entitiesDominant());
        RemediationCatalog cat = RemediationCatalog.defaultCatalog();

        double[] samples = {40, 45, 50, 52, 60, 72};
        DiagnosisReport report = new DiagnosisBuilder(cat)
                .window(Duration.ofSeconds(60))
                .tps(18.42)
                .mspt(MsptSummary.of(samples))
                .breakdown(breakdown)
                .addOffender(Subsystem.ENTITIES,
                        new Offender("minecraft:zombie", "9412 in chunk 47,-12 (world_nether)", "FarmPlugin"))
                .addOffender(Subsystem.ENTITIES,
                        Offender.of("minecraft:villager", "1204 in chunk 12,8 (world)"))
                .build();

        check("top offender is ENTITIES", report.topOffender().subsystem() == Subsystem.ENTITIES);
        check("top offender flagged dominant", report.topOffender().dominant());
        check("offenders drilled onto ENTITIES", report.topOffender().offenders().size() == 2);
        check("offender attribution preserved",
                report.topOffender().offenders().get(0).owningPlugin().equals("FarmPlugin"));

        List<Remediation> rem = report.remediations();
        check("suggests dab", containsId(rem, "dab-on"));
        check("suggests per-player-spawns", containsId(rem, "per-player-spawns"));
        check("suggests activation-range", containsId(rem, "activation-range"));

        // REDSTONE at 20% is also dominant (>= 15% default) -> its fix surfaces too
        check("REDSTONE also dominant -> ac-redstone suggested", containsId(rem, "ac-redstone"));

        // every suggested remediation is reversible
        boolean allReversible = true;
        for (Remediation r : rem) {
            if (cat.revertOf(r) == null) allReversible = false;
        }
        check("every suggested remediation has a revert", allReversible);

        // aggregated list has no duplicate ids
        check("no duplicate remediation ids", rem.size() == distinctIds(rem));
        return report;
    }

    // ---- 7. Renderers ---------------------------------------------------------------------------

    private static void testRenderers(DiagnosisReport report) {
        String text = DoctorRenderer.renderText(report);
        check("text mentions Entities", text.contains("Entities"));
        check("text marks dominant", text.contains("[DOMINANT]"));
        check("text lists dab-on", text.contains("[dab-on]"));
        check("text shows revert", text.contains("revert:"));
        check("text shows offender owner", text.contains("owner: FarmPlugin"));
        check("text shows illustrative disclaimer", text.contains("illustrative"));

        String json = DoctorRenderer.renderJson(report);
        check("json is an object", json.startsWith("{") && json.endsWith("}"));
        check("json has window", json.contains("\"window\":60"));
        check("json has tps", json.contains("\"tps\":18.42"));
        check("json has mspt.p95", json.contains("\"p95\""));
        check("json has subsystems", json.contains("\"subsystems\""));
        check("json names entities subsystem", json.contains("\"name\":\"entities\""));
        check("json has detail offenders", json.contains("\"key\":\"minecraft:zombie\""));
        check("json has owningPlugin", json.contains("\"owningPlugin\":\"FarmPlugin\""));
        check("json has null owner for unattributed", json.contains("\"owningPlugin\":null"));
        check("json has remediations", json.contains("\"remediations\""));
        check("json has dab-on id", json.contains("\"id\":\"dab-on\""));
        check("json has revertId", json.contains("\"revertId\":\"dab-off\""));
        check("json has writes patch", json.contains("\"key\":\"optimizations.entities.dab\""));
    }

    // ---- 8. Dedup + dominance threshold ---------------------------------------------------------

    private static void testDedupAndDominance() {
        // CHUNK_GEN and CHUNK_IO both dominant, both suggest view-distance + sim-distance -> dedup
        Map<Subsystem, Double> m = new EnumMap<>(Subsystem.class);
        m.put(Subsystem.CHUNK_GEN, 10.0);
        m.put(Subsystem.CHUNK_IO, 10.0);
        DiagnosisReport r = new DiagnosisBuilder(RemediationCatalog.defaultCatalog())
                .breakdown(new TickBreakdown(m))
                .build();
        long viewCount = r.remediations().stream().filter(x -> x.id().equals("view-distance")).count();
        check("shared remediation de-duplicated", viewCount == 1);

        // With a high threshold, only the #1 subsystem is dominant; a lesser one gets no fixes.
        DiagnosisReport strict = new DiagnosisBuilder(RemediationCatalog.defaultCatalog())
                .breakdown(new TickBreakdown(entitiesDominant()))
                .dominanceThresholdPct(90.0)
                .build();
        check("top forced dominant below threshold", strict.topOffender().dominant());
        check("non-top below threshold not dominant",
                strict.subsystems().stream().anyMatch(s -> s.subsystem() == Subsystem.REDSTONE && !s.dominant()));
        check("strict aggregate excludes non-dominant fix", !containsId(strict.remediations(), "ac-redstone"));
        check("strict aggregate still has entities fix", containsId(strict.remediations(), "dab-on"));

        // maxEntries truncates offenders
        DiagnosisBuilder db = new DiagnosisBuilder(RemediationCatalog.defaultCatalog())
                .breakdown(new TickBreakdown(entitiesDominant()))
                .maxEntries(3);
        for (int i = 0; i < 5; i++) db.addOffender(Subsystem.ENTITIES, Offender.of("mob" + i, "x"));
        DiagnosisReport trunc = db.build();
        check("offenders truncated to maxEntries", trunc.topOffender().offenders().size() == 3);
    }

    // ---- 9. Empty breakdown renders cleanly -----------------------------------------------------

    private static void testEmptyBreakdown() {
        DiagnosisReport r = new DiagnosisBuilder(RemediationCatalog.defaultCatalog())
                .breakdown(new TickBreakdown(Map.of()))
                .build();
        check("empty report has no subsystems", r.subsystems().isEmpty());
        check("empty report has no remediations", r.remediations().isEmpty());
        check("empty report topOffender null", r.topOffender() == null);
        String text = DoctorRenderer.renderText(r);
        check("empty text notes no timing data", text.contains("no timing data"));
        String json = DoctorRenderer.renderJson(r);
        check("empty json still valid object", json.startsWith("{") && json.endsWith("}")
                && json.contains("\"subsystems\":[]"));
    }

    // ---- fixtures + tiny harness ----------------------------------------------------------------

    /** ENTITIES 65% (26 ms), REDSTONE 20% (8), BLOCK_ENTITIES 7.5% (3), NETWORK 5% (2), OTHER 2.5% (1). */
    private static Map<Subsystem, Double> entitiesDominant() {
        Map<Subsystem, Double> m = new EnumMap<>(Subsystem.class);
        m.put(Subsystem.ENTITIES, 26.0);
        m.put(Subsystem.REDSTONE, 8.0);
        m.put(Subsystem.BLOCK_ENTITIES, 3.0);
        m.put(Subsystem.NETWORK, 2.0);
        m.put(Subsystem.OTHER, 1.0);
        return m;
    }

    private static double sumPct(TickBreakdown b) {
        double s = 0;
        for (TickBreakdown.Slice sl : b.ranked()) s += sl.pct();
        return s;
    }

    private static boolean containsId(List<Remediation> list, String id) {
        for (Remediation r : list) if (r.id().equals(id)) return true;
        return false;
    }

    private static int distinctIds(List<Remediation> list) {
        List<String> seen = new ArrayList<>();
        for (Remediation r : list) if (!seen.contains(r.id())) seen.add(r.id());
        return seen.size();
    }

    private static boolean eq(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private interface Thrower { void run(); }

    private static boolean throwsIAE(Thrower t) {
        try { t.run(); return false; }
        catch (IllegalArgumentException e) { return true; }
    }

    private static boolean throwsNpe(Thrower t) {
        try { t.run(); return false; }
        catch (NullPointerException e) { return true; }
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }
}
