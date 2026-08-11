// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.fixture;

import cloud.victus.hybrid.bukkit.api.NamespacedIdentifier;
import cloud.victus.hybrid.bukkit.event.EventTranslationResult;
import cloud.victus.hybrid.bukkit.event.EventTranslator;
import cloud.victus.hybrid.bukkit.event.TranslatedEvent;
import cloud.victus.hybrid.bukkit.network.ClientHandshake;
import cloud.victus.hybrid.bukkit.network.HandshakeAdapter;
import cloud.victus.hybrid.bukkit.network.HandshakeDecision;
import cloud.victus.hybrid.bukkit.network.HandshakeStyle;
import cloud.victus.hybrid.bukkit.network.NetworkChannel;
import cloud.victus.hybrid.bukkit.network.NetworkProfile;
import cloud.victus.hybrid.bukkit.registry.RegistryEntry;
import cloud.victus.hybrid.bukkit.registry.RegistryKind;
import cloud.victus.hybrid.bukkit.spi.BridgeAdapter;
import cloud.victus.hybrid.common.LoaderProfile;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class FixtureBridgeAdapter implements BridgeAdapter {
    public static boolean constructed;

    public FixtureBridgeAdapter() {
        constructed = true;
    }

    @Override
    public LoaderProfile profile() {
        return LoaderProfile.FABRIC;
    }

    @Override
    public String adapterVersion() {
        return "fixture-1";
    }

    @Override
    public Collection<RegistryEntry> registryEntries() {
        return List.of(
                new RegistryEntry(RegistryKind.BLOCK, NamespacedIdentifier.parse("minecraft:stone"), true, Map.of()),
                new RegistryEntry(RegistryKind.BLOCK, NamespacedIdentifier.parse("fixture:moon_rock"), false,
                        Map.of("hardness", "4.0")),
                new RegistryEntry(RegistryKind.ITEM, NamespacedIdentifier.parse("fixture:wrench"), false, Map.of()),
                new RegistryEntry(RegistryKind.ENTITY_TYPE, NamespacedIdentifier.parse("fixture:robot"), false, Map.of()),
                new RegistryEntry(RegistryKind.BIOME, NamespacedIdentifier.parse("fixture:moon"), false, Map.of()),
                new RegistryEntry(RegistryKind.DIMENSION, NamespacedIdentifier.parse("fixture:orbit"), false, Map.of()),
                new RegistryEntry(RegistryKind.RECIPE, NamespacedIdentifier.parse("fixture:wrench_recipe"), false, Map.of()),
                new RegistryEntry(RegistryKind.TAG, NamespacedIdentifier.parse("fixture:mineable/wrench"), false, Map.of()));
    }

    @Override
    public Collection<? extends EventTranslator> eventTranslators() {
        return List.of(new EventTranslator() {
            @Override
            public NamespacedIdentifier eventType() {
                return NamespacedIdentifier.parse("fixture:block_charge");
            }

            @Override
            public EventTranslationResult translate(cloud.victus.hybrid.bukkit.event.HybridEvent event) {
                return EventTranslationResult.partial(
                        new TranslatedEvent("org.bukkit.event.block.BlockPhysicsEvent", false, event.payload()),
                        "Bukkit event carries location but cannot represent fixture charge level mutation");
            }
        });
    }

    @Override
    public HandshakeAdapter handshakeAdapter() {
        return new HandshakeAdapter() {
            @Override public HandshakeStyle style() { return HandshakeStyle.FABRIC; }
            @Override public NetworkProfile serverProfile() {
                return new NetworkProfile(HandshakeStyle.FABRIC, "1", List.of(
                        new NetworkChannel(NamespacedIdentifier.parse("fixture:main"), "1", true)));
            }
            @Override public ClientHandshake decode(byte[] payload) {
                return new ClientHandshake(HandshakeStyle.FABRIC, new String(payload, StandardCharsets.UTF_8),
                        Map.of("fixture:main", "1"));
            }
            @Override public byte[] encodeDecision(HandshakeDecision decision) {
                return decision.code().getBytes(StandardCharsets.UTF_8);
            }
        };
    }
}
