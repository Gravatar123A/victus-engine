// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable, per-{@link Subsystem} MSPT breakdown averaged over a diagnosis window: how many
 * milliseconds each subsystem cost per tick, and what share of the measured tick that is.
 *
 * <p>This is the rolling breakdown from module&nbsp;01 (see {@code docs/phase-2/02-lag-doctor.md} step&nbsp;1).
 * The pure core just consumes the numbers — they arrive as a plain {@code Map<Subsystem,Double>}.
 * Percentages are computed against the <b>sum of the provided slices</b> (the measured tick), so they
 * total ~100%. Missing subsystems are treated as {@code 0 ms}; negative inputs are clamped to {@code 0}.
 */
public final class TickBreakdown {

    /** One subsystem's cost within the tick. */
    public record Slice(Subsystem subsystem, double ms, double pct) {
    }

    private final Map<Subsystem, Double> msBySubsystem;
    private final double totalMs;

    /**
     * @param msBySubsystem measured ms-per-tick by subsystem; may be partial or empty, values &lt; 0 are
     *                      clamped to 0. Not retained (a defensive copy is taken).
     */
    public TickBreakdown(Map<Subsystem, Double> msBySubsystem) {
        Map<Subsystem, Double> copy = new EnumMap<>(Subsystem.class);
        double total = 0.0;
        if (msBySubsystem != null) {
            for (Map.Entry<Subsystem, Double> e : msBySubsystem.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                double ms = Math.max(0.0, e.getValue());
                copy.put(e.getKey(), ms);
                total += ms;
            }
        }
        this.msBySubsystem = Collections.unmodifiableMap(copy);
        this.totalMs = total;
    }

    /** Milliseconds-per-tick attributed to {@code s} (0 if absent). */
    public double ms(Subsystem s) {
        return msBySubsystem.getOrDefault(s, 0.0);
    }

    /** Share of the measured tick attributed to {@code s}, in percent (0 when the tick total is 0). */
    public double pct(Subsystem s) {
        if (totalMs <= 0.0) {
            return 0.0;
        }
        return ms(s) / totalMs * 100.0;
    }

    /** Sum of all provided slices (the measured tick length), in ms. */
    public double totalMs() {
        return totalMs;
    }

    /**
     * Subsystems ranked by descending tick share. Only subsystems actually present in the input are
     * returned (a zero-cost subsystem is not interesting); ties break by enum order for determinism.
     *
     * @return an immutable, most-expensive-first list of {@link Slice}
     */
    public List<Slice> ranked() {
        List<Slice> slices = new ArrayList<>(msBySubsystem.size());
        for (Map.Entry<Subsystem, Double> e : msBySubsystem.entrySet()) {
            slices.add(new Slice(e.getKey(), e.getValue(), pct(e.getKey())));
        }
        slices.sort((a, b) -> {
            int byMs = Double.compare(b.ms(), a.ms());
            return byMs != 0 ? byMs : Integer.compare(a.subsystem().ordinal(), b.subsystem().ordinal());
        });
        return Collections.unmodifiableList(slices);
    }

    @Override
    public String toString() {
        return "TickBreakdown{totalMs=" + totalMs + ", slices=" + ranked() + "}";
    }
}
