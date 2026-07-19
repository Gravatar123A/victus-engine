// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.metrics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounds the number of distinct label-sets a single metric may mint, so a pathological world (a mob
 * farm with thousands of entity types, a plugin labelling by player name, …) can't explode Prometheus
 * cardinality or the registry's memory.
 *
 * <p>Per metric name the first {@code limit} distinct label-sets are admitted unchanged. Any further
 * distinct set is folded into a single <b>overflow bucket</b> — the same label keys with every value
 * replaced by {@link #OVERFLOW_VALUE} (see {@link Labels#overflow()}). Overflow therefore adds at most
 * one extra series per metric, giving a hard ceiling of {@code limit + 1} series. A {@code limit <= 0}
 * disables capping entirely (the {@code cardinality-limit} escape hatch from victus.yml).
 *
 * <p>Admission is monotonic and idempotent: an already-admitted set is always returned as-is, so a
 * series' identity is stable across scrapes.
 */
public final class CardinalityGuard {

    /** Sentinel value used for every label in the overflow bucket ({@code metric{world="other"}}). */
    public static final String OVERFLOW_VALUE = "other";

    private final int limit;
    private final ConcurrentHashMap<String, Set<Labels>> admitted = new ConcurrentHashMap<>();

    public CardinalityGuard(int limit) {
        this.limit = limit;
    }

    /** The configured cap ({@code <= 0} means unlimited). */
    public int limit() {
        return limit;
    }

    /**
     * Return the label-set to actually use for {@code metric}: either {@code labels} (admitted) or the
     * overflow bucket (capped). Thread-safe.
     */
    public Labels admit(String metric, Labels labels) {
        if (limit <= 0 || labels == null || labels.isEmpty()) {
            return labels == null ? Labels.EMPTY : labels;
        }
        Set<Labels> set = admitted.computeIfAbsent(metric, k -> new HashSet<>());
        synchronized (set) {
            if (set.contains(labels)) return labels;
            if (set.size() < limit) {
                set.add(labels);
                return labels;
            }
            Labels overflow = labels.overflow();
            set.add(overflow); // counted once; further overflow maps here and is already present
            return overflow;
        }
    }

    /**
     * Whether {@code labels} would be capped to the overflow bucket right now, without admitting it.
     * (Diagnostic only; does not mutate state.)
     */
    public boolean isOverflowing(String metric, Labels labels) {
        if (limit <= 0 || labels == null || labels.isEmpty()) return false;
        Set<Labels> set = admitted.get(metric);
        if (set == null) return false;
        synchronized (set) {
            if (set.contains(labels)) return false;
            return set.size() >= limit;
        }
    }

    /** Number of distinct series (including the overflow bucket, if any) tracked for {@code metric}. */
    public int distinctCount(String metric) {
        Set<Labels> set = admitted.get(metric);
        if (set == null) return 0;
        synchronized (set) {
            return set.size();
        }
    }

    /** Snapshot of per-metric distinct counts, for diagnostics / {@code /victus doctor}. */
    public Map<String, Integer> distinctCounts() {
        ConcurrentHashMap<String, Integer> out = new ConcurrentHashMap<>();
        for (Map.Entry<String, Set<Labels>> e : admitted.entrySet()) {
            Set<Labels> set = e.getValue();
            synchronized (set) {
                out.put(e.getKey(), set.size());
            }
        }
        return out;
    }
}
