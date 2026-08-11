// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.lifecycle;

/** Ordered bridge phases shared by loader adapters and the Bukkit host. */
public enum BridgePhase {
    DISABLED,
    CREATED,
    LOADER_DISCOVERY,
    REGISTRATION,
    REGISTRIES_FROZEN,
    BUKKIT_BINDING,
    READY,
    STOPPING,
    STOPPED,
    FAILED
}
