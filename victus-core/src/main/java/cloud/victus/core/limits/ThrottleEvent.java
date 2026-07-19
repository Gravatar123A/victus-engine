// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.limits;

import java.util.List;
import java.util.Objects;

/**
 * A structured record of a single throttle-ladder change (docs/phase-2/04-per-instance-limits.md).
 *
 * <p>Emitted whenever the {@link ThrottleController} climbs ({@link ThrottleAction#ESCALATE}) or
 * descends ({@link ThrottleAction#RELAX}) a rung — never on a {@link ThrottleAction#HOLD}. The
 * engine turns this into the module-01 {@code throttle} JSON log line, the
 * {@code victus_throttle_active{subsystem}} / {@code victus_throttle_level} metrics, and the
 * {@code /victus doctor} view. Transparency is a hard rule: no player-visible behaviour changes
 * without one of these.
 *
 * <p>This module never performs I/O — the event is returned/handed to a callback and the caller logs it.
 *
 * @param action      whether the ladder climbed or descended
 * @param rung        the rung that was (de)activated by this change
 * @param fromLevel   ladder depth before the change
 * @param toLevel     ladder depth after the change
 * @param mspt        rolling-average MSPT at the decision point
 * @param msptP95     p95 MSPT over the window at the decision point
 * @param activeRungs snapshot of the rungs active after the change (cheapest-first), immutable
 * @param tick        1-based tick index on which the decision was made
 */
public record ThrottleEvent(
        ThrottleAction action,
        ThrottleRung rung,
        int fromLevel,
        int toLevel,
        double mspt,
        double msptP95,
        List<ThrottleRung> activeRungs,
        long tick) {

    public ThrottleEvent {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(rung, "rung");
        activeRungs = activeRungs == null ? List.of() : List.copyOf(activeRungs);
    }

    /**
     * The structured one-line log form, mirroring the module-01 {@code throttle} JSON event shape:
     * {@code {"event":"throttle","subsystem":...,"action":...,"from":...,"to":...,"mspt_p95":...}}.
     */
    public String toLogLine() {
        return "{\"event\":\"throttle\""
                + ",\"subsystem\":\"" + rung.subsystem() + "\""
                + ",\"action\":\"" + action.name().toLowerCase() + "\""
                + ",\"from\":" + fromLevel
                + ",\"to\":" + toLevel
                + ",\"mspt\":" + round2(mspt)
                + ",\"mspt_p95\":" + round2(msptP95)
                + "}";
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
