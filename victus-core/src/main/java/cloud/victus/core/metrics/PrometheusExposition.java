// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.metrics;

import java.util.Map;
import java.util.TreeMap;

/**
 * Renders a {@link MetricRegistry} into Prometheus <b>text exposition format 0.0.4</b> — the body of a
 * {@code GET /metrics} scrape. Pure string building, no I/O and no external client library.
 *
 * <p>Contract enforced here:
 * <ul>
 *   <li>Each emitted metric family carries exactly one {@code # HELP} (if help text is set) and one
 *       {@code # TYPE} line, before its samples.</li>
 *   <li>A gauge emits {@code name{labels} value} per series.</li>
 *   <li>A summary emits one {@code name{labels,quantile="q"} value} line per configured quantile
 *       (with {@code 1.0} being the exact max), followed by {@code name_count{labels}} and
 *       {@code name_sum{labels}}.</li>
 *   <li>Series and families are sorted (name, then rendered labels) so output is byte-stable across
 *       scrapes — a property the panel's diff-based scrapers rely on.</li>
 *   <li>Label values are escaped ({@code \\}, {@code \"}, {@code \n}); values render as {@code NaN},
 *       {@code +Inf}, {@code -Inf}, integers without a fractional part, else {@code Double.toString}.</li>
 * </ul>
 *
 * <p>This is always run on the exporter's background thread, never on the tick thread (a single
 * {@link Summary#snapshot()} per series is taken here, off the record path).
 */
public final class PrometheusExposition {

    /** The {@code Content-Type} a Prometheus scraper expects for this body. */
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private static final String QUANTILE_LABEL = "quantile";

    private PrometheusExposition() {
    }

    /** Render the whole registry to a scrape body (ends with a trailing newline). */
    public static String write(MetricRegistry registry) {
        StringBuilder sb = new StringBuilder(4096);
        for (String name : registry.gaugeNames()) {
            writeGaugeFamily(sb, registry, name);
        }
        for (String name : registry.summaryNames()) {
            writeSummaryFamily(sb, registry, name);
        }
        return sb.toString();
    }

    private static void writeGaugeFamily(StringBuilder sb, MetricRegistry registry, String name) {
        Map<Labels, Gauge> series = registry.gaugeSeries(name);
        if (series.isEmpty()) return;
        writeHeader(sb, name, MetricType.GAUGE, registry.helpFor(name));
        // Sort series by rendered label string for stable output.
        TreeMap<String, Gauge> ordered = new TreeMap<>();
        for (Map.Entry<Labels, Gauge> e : series.entrySet()) {
            ordered.put(renderLabels(e.getKey()), e.getValue());
        }
        for (Map.Entry<String, Gauge> e : ordered.entrySet()) {
            sb.append(name).append(e.getKey()).append(' ')
                    .append(formatValue(e.getValue().get())).append('\n');
        }
    }

    private static void writeSummaryFamily(StringBuilder sb, MetricRegistry registry, String name) {
        Map<Labels, Summary> series = registry.summarySeries(name);
        if (series.isEmpty()) return;
        writeHeader(sb, name, MetricType.SUMMARY, registry.helpFor(name));
        // Sort series by rendered base-label string for stable output.
        TreeMap<String, Summary> ordered = new TreeMap<>();
        for (Map.Entry<Labels, Summary> e : series.entrySet()) {
            ordered.put(renderLabels(e.getKey()), e.getValue());
        }
        for (Map.Entry<String, Summary> e : ordered.entrySet()) {
            Summary summary = e.getValue();
            SummarySnapshot snap = summary.snapshot();
            Labels base = findBase(series, e.getKey());
            for (double q : summary.quantiles()) {
                Labels withQ = base.with(QUANTILE_LABEL, quantileLabel(q));
                sb.append(name).append(renderLabels(withQ)).append(' ')
                        .append(formatValue(snap.quantile(q))).append('\n');
            }
            String baseLabels = e.getKey();
            sb.append(name).append("_count").append(baseLabels).append(' ')
                    .append(formatValue(snap.count())).append('\n');
            sb.append(name).append("_sum").append(baseLabels).append(' ')
                    .append(formatValue(snap.sum())).append('\n');
        }
    }

    /** Recover the {@link Labels} key whose rendering equals {@code rendered} (base labels for a series). */
    private static Labels findBase(Map<Labels, Summary> series, String rendered) {
        for (Labels l : series.keySet()) {
            if (renderLabels(l).equals(rendered)) return l;
        }
        return Labels.EMPTY;
    }

    private static void writeHeader(StringBuilder sb, String name, MetricType type, String help) {
        if (help != null && !help.isEmpty()) {
            sb.append("# HELP ").append(name).append(' ').append(escapeHelp(help)).append('\n');
        }
        sb.append("# TYPE ").append(name).append(' ').append(type.exposition()).append('\n');
    }

    // ---- rendering primitives ----

    /** {@code {k="v",...}} with escaped values, or {@code ""} for the empty set. */
    static String renderLabels(Labels labels) {
        if (labels.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : labels.asMap().entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append("=\"").append(escapeLabelValue(e.getValue())).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    /** Format a metric value per the exposition rules. */
    static String formatValue(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (v == Double.POSITIVE_INFINITY) return "+Inf";
        if (v == Double.NEGATIVE_INFINITY) return "-Inf";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v); // integral -> no ".0", exact for byte counts etc.
        }
        return Double.toString(v); // locale-independent, always '.' decimal
    }

    private static String formatValue(long v) {
        return Long.toString(v);
    }

    /** The {@code quantile} label value: {@code 0.5}, {@code 0.95}, {@code 0.99}, {@code 1.0}. */
    static String quantileLabel(double q) {
        String s = Double.toString(q);
        // Double.toString(0.5)="0.5", (0.95)="0.95", (0.99)="0.99", (1.0)="1.0" — already canonical.
        return s;
    }

    /** Escape a label value: backslash, double-quote, newline. */
    static String escapeLabelValue(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Escape {@code # HELP} text: backslash and newline only (quotes are literal in HELP). */
    static String escapeHelp(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
