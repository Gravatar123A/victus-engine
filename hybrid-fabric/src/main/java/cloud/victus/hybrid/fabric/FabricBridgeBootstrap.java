// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fabric;

import cloud.victus.hybrid.bukkit.HybridBridgeRuntime;
import cloud.victus.hybrid.bukkit.HybridBukkitBridge;
import cloud.victus.hybrid.bukkit.registry.RegistryKind;
import cloud.victus.hybrid.common.LoaderProfile;
/** Called reflectively by the Paper host once registries, recipes, and commands are safe to snapshot. */
public final class FabricBridgeBootstrap {
    private static volatile Object server;

    private FabricBridgeBootstrap() {
    }

    public static synchronized Object initialize(Object minecraftServer) {
        if (minecraftServer == null || !isMinecraftServer(minecraftServer.getClass())) {
            throw new IllegalArgumentException("Expected MinecraftServer, got " + minecraftServer);
        }
        if (server == null) server = minecraftServer;
        else if (server != minecraftServer) throw new IllegalStateException("Fabric bridge was initialized for another server");
        HybridBukkitBridge bridge = HybridBridgeRuntime.initialize(LoaderProfile.FABRIC,
                FabricBridgeBootstrap.class.getClassLoader());
        long entries = java.util.Arrays.stream(RegistryKind.values())
                .mapToLong(kind -> bridge.registries().values(kind).size()).sum();
        System.out.println("VICTUS_FABRIC_BRIDGE_INITIALIZED adapter=" + bridge.adapter().adapterVersion()
                + " registryEntries=" + entries + " commands=" + bridge.adapter().commandDefinitions().size()
                + " channels=" + bridge.adapter().handshakeAdapter().serverProfile().channels().size());
        return bridge;
    }

    static Object requireServer() {
        Object current = server;
        if (current == null) throw new IllegalStateException("Fabric bridge server is not published");
        return current;
    }

    private static boolean isMinecraftServer(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            if ("net.minecraft.server.MinecraftServer".equals(current.getName())) return true;
            current = current.getSuperclass();
        }
        return false;
    }
}
