package io.wispforest.worldmesher.renderers;

import com.google.gson.JsonArray;
import it.unimi.dsi.fastutil.objects.Object2ByteLinkedOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.BlockView;

import java.util.BitSet;
import java.util.List;

public class WorldMesherBlockModelRenderer extends BlockModelRenderer {

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final ThreadLocal<Object2ByteLinkedOpenHashMap<Block.NeighborGroup>> FACE_CULL_MAP = ThreadLocal.withInitial(() -> {
        Object2ByteLinkedOpenHashMap<Block.NeighborGroup> object2ByteLinkedOpenHashMap = new Object2ByteLinkedOpenHashMap<Block.NeighborGroup>(2048, 0.25F) {
            protected void rehash(int newN) {
            }
        };
        object2ByteLinkedOpenHashMap.defaultReturnValue((byte)127);
        return object2ByteLinkedOpenHashMap;
    });
    private byte cullingOverrides = 0;

    public WorldMesherBlockModelRenderer() {
        super(MinecraftClient.getInstance().getBlockColors());
    }

    public void setCullDirection(Direction direction, boolean alwaysDraw) {
        if (!alwaysDraw) return;
        cullingOverrides |= (byte) (1 << direction.getId());
    }

    public void clearCullingOverrides() {
        cullingOverrides = 0;
    }

    private boolean shouldAlwaysDraw(Direction direction) {
        return (cullingOverrides & (1 << direction.getId())) != 0;
    }

    @Override
    public void renderSmooth(BlockRenderView world, BakedModel model, BlockState state, BlockPos pos, MatrixStack matrices, VertexConsumer vertexConsumer, boolean cull, Random random, long seed, int overlay) {
        float[] fs = new float[DIRECTIONS.length * 2];
        BitSet bitSet = new BitSet(3);
        BlockModelRenderer.AmbientOcclusionCalculator ambientOcclusionCalculator = new BlockModelRenderer.AmbientOcclusionCalculator();
        BlockPos.Mutable mutable = pos.mutableCopy();

        for (Direction direction : DIRECTIONS) {
            random.setSeed(seed);
            List<BakedQuad> list = model.getQuads(state, direction, random);
            if (!list.isEmpty()) {
                mutable.set(pos, direction);
                if (!cull || shouldAlwaysDraw(direction) || shouldDrawSide(state, world, pos, direction, mutable)) {
                    this.renderQuadsSmooth(world, state, !shouldAlwaysDraw(direction) ? pos : pos.add(0, 500, 0), matrices, vertexConsumer, list, fs, bitSet, ambientOcclusionCalculator, overlay);
                }
            }
        }

        random.setSeed(seed);
        List<BakedQuad> quads = model.getQuads(state, null, random);
        if (!quads.isEmpty()) {
            this.renderQuadsSmooth(world, state, pos, matrices, vertexConsumer, quads, fs, bitSet, ambientOcclusionCalculator, overlay);
        }

    }

    private static boolean shouldDrawSide(BlockState state, BlockView world, BlockPos pos, Direction side, BlockPos otherPos) {
        BlockState blockState = world.getBlockState(otherPos);
        if (state.isSideInvisible(blockState, side)) {
            return false;
        } else if (blockState.isOpaque()) {
            Block.NeighborGroup neighborGroup = new Block.NeighborGroup(state, blockState, side);
            Object2ByteLinkedOpenHashMap<Block.NeighborGroup> object2ByteLinkedOpenHashMap = (Object2ByteLinkedOpenHashMap)FACE_CULL_MAP.get();
            byte b = object2ByteLinkedOpenHashMap.getAndMoveToFirst(neighborGroup);
            if (b != 127) {
                return b != 0;
            } else {
                VoxelShape voxelShape = state.getCullingFace(world, pos, side);
                if (voxelShape.isEmpty()) {
                    return true;
                } else {
                    VoxelShape voxelShape2 = blockState.getCullingFace(world, otherPos, side.getOpposite());
                    boolean bl = VoxelShapes.matchesAnywhere(voxelShape, voxelShape2, BooleanBiFunction.ONLY_FIRST);
                    if (object2ByteLinkedOpenHashMap.size() == 2048) {
                        object2ByteLinkedOpenHashMap.removeLastByte();
                    }

                    object2ByteLinkedOpenHashMap.putAndMoveToFirst(neighborGroup, (byte)(bl ? 1 : 0));
                    return bl;
                }
            }
        } else {
            return true;
        }
    }

    public void renderFlat(BlockRenderView world, BakedModel model, BlockState state, BlockPos pos, MatrixStack matrices, VertexConsumer vertexConsumer, boolean cull, Random random, long seed, int overlay) {
        BitSet bitSet = new BitSet(3);
        BlockPos.Mutable mutable = pos.mutableCopy();

        for (Direction direction : DIRECTIONS) {
            random.setSeed(seed);
            List<BakedQuad> list = model.getQuads(state, direction, random);
            if (!list.isEmpty()) {
                mutable.set(pos, direction);
                if (!cull || shouldAlwaysDraw(direction) || Block.shouldDrawSide(state, world, pos, direction, mutable)) {
                    int i = WorldRenderer.getLightmapCoordinates(world, state, mutable);
                    this.renderQuadsFlat(world, state, !shouldAlwaysDraw(direction) ? pos : pos.add(0, 500, 0), i, overlay, false, matrices, vertexConsumer, list, bitSet);
                }
            }
        }

        random.setSeed(seed);
        List<BakedQuad> list2 = model.getQuads(state, null, random);
        if (!list2.isEmpty()) {
            this.renderQuadsFlat(world, state, pos, -1, overlay, true, matrices, vertexConsumer, list2, bitSet);
        }

    }

}
