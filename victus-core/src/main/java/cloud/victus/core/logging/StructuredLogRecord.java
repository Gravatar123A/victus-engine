// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.logging;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable structured log record, serialized as a single compact JSON object by {@link #toJson()}
 * — the {@code hosting.logging.format: json} shape from {@code docs/phase-2/01-metrics-observability.md}.
 *
 * <p>The base fields are emitted in a stable order: {@code ts}, {@code level}, {@code logger}, then the
 * optional {@code thread} and {@code msg} (omitted entirely when {@code null}), then the optional
 * {@code event} discriminator, and finally the event's typed structured fields in insertion order.
 * With {@code thread}, {@code msg} unset this reproduces the spec's throttle example verbatim:
 * <pre>
 * {"ts":"2026-07-19T10:11:12.000Z","level":"INFO","logger":"victus.limits","event":"throttle",
 *  "subsystem":"entities","action":"reduce-ai-radius","from":48,"to":32,"mspt_p95":58.2}
 * </pre>
 *
 * <p>Use {@link #builder()} for ad-hoc records or {@link LogEvents} for the typed control-plane events
 * ({@code throttle}, {@code doctor.applied}, {@code limit}, {@code drain}, {@code migration}).
 */
public final class StructuredLogRecord {

    /**
     * Timestamp format used across Victus structured logs: ISO-8601, always UTC, always exactly three
     * fractional (millisecond) digits and a literal {@code Z}, e.g. {@code 2026-07-19T10:11:12.000Z}.
     */
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final String ts;
    private final LogLevel level;
    private final String logger;
    private final String thread;
    private final String msg;
    private final String event;
    private final Map<String, Object> fields;

    private StructuredLogRecord(Builder b) {
        this.ts = Objects.requireNonNull(b.ts, "ts");
        this.level = Objects.requireNonNull(b.level, "level");
        this.logger = Objects.requireNonNull(b.logger, "logger");
        this.thread = b.thread;
        this.msg = b.msg;
        this.event = b.event;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(b.fields));
    }

    /** @return a fresh builder (level defaults to {@link LogLevel#INFO}). */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Format an {@link Instant} to the canonical Victus log timestamp
     * (UTC, millisecond precision, e.g. {@code 2026-07-19T10:11:12.000Z}).
     */
    public static String formatTimestamp(Instant instant) {
        return TS_FORMAT.format(Objects.requireNonNull(instant, "instant"));
    }

    // ---- accessors ----

    public String ts() {
        return ts;
    }

    public LogLevel level() {
        return level;
    }

    public String logger() {
        return logger;
    }

    /** @return the emitting thread name, or {@code null} if unset (then omitted from JSON). */
    public String thread() {
        return thread;
    }

    /** @return the human-readable message, or {@code null} if unset (then omitted from JSON). */
    public String msg() {
        return msg;
    }

    /** @return the control-plane event discriminator, or {@code null} for a plain log line. */
    public String event() {
        return event;
    }

    /** @return an unmodifiable, ordered view of the typed structured fields. */
    public Map<String, Object> fields() {
        return fields;
    }

    /**
     * Serialize to a single compact JSON object.
     *
     * @return the record as one line of JSON (no trailing newline)
     */
    public String toJson() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("ts", ts);
        out.put("level", level.name());
        out.put("logger", logger);
        if (thread != null) {
            out.put("thread", thread);
        }
        if (msg != null) {
            out.put("msg", msg);
        }
        if (event != null) {
            out.put("event", event);
        }
        out.putAll(fields);
        return JsonWriter.write(out);
    }

    @Override
    public String toString() {
        return toJson();
    }

    /**
     * Mutable builder for {@link StructuredLogRecord}. Field insertion order is preserved and drives
     * the JSON field order after the base fields. Not thread-safe; build once and share the immutable
     * result.
     */
    public static final class Builder {
        private String ts;
        private LogLevel level = LogLevel.INFO;
        private String logger;
        private String thread;
        private String msg;
        private String event;
        private final LinkedHashMap<String, Object> fields = new LinkedHashMap<>();

        private Builder() {
        }

        /** Set the timestamp from an {@link Instant} (formatted via {@link #formatTimestamp}). */
        public Builder ts(Instant instant) {
            this.ts = formatTimestamp(instant);
            return this;
        }

        /** Set an already-formatted timestamp string (e.g. when replaying upstream records). */
        public Builder ts(String formatted) {
            this.ts = formatted;
            return this;
        }

        public Builder level(LogLevel level) {
            this.level = level;
            return this;
        }

        public Builder logger(String logger) {
            this.logger = logger;
            return this;
        }

        public Builder thread(String thread) {
            this.thread = thread;
            return this;
        }

        public Builder msg(String msg) {
            this.msg = msg;
            return this;
        }

        public Builder event(String event) {
            this.event = event;
            return this;
        }

        /**
         * Append a typed structured field. Later calls with the same key overwrite the value but keep
         * the original position (standard {@link LinkedHashMap} semantics).
         *
         * @param key   the JSON field name
         * @param value any {@link JsonWriter}-supported value (String, Number, Boolean, null, ...)
         */
        public Builder field(String key, Object value) {
            this.fields.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        public StructuredLogRecord build() {
            return new StructuredLogRecord(this);
        }

        /** Convenience: {@link #build()} then {@link StructuredLogRecord#toJson()}. */
        public String toJson() {
            return build().toJson();
        }
    }
}
