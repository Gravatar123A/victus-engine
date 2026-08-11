// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.network;

import java.util.Map;
import java.util.Objects;

/** Loader-neutral client response used for deterministic compatibility decisions. */
public record ClientHandshake(HandshakeStyle style, String protocolVersion, Map<String, String> channels) {
    public ClientHandshake {
        Objects.requireNonNull(style, "style");
        protocolVersion = Objects.requireNonNull(protocolVersion, "protocolVersion").trim();
        channels = Map.copyOf(channels == null ? Map.of() : channels);
    }
}
