// SPDX-License-Identifier: GPL-3.0-only
package org.bukkit.craftbukkit;

/** Owned class-bearing target used only by the Fabric provider integration test. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.setProperty("victus.fixture.target.called", "true");
    }
}
