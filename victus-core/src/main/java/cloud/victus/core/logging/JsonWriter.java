// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.logging;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;

/**
 * A tiny, correct, <b>dependency-free</b> JSON serializer — deliberately no third-party JSON library,
 * because {@code victus-core} must compile and run offline with only the JDK (see the module rationale
 * in {@code cloud.victus.core.config}).
 *
 * <p>It emits <b>compact</b> JSON (no insignificant whitespace), one object per line, matching the
 * structured-log record shape in {@code docs/phase-2/01-metrics-observability.md}. Strings are escaped
 * per RFC&nbsp;8259: the six short escapes ({@code \" \\ \n \r \t \b \f}) plus {@code \\u00XX} for all
 * other control characters below {@code U+0020}. Characters at or above {@code U+0020} (including
 * non-ASCII) are emitted verbatim as UTF-8, which is valid JSON and keeps records compact.
 *
 * <p>Supported value types: {@link String}, {@link Number} (integral types verbatim; {@link Double}/
 * {@link Float}/{@link BigDecimal} formatted losslessly; non-finite doubles emitted as {@code null}
 * since JSON has no NaN/Infinity), {@link Boolean}, {@code null}, {@link Map} (as a JSON object,
 * iterated in the map's own order — use a {@link java.util.LinkedHashMap} for a stable field order),
 * {@link Iterable} and {@code Object[]} (as JSON arrays). Anything else is coerced via
 * {@link String#valueOf(Object)}.
 *
 * <p>All methods are static and side-effect free; the class is not instantiable.
 */
public final class JsonWriter {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsonWriter() {
    }

    /**
     * Serialize an arbitrary value to compact JSON.
     *
     * @param value any supported value (see class docs); {@code null} yields the literal {@code null}
     * @return a valid, compact JSON document
     */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(128);
        writeValue(sb, value);
        return sb.toString();
    }

    /**
     * Escape and quote a bare string as a JSON string literal (including the surrounding quotes).
     *
     * @param s the raw string, may be empty; must not be {@code null}
     * @return the string as a JSON string token, e.g. {@code a"b} &rarr; {@code "a\"b"}
     */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        writeString(sb, s);
        return sb.toString();
    }

    // ---- internals ----

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Number) {
            writeNumber(sb, (Number) v);
        } else if (v instanceof Map) {
            writeObject(sb, (Map<?, ?>) v);
        } else if (v instanceof Iterable) {
            writeArray(sb, (Iterable<?>) v);
        } else if (v instanceof Object[]) {
            writeArray(sb, Arrays.asList((Object[]) v));
        } else {
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> items) {
        sb.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, item);
        }
        sb.append(']');
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Double) {
            double d = (Double) n;
            sb.append(Double.isFinite(d) ? Double.toString(d) : "null");
        } else if (n instanceof Float) {
            float f = (Float) n;
            sb.append(Float.isFinite(f) ? Float.toString(f) : "null");
        } else if (n instanceof BigDecimal) {
            sb.append(((BigDecimal) n).toPlainString());
        } else {
            // Integer, Long, Short, Byte, BigInteger, AtomicInteger, ... all have JSON-safe toString().
            sb.append(n.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u")
                                .append(HEX[(c >> 12) & 0xF])
                                .append(HEX[(c >> 8) & 0xF])
                                .append(HEX[(c >> 4) & 0xF])
                                .append(HEX[c & 0xF]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
