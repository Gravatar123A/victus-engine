// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.limits;

import cloud.victus.core.config.ResolvedConfig;

import java.util.List;

/**
 * Immutable tuning for the {@link ThrottleController} (docs/phase-2/04-per-instance-limits.md).
 *
 * <p>The controller is a pure per-tick state machine, so all durations here are expressed in
 * <b>ticks</b> — deterministic and testable offline. The victus.yml keys are in seconds; convert
 * at construction via {@link Builder#windowSeconds(double)} / {@link Builder#hysteresisSeconds(double)}
 * (defaulting to {@value #DEFAULT_TICKS_PER_SECOND} ticks per second).
 *
 * <p>Two independent hysteresis mechanisms keep the ladder from flapping:
 * <ul>
 *   <li><b>Time hysteresis</b> — a breach must persist {@link #breachTicks()} ticks before the
 *       ladder climbs, and recovery must persist {@link #recoveryTicks()} ticks before it descends.</li>
 *   <li><b>Threshold band</b> — the ladder climbs only above {@link #maxMspt()} and descends only
 *       below {@link #relaxThreshold()}; the gap between them is a dead band where the level is held.</li>
 * </ul>
 */
public final class ThrottleConfig {

    /** Minecraft's nominal tick rate, used to convert the second-based victus.yml keys to ticks. */
    public static final int DEFAULT_TICKS_PER_SECOND = 20;

    private final double maxMspt;
    private final int windowTicks;
    private final int breachTicks;
    private final int recoveryTicks;
    private final double relaxThreshold;
    private final List<ThrottleRung> ladder;

    private ThrottleConfig(Builder b) {
        this.maxMspt = b.maxMspt;
        this.windowTicks = b.windowTicks;
        this.breachTicks = b.breachTicks;
        this.recoveryTicks = b.recoveryTicks;
        this.relaxThreshold = b.effectiveRelaxThreshold();
        this.ladder = List.copyOf(b.ladder);
    }

    /** Soft MSPT budget ({@code hosting.limits.max-mspt}); {@code <= 0} disables throttling. */
    public double maxMspt() {
        return maxMspt;
    }

    /** Rolling-average window length in ticks ({@code hosting.limits.max-mspt-window-seconds}). */
    public int windowTicks() {
        return windowTicks;
    }

    /** Sustained over-budget ticks required before climbing one rung ({@code hysteresis-seconds}). */
    public int breachTicks() {
        return breachTicks;
    }

    /** Sustained recovered ticks required before descending one rung ({@code hysteresis-seconds}). */
    public int recoveryTicks() {
        return recoveryTicks;
    }

    /** MSPT below which recovery is counted; forms a dead band with {@link #maxMspt()}. */
    public double relaxThreshold() {
        return relaxThreshold;
    }

    /** The ordered throttle ladder ({@code hosting.limits.throttle-ladder}); unmodifiable. */
    public List<ThrottleRung> ladder() {
        return ladder;
    }

    /** Deepest rung index the ladder can reach (i.e. {@code ladder().size()}). */
    public int maxLevel() {
        return ladder.size();
    }

    /** {@code true} when a positive budget is set and there is at least one rung to climb. */
    public boolean enabled() {
        return maxMspt > 0 && !ladder.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Build a config from a resolved victus.yml, reading {@link ResolvedConfig#maxMspt} for the
     * budget and using the documented defaults for everything else. A {@code null} config or a
     * non-positive budget yields a {@link #enabled() disabled} config.
     */
    public static ThrottleConfig fromResolved(ResolvedConfig c) {
        return builder().maxMspt(c == null ? 0 : c.maxMspt).build();
    }

    @Override
    public String toString() {
        return "ThrottleConfig{maxMspt=" + maxMspt
                + ", windowTicks=" + windowTicks
                + ", breachTicks=" + breachTicks
                + ", recoveryTicks=" + recoveryTicks
                + ", relaxThreshold=" + relaxThreshold
                + ", ladder=" + ladder
                + ", enabled=" + enabled() + "}";
    }

    /** Fluent builder; all durations are in ticks unless a {@code *Seconds} helper is used. */
    public static final class Builder {
        private double maxMspt = 45.0;                 // hosting.limits.max-mspt
        private int windowTicks = 10 * DEFAULT_TICKS_PER_SECOND;      // 10s window
        private int breachTicks = 15 * DEFAULT_TICKS_PER_SECOND;      // 15s hysteresis (climb)
        private int recoveryTicks = 15 * DEFAULT_TICKS_PER_SECOND;    // 15s hysteresis (descend)
        private double relaxFactor = 0.9;              // relax below 90% of the budget by default
        private Double relaxThreshold = null;          // explicit override; else derived from factor
        private List<ThrottleRung> ladder = ThrottleRung.defaultLadder();

        public Builder maxMspt(double v) {
            this.maxMspt = v;
            return this;
        }

        public Builder windowTicks(int v) {
            this.windowTicks = v;
            return this;
        }

        public Builder windowSeconds(double seconds) {
            this.windowTicks = ticks(seconds);
            return this;
        }

        /** Sets both climb and descend hysteresis to the same tick count. */
        public Builder hysteresisTicks(int v) {
            this.breachTicks = v;
            this.recoveryTicks = v;
            return this;
        }

        /** Sets both climb and descend hysteresis from a second-based value. */
        public Builder hysteresisSeconds(double seconds) {
            return hysteresisTicks(ticks(seconds));
        }

        public Builder breachTicks(int v) {
            this.breachTicks = v;
            return this;
        }

        public Builder recoveryTicks(int v) {
            this.recoveryTicks = v;
            return this;
        }

        /** Fraction of {@link #maxMspt(double)} below which recovery counts (default {@code 0.9}). */
        public Builder relaxFactor(double v) {
            this.relaxFactor = v;
            this.relaxThreshold = null;
            return this;
        }

        /** Absolute relax threshold, overriding {@link #relaxFactor(double)}. */
        public Builder relaxThreshold(double v) {
            this.relaxThreshold = v;
            return this;
        }

        public Builder ladder(List<ThrottleRung> v) {
            this.ladder = v == null ? ThrottleRung.defaultLadder() : List.copyOf(v);
            return this;
        }

        public ThrottleConfig build() {
            if (windowTicks < 1) {
                throw new IllegalArgumentException("windowTicks must be >= 1, got " + windowTicks);
            }
            if (breachTicks < 1) {
                throw new IllegalArgumentException("breachTicks must be >= 1, got " + breachTicks);
            }
            if (recoveryTicks < 1) {
                throw new IllegalArgumentException("recoveryTicks must be >= 1, got " + recoveryTicks);
            }
            if (relaxThreshold == null && (relaxFactor <= 0 || relaxFactor > 1)) {
                throw new IllegalArgumentException("relaxFactor must be in (0, 1], got " + relaxFactor);
            }
            if (maxMspt > 0 && effectiveRelaxThreshold() > maxMspt) {
                throw new IllegalArgumentException("relaxThreshold (" + effectiveRelaxThreshold()
                        + ") must not exceed maxMspt (" + maxMspt + ")");
            }
            return new ThrottleConfig(this);
        }

        private double effectiveRelaxThreshold() {
            if (relaxThreshold != null) {
                return relaxThreshold;
            }
            return maxMspt <= 0 ? 0.0 : maxMspt * relaxFactor;
        }

        private static int ticks(double seconds) {
            int t = (int) Math.round(seconds * DEFAULT_TICKS_PER_SECOND);
            return Math.max(1, t);
        }
    }
}
