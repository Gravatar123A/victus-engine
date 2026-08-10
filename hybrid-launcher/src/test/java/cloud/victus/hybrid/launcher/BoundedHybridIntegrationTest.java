// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.launcher;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Foundation integration contract: each enabled profile is bounded and must either produce a real lifecycle
 * marker later or refuse before target delegation with the asserted known missing-runtime blocker today.
 */
public final class BoundedHybridIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path temp = Files.createTempDirectory("victus-bounded-integration");
        int fabric = run(root, temp, "fabric", "hybrid-fabric", "FABRIC_RUNTIME_CLASSPATH_MISSING");
        int neo = run(root, temp, "neoforge", "hybrid-neoforge", "NEOFORGE_RUNTIME_CLASSPATH_MISSING");
        if (fabric != 2 || neo != 2) {
            throw new AssertionError("enabled profiles must fail closed with status 2 in foundation tests");
        }
        System.out.println("BoundedHybridIntegrationTest: known blockers asserted for Fabric and NeoForge");
    }

    private static int run(Path root, Path temp, String profile, String adapterProject, String blocker) throws Exception {
        Path adapterClasses = root.resolve(adapterProject).resolve("build/classes/java/main");
        Path adapterResources = root.resolve(adapterProject).resolve("build/resources/main");
        String adapters = adapterClasses + java.io.File.pathSeparator + adapterResources;
        Path report = temp.resolve(profile + ".txt");
        Path missingLoader = temp.resolve(profile + "-runtime-missing.jar");
        Path missingServer = temp.resolve("patched-server-missing.jar");
        long started = System.nanoTime();
        int status = VictusHybridLauncher.run(new String[]{
                "--hybrid-profile=" + profile,
                "--hybrid-target-main=" + (profile.equals("fabric") ? "org.bukkit.craftbukkit.Main" : "net.minecraft.server.Main"),
                "--hybrid-target-artifact=" + missingServer,
                "--hybrid-adapter-classpath=" + adapters,
                "--hybrid-loader-classpath=" + missingLoader,
                "--hybrid-game-dir=" + temp,
                "--hybrid-report=" + report,
                "--", "--nogui"
        });
        long seconds = java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds();
        if (seconds >= Long.getLong("victus.hybrid.integration.timeoutSeconds", 20L)) {
            throw new AssertionError(profile + " integration exceeded bound: " + seconds + "s");
        }
        String text = Files.readString(report);
        if (!text.contains(blocker) || !text.contains("state=FAILED") || text.contains("history=TARGET_DELEGATED")
                || text.contains("VICTUS_HYBRID_TARGET_DELEGATED")) {
            throw new AssertionError(profile + " report did not assert known fail-closed blocker:\n" + text);
        }
        return status;
    }
}
