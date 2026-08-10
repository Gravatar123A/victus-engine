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
        Object dabRaw = resolveWithProfile("optimizations.entities.dab", Boolean.TRUE);
        c.dab = boolVal(dabRaw);
        if (dabRaw instanceof Map<?, ?> dabMap) {
            // Only 'enabled' is honored inside a dab:{} map; the tuning knobs are hyphenated siblings.
            // Warn (don't silently swallow) so an operator's nested keys don't vanish without a trace (review #8).
            for (Object k : dabMap.keySet()) {
                if (!"enabled".equals(String.valueOf(k))) {
                    c.warnings.add("optimizations.entities.dab." + k + " is not a recognized sub-key "
                            + "(only 'enabled' is read inside dab:{}); use the hyphenated siblings instead "
                            + "(dab-start-distance / dab-max-tick-interval / dab-activation-dist-mod / dab-blacklist).");
                }
            }
        }
        // Clamp to a sane range: 0..4096 blocks. Upper bound also prevents the int square below
        // from overflowing (46341^2 > Integer.MAX_VALUE).
        c.dabStartDistance = Math.min(4096, Math.max(0, toInt(resolveWithProfile("optimizations.entities.dab-start-distance", 12), 12)));
        c.dabStartDistanceSq = c.dabStartDistance * c.dabStartDistance;
        c.dabMaxTickInterval = Math.max(1, toInt(resolveWithProfile("optimizations.entities.dab-max-tick-interval", 20), 20));
        int rawDabDistMod = toInt(resolveWithProfile("optimizations.entities.dab-activation-dist-mod", 8), 8);
        // Clamp to [1,16]: it is a right-shift exponent on squared distance, so a large value (>=~19)
        // shifts the interval to 0 and turns DAB into a silent no-op while it still logs "enabled" (review #7).
        c.dabActivationDistMod = Math.min(16, Math.max(1, rawDabDistMod));
        if (rawDabDistMod > 16) {
            c.warnings.add("optimizations.entities.dab-activation-dist-mod=" + rawDabDistMod
                    + " is too high (a large right-shift would make DAB a silent no-op); clamped to 16.");
        }
        Object dabBl = resolveWithProfile("optimizations.entities.dab-blacklist", null);
        if (dabBl instanceof java.util.List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) c.dabBlacklist.add(String.valueOf(o).trim());
            }
        }
        c.asyncPathfinding = boolVal(resolveWithProfile("optimizations.entities.async-pathfinding", Boolean.FALSE));
        c.asyncPathfindingMaxThreads = Math.max(0, toInt(resolveWithProfile("optimizations.entities.async-pathfinding-max-threads", 0), 0));
        c.asyncPathfindingQueueSize = Math.max(0, toInt(resolveWithProfile("optimizations.entities.async-pathfinding-queue-size", 0), 0));
        c.asyncPathfindingKeepaliveSeconds = Math.max(1, toInt(resolveWithProfile("optimizations.entities.async-pathfinding-keepalive", 60), 60));
        String rp = String.valueOf(resolveWithProfile("optimizations.entities.async-pathfinding-reject-policy", "CALLER_RUNS")).trim().toUpperCase();
        c.asyncPathfindingRejectPolicy = rp.equals("FLUSH_ALL") ? "FLUSH_ALL" : "CALLER_RUNS";
        c.asyncPathfindingGround = boolVal(resolveWithProfile("optimizations.entities.async-pathfinding-ground", Boolean.TRUE));
        c.asyncPathfindingFlying = boolVal(resolveWithProfile("optimizations.entities.async-pathfinding-flying", Boolean.FALSE));
        c.asyncPathfindingWater = boolVal(resolveWithProfile("optimizations.entities.async-pathfinding-water", Boolean.FALSE));

        c.asyncChunkSend = boolVal(resolveWithProfile("optimizations.chunks.async-send", Boolean.FALSE));
        c.asyncChunkSendThreads = toInt(resolveWithProfile("optimizations.chunks.async-send-threads", -1), -1);
        c.asyncChunkSendQueueCapacity = toInt(resolveWithProfile("optimizations.chunks.async-send-queue-capacity", -1), -1);
        c.asyncChunkSendWatchdogMs = Math.max(100L, toInt(resolveWithProfile("optimizations.chunks.async-send-watchdog-ms", 1500), 1500));
        c.asyncChunkSendAntiXrayForceSync = boolVal(resolveWithProfile("optimizations.chunks.async-send-anti-xray-force-sync", Boolean.FALSE));
        c.asyncChunkSendFallbackOnException = boolVal(resolveWithProfile("optimizations.chunks.async-send-fallback-on-exception", Boolean.TRUE));

        c.perPlayerMobSpawns = boolVal(resolveWithProfile("optimizations.entities.per-player-mob-spawns", Boolean.TRUE));
        c.maxMspt = intVal("hosting.limits.max-mspt", 45);
        c.dedicated = boolAt("hosting.dedicated", false);
        c.nodeCores = intVal("hosting.node-cores", -1);
        c.nodeNvme = boolAt("hosting.node-nvme", false);
        c.hybridEnabled = boolAt("hybrid.enabled", false);
        String hybLoader = str("hybrid.loader", "auto").trim().toLowerCase(java.util.Locale.ROOT);
        c.hybridLoader = (hybLoader.equals("fabric") || hybLoader.equals("neoforge")) ? hybLoader : "auto";
        c.hybridSafeMode = boolAt("hybrid.safe-mode", true);
        if (c.hybridEnabled) {
            c.warnings.add("hybrid.enabled=true is diagnostics-only inside an already-running server; no mod code "
                    + "will be invoked late. A real loader profile must be selected by VictusHybridLauncher before "
                    + "the server main, and remains unsupported foundation work. See docs/phase-4/04.");
        }
        c.monsterSpawnCap = toInt(resolveWithProfile("optimizations.entities.monster-spawn-cap", -1), -1);
        c.projectileSaveLimit = toInt(resolveWithProfile("optimizations.entities.projectile-save-limit", -1), -1);

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

    /** Boolean at a dotted path (hosting.* — operator settings, NOT profile-overlaid); {@code def} if absent. */
    private boolean boolAt(String dotted, boolean def) {
        Object v = path(victus, dotted);
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
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

    /** Supports the "boolean-or-map" coercion: a bare boolean, or a {@code {enabled: <bool>}} map.
     *  NOTE: only the {@code enabled} key is read here; other sub-keys are not implemented — the dab
     *  resolution site warns on any unknown nested sub-key (review #8). */
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
