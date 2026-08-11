// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.launcher;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs on the launcher's production runtime classpath, not testRuntimeClasspath. */
public final class LauncherIsolationSelfTest {
    public static void main(String[] args) throws Exception {
        absent("cloud.victus.hybrid.fabric.FabricLoaderAdapter");
        absent("cloud.victus.hybrid.neoforge.NeoForgeLoaderAdapter");
        absent("cloud.victus.hybrid.bukkit.HybridBukkitBridge");
        absent("net.fabricmc.loader.impl.launch.knot.KnotServer");
        absent("net.neoforged.fml.startup.Server");

        Path temp = Files.createTempDirectory("victus-isolation-test");
        Path testClasses = Path.of(LauncherIsolationSelfTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        int status = VictusHybridLauncher.run(new String[]{
                "--hybrid-profile=disabled",
                "--hybrid-target-main=cloud.victus.hybrid.launcher.fixture.DelegationTarget",
                "--hybrid-target-classpath=" + testClasses,
                "--hybrid-report=" + temp.resolve("report.txt"),
                "--", "isolated"
        });
        if (status != 0 || !"isolated".equals(System.getProperty("victus.launcher.delegated"))) {
            throw new AssertionError("disabled delegation failed on production-isolated launcher classpath");
        }
        System.out.println("LauncherIsolationSelfTest: disabled path contains no adapter or loader classes");
    }

    private static void absent(String name) {
        try {
            Class.forName(name, false, LauncherIsolationSelfTest.class.getClassLoader());
            throw new AssertionError(name + " leaked onto launcher production runtime classpath");
        } catch (ClassNotFoundException expected) {
            // Expected isolation boundary.
        }
    }
}
