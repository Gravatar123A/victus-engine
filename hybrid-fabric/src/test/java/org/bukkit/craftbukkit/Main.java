// SPDX-License-Identifier: GPL-3.0-only
package org.bukkit.craftbukkit;

/** Owned class-bearing target used only by the Fabric provider integration test. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        String probe = System.getProperty("victus.fabric.externalLibraryProbe", "");
        if (!probe.isBlank()) {
            try {
                Class<?> type = Class.forName(probe, true, Thread.currentThread().getContextClassLoader());
                System.setProperty("victus.fixture.target.externalLibrary", String.valueOf(type.getMethod("marker").invoke(null)));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("owned target cannot see external runtime library " + probe, failure);
            }
        }
        System.setProperty("victus.fixture.target.called", "true");
    }
}
