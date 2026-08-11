// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.bukkit;

import org.bukkit.plugin.java.JavaPlugin;

/** Full-boot Bukkit proof. All bridge calls are reflective so the plugin has no hybrid compile/runtime linkage. */
public final class FixtureBukkitPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        System.setProperty("victus.fixture.bukkit.enable", "VICTUS_FIXTURE_BUKKIT_ENABLE");
        try {
            Class<?> host = Class.forName("cloud.victus.core.runtime.HybridBukkitHost", true,
                    getServer().getClass().getClassLoader());
            String proof = (String) host.getMethod("runFixtureProof", String.class)
                    .invoke(null, getServer().getWorldContainer().toPath().toAbsolutePath().normalize().toString());
            getLogger().info(proof);
            System.setProperty("victus.fixture.bukkit.proof", proof);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("VICTUS_FIXTURE_BUKKIT_BRIDGE_FAILED", failure);
        }
    }
}
