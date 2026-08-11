// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.network;

/** Common compatibility policy; wire parsing and response emission remain adapter responsibilities. */
public final class HandshakePolicy {
    public HandshakeDecision evaluate(NetworkProfile server, ClientHandshake client) {
        if (server.style() != client.style()) {
            return HandshakeDecision.refuse("LOADER_MISMATCH", "Client and server loader handshake families differ");
        }
        if (!server.protocolVersion().equals(client.protocolVersion())) {
            return HandshakeDecision.refuse("PROTOCOL_MISMATCH", "Client and server hybrid protocol versions differ");
        }
        for (NetworkChannel required : server.channels()) {
            if (!required.required()) continue;
            String clientVersion = client.channels().get(required.id().toString());
            if (clientVersion == null) {
                return HandshakeDecision.refuse("CHANNEL_MISSING", "Required channel missing: " + required.id());
            }
            if (!required.version().equals(clientVersion)) {
                return HandshakeDecision.refuse("CHANNEL_VERSION_MISMATCH", "Channel version differs: " + required.id());
            }
        }
        return HandshakeDecision.accept();
    }
}
