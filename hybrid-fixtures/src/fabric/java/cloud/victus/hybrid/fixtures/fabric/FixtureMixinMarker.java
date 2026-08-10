// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.fabric;

/** Owned transformation target. Its return value changes only when the fixture Mixin has run. */
public final class FixtureMixinMarker {
    private FixtureMixinMarker() {
    }

    public static String marker() {
        return "UNTRANSFORMED";
    }
}
