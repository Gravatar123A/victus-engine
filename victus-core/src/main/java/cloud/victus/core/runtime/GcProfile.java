// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

import java.util.Locale;

/**
 * The garbage collector Victus knows about — used both as the value of {@code optimizations.gc.profile}
 * (what the operator <em>expects</em> to be running) and as the result of runtime detection
 * (what is <em>actually</em> running).
 *
 * <p>Per <a href="file:docs/phase-1/07-gc.md">docs/phase-1/07-gc.md</a> the collector is chosen by JVM
 * <b>launch flags, never at runtime</b> — this module only <b>detects</b> the active collector and
 * <b>advises</b>; it never sets or changes a JVM flag.
 *
 * <p>{@link #UNKNOWN} is the fail-safe bucket: if the running JDK exposes GC MXBean names this module
 * does not recognise, detection degrades to {@code UNKNOWN} and the advisor stays quiet rather than
 * raising a false alarm.
 *
 * <p>Note the config schema also accepts the string {@code auto} ("accept whatever is running; suppress
 * the mismatch warning"). {@code auto} is a <em>directive</em>, not a collector, so it has no enum
 * constant — use {@link #isAuto(String)} before {@link #fromConfig(String)}.
 */
public enum GcProfile {
    /** Generational ZGC ({@code -XX:+UseZGC -XX:+ZGenerational}) — the Victus recommended default. */
    ZGC_GENERATIONAL,
    /** Single-generation ZGC ({@code -XX:+UseZGC} without generational mode) — discouraged (throughput loss). */
    ZGC,
    /** HotSpot G1 — the legacy Aikar baseline; retained as the fallback for tiny/CPU-starved plans. */
    G1,
    /** Red Hat / OpenJDK Shenandoah low-pause collector. */
    SHENANDOAH,
    /** The throughput-oriented Parallel collector. */
    PARALLEL,
    /** The single-threaded Serial collector. */
    SERIAL,
    /** Unrecognised / undetectable collector — the fail-safe bucket. */
    UNKNOWN;

    /** The {@code optimizations.gc.profile} config directive that accepts whatever collector is running. */
    public static final String AUTO = "auto";

    /** {@code true} if a raw {@code optimizations.gc.profile} value is the {@code auto} directive. */
    public static boolean isAuto(String configValue) {
        return configValue != null && AUTO.equalsIgnoreCase(configValue.trim());
    }

    /** {@code true} for either generational or non-generational ZGC. */
    public boolean isZgc() {
        return this == ZGC || this == ZGC_GENERATIONAL;
    }

    /** {@code true} only for Generational ZGC. */
    public boolean isGenerationalZgc() {
        return this == ZGC_GENERATIONAL;
    }

    /** The canonical {@code optimizations.gc.profile} spelling for this collector. */
    public String configName() {
        switch (this) {
            case ZGC_GENERATIONAL: return "zgc-generational";
            case ZGC:              return "zgc";
            case G1:               return "g1";
            case SHENANDOAH:       return "shenandoah";
            case PARALLEL:         return "parallel";
            case SERIAL:           return "serial";
            case UNKNOWN:
            default:               return "unknown";
        }
    }

    /** A human-readable label for boot warnings and {@code /victus doctor}. */
    public String displayName() {
        switch (this) {
            case ZGC_GENERATIONAL: return "Generational ZGC";
            case ZGC:              return "ZGC (non-generational)";
            case G1:               return "G1";
            case SHENANDOAH:       return "Shenandoah";
            case PARALLEL:         return "Parallel";
            case SERIAL:           return "Serial";
            case UNKNOWN:
            default:               return "an unrecognized collector";
        }
    }

    /**
     * Parse an {@code optimizations.gc.profile} value to a collector.
     *
     * <p>Does <b>not</b> accept the {@code auto} directive (it is not a collector) — callers must handle
     * {@link #isAuto(String)} first. Throws on {@code null} and on unrecognised values, consistent with
     * the rest of {@code cloud.victus.core.config}.
     */
    public static GcProfile fromConfig(String s) {
        if (s == null) {
            throw new IllegalArgumentException("optimizations.gc.profile is null "
                    + "(expected zgc-generational|zgc|g1|shenandoah|parallel|serial|auto)");
        }
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "zgc-generational":
            case "zgc_generational":
            case "generational-zgc": return ZGC_GENERATIONAL;
            case "zgc":              return ZGC;
            case "g1":
            case "g1gc":             return G1;
            case "shenandoah":       return SHENANDOAH;
            case "parallel":
            case "parallelgc":       return PARALLEL;
            case "serial":
            case "serialgc":         return SERIAL;
            case "auto":
                throw new IllegalArgumentException("optimizations.gc.profile 'auto' is a directive "
                        + "(accept whatever collector is running), not a collector target; call "
                        + "GcProfile.isAuto(...) before fromConfig(...)");
            default:
                throw new IllegalArgumentException("unknown optimizations.gc.profile: " + s
                        + " (expected zgc-generational|zgc|g1|shenandoah|parallel|serial|auto)");
        }
    }
}
