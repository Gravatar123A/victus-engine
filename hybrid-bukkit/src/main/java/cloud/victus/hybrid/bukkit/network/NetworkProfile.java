// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.network;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable network requirements advertised by a loader adapter. */
public record NetworkProfile(HandshakeStyle style, String protocolVersion, List<NetworkChannel> channels) {
    public NetworkProfile {
        Objects.requireNonNull(style, "style");
        protocolVersion = Objects.requireNonNull(protocolVersion, "protocolVersion").trim();
        if (protocolVersion.isEmpty()) throw new IllegalArgumentException("protocolVersion must not be empty");
        channels = (channels == null ? List.<NetworkChannel>of() : channels).stream()
                .sorted(Comparator.comparing(channel -> channel.id().toString())).toList();
    }

    public static NetworkProfile vanilla(String protocolVersion) {
        return new NetworkProfile(HandshakeStyle.VANILLA, protocolVersion, List.of());
    }
}
