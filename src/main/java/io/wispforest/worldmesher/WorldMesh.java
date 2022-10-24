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
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

public class WorldMesh {

    private final BlockRenderView world;
    private final BlockPos origin;
    private final BlockPos end;
    private final boolean cull;
    private final Box dimensions;

    private MeshState state = MeshState.NEW;
    private float buildProgress = 0;

    private final Map<RenderLayer, VertexBuffer> bufferStorage;
    private final Map<RenderLayer, BufferBuilder> initializedLayers;

    private DynamicRenderInfo renderInfo;
    private boolean entitiesFrozen;
    private boolean freezeEntities;
    private final Function<PlayerEntity, List<Entity>> entitySupplier;

    private final Runnable renderStartAction;
    private final Runnable renderEndAction;

    private Supplier<MatrixStack> matrixStackSupplier = MatrixStack::new;

    private WorldMesh(BlockRenderView world, BlockPos origin, BlockPos end, boolean cull, boolean freezeEntities, Runnable renderStartAction, Runnable renderEndAction, Function<PlayerEntity, List<Entity>> entitySupplier) {
        this.world = world;
        this.origin = origin;
        this.end = end;

        this.cull = cull;
        this.freezeEntities = freezeEntities;
        this.dimensions = new Box(this.origin, this.end);
        this.entitySupplier = entitySupplier;

        this.renderStartAction = renderStartAction;
        this.renderEndAction = renderEndAction;

        this.bufferStorage = new HashMap<>();
        this.initializedLayers = new HashMap<>();
        this.renderInfo = new DynamicRenderInfo();

        this.scheduleRebuild();
    }

    /**
     * Renders this world mesh into the current framebuffer, translated using the given matrix
     *
     * @param matrices The translation matrices. This is applied to the entire mesh
     */
    public void render(MatrixStack matrices) {

        final var matrix = matrices.peek().getPositionMatrix();

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

        VertexBuffer.unbind();
    }

    public void setMatrixStackSupplier(Supplier<MatrixStack> stackSupplier) {
        this.matrixStackSupplier = stackSupplier;
    }

    /**
     * Checks whether this mesh is ready for rendering
     */
    public boolean canRender() {
        return this.state.canRender;
    }

    /**
     * Returns the current state of this mesh, used to indicate building progress and rendering availability
     *
     * @return The current {@code MeshState} constant
     */
    public MeshState getState() {
        return this.state;
    }

    /**
     * How much of this mesh is built
     *
     * @return The build progress of this mesh
     */
    public float getBuildProgress() {
        return this.buildProgress;
    }

    public DynamicRenderInfo getRenderInfo() {
        return this.renderInfo;
    }

    public BlockPos startPos() {
        return this.origin;
    }

    public BlockPos endPos() {
        return this.end;
    }

    public boolean entitiesFrozen() {
        return this.entitiesFrozen;
    }

    public void setFreezeEntities(boolean freezeEntities) {
        this.freezeEntities = freezeEntities;
    }

    public Box dimensions() {
        return dimensions;
    }

    /**
     * Schedules a rebuild of this mesh
     */
    public void scheduleRebuild() {
        if (state.isBuildStage) return;
        state = state == MeshState.NEW ? MeshState.BUILDING : MeshState.REBUILDING;
        initializedLayers.clear();

        CompletableFuture.runAsync(this::build, Util.getMainWorkerExecutor()).whenComplete((unused, throwable) -> {
            if (throwable != null) {
                throwable.printStackTrace();
                state = MeshState.NEW;
            } else {
                state = MeshState.READY;
            }
        });
    }

    private void build() {
        final var client = MinecraftClient.getInstance();
        final var blockRenderManager = client.getBlockRenderManager();
        final var blockRenderer = new WorldMesherBlockModelRenderer();
        final var fluidRenderer = new WorldMesherFluidRenderer();
        MatrixStack matrices = matrixStackSupplier.get();

        var renderContext = RendererAccess.INSTANCE.getRenderer() instanceof IndigoRenderer ?
                new WorldMesherRenderContext(this.world, this::getOrCreateBuffer) : null;

        Random random = Random.createLocal();

        List<BlockPos> possess = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(this.origin, this.end)) {
            possess.add(pos.toImmutable());
        }

        int blocksToBuild = possess.size();
        final DynamicRenderInfo.Mutable tempRenderInfo = new DynamicRenderInfo.Mutable();

        this.entitiesFrozen = this.freezeEntities;
        final var entitiesFuture = new CompletableFuture<List<DynamicRenderInfo.EntityEntry>>();
        client.execute(() -> {
            entitiesFuture.complete(
                    this.entitySupplier.apply(client.player)
                            .stream()
                            .map(entity -> {
                                if (freezeEntities) {
                                    var originalEntity = entity;
                                    entity = entity.getType().create(client.world);

                                    entity.copyFrom(originalEntity);
                                    entity.copyPositionAndRotation(originalEntity);
                                    entity.tick();
                                }

                                return new DynamicRenderInfo.EntityEntry(
                                        entity,
                                        client.getEntityRenderDispatcher().getLight(entity, 0)
                                );
                            }).toList()
            );
        });

        for (int i = 0; i < blocksToBuild; i++) {
            BlockPos pos = possess.get(i);
            BlockPos renderPos = pos.subtract(origin);

            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;

            if (world.getBlockEntity(pos) != null) tempRenderInfo.addBlockEntity(renderPos, world.getBlockEntity(pos));

            if (!world.getFluidState(pos).isEmpty()) {

                FluidState fluidState = world.getFluidState(pos);
                RenderLayer fluidLayer = RenderLayers.getFluidLayer(fluidState);

                matrices.push();
                matrices.translate(-(pos.getX() & 15), -(pos.getY() & 15), -(pos.getZ() & 15));
                matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());

                fluidRenderer.setMatrix(matrices.peek().getPositionMatrix());
                fluidRenderer.render(world, pos, getOrCreateBuffer(fluidLayer), state, fluidState);

                matrices.pop();
            }

            matrices.push();
            matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());

            blockRenderer.clearCullingOverrides();
            blockRenderer.setCullDirection(Direction.EAST, pos.getX() == this.end.getX());
            blockRenderer.setCullDirection(Direction.WEST, pos.getX() == this.origin.getX());
            blockRenderer.setCullDirection(Direction.SOUTH, pos.getZ() == this.end.getZ());
            blockRenderer.setCullDirection(Direction.NORTH, pos.getZ() == this.origin.getZ());
            blockRenderer.setCullDirection(Direction.UP, pos.getY() == this.end.getY());
            blockRenderer.setCullDirection(Direction.DOWN, pos.getY() == this.origin.getY());

            RenderLayer renderLayer = RenderLayers.getBlockLayer(state);

            final var model = blockRenderManager.getModel(state);
            if (!((FabricBakedModel) model).isVanillaAdapter() && renderContext != null) {
                renderContext.tessellateBlock(this.world, state, pos, model, matrices);
            } else if(state.getRenderType() == BlockRenderType.MODEL) {
                blockRenderer.render(this.world, model, state, pos, matrices, getOrCreateBuffer(renderLayer), cull, random, state.getRenderingSeed(pos), OverlayTexture.DEFAULT_UV);
            }

            matrices.pop();

            this.buildProgress = i / (float) blocksToBuild;
        }

        if (initializedLayers.containsKey(RenderLayer.getTranslucent())) {
            var translucentBuilder = initializedLayers.get(RenderLayer.getTranslucent());
            var camera = client.gameRenderer.getCamera();
            translucentBuilder.sortFrom((float) camera.getPos().x - (float) origin.getX(), (float) camera.getPos().y - (float) origin.getY(), (float) camera.getPos().z - (float) origin.getZ());
        }

        var future = new CompletableFuture<Void>();
        RenderSystem.recordRenderCall(() -> {
            initializedLayers.forEach((renderLayer, bufferBuilder) -> {
                final var vertexBuffer = new VertexBuffer();

                vertexBuffer.bind();
                vertexBuffer.upload(bufferBuilder.end());

                bufferStorage.put(renderLayer, vertexBuffer);
            });

            future.complete(null);
        });
        future.join();

        entitiesFuture.join().forEach(entry -> tempRenderInfo.addEntity(entry.entity().getPos().subtract(origin.getX(), origin.getY(), origin.getZ()), entry));
        this.renderInfo = tempRenderInfo.toImmutable();
    }

    private VertexConsumer getOrCreateBuffer(RenderLayer layer) {
        if (!initializedLayers.containsKey(layer)) {
            BufferBuilder builder = new BufferBuilder(layer.getExpectedBufferSize());
            initializedLayers.put(layer, builder);
            builder.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
        }

        return initializedLayers.get(layer);
    }

    private void draw(RenderLayer renderLayer, VertexBuffer vertexBuffer, Matrix4f matrix) {
        renderLayer.startDrawing();
        renderStartAction.run();

        vertexBuffer.bind();
        vertexBuffer.draw(matrix, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());

        renderEndAction.run();
        renderLayer.endDrawing();
    }

    public static class Builder {

        private final BlockRenderView world;
        private final Function<PlayerEntity, List<Entity>> entitySupplier;

        private final BlockPos origin;
        private final BlockPos end;
        private boolean cull = true;
        private boolean freezeEntities = false;

        private Runnable startAction = () -> {};
        private Runnable endAction = () -> {};

        public Builder(BlockRenderView world, BlockPos origin, BlockPos end, Function<PlayerEntity, List<Entity>> entitySupplier) {
            this.world = world;
            this.origin = origin;
            this.end = end;
            this.entitySupplier = entitySupplier;
        }

        public Builder(World world, BlockPos origin, BlockPos end) {
            this(world, origin, end, (except) -> world.getOtherEntities(except, new Box(origin, end).expand(.5), entity -> !(entity instanceof PlayerEntity)));
        }

        public Builder(BlockRenderView world, BlockPos origin, BlockPos end) {
            this(world, origin, end, List::of);
        }

        public Builder disableCulling() {
            this.cull = false;
            return this;
        }

        public Builder freezeEntities() {
            this.freezeEntities = true;
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

            return new WorldMesh(world, start, target, cull, freezeEntities, startAction, endAction, entitySupplier);
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