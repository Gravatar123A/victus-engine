// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.config;

/** Per-instance tick threading tier. See docs/design/modules/threading-tiers.md. */
public enum ThreadingMode {
    /** One tick thread + async offload. 100% plugin-compatible. Default. */
    SINGLE,
    /** Barrier-synchronized parallel world/region ticking. Keeps plugin compat. */
    PARALLEL,
    /** Folia-style regionized ticking. Massive concurrency; Folia-aware plugins only. */
    REGIONIZED;

    public static ThreadingMode fromConfig(String s) {
        if (s == null) return SINGLE;
        switch (s.trim().toLowerCase()) {
            case "single":     return SINGLE;
            case "parallel":   return PARALLEL;
            case "regionized": return REGIONIZED;
            default:
                throw new IllegalArgumentException("unknown threading.mode: " + s
                        + " (expected single|parallel|regionized)");
        }
    }
}
