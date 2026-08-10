// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.neoforge;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Real FML constructor, event-bus, deferred-register, and common-setup lifecycle fixture. */
@Mod(FixtureNeoForgeMod.MOD_ID)
public final class FixtureNeoForgeMod {
    public static final String MOD_ID = "victus_hybrid_fixture_neoforge";
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    @SuppressWarnings("unused")
    private static final DeferredItem<Item> MARKER_ITEM = ITEMS.registerSimpleItem("marker");

    public FixtureNeoForgeMod(IEventBus modBus, ModContainer container) {
        ITEMS.register(modBus);
        modBus.addListener(FMLCommonSetupEvent.class, this::commonSetup);
        FixtureSignals.marker("construct", "VICTUS_FIXTURE_NEOFORGE_CONSTRUCT");
        FixtureSignals.marker("event_bus", "VICTUS_FIXTURE_NEOFORGE_EVENT_BUS");
        FixtureSignals.marker("deferred_register", "VICTUS_FIXTURE_NEOFORGE_DEFERRED_REGISTER");
        FixtureSignals.marker("container", container.getModId());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        FixtureSignals.marker("common_setup", "VICTUS_FIXTURE_NEOFORGE_COMMON_SETUP");
    }

}
