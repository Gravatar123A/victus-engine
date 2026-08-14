/*
 * Victus full-source bridge.
 * Base: Minecraft 26.2 / Paper patched CommonListenerCookie.
 * NeoForge side: 26.2.0.57 ConnectionType survives login, configuration, and play handoffs.
 * Paper side: brand, plugin channels, and KeepAlive state remain part of the same immutable cookie.
 */
package net.minecraft.server.network;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ClientInformation;

public record CommonListenerCookie(
    GameProfile gameProfile,
    int latency,
    ClientInformation clientInformation,
    boolean transferred,
    @org.jspecify.annotations.Nullable String clientBrand,
    java.util.Set<String> channels,
    io.papermc.paper.util.KeepAlive keepAlive,
    net.neoforged.neoforge.network.connection.ConnectionType connectionType
) {
    /** Paper-compatible constructor for call sites that have not negotiated NeoForge yet. */
    public CommonListenerCookie(
        final GameProfile gameProfile,
        final int latency,
        final ClientInformation clientInformation,
        final boolean transferred,
        final @org.jspecify.annotations.Nullable String clientBrand,
        final java.util.Set<String> channels,
        final io.papermc.paper.util.KeepAlive keepAlive
    ) {
        this(
            gameProfile,
            latency,
            clientInformation,
            transferred,
            clientBrand,
            channels,
            keepAlive,
            net.neoforged.neoforge.network.connection.ConnectionType.OTHER
        );
    }

    public static CommonListenerCookie createInitial(final GameProfile gameProfile, final boolean transferred) {
        return new CommonListenerCookie(
            gameProfile,
            0,
            ClientInformation.createDefault(),
            transferred,
            null,
            new java.util.HashSet<>(),
            new io.papermc.paper.util.KeepAlive(),
            net.neoforged.neoforge.network.connection.ConnectionType.OTHER
        );
    }
}
