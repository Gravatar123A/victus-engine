/*
 * Victus full-source bridge.
 * Base: Minecraft/Paper 26.2 clientbound custom-payload packet.
 * NeoForge 26.2.0.57 supplies protocol/flow-aware codecs for negotiated mod payloads;
 * vanilla/Paper brand and unknown-payload fallbacks retain the one MiB boundary.
 */
package net.minecraft.network.protocol.common;

import com.google.common.collect.Lists;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.util.Util;

public record ClientboundCustomPayloadPacket(CustomPacketPayload payload) implements Packet<ClientCommonPacketListener> {
    private static final int MAX_PAYLOAD_SIZE = 1048576;
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundCustomPayloadPacket> GAMEPLAY_STREAM_CODEC = CustomPacketPayload.<RegistryFriendlyByteBuf>codec(
            id -> DiscardedPayload.codec(id, MAX_PAYLOAD_SIZE),
            Util.make(Lists.newArrayList(new CustomPacketPayload.TypeAndCodec<>(BrandPayload.TYPE, BrandPayload.STREAM_CODEC)), types -> {}),
            net.minecraft.network.ConnectionProtocol.PLAY,
            net.minecraft.network.protocol.PacketFlow.CLIENTBOUND
        )
        .map(ClientboundCustomPayloadPacket::new, ClientboundCustomPayloadPacket::payload);
    public static final StreamCodec<FriendlyByteBuf, ClientboundCustomPayloadPacket> CONFIG_STREAM_CODEC = CustomPacketPayload.<FriendlyByteBuf>codec(
            id -> DiscardedPayload.codec(id, MAX_PAYLOAD_SIZE),
            List.of(new CustomPacketPayload.TypeAndCodec<>(BrandPayload.TYPE, BrandPayload.STREAM_CODEC)),
            net.minecraft.network.ConnectionProtocol.CONFIGURATION,
            net.minecraft.network.protocol.PacketFlow.CLIENTBOUND
        )
        .map(ClientboundCustomPayloadPacket::new, ClientboundCustomPayloadPacket::payload);

    @Override
    public PacketType<ClientboundCustomPayloadPacket> type() {
        return CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD;
    }

    @Override
    public void handle(final ClientCommonPacketListener listener) {
        listener.handleCustomPayload(this);
    }
}
