// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.logging;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dependency-free self-test (no JUnit needed, so it runs offline with just the JDK):
 * <pre>
 *   javac -encoding UTF-8 -d out src/main/java/cloud/victus/core/config/*.java \
 *                                src/main/java/cloud/victus/core/logging/*.java
 *   java -cp out cloud.victus.core.logging.JsonLogSelfTest
 * </pre>
 * Covers the JSON serializer's escaping + number formatting, the {@link StructuredLogRecord} field
 * ordering/omission, the typed {@link LogEvents} builders, exact reproduction of the Phase-2 spec's
 * throttle example, and full round-trip through the bundled {@link JsonParser}.
 * Replaced by proper JUnit tests once the repo builds online.
 */
public final class JsonLogSelfTest {
    private static int passed = 0, failed = 0;

    /** e-acute (U+00E9) and snowman (U+2603), used to prove non-ASCII passes through verbatim. */
    private static final String EACUTE = "é";
    private static final String SNOWMAN = "☃";
    /** DEL (U+007F) is a control char but >= 0x20, so it is NOT escaped by the writer. */
    private static final String DEL = new String(new char[]{0x7f});
    /** Raw NUL (U+0000), for exercising escaping in the round-trip. */
    private static final String NUL = new String(new char[]{0x00});

    public static void main(String[] args) {
        testEscaping();
        testNumbersAndValues();
        testTimestamp();
        testThrottleMatchesSpecExactly();
        testBaseFieldOrderingAndOmission();
        testEventBuilders();
        testRoundTrip();
        testParser();

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        StructuredLogRecord sample = LogEvents.throttle(
                Instant.parse("2026-07-19T10:11:12Z"), "entities", "reduce-ai-radius", 48, 32, 58.2).build();
        System.out.println("Sample record: " + sample.toJson());
        if (failed > 0) System.exit(1);
    }

    // 1. String escaping (quotes, backslashes, short escapes, unicode control chars, non-ASCII).
    private static void testEscaping() {
        eq("quote plain", "\"hello\"", JsonWriter.quote("hello"));
        eq("quote empty", "\"\"", JsonWriter.quote(""));
        eq("quote quote+backslash", "\"a\\\"b\\\\c\"", JsonWriter.quote("a\"b\\c"));
        eq("quote newline/tab/cr", "\"a\\nb\\tc\\rd\"", JsonWriter.quote("a\nb\tc\rd"));
        eq("quote backspace+formfeed", "\"\\b\\f\"", JsonWriter.quote("\b\f"));
        // NUL, BEL(0x07), unit-separator(0x1f): no short escape -> \\u00XX (lowercase hex).
        String controls = new String(new char[]{0x00, 0x07, 0x1f});
        eq("quote control chars -> \\u00xx", "\"\\u0000\\u0007\\u001f\"", JsonWriter.quote(controls));
        // DEL (>= 0x20) is emitted raw; non-ASCII (e-acute, snowman) passes through verbatim.
        String delAndUnicode = DEL + " caf" + EACUTE + " " + SNOWMAN;
        String expectedDel = "\"" + DEL + " caf" + EACUTE + " " + SNOWMAN + "\"";
        eq("quote DEL + non-ASCII verbatim", expectedDel, JsonWriter.quote(delAndUnicode));
        check("escaped output has no raw newline", JsonWriter.quote("x\ny").indexOf('\n') < 0);
    }

    // 2. Value serialization: number typing, booleans, null, nested structures.
    private static void testNumbersAndValues() {
        eq("int -> no decimal", "48", JsonWriter.write(48));
        eq("long verbatim", "9000000000", JsonWriter.write(9_000_000_000L));
        eq("double 58.2", "58.2", JsonWriter.write(58.2));
        eq("whole double keeps .0", "32.0", JsonWriter.write(32.0));
        eq("negative double", "-1.5", JsonWriter.write(-1.5));
        eq("boolean true", "true", JsonWriter.write(true));
        eq("boolean false", "false", JsonWriter.write(false));
        eq("null literal", "null", JsonWriter.write(null));
        eq("NaN -> null (no JSON NaN)", "null", JsonWriter.write(Double.NaN));
        eq("Infinity -> null", "null", JsonWriter.write(Double.POSITIVE_INFINITY));
        eq("nested object ordered", "{\"a\":1,\"b\":[true,null,\"x\"]}",
                JsonWriter.write(ordered("a", 1, "b", java.util.Arrays.asList(true, null, "x"))));
        eq("array of mixed", "[1,\"two\",3.5,false,null]",
                JsonWriter.write(java.util.Arrays.asList(1, "two", 3.5, false, null)));
    }

    // 3. Timestamp formatting: always UTC, always millisecond precision, literal Z.
    private static void testTimestamp() {
        eq("instant zero millis", "2026-07-19T10:11:12.000Z",
                StructuredLogRecord.formatTimestamp(Instant.parse("2026-07-19T10:11:12Z")));
        eq("instant with millis", "2026-07-19T10:11:12.345Z",
                StructuredLogRecord.formatTimestamp(Instant.parse("2026-07-19T10:11:12.345Z")));
        // A non-UTC offset instant is normalized to UTC.
        eq("offset normalized to UTC", "2026-07-19T06:11:12.000Z",
                StructuredLogRecord.formatTimestamp(Instant.parse("2026-07-19T10:11:12+04:00")));
    }

    // 4. The headline test: reproduce the spec's throttle example byte-for-byte.
    private static void testThrottleMatchesSpecExactly() {
        String expected = "{\"ts\":\"2026-07-19T10:11:12.000Z\",\"level\":\"INFO\","
                + "\"logger\":\"victus.limits\",\"event\":\"throttle\",\"subsystem\":\"entities\","
                + "\"action\":\"reduce-ai-radius\",\"from\":48,\"to\":32,\"mspt_p95\":58.2}";
        String actual = LogEvents.throttle(Instant.parse("2026-07-19T10:11:12Z"),
                "entities", "reduce-ai-radius", 48, 32, 58.2).build().toJson();
        eq("throttle reproduces spec example exactly", expected, actual);
    }

    // 5. Base-field ordering, and omission of null thread/msg.
    private static void testBaseFieldOrderingAndOmission() {
        String noExtras = StructuredLogRecord.builder()
                .ts(Instant.parse("2026-07-19T00:00:00Z"))
                .level(LogLevel.WARN)
                .logger("victus.core")
                .build().toJson();
        eq("no thread/msg/event omitted",
                "{\"ts\":\"2026-07-19T00:00:00.000Z\",\"level\":\"WARN\",\"logger\":\"victus.core\"}",
                noExtras);

        String full = StructuredLogRecord.builder()
                .ts(Instant.parse("2026-07-19T00:00:00Z"))
                .level(LogLevel.INFO)
                .logger("victus.core")
                .thread("Server thread")
                .msg("started")
                .event("boot")
                .field("phase", "ready")
                .build().toJson();
        eq("full base-field order ts,level,logger,thread,msg,event,fields",
                "{\"ts\":\"2026-07-19T00:00:00.000Z\",\"level\":\"INFO\",\"logger\":\"victus.core\","
                        + "\"thread\":\"Server thread\",\"msg\":\"started\",\"event\":\"boot\",\"phase\":\"ready\"}",
                full);
    }

    // 6. Each typed event builder: discriminator, logger, and payload fields/order.
    private static void testEventBuilders() {
        Instant t = Instant.parse("2026-07-19T12:00:00Z");

        Map<String, Object> doctor = parseObj(LogEvents.doctorApplied(t, "enable-dab", "entities",
                "sustained p95 over budget", 61.4).build().toJson());
        eq("doctor.applied event name", "doctor.applied", doctor.get("event"));
        eq("doctor.applied logger", "victus.doctor", doctor.get("logger"));
        eq("doctor.applied fix", "enable-dab", doctor.get("fix"));
        eq("doctor.applied subsystem", "entities", doctor.get("subsystem"));
        eq("doctor.applied reason", "sustained p95 over budget", doctor.get("reason"));
        eq("doctor.applied mspt_p95", 61.4, ((Number) doctor.get("mspt_p95")).doubleValue());

        Map<String, Object> limit = parseObj(LogEvents.limit(t, "entities", 5000, 5001, "reject-spawn")
                .build().toJson());
        eq("limit event name", "limit", limit.get("event"));
        eq("limit logger", "victus.limits", limit.get("logger"));
        eq("limit level is WARN", "WARN", limit.get("level"));
        eq("limit resource", "entities", limit.get("resource"));
        eq("limit limit", 5000L, ((Number) limit.get("limit")).longValue());
        eq("limit current", 5001L, ((Number) limit.get("current")).longValue());
        eq("limit action", "reject-spawn", limit.get("action"));

        Map<String, Object> drain = parseObj(LogEvents.drain(t, "node-restart", 17, "hub-1")
                .build().toJson());
        eq("drain event name", "drain", drain.get("event"));
        eq("drain logger", "victus.drain", drain.get("logger"));
        eq("drain reason", "node-restart", drain.get("reason"));
        eq("drain players", 17L, ((Number) drain.get("players")).longValue());
        eq("drain target", "hub-1", drain.get("target"));

        Map<String, Object> mig = parseObj(LogEvents.migration(t, "smp-42", "de-1", "mumbai-1", "start")
                .build().toJson());
        eq("migration event name", "migration", mig.get("event"));
        eq("migration logger", "victus.migration", mig.get("logger"));
        eq("migration instance", "smp-42", mig.get("instance"));
        eq("migration from", "de-1", mig.get("from"));
        eq("migration to", "mumbai-1", mig.get("to"));
        eq("migration phase", "start", mig.get("phase"));

        // Builder is refinable: caller can add thread/msg and override level after the factory.
        String refined = LogEvents.throttle(t, "redstone", "defer-ticks", 10, 4, 47.0)
                .thread("Region Scheduler-3").msg("throttling redstone").level(LogLevel.DEBUG)
                .build().toJson();
        Map<String, Object> r = parseObj(refined);
        eq("refined event keeps event", "throttle", r.get("event"));
        eq("refined event overrides level", "DEBUG", r.get("level"));
        eq("refined event adds thread", "Region Scheduler-3", r.get("thread"));
        eq("refined event adds msg", "throttling redstone", r.get("msg"));
    }

    // 7. Round-trip: nasty characters survive write -> parse unchanged; field order stable.
    private static void testRoundTrip() {
        // Includes an embedded quote, backslash, newline, tab, a raw NUL, and a snowman.
        String nasty = "quote:\" backslash:\\ newline:\n tab:\t nul:" + NUL + " snow:" + SNOWMAN + " done";
        String json = StructuredLogRecord.builder()
                .ts(Instant.parse("2026-07-19T10:11:12.500Z"))
                .level(LogLevel.ERROR)
                .logger("victus.test")
                .thread("t\"1")
                .msg(nasty)
                .event("throttle")
                .field("subsystem", "entities")
                .field("from", 48)
                .field("to", 32)
                .field("mspt_p95", 58.2)
                .build().toJson();

        // The NUL must have been escaped on the wire: no raw NUL byte, but an "\\u0000" token present.
        check("round-trip escapes raw NUL on the wire", json.indexOf(0) < 0 && json.contains("\\u0000"));

        Map<String, Object> back = parseObj(json);
        eq("round-trip ts", "2026-07-19T10:11:12.500Z", back.get("ts"));
        eq("round-trip level", "ERROR", back.get("level"));
        eq("round-trip logger", "victus.test", back.get("logger"));
        eq("round-trip thread with quote", "t\"1", back.get("thread"));
        eq("round-trip nasty msg preserved exactly", nasty, back.get("msg"));
        eq("round-trip event", "throttle", back.get("event"));
        eq("round-trip int field stays integral", 48L, ((Number) back.get("from")).longValue());
        eq("round-trip double field", 58.2, ((Number) back.get("mspt_p95")).doubleValue());
        check("round-trip preserves key order",
                new java.util.ArrayList<>(back.keySet()).equals(List.of(
                        "ts", "level", "logger", "thread", "msg", "event",
                        "subsystem", "from", "to", "mspt_p95")));
    }

    // 8. The bundled parser on its own: empties, nesting, escapes, and rejection of bad input.
    private static void testParser() {
        check("parse empty object", ((Map<?, ?>) JsonParser.parse("{}")).isEmpty());
        check("parse empty array", ((List<?>) JsonParser.parse("[]")).isEmpty());
        check("parse whitespace tolerated", ((Map<?, ?>) JsonParser.parse("  {\n  }  ")).isEmpty());

        Map<String, Object> m = parseObj("{\"a\":[1,2,{\"b\":true}],\"c\":null,\"d\":-3.5e1}");
        List<?> a = (List<?>) m.get("a");
        eq("parse nested array size", 3, a.size());
        eq("parse nested int", 2L, ((Number) a.get(1)).longValue());
        eq("parse deeply nested bool", Boolean.TRUE, ((Map<?, ?>) a.get(2)).get("b"));
        check("parse explicit null", m.containsKey("c") && m.get("c") == null);
        eq("parse exponent double", -35.0, ((Number) m.get("d")).doubleValue());

        // "\\u001F!" parses to a 2-char string: unit-separator (U+001F) + '!'.
        eq("parse \\u escape", new String(new char[]{0x1f, '!'}), JsonParser.parse("\"\\u001F!\""));
        eq("parse \\/ escape", "a/b", JsonParser.parse("\"a\\/b\""));

        // A raw (unescaped) control char inside a JSON string is invalid and must be rejected.
        String rawCtl = "\"a" + ((char) 1) + "b\"";
        check("reject raw control char in string", throwsIAE(() -> JsonParser.parse(rawCtl)));
        check("reject trailing garbage", throwsIAE(() -> JsonParser.parse("{} x")));
        check("reject unterminated string", throwsIAE(() -> JsonParser.parse("\"abc")));
        check("reject unterminated object", throwsIAE(() -> JsonParser.parse("{\"a\":1")));
        check("reject bad literal", throwsIAE(() -> JsonParser.parse("tru")));
    }

    // ---- tiny helpers ----

    private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObj(String json) {
        return (Map<String, Object>) JsonParser.parse(json);
    }

    interface Thrower {
        void run();
    }

    static boolean throwsIAE(Thrower t) {
        try {
            t.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    static void eq(String name, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + "  (expected <" + expected + "> got <" + actual + ">)");
        }
    }

    static void check(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }
}
