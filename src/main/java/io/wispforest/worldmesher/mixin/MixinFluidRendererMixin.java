package io.wispforest.worldmesher.mixin;

import io.wispforest.worldmesher.renderers.WorldMesherFluidRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.FluidRenderer;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * This mixin is injected after Fabric API has done its things, so
 * that we can go right back and revert them if it's our renderer
 */
@Mixin(value = FluidRenderer.class, priority = 1100)
public class MixinFluidRendererMixin {

    @SuppressWarnings("MixinAnnotationTarget")
    @Shadow(remap = false)
    @Final
    private ThreadLocal<Boolean> fabric_customRendering;

    @SuppressWarnings({"UnresolvedMixinReference", "MixinAnnotationTarget", "CancellableInjectionUsage", "ConstantConditions"})
    @Inject(method = "tessellateViaHandler", at = @At("HEAD"), cancellable = true, remap = false)
    private void cancelFabricOnTwitter(BlockRenderView view, BlockPos pos, VertexConsumer vertexConsumer, BlockState blockState, FluidState fluidState, CallbackInfo delegateInfo, CallbackInfo info) {
        if (!((Object) this instanceof WorldMesherFluidRenderer)) return;
        fabric_customRendering.set(true);
        info.cancel();
    }

}
