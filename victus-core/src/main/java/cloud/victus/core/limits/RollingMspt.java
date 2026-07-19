// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.limits;

import java.util.Arrays;

/**
 * A fixed-window rolling MSPT accumulator: records one MSPT sample per tick and exposes the
 * windowed average (the primary signal the throttle ladder reacts to) plus cheap summary stats.
 *
 * <p>Backed by a ring buffer, so {@link #record(double)} and {@link #average()} are O(1).
 * Percentiles ({@link #percentile(double)}) copy-and-sort the window — a "sorted reservoir" — which
 * is fine for the small windows used here and avoids any external histogram dependency.
 *
 * <p>Not thread-safe: the engine drives it from the single tick loop.
 */
public final class RollingMspt {

    private final double[] ring;
    private int head;   // index of the next write
    private int count;  // number of valid samples (grows to capacity, then stays)
    private double sum;  // running sum of the valid samples

    /**
     * @param windowTicks number of most-recent ticks to keep (must be &ge; 1)
     */
    public RollingMspt(int windowTicks) {
        if (windowTicks < 1) {
            throw new IllegalArgumentException("windowTicks must be >= 1, got " + windowTicks);
        }
        this.ring = new double[windowTicks];
    }

    /** Record one tick's MSPT, evicting the oldest sample once the window is full. */
    public void record(double mspt) {
        if (count == ring.length) {
            sum -= ring[head];      // evict the oldest (currently at head)
        } else {
            count++;
        }
        ring[head] = mspt;
        sum += mspt;
        head = (head + 1) % ring.length;
    }

    /** The configured window length in ticks. */
    public int capacity() {
        return ring.length;
    }

    /** Number of samples currently held (&le; {@link #capacity()}). */
    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /** {@code true} once the window has filled and eviction is in effect. */
    public boolean isFull() {
        return count == ring.length;
    }

    /** Windowed mean MSPT, or {@code 0.0} when no samples have been recorded. */
    public double average() {
        return count == 0 ? 0.0 : sum / count;
    }

    /** The most recently recorded sample, or {@code 0.0} when empty. */
    public double latest() {
        if (count == 0) {
            return 0.0;
        }
        return ring[(head - 1 + ring.length) % ring.length];
    }

    /** Max over the current window, or {@code 0.0} when empty. */
    public double max() {
        if (count == 0) {
            return 0.0;
        }
        double m = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {  // valid samples always live in [0, count)
            if (ring[i] > m) {
                m = ring[i];
            }
        }
        return m;
    }

    /**
     * Nearest-rank percentile over the current window (e.g. {@code percentile(0.95)} for p95).
     * Returns {@code 0.0} when empty. {@code q} is clamped to {@code (0, 1]}.
     */
    public double percentile(double q) {
        if (count == 0) {
            return 0.0;
        }
        double[] sorted = Arrays.copyOf(ring, count);  // valid samples are indices [0, count)
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(q * sorted.length);
        if (rank < 1) {
            rank = 1;
        } else if (rank > sorted.length) {
            rank = sorted.length;
        }
        return sorted[rank - 1];
    }

    /** Clear all samples back to the empty state. */
    public void reset() {
        Arrays.fill(ring, 0.0);
        head = 0;
        count = 0;
        sum = 0.0;
    }
}
