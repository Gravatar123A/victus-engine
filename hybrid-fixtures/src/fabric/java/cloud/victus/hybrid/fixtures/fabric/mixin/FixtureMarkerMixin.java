// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.fabric.mixin;

import cloud.victus.hybrid.fixtures.fabric.FixtureMixinMarker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Proves that the owned marker class was defined only after Mixin became active. */
@Mixin(FixtureMixinMarker.class)
public abstract class FixtureMarkerMixin {
    @Inject(method = "marker", at = @At("HEAD"), cancellable = true)
    private static void victus$markTransformed(CallbackInfoReturnable<String> callback) {
        callback.setReturnValue("VICTUS_FIXTURE_FABRIC_MIXIN");
    }
}
