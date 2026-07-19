// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.metrics;

/**
 * An immutable point-in-time view of a {@link Summary}: its count, sum, tracked max, and a sorted
 * copy of the retained sample reservoir from which quantiles are computed.
 *
 * <p>Quantiles use the <b>nearest-rank</b> method on the sorted samples: for a sorted set of {@code n}
 * samples, the quantile {@code q} is the value at 1-based rank {@code ceil(q*n)}. This is exact when
 * every sample was retained (i.e. the stream fit inside the reservoir) and is deterministic, so the
 * self-test can assert precise values on a known input.
 */
public final class SummarySnapshot {

    private final double[] sorted; // ascending; may be empty
    private final long count;      // total observations ever recorded (not just retained)
    private final double sum;      // sum of all observations
    private final double max;      // max of all observations (tracked exactly, reservoir-independent)

    SummarySnapshot(double[] sortedAscending, long count, double sum, double max) {
        this.sorted = sortedAscending;
        this.count = count;
        this.sum = sum;
        this.max = max;
    }

    /**
     * The value at quantile {@code q} (0..1) by nearest-rank.
     *
     * <p>{@code q >= 1.0} returns the exact tracked {@link #max()} (which reservoir sampling can never
     * lose); {@code q <= 0.0} returns the smallest retained sample. Returns {@link Double#NaN} when no
     * samples have been recorded.
     */
    public double quantile(double q) {
        if (count == 0 || sorted.length == 0) return Double.NaN;
        if (q >= 1.0) return max;
        if (q <= 0.0) return sorted[0];
        int rank = (int) Math.ceil(q * sorted.length); // 1-based
        int idx = rank - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    /** The 50th percentile (median). */
    public double p50() {
        return quantile(0.50);
    }

    /** The 95th percentile. */
    public double p95() {
        return quantile(0.95);
    }

    /** The 99th percentile. */
    public double p99() {
        return quantile(0.99);
    }

    /** The exact maximum observation ({@link Double#NaN} if none). */
    public double max() {
        return count == 0 ? Double.NaN : max;
    }

    /** Total number of observations recorded (across the whole stream, not just those retained). */
    public long count() {
        return count;
    }

    /** Sum of all observations. */
    public double sum() {
        return sum;
    }

    /** Number of samples actually retained in the reservoir (&le; {@link #count()}). */
    public int retained() {
        return sorted.length;
    }

    @Override
    public String toString() {
        return "SummarySnapshot{count=" + count + ", sum=" + sum
                + ", p50=" + p50() + ", p95=" + p95() + ", p99=" + p99() + ", max=" + max() + "}";
    }
}
