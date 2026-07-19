// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.logging;

import java.time.Instant;

/**
 * Typed builders for the Victus control-plane structured-log events named in
 * {@code docs/phase-2/01-metrics-observability.md}:
 * {@code throttle}, {@code doctor.applied}, {@code limit}, {@code drain} and {@code migration}.
 *
 * <p>Each factory returns a {@link StructuredLogRecord.Builder} pre-populated with the event
 * discriminator, an appropriate {@code logger}, a default {@link LogLevel#INFO} level, and the event's
 * typed payload fields <b>in the spec's field order</b>. The caller may still refine the record — for
 * example add {@code .thread(...)} / {@code .msg(...)} or bump the level — before {@code .build()}.
 *
 * <p>The {@link #throttle} factory reproduces the spec's example object exactly when built with no
 * extra fields.
 */
public final class LogEvents {

    /** Logger name for per-instance limit / throttle events (module 04). */
    public static final String LOGGER_LIMITS = "victus.limits";
    /** Logger name for lag-doctor remediation events (module 02). */
    public static final String LOGGER_DOCTOR = "victus.doctor";
    /** Logger name for instance drain events. */
    public static final String LOGGER_DRAIN = "victus.drain";
    /** Logger name for cross-node migration events. */
    public static final String LOGGER_MIGRATION = "victus.migration";

    private LogEvents() {
    }

    /**
     * {@code throttle} — an adaptive limiter reduced a subsystem's workload to defend MSPT.
     * Matches the spec example: {@code subsystem, action, from, to, mspt_p95}.
     *
     * @param ts        event time
     * @param subsystem the subsystem being throttled (e.g. {@code entities})
     * @param action    the remediation applied (e.g. {@code reduce-ai-radius})
     * @param from      the previous value of the tuned setting
     * @param to        the new (reduced) value
     * @param msptP95   the p95 MSPT that triggered the throttle
     */
    public static StructuredLogRecord.Builder throttle(Instant ts, String subsystem, String action,
                                                       int from, int to, double msptP95) {
        return StructuredLogRecord.builder()
                .ts(ts)
                .level(LogLevel.INFO)
                .logger(LOGGER_LIMITS)
                .event("throttle")
                .field("subsystem", subsystem)
                .field("action", action)
                .field("from", from)
                .field("to", to)
                .field("mspt_p95", msptP95);
    }

    /**
     * {@code doctor.applied} — the lag-doctor automatically applied a fix.
     * Payload: {@code fix, subsystem, reason, mspt_p95}.
     *
     * @param ts        event time
     * @param fix       the fix identifier applied (e.g. {@code enable-dab})
     * @param subsystem the subsystem the fix targets (e.g. {@code entities})
     * @param reason    a short human-readable justification
     * @param msptP95   the p95 MSPT that motivated the fix
     */
    public static StructuredLogRecord.Builder doctorApplied(Instant ts, String fix, String subsystem,
                                                            String reason, double msptP95) {
        return StructuredLogRecord.builder()
                .ts(ts)
                .level(LogLevel.INFO)
                .logger(LOGGER_DOCTOR)
                .event("doctor.applied")
                .field("fix", fix)
                .field("subsystem", subsystem)
                .field("reason", reason)
                .field("mspt_p95", msptP95);
    }

    /**
     * {@code limit} — a per-instance hosting limit was reached and enforced.
     * Payload: {@code resource, limit, current, action}.
     *
     * @param ts       event time
     * @param resource the limited resource (e.g. {@code entities}, {@code chunks})
     * @param limit    the configured cap
     * @param current  the observed value at enforcement time
     * @param action   the enforcement action taken (e.g. {@code reject-spawn})
     */
    public static StructuredLogRecord.Builder limit(Instant ts, String resource, long limit,
                                                    long current, String action) {
        return StructuredLogRecord.builder()
                .ts(ts)
                .level(LogLevel.WARN)
                .logger(LOGGER_LIMITS)
                .event("limit")
                .field("resource", resource)
                .field("limit", limit)
                .field("current", current)
                .field("action", action);
    }

    /**
     * {@code drain} — an instance began draining its players (e.g. before restart or migration).
     * Payload: {@code reason, players, target}.
     *
     * @param ts      event time
     * @param reason  why the drain was initiated (e.g. {@code node-restart})
     * @param players the number of players being moved off the instance
     * @param target  where players are being sent (e.g. a hub or fallback server name)
     */
    public static StructuredLogRecord.Builder drain(Instant ts, String reason, int players,
                                                    String target) {
        return StructuredLogRecord.builder()
                .ts(ts)
                .level(LogLevel.INFO)
                .logger(LOGGER_DRAIN)
                .event("drain")
                .field("reason", reason)
                .field("players", players)
                .field("target", target);
    }

    /**
     * {@code migration} — an instance is moving between nodes.
     * Payload: {@code instance, from, to, phase}.
     *
     * @param ts       event time
     * @param instance the instance identifier being migrated
     * @param from     the source node
     * @param to       the destination node
     * @param phase    the migration phase (e.g. {@code start}, {@code transfer}, {@code complete})
     */
    public static StructuredLogRecord.Builder migration(Instant ts, String instance, String from,
                                                        String to, String phase) {
        return StructuredLogRecord.builder()
                .ts(ts)
                .level(LogLevel.INFO)
                .logger(LOGGER_MIGRATION)
                .event("migration")
                .field("instance", instance)
                .field("from", from)
                .field("to", to)
                .field("phase", phase);
    }
}
