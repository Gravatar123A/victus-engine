// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

import java.util.Arrays;
import java.util.Collection;

/**
 * A compact MSPT distribution summary for the diagnosis header: the median, tail percentiles, max, mean
 * and sample count over the window (see the JSON shape in {@code docs/phase-2/02-lag-doctor.md}
 * &mdash; {@code mspt:{p50,p95,p99,max}}).
 *
 * <p>victus-core is dependency-free, so there is no HdrHistogram here: quantiles are computed with the
 * <b>nearest-rank</b> method on a sorted copy of the raw samples. That is exact for the modest sample
 * counts a per-tick reservoir holds over a 60s window and needs no external library.
 *
 * @param p50         median MSPT
 * @param p95         95th-percentile MSPT
 * @param p99         99th-percentile MSPT
 * @param max         worst observed MSPT
 * @param mean        arithmetic mean MSPT
 * @param sampleCount number of samples the summary was built from
 */
public record MsptSummary(double p50, double p95, double p99, double max, double mean, int sampleCount) {

    /** An empty summary (all zero), used when no samples are available. */
    public static final MsptSummary EMPTY = new MsptSummary(0, 0, 0, 0, 0, 0);

    /**
     * Build a summary from raw MSPT samples using nearest-rank percentiles.
     *
     * @param samples per-tick MSPT values; may be empty (yields {@link #EMPTY}). Not mutated.
     * @return the computed summary
     */
    public static MsptSummary of(double... samples) {
        if (samples == null || samples.length == 0) {
            return EMPTY;
        }
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double sum = 0.0;
        for (double v : sorted) {
            sum += v;
        }
        return new MsptSummary(
                quantile(sorted, 0.50),
                quantile(sorted, 0.95),
                quantile(sorted, 0.99),
                sorted[sorted.length - 1],
                sum / sorted.length,
                sorted.length);
    }

    /** Build a summary from a collection of samples (boxed convenience). */
    public static MsptSummary of(Collection<Double> samples) {
        if (samples == null || samples.isEmpty()) {
            return EMPTY;
        }
        double[] arr = new double[samples.size()];
        int i = 0;
        for (Double d : samples) {
            arr[i++] = d == null ? 0.0 : d;
        }
        return of(arr);
    }

    /**
     * Nearest-rank quantile on an already-sorted (ascending) array.
     *
     * <p>Rank {@code = ceil(p * n)}, clamped to {@code [1, n]}, then read as {@code sorted[rank-1]}.
     */
    private static double quantile(double[] sorted, double p) {
        int n = sorted.length;
        int rank = (int) Math.ceil(p * n);
        if (rank < 1) {
            rank = 1;
        } else if (rank > n) {
            rank = n;
        }
        return sorted[rank - 1];
    }
}
