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
