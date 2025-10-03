package net.fabricmc.fabric.impl.client.indigo.renderer.render;

import net.fabricmc.fabric.api.renderer.v1.render.BlockVertexConsumerProvider;
import net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.AoLuminanceFix;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashReportSection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

@SuppressWarnings("UnstableApiUsage")
public class WorldMesherRenderContext extends AbstractTerrainRenderContext {

    private final BlockRenderView blockView;
    private final BlockVertexConsumerProvider bufferFunc;

	private final Random random = Random.createLocal();

    public WorldMesherRenderContext(BlockRenderView blockView, BlockVertexConsumerProvider bufferFunc) {
        this.blockView = blockView;
        this.bufferFunc = bufferFunc;

        this.blockInfo.prepareForWorld(blockView, true);
    }

    public void tessellateBlock(BlockState blockState, BlockPos blockPos, final BlockStateModel model, MatrixStack matrixStack) {
        try {
            Vec3d offset = blockState.getModelOffset(blockPos);
            matrixStack.translate(offset.x, offset.y, offset.z);

            matrices = matrixStack.peek();

	        random.setSeed(blockState.getRenderingSeed(blockPos));

			prepare(blockPos, blockState);
            model.emitQuads(getEmitter(), blockInfo.blockView, blockInfo.blockPos, blockInfo.blockState, random, blockInfo::shouldCullSide);
        } catch (Throwable throwable) {
            CrashReport crashReport = CrashReport.create(throwable, "Tessellating block in WorldMesher mesh");
            CrashReportSection crashReportSection = crashReport.addElement("Block being tessellated");
            CrashReportSection.addBlockInfo(crashReportSection, blockView, blockPos, blockState);
            throw new CrashException(crashReport);
        }
    }

	@Override
	protected LightDataProvider createLightDataProvider(BlockRenderInfo blockInfo) {
		// TODO: Use a cache whenever vanilla would use a cache (BrightnessCache.enabled)
		return new LightDataProvider() {
			@Override
			public int light(BlockPos pos, BlockState state) {
				return WorldRenderer.getLightmapCoordinates(WorldRenderer.BrightnessGetter.DEFAULT, blockInfo.blockView, state, pos);
			}

			@Override
			public float ao(BlockPos pos, BlockState state) {
				return AoLuminanceFix.INSTANCE.apply(blockInfo.blockView, pos, state);
			}
		};
	}

	@Override
	protected VertexConsumer getVertexConsumer(BlockRenderLayer layer) {
		return this.bufferFunc.getBuffer(layer);
	}
}
