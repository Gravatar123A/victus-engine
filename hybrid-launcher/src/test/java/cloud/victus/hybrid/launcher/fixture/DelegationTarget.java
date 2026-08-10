// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.launcher.fixture;

public final class DelegationTarget {
    public static void main(String[] args) {
        System.setProperty("victus.launcher.delegated", String.join("", args));
    }
}
