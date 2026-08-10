// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.neoforge;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** No-op Mixin config plugin marker; discovery proves FML's Mixin service consumed fixture metadata. */
public final class FixtureMixinMarker implements IMixinConfigPlugin {
    public static final String MARKER = "VICTUS_FIXTURE_NEOFORGE_MIXIN_SERVICE";

    @Override
    public void onLoad(String mixinPackage) {
        FixtureSignals.marker("mixin_service", MARKER);
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return List.of(); }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
