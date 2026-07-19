// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.logging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free recursive-descent JSON parser — the inverse of {@link JsonWriter}.
 *
 * <p>It exists mainly so the module can prove its own output is <b>round-trippable</b> without pulling
 * in a JSON library, but it is a correct RFC&nbsp;8259 reader for the subset {@link JsonWriter}
 * produces (and a bit more: it also accepts the {@code \/} escape and exponent notation). Parsed
 * values map to plain JDK types:
 * <ul>
 *   <li>object &rarr; {@link LinkedHashMap} (insertion order preserved)</li>
 *   <li>array &rarr; {@link ArrayList}</li>
 *   <li>string &rarr; {@link String}</li>
 *   <li>number &rarr; {@link Long} if integral and in range, else {@link Double}</li>
 *   <li>{@code true}/{@code false} &rarr; {@link Boolean}</li>
 *   <li>{@code null} &rarr; {@code null}</li>
 * </ul>
 *
 * <p>Malformed input raises {@link IllegalArgumentException} with the offending offset.
 */
public final class JsonParser {

    private final String s;
    private int i;

    private JsonParser(String s) {
        this.s = s;
    }

    /**
     * Parse a complete JSON document.
     *
     * @param json the document text
     * @return the parsed value (see class docs for the type mapping)
     * @throws IllegalArgumentException if the text is not a single well-formed JSON value
     */
    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON input is null");
        }
        JsonParser p = new JsonParser(json);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.i != p.s.length()) {
            throw p.err("trailing content");
        }
        return v;
    }

    private Object value() {
        skipWs();
        char c = peek();
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
            case 'f':
                return bool();
            case 'n':
                return nul();
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        expect('{');
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            expect(':');
            m.put(key, value());
            skipWs();
            char c = next();
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> a = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            i++;
            return a;
        }
        while (true) {
            a.add(value());
            skipWs();
            char c = next();
            if (c == ']') {
                return a;
            }
            if (c != ',') {
                throw err("expected ',' or ']'");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        int cp = 0;
                        for (int k = 0; k < 4; k++) {
                            cp = (cp << 4) | hex(next());
                        }
                        sb.append((char) cp);
                        break;
                    default:
                        throw err("invalid escape '\\" + e + "'");
                }
            } else if (c < 0x20) {
                throw err("unescaped control character U+" + String.format("%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        if (i < s.length() && s.charAt(i) == '-') {
            i++;
        }
        boolean fractional = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                fractional = true;
                i++;
            } else {
                break;
            }
        }
        String num = s.substring(start, i);
        if (num.isEmpty() || "-".equals(num)) {
            throw err("invalid number");
        }
        try {
            if (fractional) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        } catch (NumberFormatException ex) {
            // Very large integers or odd forms fall back to double.
            return Double.parseDouble(num);
        }
    }

    private Boolean bool() {
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        throw err("invalid literal");
    }

    private Object nul() {
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        throw err("invalid literal");
    }

    // ---- lexing helpers ----

    private void skipWs() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw err("unexpected end of input");
        }
        return s.charAt(i);
    }

    private char next() {
        if (i >= s.length()) {
            throw err("unexpected end of input");
        }
        return s.charAt(i++);
    }

    private void expect(char c) {
        char g = next();
        if (g != c) {
            throw err("expected '" + c + "' but found '" + g + "'");
        }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw new IllegalArgumentException("invalid hex digit '" + c + "'");
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON parse error at offset " + i + ": " + msg);
    }
}
