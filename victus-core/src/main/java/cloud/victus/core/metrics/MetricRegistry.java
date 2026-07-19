// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.metrics;

import java.util.Collections;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-process store of all live {@link Gauge}s and {@link Summary}s, keyed by metric name and
 * {@link Labels}. Get-or-create is lock-free ({@link ConcurrentHashMap#computeIfAbsent}); every label
 * set passes through a shared {@link CardinalityGuard} so no metric can exceed its series cap.
 *
 * <p>This is the single object the Prometheus exporter (see {@link PrometheusExposition}) reads and the
 * tick loop / subsystems write to. It is Paper-independent: subsystems hand it plain names and
 * {@link Labels}, never Bukkit types.
 *
 * <p>A metric name is exclusively a gauge <em>or</em> a summary; mixing throws. Optional {@code # HELP}
 * text can be attached with {@link #describe}. Iteration order in exposition is derived by sorting, so
 * the concurrent maps here need not preserve insertion order.
 */
public final class MetricRegistry {

    /** Default series cap per metric (victus.yml {@code hosting.metrics.cardinality-limit}). */
    public static final int DEFAULT_CARDINALITY_LIMIT = 200;

    private final CardinalityGuard guard;

    private final ConcurrentHashMap<String, MetricType> types = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> help = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Labels, Gauge>> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Labels, Summary>> summaries = new ConcurrentHashMap<>();

    /** A registry with the default cardinality cap. */
    public MetricRegistry() {
        this(DEFAULT_CARDINALITY_LIMIT);
    }

    /** A registry whose metrics are each capped at {@code cardinalityLimit} distinct series. */
    public MetricRegistry(int cardinalityLimit) {
        this.guard = new CardinalityGuard(cardinalityLimit);
    }

    /** The shared cardinality guard (diagnostics). */
    public CardinalityGuard cardinalityGuard() {
        return guard;
    }

    // ---- metadata ----

    /**
     * Attach {@code # HELP} text to a metric and assert its type. Safe to call before any series
     * exists (e.g. {@link MetricCatalog#registerDefaults}); repeated calls just update the help text.
     *
     * @throws IllegalStateException if {@code name} was already used with a different type
     */
    public MetricRegistry describe(String name, MetricType type, String help) {
        requireValidName(name);
        claimType(name, type);
        if (help != null) this.help.put(name, help);
        return this;
    }

    /** The {@code # HELP} text for {@code name}, or {@code null}. */
    public String helpFor(String name) {
        return help.get(name);
    }

    /** The declared type of {@code name}, or {@code null} if it has never been used. */
    public MetricType typeOf(String name) {
        return types.get(name);
    }

    // ---- gauges ----

    /** Get-or-create the unlabelled gauge {@code name}. */
    public Gauge gauge(String name) {
        return gauge(name, Labels.EMPTY);
    }

    /** Get-or-create the gauge {@code name} with {@code labels} (subject to the cardinality cap). */
    public Gauge gauge(String name, Labels labels) {
        requireValidName(name);
        claimType(name, MetricType.GAUGE);
        Labels eff = guard.admit(name, labels == null ? Labels.EMPTY : labels);
        return gauges.computeIfAbsent(name, n -> new ConcurrentHashMap<>())
                .computeIfAbsent(eff, l -> new Gauge());
    }

    // ---- summaries ----

    /** Get-or-create the unlabelled summary {@code name}. */
    public Summary summary(String name) {
        return summary(name, Labels.EMPTY);
    }

    /** Get-or-create the summary {@code name} with {@code labels} (subject to the cardinality cap). */
    public Summary summary(String name, Labels labels) {
        requireValidName(name);
        claimType(name, MetricType.SUMMARY);
        Labels eff = guard.admit(name, labels == null ? Labels.EMPTY : labels);
        return summaries.computeIfAbsent(name, n -> new ConcurrentHashMap<>())
                .computeIfAbsent(eff, l -> new Summary());
    }

    // ---- read side (for the exporter) ----

    /** All gauge metric names, sorted. */
    public java.util.SortedSet<String> gaugeNames() {
        return new TreeSet<>(gauges.keySet());
    }

    /** All summary metric names, sorted. */
    public java.util.SortedSet<String> summaryNames() {
        return new TreeSet<>(summaries.keySet());
    }

    /** Live series for a gauge metric ({@code labels -> gauge}); empty if none. */
    public Map<Labels, Gauge> gaugeSeries(String name) {
        ConcurrentHashMap<Labels, Gauge> m = gauges.get(name);
        return m == null ? Collections.emptyMap() : Collections.unmodifiableMap(m);
    }

    /** Live series for a summary metric ({@code labels -> summary}); empty if none. */
    public Map<Labels, Summary> summarySeries(String name) {
        ConcurrentHashMap<Labels, Summary> m = summaries.get(name);
        return m == null ? Collections.emptyMap() : Collections.unmodifiableMap(m);
    }

    // ---- internals ----

    private void claimType(String name, MetricType type) {
        MetricType prev = types.putIfAbsent(name, type);
        if (prev != null && prev != type) {
            throw new IllegalStateException("metric '" + name + "' already registered as " + prev
                    + ", cannot reuse as " + type);
        }
    }

    private static void requireValidName(String name) {
        if (!Labels.isValidName(name)) {
            throw new IllegalArgumentException("invalid metric name '" + name
                    + "' (must match [a-zA-Z_][a-zA-Z0-9_]*)");
        }
    }
}
