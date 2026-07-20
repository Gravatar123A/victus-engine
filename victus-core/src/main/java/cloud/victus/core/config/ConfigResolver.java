// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.config;

import java.util.Map;

/**
 * Resolves a typed {@link ResolvedConfig} from raw parsed victus.yml (nested maps, exactly what any
 * YAML library produces) plus an optional explicit Paper per-world override.
 *
 * <p>Precedence (per docs/phase-1/01-redstone.md):
 * <pre>explicit Paper per-world value  &gt;  victus.yml  &gt;  profile default  &gt;  hard default</pre>
 *
 * <p>This core is deliberately <b>dependency-free and Paper-independent</b>, so the resolution +
 * validation logic is unit-testable offline (see {@link ConfigSelfTest}). The actual YAML parsing
 * (SnakeYAML / Bukkit config) is injected by the fork; it just hands this class a {@code Map}.
 */
public final class ConfigResolver {

    private final Map<String, Object> victus;
    private final Profile profile;

    public ConfigResolver(Map<String, Object> victusYml) {
        this.victus = victusYml == null ? Map.of() : victusYml;
        Object p = path(this.victus, "engine.profile");
        this.profile = Profile.fromConfig(p == null ? null : String.valueOf(p));
    }

    /**
     * Resolve all settings.
     *
     * @param explicitPaperRedstone a per-world redstone-implementation the operator set directly in
     *                              Paper config, or {@code null} if none — it wins over victus.yml.
     */
    public ResolvedConfig resolve(String explicitPaperRedstone) {
        ResolvedConfig c = new ResolvedConfig();
        c.profile = profile;
        c.threadingMode = ThreadingMode.fromConfig(str("threading.mode", "single"));

        String redstone = explicitPaperRedstone != null
                ? explicitPaperRedstone
                : String.valueOf(resolveWithProfile("optimizations.redstone", "vanilla"));
        c.redstone = RedstoneImpl.fromConfig(redstone);

        c.compression = CompressionBackend.fromConfig(str("optimizations.network.compression", "libdeflate"));
        c.compressionThreshold = intVal("optimizations.network.compression-threshold", 256);
        c.dab = boolVal(resolveWithProfile("optimizations.entities.dab", Boolean.TRUE));
        c.asyncPathfinding = boolVal(resolveWithProfile("optimizations.entities.async-pathfinding", Boolean.TRUE));
        c.perPlayerMobSpawns = boolVal(resolveWithProfile("optimizations.entities.per-player-mob-spawns", Boolean.TRUE));
        c.maxMspt = intVal("hosting.limits.max-mspt", 45);
        c.monsterSpawnCap = toInt(resolveWithProfile("optimizations.entities.monster-spawn-cap", -1), -1);

        if (c.threadingMode == ThreadingMode.REGIONIZED) {
            c.warnings.add("threading.mode=regionized requires Folia-aware plugins; "
                    + "non-aware plugins will be refused at load.");
        }
        if (c.compressionThreshold < 0) {
            c.warnings.add("optimizations.network.compression-threshold < 0; compression treated as disabled.");
        }
        if (c.maxMspt <= 0) {
            c.warnings.add("hosting.limits.max-mspt <= 0; per-instance MSPT throttle ladder is disabled.");
        }
        return c;
    }

    // ---- resolution helpers ----

    /** victus.yml value, else the profile-default overlay, else the hard default. */
    private Object resolveWithProfile(String dotted, Object hardDefault) {
        Object v = path(victus, dotted);
        if (v != null) return v;
        Object pd = profile.defaults().get(dotted);
        if (pd != null) return pd;
        return hardDefault;
    }

    private String str(String dotted, String def) {
        Object v = path(victus, dotted);
        return v == null ? def : String.valueOf(v);
    }

    private int intVal(String dotted, int def) {
        Object v = path(victus, dotted);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected an integer at " + dotted + ", got: " + v);
        }
    }

    /** Coerce a resolved value (from victus.yml / profile / default) to an int. */
    private static int toInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Supports the "boolean-or-map" coercion (e.g. {@code dab: {enabled: true, ...}}). */
    private static boolean boolVal(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Map) {
            Object enabled = ((Map<?, ?>) v).get("enabled");
            return enabled == null || Boolean.parseBoolean(String.valueOf(enabled));
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /** Read a dotted path from nested maps; {@code null} if any segment is absent. */
    @SuppressWarnings("unchecked")
    static Object path(Map<String, Object> root, String dotted) {
        Object cur = root;
        for (String part : dotted.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(part);
            if (cur == null) return null;
        }
        return cur;
    }
}
