// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.limits;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The per-instance throttle ladder as a pure, dependency-free state machine
 * (docs/phase-2/04-per-instance-limits.md).
 *
 * <p>Fed one MSPT sample per tick via {@link #tick(double)}, it maintains a rolling average and
 * decides, with {@link ThrottleConfig hysteresis}, whether to {@link ThrottleAction#ESCALATE climb}
 * one rung (after a sustained over-budget breach), {@link ThrottleAction#HOLD hold}, or
 * {@link ThrottleAction#RELAX descend} one rung (after a sustained recovery). It climbs/descends
 * <b>at most one rung per tick</b>, so a huge spike cannot jump straight to the top — each rung
 * costs another full breach window.
 *
 * <p>On a change it returns (and optionally hands a callback) a {@link ThrottleEvent}; it performs no
 * I/O itself. When {@link ThrottleConfig#enabled()} is false (e.g. {@code max-mspt <= 0}) it is inert:
 * every tick is a {@link ThrottleAction#HOLD} at level 0.
 *
 * <p>Not thread-safe: driven from the single tick loop.
 */
public final class ThrottleController {

    private final ThrottleConfig config;
    private final RollingMspt rolling;
    private final Consumer<ThrottleEvent> listener;

    private int level;                       // 0..config.maxLevel()
    private int breachStreak;                // consecutive over-budget ticks
    private int recoveryStreak;              // consecutive recovered ticks
    private ThrottleAction lastAction = ThrottleAction.HOLD;
    private long tickCount;

    public ThrottleController(ThrottleConfig config) {
        this(config, null);
    }

    /**
     * @param config   tuning (see {@link ThrottleConfig})
     * @param listener optional callback invoked on every {@link ThrottleEvent}; may be {@code null}
     */
    public ThrottleController(ThrottleConfig config, Consumer<ThrottleEvent> listener) {
        this.config = Objects.requireNonNull(config, "config");
        this.rolling = new RollingMspt(config.windowTicks());
        this.listener = listener;
    }

    /**
     * Advance the state machine by one tick.
     *
     * @param mspt this tick's measured MSPT
     * @return the change event if the ladder moved this tick, otherwise {@link Optional#empty()}
     */
    public Optional<ThrottleEvent> tick(double mspt) {
        tickCount++;
        lastAction = ThrottleAction.HOLD;

        if (!config.enabled()) {
            return Optional.empty();   // inert: never throttle when disabled
        }

        rolling.record(mspt);
        double avg = rolling.average();

        if (avg > config.maxMspt()) {
            breachStreak++;
            recoveryStreak = 0;
        } else if (avg < config.relaxThreshold()) {
            recoveryStreak++;
            breachStreak = 0;
        } else {
            // Inside the hysteresis dead band: hold the current level, count neither way.
            breachStreak = 0;
            recoveryStreak = 0;
        }

        if (breachStreak >= config.breachTicks() && level < config.maxLevel()) {
            return Optional.of(escalate(avg));
        }
        if (recoveryStreak >= config.recoveryTicks() && level > 0) {
            return Optional.of(relax(avg));
        }
        return Optional.empty();
    }

    private ThrottleEvent escalate(double avg) {
        ThrottleRung rung = config.ladder().get(level);   // next rung to activate
        int from = level;
        level++;
        breachStreak = 0;   // require a fresh sustained breach before the next rung
        lastAction = ThrottleAction.ESCALATE;
        return emit(new ThrottleEvent(ThrottleAction.ESCALATE, rung, from, level,
                avg, rolling.percentile(0.95), activeRungs(), tickCount));
    }

    private ThrottleEvent relax(double avg) {
        ThrottleRung rung = config.ladder().get(level - 1);   // top active rung, being lifted
        int from = level;
        level--;
        recoveryStreak = 0;
        lastAction = ThrottleAction.RELAX;
        return emit(new ThrottleEvent(ThrottleAction.RELAX, rung, from, level,
                avg, rolling.percentile(0.95), activeRungs(), tickCount));
    }

    private ThrottleEvent emit(ThrottleEvent e) {
        if (listener != null) {
            listener.accept(e);
        }
        return e;
    }

    // ---- observability accessors ----

    /** Current ladder depth: {@code 0} = no throttling, up to {@link #maxLevel()}. */
    public int level() {
        return level;
    }

    /** Deepest reachable level ({@code config.maxLevel()}). */
    public int maxLevel() {
        return config.maxLevel();
    }

    /** Whether throttling is armed at all (see {@link ThrottleConfig#enabled()}). */
    public boolean isEnabled() {
        return config.enabled();
    }

    /** The action taken on the most recent {@link #tick(double)}. */
    public ThrottleAction lastAction() {
        return lastAction;
    }

    /** Snapshot of the currently active rungs (cheapest-first), immutable. */
    public List<ThrottleRung> activeRungs() {
        return List.copyOf(config.ladder().subList(0, level));
    }

    /** The deepest active rung, or empty when nothing is throttled. */
    public Optional<ThrottleRung> topRung() {
        return level == 0 ? Optional.empty() : Optional.of(config.ladder().get(level - 1));
    }

    /** Current rolling-average MSPT. */
    public double currentMspt() {
        return rolling.average();
    }

    /** Current p95 MSPT over the window. */
    public double currentMsptP95() {
        return rolling.percentile(0.95);
    }

    /** The rolling-MSPT accumulator (read-only use by the metrics module). */
    public RollingMspt rollingMspt() {
        return rolling;
    }

    /** The tuning this controller was built with. */
    public ThrottleConfig config() {
        return config;
    }
}
