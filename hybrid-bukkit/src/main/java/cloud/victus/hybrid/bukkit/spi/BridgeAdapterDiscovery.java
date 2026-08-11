// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.spi;

import cloud.victus.hybrid.common.LoaderProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/** Strict ServiceLoader selection with disabled-mode isolation and duplicate refusal. */
public final class BridgeAdapterDiscovery {
    public DiscoveryResult discover(LoaderProfile profile, ClassLoader classLoader) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(classLoader, "classLoader");
        if (profile == LoaderProfile.DISABLED) return DiscoveryResult.disabled();

        List<BridgeAdapter> matches = new ArrayList<>();
        for (BridgeAdapter adapter : ServiceLoader.load(BridgeAdapter.class, classLoader)) {
            if (adapter.profile() == profile) matches.add(adapter);
        }
        if (matches.isEmpty()) throw new IllegalStateException("No Bukkit bridge adapter for " + profile);
        if (matches.size() != 1) throw new IllegalStateException("Multiple Bukkit bridge adapters for " + profile);
        return DiscoveryResult.enabled(matches.getFirst());
    }
}
