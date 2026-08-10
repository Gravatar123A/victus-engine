// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

/** Executable isolation check: disabled mode must not attempt to link Fabric lifecycle classes. */
public final class FabricLifecycleBridgeSelfTest {
    private FabricLifecycleBridgeSelfTest() {
    }

    public static void main(String[] args) {
        String previous = System.getProperty("victus.fabric.provider");
        try {
            System.clearProperty("victus.fabric.provider");
            Object server = new Object();
            FabricLifecycleBridge.starting(server);
            FabricLifecycleBridge.started(server);
            FabricLifecycleBridge.stopping(server);
            FabricLifecycleBridge.stopped(server);
            System.out.println("FabricLifecycleBridgeSelfTest: disabled mode has no Fabric linkage");
        } finally {
            if (previous == null) System.clearProperty("victus.fabric.provider");
            else System.setProperty("victus.fabric.provider", previous);
        }
    }
}
