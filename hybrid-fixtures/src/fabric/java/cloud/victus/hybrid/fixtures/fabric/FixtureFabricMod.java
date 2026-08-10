// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Owned lifecycle fixture compiled against Fabric Loader and Fabric API. */
public final class FixtureFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.setProperty("victus.fixture.fabric.entrypoint", "VICTUS_FIXTURE_FABRIC_ENTRYPOINT");
        // Referencing Fabric API's lifecycle entrypoint class at link time makes this a real API fixture.
        // The callback itself is bridge work; provider proof only needs Loader main + Mixin lifecycle.
        Class<?> lifecycleApi = ServerLifecycleEvents.class;
        System.setProperty("victus.fixture.fabric.lifecycleApi",
                lifecycleApi.getName().equals(ServerLifecycleEvents.class.getName())
                        ? "VICTUS_FIXTURE_FABRIC_LIFECYCLE_API" : "INVALID");
    }
}
