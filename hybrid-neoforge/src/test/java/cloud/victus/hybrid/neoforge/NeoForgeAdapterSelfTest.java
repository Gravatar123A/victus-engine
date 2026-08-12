// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.neoforge;

import cloud.victus.hybrid.common.LaunchRequest;
import cloud.victus.hybrid.common.LoaderProfile;
import cloud.victus.hybrid.common.PreflightResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        check(result.fingerprint().loaderVersion().contains("11.0.17"), "validated fixture FML pin in fingerprint");
        check(NeoForgeLoaderAdapter.INSTALLER_FML_VERSION.equals("11.0.16"), "installer-selected FML pin explicit");
        check(result.diagnostics().stream().anyMatch(line -> line.startsWith("NEOFORGE_PLATFORM_MODULE_MISSING")),
                "NeoForge universal module required");
        check(result.diagnostics().stream().anyMatch(line -> line.startsWith("NEOFORGE_MIXIN_SERVICE_MISSING")),
                "FML Mixin service required");
        check(!NeoForgeLoaderAdapter.hasModule(List.of(temp.resolve("missing.jar")), "fml_loader"),
                "invalid module path fails deterministically");
        int bridgeChecks = checkOwnedBridgeSources();
        System.out.println("NeoForgeAdapterSelfTest: " + (9 + bridgeChecks) + " checks passed");
    }

    private static int checkOwnedBridgeSources() throws Exception {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path bridgeRoot = Files.isDirectory(current.resolve("bridge-patches"))
                ? current.resolve("bridge-patches")
                : current.resolve("hybrid-neoforge/bridge-patches");
        Map<String, List<String>> requiredTokens = Map.of(
                "net/minecraft/server/network/ServerConnectionListener.java", List.of(
                        "neoforge.readTimeout", "DualStackUtils.checkIPv6", ".serverEventLoopGroup()",
                        "FlushConsolidationHandler", "HAProxyMessageDecoder", "ChannelInitializeListenerHolder.callListeners",
                        ".option(ChannelOption.AUTO_READ, false)"),
                "net/minecraft/server/packs/repository/PackRepository.java", List.of(
                        "new LinkedHashSet<>(List.of(sources))", "streamSelfAndChildren()",
                        "expandAndRemoveRootChildren", "filter(pack -> !pack.isHidden())", "addPackFinder"),
                "net/minecraft/server/packs/repository/ServerPacksSource.java", List.of(
                        "new PackRepository(validator", "populatePackRepository(packRepository, PackType.SERVER_DATA, false)",
                        "populatePackRepository(packRepository, PackType.SERVER_DATA, true)", "Identifier.PAPER_NAMESPACE"),
                "net/minecraft/server/MinecraftServer.java", List.of(
                        "rebuildSelected(packsToEnable, false)", "PaperBrigadier.moveBukkitCommands",
                        "ServerResourcesReloadedEvent(cause)", "DataMapHooks.populateFuelValues")
        );

        int checks = 0;
        for (Map.Entry<String, List<String>> entry : requiredTokens.entrySet()) {
            Path source = bridgeRoot.resolve(entry.getKey());
            check(Files.isRegularFile(source), "owned bridge source exists: " + entry.getKey());
            checks++;
            String text = Files.readString(source, StandardCharsets.UTF_8);
            check(text.indexOf('\r') < 0, "bridge source uses deterministic LF endings: " + entry.getKey());
            checks++;
            for (String token : entry.getValue()) {
                check(text.contains(token), "bridge semantic token present: " + entry.getKey() + " -> " + token);
                checks++;
            }
        }
        return checks;
    }

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
}
