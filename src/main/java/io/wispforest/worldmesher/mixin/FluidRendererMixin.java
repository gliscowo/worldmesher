package io.wispforest.worldmesher.mixin;

import io.wispforest.worldmesher.renderers.WorldMesherFluidRenderer;
import net.minecraft.client.render.block.FluidRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * This mixin is injected before Fabric API to prevent if from caching
 * the WorldMesherFluidRenderer as the default instance
 */
@Mixin(value = FluidRenderer.class, priority = 900)
public class FluidRendererMixin {

    @SuppressWarnings("ConstantConditions")
    @Inject(method = "onResourceReload", at = @At("RETURN"), cancellable = true)
    private void cancelFabricOnTwitter(CallbackInfo ci) {
        if (!((Object) this instanceof WorldMesherFluidRenderer)) return;
        ci.cancel();
    }

}
