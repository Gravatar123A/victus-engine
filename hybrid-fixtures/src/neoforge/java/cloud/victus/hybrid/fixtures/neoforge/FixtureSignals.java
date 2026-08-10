// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.neoforge;

/** Marker writer with no Minecraft references, safe during FML's earliest service-discovery phase. */
final class FixtureSignals {
    private FixtureSignals() {
    }

    static void marker(String name, String value) {
        System.setProperty("victus.fixture.neoforge." + name, value);
        System.out.println(value);
    }
}
