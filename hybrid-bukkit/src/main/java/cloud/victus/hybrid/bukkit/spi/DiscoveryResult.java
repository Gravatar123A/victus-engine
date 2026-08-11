// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.spi;

import java.util.Objects;
import java.util.Optional;

public record DiscoveryResult(boolean enabled, Optional<BridgeAdapter> adapter) {
    public DiscoveryResult {
        adapter = adapter == null ? Optional.empty() : adapter;
        if (enabled != adapter.isPresent()) throw new IllegalArgumentException("Enabled result must contain adapter");
    }

    public static DiscoveryResult disabled() {
        return new DiscoveryResult(false, Optional.empty());
    }

    public static DiscoveryResult enabled(BridgeAdapter adapter) {
        return new DiscoveryResult(true, Optional.of(Objects.requireNonNull(adapter, "adapter")));
    }
}
