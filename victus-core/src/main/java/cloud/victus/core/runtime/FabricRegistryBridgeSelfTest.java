// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

/** Executable isolation check: disabled mode must not attempt to link Fabric registry classes. */
public final class FabricRegistryBridgeSelfTest {
    private FabricRegistryBridgeSelfTest() {
    }

    public static void main(String[] args) {
        String previous = System.getProperty("victus.fabric.provider");
        try {
            System.clearProperty("victus.fabric.provider");
            FabricRegistryBridge.afterBootstrap();
            System.out.println("FabricRegistryBridgeSelfTest: disabled mode has no Fabric linkage");
        } finally {
            if (previous == null) System.clearProperty("victus.fabric.provider");
            else System.setProperty("victus.fabric.provider", previous);
        }
    }
}
