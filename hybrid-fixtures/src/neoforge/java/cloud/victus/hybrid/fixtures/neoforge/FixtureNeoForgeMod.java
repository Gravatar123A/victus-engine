// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.neoforge;

import net.neoforged.fml.common.Mod;

/** Owned minimal constructor marker compiled against FML 11.0.17. */
@Mod("victus_hybrid_fixture_neoforge")
public final class FixtureNeoForgeMod {
    public FixtureNeoForgeMod() {
        System.setProperty("victus.fixture.neoforge.construct", "VICTUS_FIXTURE_NEOFORGE_CONSTRUCT");
    }
}
