// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.neoforge;

import cloud.victus.hybrid.common.LaunchRequest;
import cloud.victus.hybrid.common.LoaderProfile;
import cloud.victus.hybrid.common.PreflightResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class NeoForgeAdapterSelfTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("victus-neoforge-adapter-test");
        LaunchRequest missing = new LaunchRequest(LoaderProfile.NEOFORGE, "net.minecraft.server.Main",
                temp.resolve("server.jar"), List.of(), List.of(), List.of(temp.resolve("fml-loader.jar")),
                temp, temp.resolve("mods"), List.of("--nogui"), temp.resolve("report.txt"));
        PreflightResult result = new NeoForgeLoaderAdapter().preflight(missing);
        check(!result.ready(), "missing runtime is refused");
        check(result.diagnostics().stream().anyMatch(line -> line.startsWith("NEOFORGE_RUNTIME_CLASSPATH_MISSING")),
                "missing runtime blocker is actionable");
        check(result.diagnostics().stream().anyMatch(line -> line.startsWith("NEOFORGE_FML_MODULE_MISSING")),
                "FML module-path requirement explicit");
        check(result.fingerprint().loaderVersion().contains("26.2.0.57"), "NeoForge pin in fingerprint");
        check(result.fingerprint().loaderVersion().contains("11.0.17"), "FML pin in fingerprint");
        check(!NeoForgeLoaderAdapter.hasModule(List.of(temp.resolve("missing.jar")), "fml_loader"),
                "invalid module path fails deterministically");
        System.out.println("NeoForgeAdapterSelfTest: 6 checks passed");
    }

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
}
