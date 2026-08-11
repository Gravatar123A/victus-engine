// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.command;

/** Policy for an unqualified command label claimed by Bukkit and a mod. */
public enum CollisionPolicy {
    BUKKIT_WINS,
    MOD_WINS,
    REQUIRE_NAMESPACE
}
