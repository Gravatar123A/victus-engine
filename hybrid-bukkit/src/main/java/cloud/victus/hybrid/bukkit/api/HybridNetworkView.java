// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.api;

import cloud.victus.hybrid.bukkit.network.NetworkProfile;

/** Read-only extension exposing loader handshake requirements absent from Bukkit's player API. */
public interface HybridNetworkView {
    NetworkProfile networkProfile();
}
