// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;

/** Owned fixture compiled against Fabric Loader and the retained Fabric API base module. */
public final class FixtureFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        System.setProperty("victus.fixture.fabric.entrypoint", "VICTUS_FIXTURE_FABRIC_ENTRYPOINT");
        // Referencing a retained Fabric API type at link time keeps this a real API fixture even when
        // descriptor auditing refuses lifecycle-events and its dependency closure for Paper.
        Class<?> fabricApi = Event.class;
        System.setProperty("victus.fixture.fabric.lifecycleApi",
                fabricApi.getName().equals(Event.class.getName())
                        ? "VICTUS_FIXTURE_FABRIC_API_BASE" : "INVALID");
    }
}
