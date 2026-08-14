/*
 * Victus full-source bridge.
 * Base: Minecraft/Paper 26.2 serverbound custom-payload packet.
 * NeoForge 26.2.0.57 supplies protocol/flow-aware codecs; Paper's raw DiscardedPayload
 * fallback remains intact for Bukkit plugin channels and its 32 KiB abuse boundary.
 */
package net.minecraft.network.protocol.common;

import com.google.common.collect.Lists;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.util.Util;

public record ServerboundCustomPayloadPacket(CustomPacketPayload payload) implements Packet<ServerCommonPacketListener> {
    private static final int MAX_PAYLOAD_SIZE = 32767;
    public static final StreamCodec<FriendlyByteBuf, ServerboundCustomPayloadPacket> STREAM_CODEC = CustomPacketPayload.<FriendlyByteBuf>codec(
            id -> DiscardedPayload.codec(id, MAX_PAYLOAD_SIZE),
            java.util.Collections.emptyList(), // Paper: unknown/plugin channels remain raw
            net.minecraft.network.ConnectionProtocol.PLAY,
            net.minecraft.network.protocol.PacketFlow.SERVERBOUND
        )
        .map(ServerboundCustomPayloadPacket::new, ServerboundCustomPayloadPacket::payload);
    public static final StreamCodec<FriendlyByteBuf, ServerboundCustomPayloadPacket> CONFIG_STREAM_CODEC = CustomPacketPayload.<FriendlyByteBuf>codec(
            id -> DiscardedPayload.codec(id, MAX_PAYLOAD_SIZE),
            java.util.Collections.emptyList(), // Paper: preserve raw register/brand/plugin payloads
            net.minecraft.network.ConnectionProtocol.CONFIGURATION,
            net.minecraft.network.protocol.PacketFlow.SERVERBOUND
        )
        .map(ServerboundCustomPayloadPacket::new, ServerboundCustomPayloadPacket::payload);

    @Override
    public PacketType<ServerboundCustomPayloadPacket> type() {
        return CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD;
    }

    @Override
    public void handle(final ServerCommonPacketListener listener) {
        listener.handleCustomPayload(this);
    }
}
