// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.network;

/** Adapter boundary for loader-specific wire handshake parsing and responses. */
public interface HandshakeAdapter {
    HandshakeStyle style();

    NetworkProfile serverProfile();

    ClientHandshake decode(byte[] payload);

    byte[] encodeDecision(HandshakeDecision decision);
}
