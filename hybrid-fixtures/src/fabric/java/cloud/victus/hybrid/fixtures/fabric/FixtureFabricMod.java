// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.fabric;

import net.fabricmc.api.ModInitializer;

/** Owned minimal entrypoint fixture compiled against Fabric Loader 0.19.3. */
public final class FixtureFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.setProperty("victus.fixture.fabric.entrypoint", "VICTUS_FIXTURE_FABRIC_ENTRYPOINT");
    }
}
