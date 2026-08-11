// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit;

import cloud.victus.hybrid.common.LoaderProfile;
import java.util.Optional;

/** Process-local publication point shared by the Fabric provider and the linkage-free Paper host. */
public final class HybridBridgeRuntime {
    private static volatile HybridBukkitBridge bridge;

    private HybridBridgeRuntime() {
    }

    public static synchronized HybridBukkitBridge initialize(LoaderProfile profile, ClassLoader adapterClassLoader) {
        if (bridge != null) return bridge;
        HybridBukkitBridge created = new HybridBukkitBridge(profile);
        created.initialize(adapterClassLoader);
        bridge = created;
        return created;
    }

    public static Optional<HybridBukkitBridge> current() {
        return Optional.ofNullable(bridge);
    }

    public static HybridBukkitBridge require() {
        HybridBukkitBridge current = bridge;
        if (current == null) throw new IllegalStateException("Hybrid Bukkit bridge is not initialized");
        return current;
    }

    static synchronized void resetForTest() {
        bridge = null;
    }
}
