// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.metrics;

/**
 * The Prometheus metric families this core emits.
 *
 * <p>Deliberately a small subset: a host needs cheap always-on point values ({@link #GAUGE}) and
 * streaming latency distributions ({@link #SUMMARY}). Counters/histograms are intentionally omitted —
 * the tick-timing core computes quantiles itself (see {@link Summary}) rather than shipping raw
 * histogram buckets, which keeps cardinality and scrape size bounded.
 */
public enum MetricType {
    GAUGE("gauge"),
    SUMMARY("summary");

    private final String exposition;

    MetricType(String exposition) {
        this.exposition = exposition;
    }

    /** The token used on a {@code # TYPE} line in Prometheus text exposition format 0.0.4. */
    public String exposition() {
        return exposition;
    }
}
