package com.glisco.worldmesher.renderers;

import com.glisco.worldmesher.mixin.BlockModelRendererInvoker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashReportSection;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockRenderView;

import java.util.BitSet;
import java.util.List;
import java.util.Random;

public class WorldMesherBlockModelRenderer extends BlockModelRenderer {

    private byte cullingOverrides = 0;

    public WorldMesherBlockModelRenderer() {
        super(MinecraftClient.getInstance().getBlockColors());
    }

    public void setCullDirection(Direction direction, boolean alwaysDraw) {
        if (!alwaysDraw) return;
        cullingOverrides |= (1 << direction.getId());
    }

    public void clearCullingOverrides() {
        cullingOverrides = 0;
    }

    @Override
    public boolean render(BlockRenderView world, BakedModel model, BlockState state, BlockPos pos, MatrixStack matrix, VertexConsumer vertexConsumer, boolean cull, Random random, long seed, int overlay) {
        boolean bl = MinecraftClient.isAmbientOcclusionEnabled() && state.getLuminance() == 0 && model.useAmbientOcclusion();
        Vec3d vec3d = state.getModelOffset(world, pos);
        matrix.translate(vec3d.x, vec3d.y, vec3d.z);

        try {
            return bl ? this.renderSmooth(world, model, state, pos, matrix, vertexConsumer, cull, random, seed, overlay) : this.renderFlat(world, model, state, pos, matrix, vertexConsumer, cull, random, seed, overlay);
        } catch (Throwable var17) {
            CrashReport crashReport = CrashReport.create(var17, "Tesselating block model");
            CrashReportSection crashReportSection = crashReport.addElement("Block model being tesselated");
            CrashReportSection.addBlockInfo(crashReportSection, pos, state);
            crashReportSection.add("Using AO", (Object) bl);
            throw new CrashException(crashReport);
        }
    }

    @Override
    public synchronized boolean renderSmooth(BlockRenderView world, BakedModel model, BlockState state, BlockPos pos, MatrixStack buffer, VertexConsumer vertexConsumer, boolean cull, Random random, long seed, int overlay) {
        boolean anyFacesRendered = false;
        float[] fs = new float[12];
        BitSet flags = new BitSet(3);
        BlockModelRenderer.AmbientOcclusionCalculator ambientOcclusionCalculator = new BlockModelRenderer.AmbientOcclusionCalculator();
        final BlockModelRendererInvoker invoker = (BlockModelRendererInvoker) this;

        for (Direction direction : Direction.values()) {
            random.setSeed(seed);
            List<BakedQuad> faceQuads = model.getQuads(state, direction, random);

            if (!faceQuads.isEmpty() && (!cull || shouldAlwaysDraw(direction) || Block.shouldDrawSide(state, world, pos, direction))) {
                invoker.worldmesher_renderQuadsSmooth(world, state, !shouldAlwaysDraw(direction) ? pos : pos.add(0, 500, 0), buffer, vertexConsumer, faceQuads, fs, flags, ambientOcclusionCalculator, overlay);
                anyFacesRendered = true;
            }
        }

        random.setSeed(seed);
        List<BakedQuad> quads = model.getQuads(state, null, random);
        if (!quads.isEmpty()) {
            invoker.worldmesher_renderQuadsSmooth(world, state, pos, buffer, vertexConsumer, quads, fs, flags, ambientOcclusionCalculator, overlay);
            anyFacesRendered = true;
        }

        return anyFacesRendered;
    }

    @Override
    public synchronized boolean renderFlat(BlockRenderView world, BakedModel model, BlockState state, BlockPos pos, MatrixStack buffer, VertexConsumer vertexConsumer, boolean cull, Random random, long seed, int overlay) {
        boolean anyFacesRendered = false;
        BitSet flags = new BitSet(3);
        final BlockModelRendererInvoker invoker = (BlockModelRendererInvoker) this;

        for (Direction direction : Direction.values()) {
            random.setSeed(seed);
            List<BakedQuad> faceQuads = model.getQuads(state, direction, random);

            if (!faceQuads.isEmpty() && (!cull || shouldAlwaysDraw(direction) || Block.shouldDrawSide(state, world, pos, direction))) {
                int light = WorldRenderer.getLightmapCoordinates(world, state, pos.offset(direction));
                invoker.worldmesher_renderQuadsFlat(world, state, !shouldAlwaysDraw(direction) ? pos : pos.add(0, 500, 0), light, overlay, false, buffer, vertexConsumer, faceQuads, flags);
                anyFacesRendered = true;
            }
        }


        random.setSeed(seed);
        List<BakedQuad> quads = model.getQuads(state, null, random);
        if (!quads.isEmpty()) {
            invoker.worldmesher_renderQuadsFlat(world, state, pos, -1, overlay, true, buffer, vertexConsumer, quads, flags);
            anyFacesRendered = true;
        }

        return anyFacesRendered;
    }

    private boolean shouldAlwaysDraw(Direction direction) {
        return (cullingOverrides & (1 << direction.getId())) != 0;
    }

}
