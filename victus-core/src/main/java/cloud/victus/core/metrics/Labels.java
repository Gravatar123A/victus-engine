// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * An immutable, canonically-ordered set of Prometheus label key/value pairs.
 *
 * <p>Keys are stored sorted (a {@link TreeMap}) so that two {@code Labels} built from the same pairs
 * in any order are {@link #equals(Object) equal} and render identically — this is what makes a series'
 * identity stable across scrapes and lets it be used as a map key in {@link MetricRegistry}.
 *
 * <p>Label names are validated against the Prometheus data-model rule
 * ({@code [a-zA-Z_][a-zA-Z0-9_]*}); values are arbitrary UTF-8 and are escaped only at exposition
 * time (see {@link PrometheusExposition}). This type is dependency-free — no Bukkit, no YAML.
 */
public final class Labels {

    /** The shared empty label set (an unlabelled series such as {@code victus_tps}). */
    public static final Labels EMPTY = new Labels(Collections.emptySortedMap());

    private final TreeMap<String, String> pairs;
    private final int hash;

    private Labels(Map<String, String> sorted) {
        this.pairs = new TreeMap<>(sorted);
        this.hash = this.pairs.hashCode();
    }

    /** An unlabelled set. */
    public static Labels of() {
        return EMPTY;
    }

    /** A single-label set. */
    public static Labels of(String k, String v) {
        TreeMap<String, String> m = new TreeMap<>();
        putValidated(m, k, v);
        return new Labels(m);
    }

    /** A two-label set. */
    public static Labels of(String k1, String v1, String k2, String v2) {
        TreeMap<String, String> m = new TreeMap<>();
        putValidated(m, k1, v1);
        putValidated(m, k2, v2);
        return new Labels(m);
    }

    /** A three-label set. */
    public static Labels of(String k1, String v1, String k2, String v2, String k3, String v3) {
        TreeMap<String, String> m = new TreeMap<>();
        putValidated(m, k1, v1);
        putValidated(m, k2, v2);
        putValidated(m, k3, v3);
        return new Labels(m);
    }

    /** Build from an arbitrary map (order-independent; validated and copied defensively). */
    public static Labels of(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) return EMPTY;
        TreeMap<String, String> m = new TreeMap<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            putValidated(m, e.getKey(), e.getValue());
        }
        return new Labels(m);
    }

    /**
     * Return a copy with one additional label. Used internally to merge a synthetic {@code quantile}
     * label onto a summary's base labels for exposition.
     *
     * @throws IllegalArgumentException if {@code key} already exists (labels must be unambiguous)
     */
    public Labels with(String key, String value) {
        if (pairs.containsKey(key)) {
            throw new IllegalArgumentException("duplicate label '" + key + "' in " + this);
        }
        TreeMap<String, String> m = new TreeMap<>(pairs);
        putValidated(m, key, value);
        return new Labels(m);
    }

    /**
     * Collapse this label set into the cardinality-overflow bucket: every value becomes
     * {@link CardinalityGuard#OVERFLOW_VALUE}, keeping the label <em>keys</em> so the overflow series
     * stays schema-compatible with the metric's other series. An empty set has no overflow (a single
     * unlabelled series can never blow up cardinality), so {@code EMPTY} is returned unchanged.
     */
    Labels overflow() {
        if (pairs.isEmpty()) return this;
        TreeMap<String, String> m = new TreeMap<>();
        for (String k : pairs.keySet()) {
            m.put(k, CardinalityGuard.OVERFLOW_VALUE);
        }
        return new Labels(m);
    }

    /** {@code true} if there are no labels. */
    public boolean isEmpty() {
        return pairs.isEmpty();
    }

    /** Number of labels. */
    public int size() {
        return pairs.size();
    }

    /** The value for {@code key}, or {@code null}. */
    public String get(String key) {
        return pairs.get(key);
    }

    /** An unmodifiable, key-sorted view of the pairs. */
    public Map<String, String> asMap() {
        return Collections.unmodifiableSortedMap(pairs);
    }

    private static void putValidated(Map<String, String> m, String key, String value) {
        if (!isValidName(key)) {
            throw new IllegalArgumentException("invalid label name '" + key
                    + "' (must match [a-zA-Z_][a-zA-Z0-9_]*)");
        }
        if (value == null) {
            throw new IllegalArgumentException("null value for label '" + key + "'");
        }
        m.put(key, value);
    }

    /** Prometheus data-model name rule, shared by metric names and label names. */
    static boolean isValidName(String s) {
        if (s == null || s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (!(c0 == '_' || (c0 >= 'a' && c0 <= 'z') || (c0 >= 'A' && c0 <= 'Z'))) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Labels)) return false;
        return pairs.equals(((Labels) o).pairs);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /** Human-readable {@code {k="v",...}} (unescaped) for logs and diagnostics. */
    @Override
    public String toString() {
        if (pairs.isEmpty()) return "{}";
        Map<String, String> ordered = new LinkedHashMap<>(pairs);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : ordered.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append("=\"").append(e.getValue()).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }
}
