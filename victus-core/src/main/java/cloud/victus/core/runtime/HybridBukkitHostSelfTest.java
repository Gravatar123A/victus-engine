// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.core.runtime;

/** Disabled-server proof: linkage-free bridge host must not resolve Fabric or hybrid classes. */
public final class HybridBukkitHostSelfTest {
    private HybridBukkitHostSelfTest() {
    }

    public static void main(String[] args) {
        String previous = System.getProperty("victus.fabric.provider");
        try {
            System.clearProperty("victus.fabric.provider");
            HybridBukkitHost.bind(new Object());
            HybridBukkitHost.ready();
            System.out.println("HybridBukkitHostSelfTest: disabled mode has no Fabric/hybrid linkage");
        } finally {
            if (previous == null) System.clearProperty("victus.fabric.provider");
            else System.setProperty("victus.fabric.provider", previous);
        }
    }
}
