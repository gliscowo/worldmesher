package io.wispforest.worldmesher;

import com.mojang.blaze3d.systems.RenderSystem;
import io.wispforest.worldmesher.renderers.WorldMesherBlockModelRenderer;
import io.wispforest.worldmesher.renderers.WorldMesherFluidRenderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.WorldMesherRenderContext;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class WorldMesh extends BlockRenderViewMesh {

    protected WorldMesh(World world, BlockPos origin, BlockPos end, boolean cull, boolean freezeEntities, Runnable renderStartAction, Runnable renderEndAction) {
        super(world, origin, end, cull, freezeEntities, renderStartAction, renderEndAction);

        this.scheduleRebuild();
    }

    @Override
    protected List<Entity> getEntities(MinecraftClient client) {
        return client.world.getOtherEntities(client.player, new Box(this.origin, this.end).expand(.5), entity -> !(entity instanceof PlayerEntity));
    }
}