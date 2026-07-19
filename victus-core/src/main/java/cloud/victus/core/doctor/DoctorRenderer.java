// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.doctor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a {@link DiagnosisReport} to the two shapes {@code /victus doctor} emits:
 * <ul>
 *   <li>{@link #renderText(DiagnosisReport)} — a human, op-facing console report;</li>
 *   <li>{@link #renderJson(DiagnosisReport)} — the structured object for {@code --json}, the panel and
 *       the log channel, matching the shape in {@code docs/phase-2/02-lag-doctor.md}:
 *       {@code {window, tps, mspt:{p50,p95,p99,max}, subsystems:[{name,pct,ms,detail:[...]}],
 *       remediations:[{id,title,expectedGain,caveat,revertId,writes:{...}}]}}.</li>
 * </ul>
 *
 * <p>victus-core modules compile independently (they are written in parallel), so rather than depend on
 * the logging module's JSON writer this class carries a tiny, correct, local serializer. All figures are
 * labelled illustrative in the text report, per the spec's honesty requirement.
 */
public final class DoctorRenderer {

    private DoctorRenderer() {
    }

    // ---- human report --------------------------------------------------------------------------

    /** Render a human-readable, op-facing report. */
    public static String renderText(DiagnosisReport report) {
        StringBuilder sb = new StringBuilder(512);
        long windowSecs = report.window().toSeconds();

        sb.append("Victus Lag Doctor — diagnosis over ").append(windowSecs).append("s window\n");
        MsptSummary m = report.mspt();
        sb.append(String.format(Locale.ROOT,
                "TPS: %.2f   MSPT p50/p95/p99/max: %.2f / %.2f / %.2f / %.2f ms",
                report.tps(), m.p50(), m.p95(), m.p99(), m.max()));
        if (m.sampleCount() > 0) {
            sb.append(String.format(Locale.ROOT, "   (mean %.2f over %d samples)", m.mean(), m.sampleCount()));
        }
        sb.append("\n\n");

        sb.append("Subsystems ranked by tick share:\n");
        if (report.subsystems().isEmpty()) {
            sb.append("  (no timing data)\n");
        }
        int idx = 1;
        for (RankedSubsystem rs : report.subsystems()) {
            sb.append(String.format(Locale.ROOT, "  %d. %-18s %5.1f%%   %7.2f ms%s\n",
                    idx++, rs.subsystem().label(), rs.pct(), rs.ms(),
                    rs.dominant() ? "   [DOMINANT]" : ""));
            for (Offender o : rs.offenders()) {
                sb.append("       - ").append(o.key()).append(": ").append(o.value());
                if (o.hasOwner()) {
                    sb.append("  (owner: ").append(o.owningPlugin()).append(')');
                }
                sb.append('\n');
            }
        }

        sb.append("\nSuggested remediations (reversible; gains are illustrative):\n");
        if (report.remediations().isEmpty()) {
            sb.append("  (none — investigate the top offender's owning plugin)\n");
        }
        for (Remediation r : report.remediations()) {
            sb.append("  [").append(r.id()).append("] ").append(r.title()).append('\n');
            sb.append("      expected: ").append(r.expectedGain()).append('\n');
            sb.append("      caveat:   ").append(r.behaviorCaveat()).append('\n');
            sb.append("      writes:   ").append(r.writes()).append('\n');
            sb.append("      revert:   ").append(r.revertId()).append('\n');
        }
        return sb.toString();
    }

    // ---- JSON report ---------------------------------------------------------------------------

    /** Render the structured JSON report ({@code --json} / panel / log channel). */
    public static String renderJson(DiagnosisReport report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("window", report.window().toSeconds());
        root.put("tps", round2(report.tps()));

        MsptSummary m = report.mspt();
        Map<String, Object> mspt = new LinkedHashMap<>();
        mspt.put("p50", round2(m.p50()));
        mspt.put("p95", round2(m.p95()));
        mspt.put("p99", round2(m.p99()));
        mspt.put("max", round2(m.max()));
        root.put("mspt", mspt);

        List<Object> subs = new ArrayList<>();
        for (RankedSubsystem rs : report.subsystems()) {
            Map<String, Object> so = new LinkedHashMap<>();
            so.put("name", rs.subsystem().key());
            so.put("pct", round2(rs.pct()));
            so.put("ms", round2(rs.ms()));
            so.put("dominant", rs.dominant());
            List<Object> detail = new ArrayList<>();
            for (Offender o : rs.offenders()) {
                Map<String, Object> od = new LinkedHashMap<>();
                od.put("key", o.key());
                od.put("value", o.value());
                od.put("owningPlugin", o.owningPlugin()); // null -> JSON null
                detail.add(od);
            }
            so.put("detail", detail);
            subs.add(so);
        }
        root.put("subsystems", subs);

        List<Object> rems = new ArrayList<>();
        for (Remediation r : report.remediations()) {
            Map<String, Object> ro = new LinkedHashMap<>();
            ro.put("id", r.id());
            ro.put("title", r.title());
            ro.put("expectedGain", r.expectedGain());
            ro.put("caveat", r.behaviorCaveat());
            Map<String, Object> writes = new LinkedHashMap<>();
            writes.put("file", r.writes().file());
            writes.put("key", r.writes().key());
            writes.put("value", r.writes().value());
            ro.put("writes", writes);
            ro.put("revertId", r.revertId());
            rems.add(ro);
        }
        root.put("remediations", rems);

        StringBuilder sb = new StringBuilder(512);
        writeJson(sb, root);
        return sb.toString();
    }

    /** Round to 2 decimals, avoiding {@code -0.0} and keeping the value a {@link Double} for JSON. */
    private static double round2(double v) {
        double r = Math.round(v * 100.0) / 100.0;
        return r == 0.0 ? 0.0 : r;
    }

    // ---- tiny local JSON serializer ------------------------------------------------------------
    // Supports Map, Iterable, String, Number, Boolean and null — everything this renderer produces.

    private static void writeJson(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeJsonString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            sb.append(Double.isFinite(d) ? Double.toString(d) : "null");
        } else if (v instanceof Number n) {
            sb.append(n.toString());
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJsonString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object item : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJson(sb, item);
            }
            sb.append(']');
        } else {
            writeJsonString(sb, String.valueOf(v));
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static void writeJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
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
        }
        sb.append('"');
    }
}
