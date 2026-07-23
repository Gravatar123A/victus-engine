// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dependency-free self-test (no JUnit needed, so it runs offline with just the JDK):
 * <pre>
 *   javac -d out $(find src -name '*.java')
 *   java -cp out cloud.victus.core.config.ConfigSelfTest
 * </pre>
 * Replaced by proper JUnit tests once the repo builds online.
 */
public final class ConfigSelfTest {
    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // 1. SMP profile overlay -> alternate-current redstone, dab on
        ResolvedConfig smp = new ConfigResolver(cfg("engine.profile", "smp")).resolve(null);
        check("smp profile -> AC redstone", smp.redstone == RedstoneImpl.ALTERNATE_CURRENT);
        check("smp profile -> dab on", smp.dab);
        check("default compression = libdeflate", smp.compression == CompressionBackend.LIBDEFLATE);
        check("default threading = single", smp.threadingMode == ThreadingMode.SINGLE);

        // 2. TECHNICAL profile -> vanilla redstone + dab off (vanilla-accurate)
        ResolvedConfig tech = new ConfigResolver(cfg("engine.profile", "technical")).resolve(null);
        check("technical profile -> vanilla redstone", tech.redstone == RedstoneImpl.VANILLA);
        check("technical profile -> dab off", !tech.dab);

        // 3. explicit victus value beats the profile overlay
        Map<String, Object> m = cfg("engine.profile", "technical");
        put(m, "optimizations.redstone", "eigencraft");
        ResolvedConfig ov = new ConfigResolver(m).resolve(null);
        check("explicit victus redstone beats profile overlay", ov.redstone == RedstoneImpl.EIGENCRAFT);

        // 4. explicit Paper per-world value beats victus.yml (top of precedence)
        ResolvedConfig paperWins = new ConfigResolver(m).resolve("vanilla");
        check("explicit Paper per-world beats victus.yml", paperWins.redstone == RedstoneImpl.VANILLA);

        // 5. zstd rejected for network compression, with a helpful message
        boolean threw = false;
        try {
            Map<String, Object> z = cfg("engine.profile", "smp");
            put(z, "optimizations.network.compression", "zstd");
            new ConfigResolver(z).resolve(null);
        } catch (IllegalArgumentException e) {
            threw = e.getMessage().contains("zstd") && e.getMessage().contains("libdeflate");
        }
        check("zstd network compression rejected with helpful message", threw);

        // 6. boolean-or-map coercion: dab: {enabled: false}
        Map<String, Object> mapForm = cfg("engine.profile", "smp");
        Map<String, Object> dabMap = new LinkedHashMap<>();
        dabMap.put("enabled", false);
        put(mapForm, "optimizations.entities.dab", dabMap);
        check("dab boolean-or-map coercion (enabled:false)",
                !new ConfigResolver(mapForm).resolve(null).dab);

        // 6b. unknown nested dab sub-keys warn (only 'enabled' is honored) — review #8
        Map<String, Object> dabNested = cfg("engine.profile", "smp");
        Map<String, Object> dabNestedMap = new LinkedHashMap<>();
        dabNestedMap.put("enabled", true);
        dabNestedMap.put("throttle-navigation-travel", true);
        put(dabNested, "optimizations.entities.dab", dabNestedMap);
        ResolvedConfig dabNestedR = new ConfigResolver(dabNested).resolve(null);
        check("unknown nested dab sub-key emits a warning (review #8)",
                dabNestedR.warnings.stream().anyMatch(w -> w.contains("throttle-navigation-travel")));
        check("nested dab {enabled:true} still resolves dab on", dabNestedR.dab);

        // 7. regionized threading emits a compat warning
        Map<String, Object> reg = cfg("engine.profile", "smp");
        put(reg, "threading.mode", "regionized");
        ResolvedConfig r = new ConfigResolver(reg).resolve(null);
        check("regionized emits Folia-aware compat warning",
                r.warnings.stream().anyMatch(w -> w.contains("Folia-aware")));

        // 8. bad values are rejected
        check("unknown profile rejected", throwsIAE(() -> new ConfigResolver(cfg("engine.profile", "banana")).resolve(null)));
        check("unknown threading.mode rejected", throwsIAE(() -> {
            Map<String, Object> b = cfg("engine.profile", "smp");
            put(b, "threading.mode", "quantum");
            new ConfigResolver(b).resolve(null);
        }));

        // 9. monster spawn cap per profile (minigames/network capped; smp/technical vanilla)
        check("smp -> monster cap -1 (vanilla)",
                new ConfigResolver(cfg("engine.profile", "smp")).resolve(null).monsterSpawnCap == -1);
        check("technical -> monster cap -1 (vanilla)",
                new ConfigResolver(cfg("engine.profile", "technical")).resolve(null).monsterSpawnCap == -1);
        check("minigames -> monster cap 8",
                new ConfigResolver(cfg("engine.profile", "minigames")).resolve(null).monsterSpawnCap == 8);
        check("network -> monster cap 5",
                new ConfigResolver(cfg("engine.profile", "network")).resolve(null).monsterSpawnCap == 5);
        Map<String, Object> mc = cfg("engine.profile", "minigames");
        put(mc, "optimizations.entities.monster-spawn-cap", 20);
        check("explicit monster-spawn-cap beats profile overlay",
                new ConfigResolver(mc).resolve(null).monsterSpawnCap == 20);

        // 10. projectile-save-limit: non-technical profiles cap piles; technical stays vanilla (-1)
        check("smp -> projectile save limit 16",
                new ConfigResolver(cfg("engine.profile", "smp")).resolve(null).projectileSaveLimit == 16);
        check("network -> projectile save limit 16",
                new ConfigResolver(cfg("engine.profile", "network")).resolve(null).projectileSaveLimit == 16);
        check("technical -> projectile save limit -1 (vanilla)",
                new ConfigResolver(cfg("engine.profile", "technical")).resolve(null).projectileSaveLimit == -1);

        // 11. DAB knobs: defaults, derived square, per-profile overlays, explicit override
        ResolvedConfig smpDab = new ConfigResolver(cfg("engine.profile", "smp")).resolve(null);
        check("smp -> dab start-distance 16 (profile overlay)", smpDab.dabStartDistance == 16);
        check("smp -> dab start-distance-sq derived = 256", smpDab.dabStartDistanceSq == 256);
        check("default -> dab max-tick-interval 20", smpDab.dabMaxTickInterval == 20);
        check("default -> dab activation-dist-mod 8", smpDab.dabActivationDistMod == 8);
        ResolvedConfig modDab = new ConfigResolver(cfg("engine.profile", "modded")).resolve(null);
        check("modded -> conservative dab start-distance 24", modDab.dabStartDistance == 24);
        check("modded -> conservative dab max-tick-interval 8", modDab.dabMaxTickInterval == 8);
        check("modded -> gentler dab activation-dist-mod 9", modDab.dabActivationDistMod == 9);
        check("minigames -> aggressive dab start-distance 8",
                new ConfigResolver(cfg("engine.profile", "minigames")).resolve(null).dabStartDistance == 8);
        Map<String, Object> dabOv = cfg("engine.profile", "smp");
        put(dabOv, "optimizations.entities.dab-start-distance", 40);
        ResolvedConfig dabOvR = new ConfigResolver(dabOv).resolve(null);
        check("explicit dab start-distance beats profile overlay", dabOvR.dabStartDistance == 40);
        check("explicit dab start-distance-sq recomputed = 1600", dabOvR.dabStartDistanceSq == 1600);
        Map<String, Object> dabClamp = cfg("engine.profile", "smp");
        put(dabClamp, "optimizations.entities.dab-max-tick-interval", 0);
        check("dab max-tick-interval floored at >=1",
                new ConfigResolver(dabClamp).resolve(null).dabMaxTickInterval == 1);
        Map<String, Object> dabHiMod = cfg("engine.profile", "smp");
        put(dabHiMod, "optimizations.entities.dab-activation-dist-mod", 40);
        ResolvedConfig dabHiModR = new ConfigResolver(dabHiMod).resolve(null);
        check("dab activation-dist-mod clamped to <=16 (review #7)", dabHiModR.dabActivationDistMod == 16);
        check("dab activation-dist-mod over-max emits a warning (review #7)",
                dabHiModR.warnings.stream().anyMatch(w -> w.contains("dab-activation-dist-mod")));
        Map<String, Object> dabBl = cfg("engine.profile", "smp");
        java.util.List<String> bl = new java.util.ArrayList<>();
        bl.add("minecraft:villager");
        bl.add("axolotl");
        put(dabBl, "optimizations.entities.dab-blacklist", bl);
        check("dab blacklist parsed into ResolvedConfig",
                new ConfigResolver(dabBl).resolve(null).dabBlacklist.size() == 2);

        // 12. async pathfinding: OFF by default (opt-in pending soak), knobs + reject-policy validation
        ResolvedConfig apDef = new ConfigResolver(cfg("engine.profile", "smp")).resolve(null);
        check("async-pathfinding OFF by default (opt-in)", !apDef.asyncPathfinding);
        check("async-pathfinding ground default on", apDef.asyncPathfindingGround);
        check("async-pathfinding flying default off", !apDef.asyncPathfindingFlying);
        check("async-pathfinding water default off", !apDef.asyncPathfindingWater);
        check("async-pathfinding max-threads default 0 (auto)", apDef.asyncPathfindingMaxThreads == 0);
        check("async-pathfinding default reject-policy CALLER_RUNS", apDef.asyncPathfindingRejectPolicy.equals("CALLER_RUNS"));
        Map<String, Object> apOn = cfg("engine.profile", "smp");
        put(apOn, "optimizations.entities.async-pathfinding", true);
        put(apOn, "optimizations.entities.async-pathfinding-max-threads", 4);
        put(apOn, "optimizations.entities.async-pathfinding-reject-policy", "flush_all");
        ResolvedConfig apOnR = new ConfigResolver(apOn).resolve(null);
        check("async-pathfinding explicit enable", apOnR.asyncPathfinding);
        check("async-pathfinding explicit max-threads=4", apOnR.asyncPathfindingMaxThreads == 4);
        check("async-pathfinding reject-policy normalized to FLUSH_ALL", apOnR.asyncPathfindingRejectPolicy.equals("FLUSH_ALL"));
        Map<String, Object> apBad = cfg("engine.profile", "smp");
        put(apBad, "optimizations.entities.async-pathfinding-reject-policy", "nonsense");
        check("async-pathfinding bad reject-policy falls back to CALLER_RUNS",
                new ConfigResolver(apBad).resolve(null).asyncPathfindingRejectPolicy.equals("CALLER_RUNS"));

        // 13. async chunk send: OFF by default (opt-in pending soak), knobs resolve + defaults
        ResolvedConfig acs = new ConfigResolver(cfg("engine.profile", "smp")).resolve(null);
        check("async-chunk-send OFF by default", !acs.asyncChunkSend);
        check("async-chunk-send threads default -1 (auto)", acs.asyncChunkSendThreads == -1);
        check("async-chunk-send watchdog default 1500ms", acs.asyncChunkSendWatchdogMs == 1500L);
        check("async-chunk-send fallback-on-exception default true", acs.asyncChunkSendFallbackOnException);
        check("async-chunk-send anti-xray force-sync default false", !acs.asyncChunkSendAntiXrayForceSync);
        Map<String, Object> acsOn = cfg("engine.profile", "smp");
        put(acsOn, "optimizations.chunks.async-send", true);
        put(acsOn, "optimizations.chunks.async-send-threads", 3);
        ResolvedConfig acsR = new ConfigResolver(acsOn).resolve(null);
        check("async-chunk-send explicit enable", acsR.asyncChunkSend);
        check("async-chunk-send explicit threads=3", acsR.asyncChunkSendThreads == 3);
        Map<String, Object> acsWd = cfg("engine.profile", "smp");
        put(acsWd, "optimizations.chunks.async-send-watchdog-ms", 50);
        check("async-chunk-send watchdog floored >=100ms",
                new ConfigResolver(acsWd).resolve(null).asyncChunkSendWatchdogMs == 100L);

        // 14. default template must NOT force-enable the un-soaked, opt-in async-pathfinding (review #6)
        boolean forcesAsyncPath = false;
        for (String line : DefaultConfig.YML.split("\n")) {
            String s = line.trim();
            if (!s.startsWith("#") && s.startsWith("async-pathfinding") && s.contains("true")) forcesAsyncPath = true;
        }
        check("default template does not force async-pathfinding on (review #6)", !forcesAsyncPath);
        check("default template offers async-pathfinding as a commented opt-in (review #6)",
                DefaultConfig.YML.contains("#async-pathfinding"));

        // 15. hosting.dedicated / node-cores / node-nvme (dedicated-node auto-tuning)
        ResolvedConfig hostDef = new ConfigResolver(cfg("engine.profile", "smp")).resolve(null);
        check("hosting.dedicated default false", !hostDef.dedicated);
        check("hosting.node-nvme default false", !hostDef.nodeNvme);
        check("hosting.node-cores default -1", hostDef.nodeCores == -1);
        Map<String, Object> hostOn = cfg("engine.profile", "smp");
        put(hostOn, "hosting.dedicated", true);
        put(hostOn, "hosting.node-nvme", true);
        put(hostOn, "hosting.node-cores", 16);
        ResolvedConfig hostR = new ConfigResolver(hostOn).resolve(null);
        check("hosting.dedicated explicit true", hostR.dedicated);
        check("hosting.node-nvme explicit true", hostR.nodeNvme);
        check("hosting.node-cores explicit 16", hostR.nodeCores == 16);
        // recommendedChunkWorkerThreads maps dedicated+cores → uncapped; shared/tiny → -1 (keep default)
        check("dedicated 16-core → uncapped chunk workers (cores-2)",
                cloud.victus.core.runtime.RecommendedFlags.recommendedChunkWorkerThreads(16, true) == 14);
        check("shared 16-core → keep Moonrise default (-1)",
                cloud.victus.core.runtime.RecommendedFlags.recommendedChunkWorkerThreads(16, false) == -1);

        // 16. hybrid mod bridge config (Phase 4 foundation)
        ResolvedConfig hybDef = new ConfigResolver(cfg("engine.profile", "smp")).resolve(null);
        check("hybrid disabled by default", !hybDef.hybridEnabled);
        check("hybrid loader default auto", hybDef.hybridLoader.equals("auto"));
        check("hybrid safe-mode default true", hybDef.hybridSafeMode);
        Map<String, Object> hybOn = cfg("engine.profile", "smp");
        put(hybOn, "hybrid.enabled", true);
        put(hybOn, "hybrid.loader", "fabric");
        put(hybOn, "hybrid.safe-mode", false);
        ResolvedConfig hybR = new ConfigResolver(hybOn).resolve(null);
        check("hybrid enabled explicit", hybR.hybridEnabled);
        check("hybrid loader fabric", hybR.hybridLoader.equals("fabric"));
        check("hybrid safe-mode false", !hybR.hybridSafeMode);
        check("hybrid enabled emits experimental warning",
                hybR.warnings.stream().anyMatch(w -> w.contains("EXPERIMENTAL")));
        Map<String, Object> hybBad = cfg("engine.profile", "smp");
        put(hybBad, "hybrid.loader", "banana");
        check("hybrid bad loader falls back to auto",
                new ConfigResolver(hybBad).resolve(null).hybridLoader.equals("auto"));

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        System.out.println("Sample resolved (smp): " + smp);
        if (failed > 0) System.exit(1);
    }

    // ---- tiny test harness ----

    static Map<String, Object> cfg(String dotted, Object val) {
        Map<String, Object> root = new LinkedHashMap<>();
        put(root, dotted, val);
        return root;
    }

    @SuppressWarnings("unchecked")
    static void put(Map<String, Object> root, String dotted, Object val) {
        String[] parts = dotted.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            cur = (Map<String, Object>) cur.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
        }
        cur.put(parts[parts.length - 1], val);
    }

    interface Thrower { void run(); }

    static boolean throwsIAE(Thrower t) {
        try { t.run(); return false; }
        catch (IllegalArgumentException e) { return true; }
    }

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }
}
