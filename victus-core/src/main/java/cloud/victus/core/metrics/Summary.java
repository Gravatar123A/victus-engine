// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.metrics;

import java.util.Arrays;
import java.util.Random;

/**
 * Records latency-style observations (e.g. per-subsystem MSPT, GC pause) and exposes streaming
 * quantiles without an external histogram library.
 *
 * <p>Samples are kept in a <b>bounded sorted reservoir</b>: up to {@code capacity} observations are
 * retained; once full, Vitter's Algorithm R replaces a uniformly-random slot so the reservoir stays a
 * uniform random sample of the whole stream. Quantiles are computed by sorting a copy of the reservoir
 * on {@link #snapshot()} (never on the record path) — see {@link SummarySnapshot} for the nearest-rank
 * rule. {@code count}, {@code sum} and the exact {@code max} are tracked across the entire stream, so
 * {@code _count}/{@code _sum} and the maximum are always accurate regardless of reservoir eviction.
 *
 * <p>When the stream fits inside the reservoir, every quantile is <em>exact</em>. All methods are
 * synchronized so a tick-thread {@link #record} and an off-thread {@link #snapshot} are safe.
 */
public final class Summary {

    /** Default reservoir size — plenty for a 10s scrape window of tick timings, still tiny memory. */
    public static final int DEFAULT_CAPACITY = 1024;

    /** Quantiles emitted in exposition; {@code 1.0} is the exact max. */
    static final double[] DEFAULT_QUANTILES = {0.50, 0.95, 0.99, 1.00};

    private final double[] reservoir;
    private final Random rng;

    private int size;      // number retained in the reservoir
    private long count;    // total observations
    private double sum;
    private double max = Double.NEGATIVE_INFINITY;

    public Summary() {
        this(DEFAULT_CAPACITY);
    }

    public Summary(int capacity) {
        this(capacity, new Random(0x5CA1AB1E));
    }

    /** Package-visible for deterministic testing of reservoir eviction. */
    Summary(int capacity, Random rng) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("summary reservoir capacity must be > 0, got " + capacity);
        }
        this.reservoir = new double[capacity];
        this.rng = rng;
    }

    /** Record one observation. O(1); never sorts. */
    public synchronized void record(double value) {
        count++;
        sum += value;
        if (value > max) max = value;
        if (size < reservoir.length) {
            reservoir[size++] = value;
        } else {
            // Algorithm R: keep each element with probability capacity/count.
            long j = (long) (rng.nextDouble() * count);
            if (j < reservoir.length) {
                reservoir[(int) j] = value;
            }
        }
    }

    /** The quantiles this summary exposes (ascending; {@code 1.0} == max). */
    public double[] quantiles() {
        return DEFAULT_QUANTILES.clone();
    }

    /** The reservoir capacity. */
    public int capacity() {
        return reservoir.length;
    }

    /** An immutable snapshot for exposition / assertions. Sorts a copy off the record path. */
    public synchronized SummarySnapshot snapshot() {
        double[] copy = Arrays.copyOf(reservoir, size);
        Arrays.sort(copy);
        double effectiveMax = count == 0 ? Double.NaN : max;
        return new SummarySnapshot(copy, count, sum, effectiveMax);
    }

    @Override
    public String toString() {
        return "Summary" + snapshot();
    }
}
