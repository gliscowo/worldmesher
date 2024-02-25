package io.wispforest.worldmesher.render;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;

public class MeshRenderView implements BlockRenderView {

    private final BlockRenderView delegate;
    private final BlockPos from, to;

    public MeshRenderView(BlockRenderView delegate, BlockPos from, BlockPos to) {
        this.delegate = delegate;
        this.from = from;
        this.to = to;
    }

    @Override
    public float getBrightness(Direction direction, boolean shaded) {
        return this.delegate.getBrightness(direction, shaded);
    }

    @Override
    public LightingProvider getLightingProvider() {
        return this.delegate.getLightingProvider();
    }

    @Override
    public int getColor(BlockPos pos, ColorResolver colorResolver) {
        return this.delegate.getColor(pos, colorResolver);
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.contains(pos)
                ? this.delegate.getBlockEntity(pos)
                : null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.contains(pos)
                ? this.delegate.getBlockState(pos)
                : Blocks.AIR.getDefaultState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.contains(pos)
                ? this.delegate.getFluidState(pos)
                : Fluids.EMPTY.getDefaultState();
    }

    @Override
    public int getLightLevel(LightType type, BlockPos pos) {
        return this.contains(pos)
                ? BlockRenderView.super.getLightLevel(type, pos)
                : type == LightType.SKY ? 15 : 0;
    }

    @Override
    public int getBaseLightLevel(BlockPos pos, int ambientDarkness) {
        return this.contains(pos)
                ? BlockRenderView.super.getBaseLightLevel(pos, ambientDarkness)
                : 15;
    }

    @Override
    public int getHeight() {
        return this.delegate.getHeight();
    }

    @Override
    public int getBottomY() {
        return this.delegate.getBottomY();
    }

    public boolean contains(BlockPos pos) {
        return this.from.getX() <= pos.getX() && this.from.getY() <= pos.getY() && this.from.getZ() <= pos.getZ()
                && this.to.getX() >= pos.getX() && this.to.getY() >= pos.getY() && this.to.getZ() >= pos.getZ();
    }
}
