// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.limits;

import java.util.ArrayList;
import java.util.List;

/**
 * One rung of the per-instance throttle ladder (docs/phase-2/04-per-instance-limits.md).
 *
 * <p>The ladder is climbed <b>cheapest-impact first</b>: the engine sheds the least
 * player-visible work before the more disruptive levers. Order is data-driven — operators can
 * reorder or cap the depth in {@code hosting.limits.throttle-ladder} — so this enum only defines
 * the available rungs, never a fixed sequence. See {@link ThrottleConfig#ladder()}.
 *
 * <p>Each rung carries its {@link #configKey() victus.yml token} and the
 * {@link #subsystem() metric label} used for {@code victus_throttle_active{subsystem}}.
 */
public enum ThrottleRung {

    /** Rung 1 (cheapest, least visible): reduce mob-AI activation radius. */
    REDUCE_AI_RADIUS("reduce-ai-radius", "mob-ai"),
    /** Rung 2: tighten per-player mob caps. */
    TIGHTEN_MOB_CAPS("tighten-mob-caps", "mob-caps"),
    /** Rung 3: reduce non-player chunk-tick (random-tick) range. */
    REDUCE_RANDOM_TICK_RANGE("reduce-random-tick-range", "random-tick"),
    /** Rung 4 (optional final, most disruptive): throttle chunk-generation rate. */
    THROTTLE_CHUNK_GEN("throttle-chunk-gen", "chunk-gen");

    private final String configKey;
    private final String subsystem;

    ThrottleRung(String configKey, String subsystem) {
        this.configKey = configKey;
        this.subsystem = subsystem;
    }

    /** The token used for this rung in {@code hosting.limits.throttle-ladder}. */
    public String configKey() {
        return configKey;
    }

    /** The label emitted for the {@code victus_throttle_active{subsystem}} metric. */
    public String subsystem() {
        return subsystem;
    }

    /**
     * Parse a single ladder token (as written in victus.yml).
     *
     * @throws IllegalArgumentException on an unknown token, with a helpful message
     */
    public static ThrottleRung fromConfig(String s) {
        if (s == null) {
            throw new IllegalArgumentException("throttle-ladder rung is null "
                    + "(expected reduce-ai-radius|tighten-mob-caps|reduce-random-tick-range|throttle-chunk-gen)");
        }
        switch (s.trim().toLowerCase().replace('_', '-')) {
            case "reduce-ai-radius":         return REDUCE_AI_RADIUS;
            case "tighten-mob-caps":         return TIGHTEN_MOB_CAPS;
            case "reduce-random-tick-range": return REDUCE_RANDOM_TICK_RANGE;
            case "throttle-chunk-gen":       return THROTTLE_CHUNK_GEN;
            default:
                throw new IllegalArgumentException("unknown throttle-ladder rung: " + s
                        + " (expected reduce-ai-radius|tighten-mob-caps|reduce-random-tick-range|throttle-chunk-gen)");
        }
    }

    /** The full default ladder, cheapest-impact first. */
    public static List<ThrottleRung> defaultLadder() {
        return List.of(REDUCE_AI_RADIUS, TIGHTEN_MOB_CAPS, REDUCE_RANDOM_TICK_RANGE, THROTTLE_CHUNK_GEN);
    }

    /**
     * Parse an ordered ladder from a raw YAML list (whatever a YAML lib produced — a
     * {@code List<Object>} of strings). {@code null}/empty falls back to {@link #defaultLadder()}.
     *
     * @throws IllegalArgumentException on an unknown or duplicated rung
     */
    public static List<ThrottleRung> ladderFromConfig(List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            return defaultLadder();
        }
        List<ThrottleRung> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            ThrottleRung r = fromConfig(String.valueOf(o));
            if (out.contains(r)) {
                throw new IllegalArgumentException("duplicate throttle-ladder rung: " + r.configKey());
            }
            out.add(r);
        }
        return List.copyOf(out);
    }
}
