// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.bukkit;

import org.bukkit.plugin.java.JavaPlugin;

/** Owned minimal Bukkit lifecycle marker compiled against the Paper 26.2 API. */
public final class FixtureBukkitPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        System.setProperty("victus.fixture.bukkit.enable", "VICTUS_FIXTURE_BUKKIT_ENABLE");
    }
}
