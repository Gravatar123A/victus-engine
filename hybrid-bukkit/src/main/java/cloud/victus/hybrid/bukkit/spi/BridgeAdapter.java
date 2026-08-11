// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.spi;

import cloud.victus.hybrid.bukkit.event.EventTranslator;
import cloud.victus.hybrid.bukkit.network.HandshakeAdapter;
import cloud.victus.hybrid.bukkit.registry.RegistryEntry;
import cloud.victus.hybrid.common.LoaderProfile;
import java.util.Collection;

/**
 * Service-loaded loader integration. Implementations live in loader adapter modules; this common bridge
 * intentionally has no Fabric, NeoForge, Minecraft, Bukkit, or Paper linkage.
 */
public interface BridgeAdapter {
    LoaderProfile profile();

    String adapterVersion();

    Collection<RegistryEntry> registryEntries();

    Collection<? extends EventTranslator> eventTranslators();

    HandshakeAdapter handshakeAdapter();
}
