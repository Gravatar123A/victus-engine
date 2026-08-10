// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fabric;

import cloud.victus.hybrid.common.LaunchRequest;
import cloud.victus.hybrid.common.LoaderProfile;
import cloud.victus.hybrid.common.PreflightResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class FabricAdapterSelfTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("victus-fabric-adapter-test");
        LaunchRequest missing = new LaunchRequest(LoaderProfile.FABRIC, "net.minecraft.server.Main",
                temp.resolve("server.jar"), List.of(), List.of(), List.of(temp.resolve("fabric-loader.jar")),
                temp, temp.resolve("mods"), List.of("--nogui"), temp.resolve("report.txt"));
        PreflightResult result = new FabricLoaderAdapter().preflight(missing);
        check(!result.ready(), "missing runtime is refused");
        check(result.diagnostics().stream().anyMatch(line -> line.startsWith("FABRIC_RUNTIME_CLASSPATH_MISSING")),
                "missing runtime blocker is actionable");
        check(result.diagnostics().stream().anyMatch(line -> line.startsWith("FABRIC_KNOT_MISSING")),
                "Knot requirement is explicit");
        check(result.fingerprint().loaderVersion().equals("0.19.3"), "loader pin in fingerprint");
        check(result.fingerprint().apiVersion().equals("0.156.0+26.2"), "API pin in fingerprint");
        System.out.println("FabricAdapterSelfTest: 5 checks passed");
    }

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
}
