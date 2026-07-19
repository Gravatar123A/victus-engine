// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.limits;

import cloud.victus.core.config.ConfigResolver;
import cloud.victus.core.config.ResolvedConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dependency-free self-test for the per-instance throttle ladder (no JUnit — runs offline with the JDK):
 * <pre>
 *   javac -d out src/main/java/cloud/victus/core/config/*.java src/main/java/cloud/victus/core/limits/*.java
 *   java -cp out cloud.victus.core.limits.LimitsSelfTest
 * </pre>
 * Drives synthetic MSPT time-series to prove: it climbs one rung per sustained breach (never instant),
 * descends after sustained recovery, does NOT flap near the threshold (hysteresis), and stays inert
 * when disabled. Replaced by proper JUnit tests once the repo builds online.
 */
public final class LimitsSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        rollingMsptTests();
        configTests();
        rungTests();
        disabledTests();
        escalationTests();
        recoveryTests();
        noFlapTests();
        eventTests();

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---------- RollingMspt ----------

    private static void rollingMsptTests() {
        RollingMspt r = new RollingMspt(3);
        check("rolling empty average = 0", r.average() == 0.0);
        check("rolling empty size = 0", r.size() == 0 && r.isEmpty());
        r.record(10);
        r.record(20);
        r.record(30);
        check("rolling avg (10,20,30) = 20", r.average() == 20.0);
        check("rolling full after 3", r.isFull() && r.size() == 3);
        check("rolling latest = 30", r.latest() == 30.0);
        check("rolling max = 30", r.max() == 30.0);
        r.record(40); // evicts 10 -> window (20,30,40)
        check("rolling evicts oldest, avg = 30", r.average() == 30.0);
        check("rolling max after evict = 40", r.max() == 40.0);
        check("rolling size stays capped at 3", r.size() == 3);

        RollingMspt p = new RollingMspt(5);
        for (double v : new double[]{10, 20, 30, 40, 50}) p.record(v);
        check("rolling p95 nearest-rank = 50", p.percentile(0.95) == 50.0);
        check("rolling p50 nearest-rank = 30", p.percentile(0.50) == 30.0);
        check("rolling p0 clamps to min = 10", p.percentile(0.0) == 10.0);

        r.reset();
        check("rolling reset -> empty", r.isEmpty() && r.average() == 0.0);
        check("rolling ctor rejects window < 1", throwsIAE(() -> new RollingMspt(0)));
    }

    // ---------- ThrottleConfig ----------

    private static void configTests() {
        // Reads ResolvedConfig.maxMspt for the budget default (default victus.yml -> 45).
        ResolvedConfig def = new ConfigResolver(java.util.Map.of()).resolve(null);
        ThrottleConfig fromDef = ThrottleConfig.fromResolved(def);
        check("fromResolved reads maxMspt default (45)", fromDef.maxMspt() == 45.0);
        check("fromResolved enabled at budget 45", fromDef.enabled());
        check("fromResolved default relaxThreshold = 45*0.9", fromDef.relaxThreshold() == 40.5);
        check("fromResolved default ladder = 4 rungs", fromDef.maxLevel() == 4);

        check("fromResolved(null) disabled", !ThrottleConfig.fromResolved(null).enabled());

        // maxMspt <= 0 disables (matches ConfigResolver's warning).
        check("maxMspt=0 disabled", !ThrottleConfig.builder().maxMspt(0).build().enabled());
        check("maxMspt<0 disabled", !ThrottleConfig.builder().maxMspt(-5).build().enabled());

        // seconds -> ticks conversion (20 tps).
        ThrottleConfig sec = ThrottleConfig.builder()
                .windowSeconds(10).hysteresisSeconds(15).build();
        check("windowSeconds(10) -> 200 ticks", sec.windowTicks() == 200);
        check("hysteresisSeconds(15) -> 300 ticks", sec.breachTicks() == 300 && sec.recoveryTicks() == 300);

        // validation
        check("windowTicks 0 rejected", throwsIAE(() -> ThrottleConfig.builder().windowTicks(0).build()));
        check("breachTicks 0 rejected", throwsIAE(() -> ThrottleConfig.builder().breachTicks(0).build()));
        check("recoveryTicks 0 rejected", throwsIAE(() -> ThrottleConfig.builder().recoveryTicks(0).build()));
        check("relaxFactor > 1 rejected", throwsIAE(() -> ThrottleConfig.builder().relaxFactor(1.5).build()));
        check("relaxThreshold above budget rejected",
                throwsIAE(() -> ThrottleConfig.builder().maxMspt(45).relaxThreshold(50).build()));

        // empty ladder -> nothing to climb -> disabled even with a budget.
        check("empty ladder disables", !ThrottleConfig.builder().maxMspt(45).ladder(List.of()).build().enabled());
    }

    // ---------- ThrottleRung ----------

    private static void rungTests() {
        check("fromConfig token", ThrottleRung.fromConfig("reduce-ai-radius") == ThrottleRung.REDUCE_AI_RADIUS);
        check("fromConfig case/underscore", ThrottleRung.fromConfig("Throttle_Chunk_Gen") == ThrottleRung.THROTTLE_CHUNK_GEN);
        check("fromConfig unknown rejected", throwsIAE(() -> ThrottleRung.fromConfig("melt-the-cpu")));
        check("fromConfig null rejected", throwsIAE(() -> ThrottleRung.fromConfig(null)));

        check("default ladder = 4 rungs cheapest-first",
                ThrottleRung.ladderFromConfig(null).equals(ThrottleRung.defaultLadder()));
        check("ladderFromConfig empty -> default",
                ThrottleRung.ladderFromConfig(List.of()).equals(ThrottleRung.defaultLadder()));

        List<ThrottleRung> reordered = ThrottleRung.ladderFromConfig(List.of("tighten-mob-caps", "reduce-ai-radius"));
        check("ladderFromConfig preserves order",
                reordered.equals(List.of(ThrottleRung.TIGHTEN_MOB_CAPS, ThrottleRung.REDUCE_AI_RADIUS)));
        check("ladderFromConfig rejects duplicates",
                throwsIAE(() -> ThrottleRung.ladderFromConfig(List.of("reduce-ai-radius", "reduce-ai-radius"))));

        check("subsystem label present", ThrottleRung.REDUCE_AI_RADIUS.subsystem().equals("mob-ai"));
    }

    // ---------- disabled ----------

    private static void disabledTests() {
        ThrottleController c = new ThrottleController(ThrottleConfig.builder().maxMspt(0).build());
        check("disabled controller not enabled", !c.isEnabled());
        boolean anyEvent = false;
        for (int i = 0; i < 100; i++) anyEvent |= c.tick(9999).isPresent(); // absurd overload
        check("disabled: no events despite huge MSPT", !anyEvent);
        check("disabled: stays at level 0", c.level() == 0);
        check("disabled: lastAction HOLD", c.lastAction() == ThrottleAction.HOLD);
    }

    // ---------- escalation ----------

    private static void escalationTests() {
        // window=1 (no smoothing) so avg == mspt; breach=3 sustained ticks per rung.
        ThrottleConfig cfg = ThrottleConfig.builder()
                .maxMspt(45).windowTicks(1).hysteresisTicks(3).relaxThreshold(40).build();
        ThrottleController c = new ThrottleController(cfg);

        // Sustained overload: expect exactly one rung per 3 ticks, never a jump.
        int[] levelAfter = new int[12];
        int escalateEvents = 0;
        int maxJump = 0, prev = 0;
        for (int i = 0; i < 12; i++) {
            Optional<ThrottleEvent> e = c.tick(60);
            if (e.isPresent() && e.get().action() == ThrottleAction.ESCALATE) escalateEvents++;
            levelAfter[i] = c.level();
            maxJump = Math.max(maxJump, c.level() - prev);
            prev = c.level();
        }
        int[] expected = {0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4};
        check("escalates NOT instantly (still 0 after 2 breach ticks)", levelAfter[1] == 0);
        check("escalates one rung after 3 sustained breach ticks", levelAfter[2] == 1);
        check("escalation climbs one rung per sustained breach", java.util.Arrays.equals(levelAfter, expected));
        check("escalation never jumps >1 rung/tick", maxJump == 1);
        check("escalation emitted 4 ESCALATE events", escalateEvents == 4);

        // capped at top rung
        boolean anyMore = false;
        for (int i = 0; i < 20; i++) anyMore |= c.tick(500).isPresent();
        check("escalation caps at maxLevel", c.level() == 4 && c.level() == cfg.maxLevel());
        check("escalation emits nothing once capped", !anyMore);

        // exactly-at-budget never escalates (strict > required, and inside dead band)
        ThrottleController atBudget = new ThrottleController(
                ThrottleConfig.builder().maxMspt(45).windowTicks(1).hysteresisTicks(3).relaxThreshold(40).build());
        boolean escalatedAtBudget = false;
        for (int i = 0; i < 30; i++) escalatedAtBudget |= atBudget.tick(45).isPresent();
        check("MSPT exactly at budget never escalates", !escalatedAtBudget && atBudget.level() == 0);

        // configured ladder order is honoured
        ThrottleController reordered = new ThrottleController(ThrottleConfig.builder()
                .maxMspt(45).windowTicks(1).hysteresisTicks(1)
                .ladder(List.of(ThrottleRung.THROTTLE_CHUNK_GEN, ThrottleRung.REDUCE_AI_RADIUS)).build());
        ThrottleEvent first = reordered.tick(80).orElseThrow();
        check("ladder order honoured: first rung = configured first", first.rung() == ThrottleRung.THROTTLE_CHUNK_GEN);

        // realistic windowed run still climbs to the top (proves averaging path works)
        ThrottleController windowed = new ThrottleController(
                ThrottleConfig.builder().maxMspt(45).windowTicks(5).hysteresisTicks(3).relaxThreshold(38).build());
        for (int i = 0; i < 100; i++) windowed.tick(100);
        check("windowed overload eventually reaches top rung", windowed.level() == windowed.maxLevel());
    }

    // ---------- recovery ----------

    private static void recoveryTests() {
        ThrottleConfig cfg = ThrottleConfig.builder()
                .maxMspt(45).windowTicks(1).hysteresisTicks(3).relaxThreshold(40).build();
        ThrottleController c = new ThrottleController(cfg);

        // climb to the top first
        for (int i = 0; i < 12; i++) c.tick(60);
        check("recovery precondition: at top rung", c.level() == 4);

        // sustained recovery: expect one rung down per 3 ticks, reverse order.
        List<ThrottleEvent> relaxEvents = new ArrayList<>();
        int[] levelAfter = new int[12];
        for (int i = 0; i < 12; i++) {
            c.tick(10).ifPresent(relaxEvents::add);
            levelAfter[i] = c.level();
        }
        int[] expected = {4, 4, 3, 3, 3, 2, 2, 2, 1, 1, 1, 0};
        check("relaxes NOT instantly (still 4 after 2 recovery ticks)", levelAfter[1] == 4);
        check("descends one rung after 3 sustained recovery ticks", levelAfter[2] == 3);
        check("recovery descends one rung per sustained recovery", java.util.Arrays.equals(levelAfter, expected));
        check("recovery reached level 0", c.level() == 0);
        check("recovery emitted 4 RELAX events", relaxEvents.size() == 4);
        check("recovery lifts rungs in reverse (deepest-first) order",
                relaxEvents.stream().map(ThrottleEvent::rung).toList().equals(List.of(
                        ThrottleRung.THROTTLE_CHUNK_GEN, ThrottleRung.REDUCE_RANDOM_TICK_RANGE,
                        ThrottleRung.TIGHTEN_MOB_CAPS, ThrottleRung.REDUCE_AI_RADIUS)));

        // nothing left to relax at level 0
        boolean any = false;
        for (int i = 0; i < 10; i++) any |= c.tick(1).isPresent();
        check("recovery emits nothing at level 0", !any && c.level() == 0);
    }

    // ---------- no flapping (hysteresis) ----------

    private static void noFlapTests() {
        // Alternating one tick above budget, one tick below relax threshold. A naive controller
        // would flap; sustained-streak hysteresis means neither streak ever reaches the threshold.
        ThrottleController c = new ThrottleController(ThrottleConfig.builder()
                .maxMspt(45).windowTicks(1).hysteresisTicks(3).relaxThreshold(40).build());
        int events = 0;
        for (int i = 0; i < 40; i++) {
            events += c.tick(i % 2 == 0 ? 60 : 30).isPresent() ? 1 : 0;
        }
        check("oscillation above/below: never escalates", c.level() == 0);
        check("oscillation above/below: zero events (no flapping)", events == 0);

        // Dead-band hysteresis: escalate to level 2, then feed values in the band between
        // relaxThreshold and maxMspt -> level is HELD, never flaps down or up.
        ThrottleController c2 = new ThrottleController(ThrottleConfig.builder()
                .maxMspt(45).windowTicks(1).hysteresisTicks(3).relaxThreshold(40).build());
        for (int i = 0; i < 6; i++) c2.tick(60); // -> level 2
        check("dead-band precondition: at level 2", c2.level() == 2);
        int bandEvents = 0;
        for (int i = 0; i < 50; i++) bandEvents += c2.tick(42).isPresent() ? 1 : 0; // 40 < 42 < 45
        check("dead band holds level (no relax)", c2.level() == 2);
        check("dead band emits no events (no flapping)", bandEvents == 0);

        // Mixed oscillation while throttled: alternate over-budget and dead-band -> still held.
        int mixEvents = 0;
        for (int i = 0; i < 40; i++) mixEvents += c2.tick(i % 2 == 0 ? 50 : 42).isPresent() ? 1 : 0;
        check("throttled oscillation held at level 2 (no flapping)", c2.level() == 2 && mixEvents == 0);
    }

    // ---------- events & callback ----------

    private static void eventTests() {
        List<ThrottleEvent> captured = new ArrayList<>();
        ThrottleController c = new ThrottleController(
                ThrottleConfig.builder().maxMspt(45).windowTicks(1).hysteresisTicks(1).relaxThreshold(40).build(),
                captured::add);

        ThrottleEvent e = c.tick(90).orElseThrow();
        check("event action = ESCALATE", e.action() == ThrottleAction.ESCALATE);
        check("event rung = first (cheapest)", e.rung() == ThrottleRung.REDUCE_AI_RADIUS);
        check("event from=0 to=1", e.fromLevel() == 0 && e.toLevel() == 1);
        check("event carries mspt (avg)", e.mspt() == 90.0);
        check("event carries mspt_p95", e.msptP95() == 90.0);
        check("event tick index = 1", e.tick() == 1L);
        check("event activeRungs snapshot", e.activeRungs().equals(List.of(ThrottleRung.REDUCE_AI_RADIUS)));
        check("callback received the event", captured.size() == 1 && captured.get(0) == e);
        check("controller topRung reflects state", c.topRung().orElse(null) == ThrottleRung.REDUCE_AI_RADIUS);
        check("controller activeRungs reflects state", c.activeRungs().equals(List.of(ThrottleRung.REDUCE_AI_RADIUS)));

        check("event log line has throttle+subsystem+action",
                e.toLogLine().contains("\"event\":\"throttle\"")
                        && e.toLogLine().contains("\"subsystem\":\"mob-ai\"")
                        && e.toLogLine().contains("\"action\":\"escalate\""));

        // activeRungs snapshot is immutable
        check("event activeRungs immutable", throwsUOE(() -> e.activeRungs().add(ThrottleRung.TIGHTEN_MOB_CAPS)));
    }

    // ---------- tiny harness ----------

    interface Thrower { void run(); }

    static boolean throwsIAE(Thrower t) {
        try { t.run(); return false; }
        catch (IllegalArgumentException e) { return true; }
    }

    static boolean throwsUOE(Thrower t) {
        try { t.run(); return false; }
        catch (UnsupportedOperationException e) { return true; }
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }
}
