// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.network;

import java.util.Objects;

public record HandshakeDecision(boolean accepted, String code, String detail) {
    public HandshakeDecision {
        code = Objects.requireNonNull(code, "code").trim();
        detail = Objects.requireNonNull(detail, "detail").trim();
        if (code.isEmpty() || detail.isEmpty()) throw new IllegalArgumentException("code/detail must not be empty");
    }

    public static HandshakeDecision accept() {
        return new HandshakeDecision(true, "ACCEPT", "Client network profile is compatible");
    }

    public static HandshakeDecision refuse(String code, String detail) {
        return new HandshakeDecision(false, code, detail);
    }
}
