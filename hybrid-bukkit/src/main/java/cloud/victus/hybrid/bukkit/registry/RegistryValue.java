// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.registry;

import cloud.victus.hybrid.bukkit.api.HybridKeyed;
import java.util.Map;

/** Lookup result common to native and unknown/modded content. */
public sealed interface RegistryValue extends HybridKeyed permits NativeRegistryValue, UnknownRegistryValue {
    RegistryKind kind();

    Map<String, String> attributes();

    default boolean hasNativeBukkitRepresentation() {
        return this instanceof NativeRegistryValue;
    }
}
