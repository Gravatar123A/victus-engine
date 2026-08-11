// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.fixture;

import cloud.victus.hybrid.bukkit.event.EventTranslator;
import cloud.victus.hybrid.bukkit.network.ClientHandshake;
import cloud.victus.hybrid.bukkit.network.HandshakeAdapter;
import cloud.victus.hybrid.bukkit.network.HandshakeDecision;
import cloud.victus.hybrid.bukkit.network.HandshakeStyle;
import cloud.victus.hybrid.bukkit.network.NetworkProfile;
import cloud.victus.hybrid.bukkit.registry.RegistryEntry;
import cloud.victus.hybrid.bukkit.spi.BridgeAdapter;
import cloud.victus.hybrid.common.LoaderProfile;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Second fixture proves profile selection without linking NeoForge classes into common bridge code. */
public final class FixtureNeoForgeBridgeAdapter implements BridgeAdapter {
    @Override public LoaderProfile profile() { return LoaderProfile.NEOFORGE; }
    @Override public String adapterVersion() { return "fixture-neoforge-1"; }
    @Override public Collection<RegistryEntry> registryEntries() { return List.of(); }
    @Override public Collection<? extends EventTranslator> eventTranslators() { return List.of(); }

    @Override
    public HandshakeAdapter handshakeAdapter() {
        return new HandshakeAdapter() {
            @Override public HandshakeStyle style() { return HandshakeStyle.NEOFORGE; }
            @Override public NetworkProfile serverProfile() { return new NetworkProfile(HandshakeStyle.NEOFORGE, "1", List.of()); }
            @Override public ClientHandshake decode(byte[] payload) {
                return new ClientHandshake(HandshakeStyle.NEOFORGE, new String(payload, StandardCharsets.UTF_8), Map.of());
            }
            @Override public byte[] encodeDecision(HandshakeDecision decision) {
                return decision.code().getBytes(StandardCharsets.UTF_8);
            }
        };
    }
}
