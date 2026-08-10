// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.common;

/** Stable marker names consumed by fixtures, bounded integration tests, and startup diagnostics. */
public final class HybridMarkers {
    public static final String PREFLIGHT = "VICTUS_HYBRID_PREFLIGHT";
    public static final String TRANSFORMER = "VICTUS_HYBRID_TRANSFORMER";
    public static final String LOADER_ENTERED = "VICTUS_HYBRID_LOADER_ENTERED";
    public static final String TARGET_DELEGATED = "VICTUS_HYBRID_TARGET_DELEGATED";
    public static final String FABRIC_FIXTURE_ENTRYPOINT = "VICTUS_FIXTURE_FABRIC_ENTRYPOINT";
    public static final String FABRIC_FIXTURE_MIXIN = "VICTUS_FIXTURE_FABRIC_MIXIN";
    public static final String NEOFORGE_FIXTURE_CONSTRUCT = "VICTUS_FIXTURE_NEOFORGE_CONSTRUCT";
    public static final String BUKKIT_FIXTURE_ENABLE = "VICTUS_FIXTURE_BUKKIT_ENABLE";

    private HybridMarkers() {
    }
}
