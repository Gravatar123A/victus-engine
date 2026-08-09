// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.config;

/** Per-instance tick threading tier. See docs/design/modules/threading-tiers.md. */
public enum ThreadingMode {
    /** One tick thread plus async offload. Default compatibility target; test each plugin set. */
    SINGLE,
    /** Unfinished experimental parallel mode; not a published capability. */
    PARALLEL,
    /** Unfinished experimental regionized mode; not a published capability. */
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
