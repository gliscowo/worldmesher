package com.glisco.worldmesher;

import com.glisco.worldmesher.renderers.WorldMesherBlockModelRenderer;
import com.glisco.worldmesher.renderers.WorldMesherFluidRenderer;
import com.google.common.collect.Lists;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.Util;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class WorldMesh {

    //TODO entities and blockentities
    private final World world;
    private final BlockPos origin;
    private final BlockPos end;
    private final boolean bakeBlockEntities;
    private final boolean bakeEntities;

    private MeshState state = MeshState.NEW;

    private final Map<RenderLayer, VertexBuffer> bufferStorage;
    private final Map<RenderLayer, BufferBuilder> initializedLayers;

    private final Runnable renderStartAction;
    private final Runnable renderEndAction;

    private Supplier<MatrixStack> matrixStackSupplier = MatrixStack::new;

    private WorldMesh(World world, BlockPos origin, BlockPos end, boolean bakeBlockEntities, boolean bakeEntities, Runnable renderStartAction, Runnable renderEndAction) {
        this.world = world;
        this.origin = origin;
        this.end = end;
        this.bakeBlockEntities = bakeBlockEntities;
        this.bakeEntities = bakeEntities;

        this.renderStartAction = renderStartAction;
        this.renderEndAction = renderEndAction;

        this.bufferStorage = RenderLayer.getBlockLayers().stream().collect(Collectors.toMap((renderLayer) -> renderLayer, (renderLayer) -> new VertexBuffer(VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL)));
        this.initializedLayers = new HashMap<>();

        this.scheduleRebuild();
    }

    /**
     * Renders this world mesh into the current framebuffer, translated using the given matrix
     *
     * @param matrix The translation matrix. This is applied to the entire mesh
     */
    public void render(Matrix4f matrix) {

        if (!this.canRender()) {
            throw new IllegalStateException("World mesh not prepared!");
        }

        final RenderLayer translucent = RenderLayer.getTranslucent();
        bufferStorage.forEach((renderLayer, vertexBuffer) -> {
            if (renderLayer == translucent) return;
            draw(renderLayer, vertexBuffer, matrix);
        });

        if (bufferStorage.containsKey(translucent)) {
            draw(translucent, bufferStorage.get(translucent), matrix);
        }

    }

    public void setMatrixStackSupplier(Supplier<MatrixStack> stackSupplier) {
        this.matrixStackSupplier = stackSupplier;
    }

    /**
     * Checks whether this mesh is ready for rendering
     */
    public boolean canRender() {
        return state.canRender;
    }

    /**
     * Returns the current state of this mesh, used to indicate building progress and rendering availability
     *
     * @return The current {@code MeshState} constant
     */
    public MeshState getState() {
        return state;
    }

    /**
     * Schedules a rebuild a of this mesh
     */
    public void scheduleRebuild() {
        if (state.isBuildStage) return;
        state = state == MeshState.NEW ? MeshState.BUILDING : MeshState.REBUILDING;
        initializedLayers.clear();

        CompletableFuture.runAsync(this::build, Util.getMainWorkerExecutor()).whenComplete((unused, throwable) -> state = MeshState.READY);
    }

    private void build() {
        final BlockRenderManager blockRenderManager = MinecraftClient.getInstance().getBlockRenderManager();
        final WorldMesherBlockModelRenderer blockRenderer = new WorldMesherBlockModelRenderer();
        final WorldMesherFluidRenderer fluidRenderer = new WorldMesherFluidRenderer();
        MatrixStack matrices = matrixStackSupplier.get();

        Random random = new Random();

        for (BlockPos pos : BlockPos.iterate(this.origin, this.end)) {

            //TODO check if loaded
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;

            matrices.push();
            BlockPos renderPos = pos.subtract(origin);
            matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());

            blockRenderer.clearCullingOverrides();
            blockRenderer.setCullDirection(Direction.EAST, pos.getX() == this.end.getX());
            blockRenderer.setCullDirection(Direction.WEST, pos.getX() == this.origin.getX());
            blockRenderer.setCullDirection(Direction.SOUTH, pos.getZ() == this.end.getZ());
            blockRenderer.setCullDirection(Direction.NORTH, pos.getZ() == this.origin.getZ());
            blockRenderer.setCullDirection(Direction.UP, pos.getY() == this.end.getY());
            blockRenderer.setCullDirection(Direction.DOWN, pos.getY() == this.origin.getY());

            RenderLayer renderLayer = RenderLayers.getBlockLayer(state);

            if (!initializedLayers.containsKey(renderLayer)) {
                BufferBuilder builder = new BufferBuilder(renderLayer.getExpectedBufferSize());
                initializedLayers.put(renderLayer, builder);
                builder.begin(7, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
            }

            blockRenderer.render(world, blockRenderManager.getModel(state), state, pos, matrices, initializedLayers.get(renderLayer), true, random, state.getRenderingSeed(pos), OverlayTexture.DEFAULT_UV);
            matrices.pop();

            if (!world.getFluidState(pos).isEmpty()) {

                FluidState fluidState = world.getFluidState(pos);
                RenderLayer fluidLayer = RenderLayers.getFluidLayer(fluidState);

                if (!initializedLayers.containsKey(fluidLayer)) {
                    BufferBuilder builder = new BufferBuilder(fluidLayer.getExpectedBufferSize());
                    initializedLayers.put(fluidLayer, builder);
                    builder.begin(7, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
                }

                matrices.push();
                matrices.translate(-(pos.getX() & 15), -(pos.getY() & 15), -(pos.getZ() & 15));
                matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());

                fluidRenderer.setMatrix(matrices.peek().getModel());
                fluidRenderer.render(world, pos, initializedLayers.get(fluidLayer), fluidState);

                matrices.pop();
            }

        }

        if (initializedLayers.containsKey(RenderLayer.getTranslucent())) {
            BufferBuilder bufferBuilder3 = initializedLayers.get(RenderLayer.getTranslucent());
            Camera camera = MinecraftClient.getInstance().gameRenderer.getCamera();
            bufferBuilder3.sortQuads((float) camera.getPos().x - (float) origin.getX(), (float) camera.getPos().y - (float) origin.getY(), (float) camera.getPos().z - (float) origin.getZ());
        }

        initializedLayers.entrySet().stream().map(Map.Entry::getValue).forEach(BufferBuilder::end);

        List<CompletableFuture<Void>> list = Lists.newArrayList();
        initializedLayers.forEach((renderLayer, bufferBuilder) -> {
            list.add(bufferStorage.get(renderLayer).submitUpload(bufferBuilder));
        });
        Util.combine(list).handle((voids, throwable) -> {
            if (throwable != null) {
                MinecraftClient.getInstance().setCrashReport(CrashReport.create(throwable, "Building WorldMesher mesh"));
            }
            return true;
        });

    }

    private void draw(RenderLayer renderLayer, VertexBuffer vertexBuffer, Matrix4f matrix) {
        vertexBuffer.bind();
        renderLayer.getVertexFormat().startDrawing(0);
        renderLayer.startDrawing();

        renderStartAction.run();

        vertexBuffer.draw(matrix, 7);

        renderEndAction.run();

        renderLayer.endDrawing();
        renderLayer.getVertexFormat().endDrawing();
        VertexBuffer.unbind();
    }

    public static class Builder {

        private final World world;

        private final BlockPos origin;
        private final BlockPos end;
        private boolean bakeBlockEntities = false;

        private boolean bakeEntities = false;
        private Runnable startAction = () -> {};

        private Runnable endAction = () -> {};

        public Builder(World world, BlockPos origin, BlockPos end) {
            this.world = world;
            this.origin = origin;
            this.end = end;
        }

        public Builder bakeBlockEntities() {
            this.bakeBlockEntities = true;
            return this;
        }

        public Builder bakeEntities() {
            this.bakeEntities = true;
            return this;
        }

        public Builder renderActions(Runnable startAction, Runnable endAction) {
            this.startAction = startAction;
            this.endAction = endAction;
            return this;
        }

        public WorldMesh build() {

            BlockPos start = new BlockPos(Math.min(origin.getX(), end.getX()), Math.min(origin.getY(), end.getY()), Math.min(origin.getZ(), end.getZ()));
            BlockPos target = new BlockPos(Math.max(origin.getX(), end.getX()), Math.max(origin.getY(), end.getY()), Math.max(origin.getZ(), end.getZ()));

            return new WorldMesh(world, start, target, bakeBlockEntities, bakeEntities, startAction, endAction);
        }
    }

    public enum MeshState {
        NEW(false, false),
        BUILDING(true, false),
        REBUILDING(true, true),
        READY(false, true);

        public final boolean isBuildStage;
        public final boolean canRender;

        MeshState(boolean buildStage, boolean canRender) {
            this.isBuildStage = buildStage;
            this.canRender = canRender;
        }
    }

}














