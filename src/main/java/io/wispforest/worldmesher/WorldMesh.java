package io.wispforest.worldmesher;

import com.google.common.collect.HashMultimap;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.wispforest.worldmesher.render.FluidVertexConsumer;
import io.wispforest.worldmesher.render.MeshRenderView;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.WorldMesherRenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.DynamicUniforms;
import net.minecraft.client.render.*;
import net.minecraft.client.render.chunk.BlockBufferAllocatorStorage;
import net.minecraft.client.render.chunk.Buffers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.World;
import org.apache.commons.lang3.function.TriFunction;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

public class WorldMesh {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldMesh.class);

    // Render setup data
    private final BlockRenderView world;
    private final BlockPos from, to;
    private final Box dimensions;

    private final boolean cull;

	// TODO: figure out a viable replacement for this
    private final Runnable renderStartAction;
    private final Runnable renderEndAction;

    private final TriFunction<PlayerEntity, BlockPos, BlockPos, List<Entity>> entitySupplier;
    private DynamicRenderInfo renderInfo = DynamicRenderInfo.EMPTY;
    private boolean entitiesFrozen;
    private boolean freezeEntities;

    // Build process data
    private MeshState state = MeshState.NEW;

    private float buildProgress = 0;
    private @Nullable CompletableFuture<Void> buildFuture = null;

    // Vertex storage
    private final Map<BlockRenderLayer, Buffers> bufferStorage = new HashMap<>();

    private WorldMesh(BlockRenderView world, BlockPos from, BlockPos to, boolean cull, boolean useGlobalNeighbors, boolean freezeEntities, Runnable renderStartAction, Runnable renderEndAction, TriFunction<PlayerEntity, BlockPos, BlockPos, List<Entity>> entitySupplier) {
        this.from = from;
        this.to = to;

        this.world = useGlobalNeighbors
                ? world
                : new MeshRenderView(world, from, to);

        this.cull = cull;
        this.freezeEntities = freezeEntities;
        this.dimensions = Box.enclosing(this.from, this.to);
        this.entitySupplier = entitySupplier;

        this.renderStartAction = renderStartAction;
        this.renderEndAction = renderEndAction;

        this.scheduleRebuild();
    }

    /**
     * Renders this world mesh into the current framebuffer, translated using the given matrix
     *
     * @param matrices The translation matrices. This is applied to the entire mesh
     */
    public void render(MatrixStack matrices) {
        if (!this.canRender()) {
            throw new IllegalStateException("World mesh not prepared!");
        }

		var state = renderBlockLayers(bufferStorage, matrices.peek().getPositionMatrix());

	    MinecraftClient.getInstance().gameRenderer.getDiffuseLighting().setShaderLights(DiffuseLighting.Type.LEVEL);
		state.renderSection(BlockRenderLayerGroup.OPAQUE);
	    state.renderSection(BlockRenderLayerGroup.TRANSLUCENT);
	    state.renderSection(BlockRenderLayerGroup.TRIPWIRE);
    }

	private SectionRenderState renderBlockLayers(Map<BlockRenderLayer, Buffers> bufferStorage, Matrix4fc posMatrix) {
		EnumMap<BlockRenderLayer, List<RenderPass.RenderObject<GpuBufferSlice[]>>> enumMap = new EnumMap<>(BlockRenderLayer.class);
		int maxIndicesRequired = 0;

		for (BlockRenderLayer layer : BlockRenderLayer.values()) {
			enumMap.put(layer, new ArrayList<>());
		}

		List<DynamicUniforms.UniformValue> list = new ArrayList<>();
		Vector4f colorModulator = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
		Matrix4f textureMatrix = new Matrix4f();

		for (BlockRenderLayer layer : BlockRenderLayer.values()) {
			var buffers = bufferStorage.get(layer);
			if (buffers != null) {
				GpuBuffer indexBuffer = null;
				VertexFormat.IndexType indexType = null;
				if (buffers.getIndexBuffer() == null) {
					if (buffers.getIndexCount() > maxIndicesRequired) maxIndicesRequired = buffers.getIndexCount();
				} else {
					indexBuffer = buffers.getIndexBuffer();
					indexType = buffers.getIndexType();
				}

				int transformIdx = list.size();
				list.add(new DynamicUniforms.UniformValue(posMatrix, colorModulator, new Vector3f(0, 0, 0 /* TODO perhaps change this? */), textureMatrix, 1.0F));

				enumMap.put(layer, List.of(new RenderPass.RenderObject<>(0, buffers.getVertexBuffer(), indexBuffer, indexType, 0, buffers.getIndexCount(),
						(transforms, uniformUploader) -> uniformUploader.upload("DynamicTransforms", transforms[transformIdx]))));
			} else enumMap.put(layer, List.of());
		}

		GpuBufferSlice[] gpuBufferSlices = RenderSystem.getDynamicUniforms()
				.writeAll(list.toArray(new DynamicUniforms.UniformValue[0]));
		return new SectionRenderState(enumMap, maxIndicesRequired, gpuBufferSlices);
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
    public MeshState state() {
        return this.state;
    }

    /**
     * Renamed to {@link #state()}
     */
    @Deprecated(forRemoval = true)
    public MeshState getState() {
        return this.state();
    }

    /**
     * How much of this mesh is built
     *
     * @return The build progress of this mesh
     */
    public float buildProgress() {
        return this.buildProgress;
    }

    /**
     * Renamed to {@link #buildProgress()}
     */
    @Deprecated(forRemoval = true)
    public float getBuildProgress() {
        return this.buildProgress();
    }

    /**
     * @return An object describing the entities and block
     * entities in the area this mesh is covering, with positions
     * relative to the mesh
     */
    public DynamicRenderInfo renderInfo() {
        return this.renderInfo;
    }

    /**
     * Renamed to {@link #renderInfo()}
     */
    @Deprecated(forRemoval = true)
    public DynamicRenderInfo getRenderInfo() {
        return this.renderInfo();
    }

    /**
     * @return The origin position of this mesh's area
     */
    public BlockPos startPos() {
        return this.from;
    }

    /**
     * @return The end position of this mesh's area
     */
    public BlockPos endPos() {
        return this.to;
    }

    public boolean entitiesFrozen() {
        return this.entitiesFrozen;
    }

    public void setFreezeEntities(boolean freezeEntities) {
        this.freezeEntities = freezeEntities;
    }

    /**
     * @return The dimensions of this mesh's entire area
     */
    public Box dimensions() {
        return dimensions;
    }

    /**
     * Reset this mesh to {@link MeshState#NEW}, releasing
     * all vertex buffers in the process
     */
    public void reset() {
        this.bufferStorage.forEach((renderLayer, buffers) -> buffers.close());
        this.bufferStorage.clear();

        this.state = MeshState.NEW;
    }

    /**
     * Renamed to {@link #reset()}
     */
    @Deprecated(forRemoval = true)
    public void clear() {
        this.reset();
    }

    /**
     * Schedule a rebuild of this mesh on
     * the main worker executor
     */
    public synchronized void scheduleRebuild() {
        this.scheduleRebuild(MinecraftClient.getInstance());
    }

    /**
     * Schedule a rebuild of this mesh,
     * on the supplied executor
     *
     * @return A future completing when the build process is finished,
     * or {@code null} if this mesh is already building
     */
    public synchronized CompletableFuture<Void> scheduleRebuild(Executor executor) {
        if (this.buildFuture != null) return this.buildFuture;

        this.buildProgress = 0;
        this.state = this.state != MeshState.NEW
                ? MeshState.REBUILDING
                : MeshState.BUILDING;

        this.buildFuture = CompletableFuture.runAsync(this::build, executor).whenComplete((unused, throwable) -> {
            this.buildFuture = null;

            if (throwable == null) {
                state = MeshState.READY;
            } else {
                LOGGER.warn("World mesh building failed", throwable);
                state = MeshState.CORRUPT;
            }
        });

        return this.buildFuture;
    }

    private void build() {
		RenderSystem.assertOnRenderThread(); // Everything in here must be run in the render thread, so enforce that.
        var allocatorStorage = new BlockBufferAllocatorStorage();

        var client = MinecraftClient.getInstance();
        var blockRenderManager = client.getBlockRenderManager();

        var matrices = new MatrixStack();
        var builderStorage = new HashMap<BlockRenderLayer, BufferBuilder>();

        WorldMesherRenderContext renderContext = null;
        try {
            //noinspection UnstableApiUsage
            renderContext = Renderer.get() instanceof IndigoRenderer
                    ? new WorldMesherRenderContext(this.world, layer -> this.getOrCreateBuilder(allocatorStorage, builderStorage, layer))
                    : null;
        } catch (Throwable throwable) {
            var fabricApiVersion = FabricLoader.getInstance().getModContainer("worldmesher").get().getMetadata().getCustomValue("worldmesher:fabric_api_build_version").getAsString();
            LOGGER.error(
                    "Could not create a context for rendering Fabric API models. This is most likely due to an incompatible Fabric API version - this build of WorldMesher was compiled against '{}', try that instead",
                    fabricApiVersion,
                    throwable
            );
        }

        this.entitiesFrozen = this.freezeEntities;
        var entitiesList = this.entitySupplier.apply(client.player, this.from, this.to.add(1, 1, 1))
                .stream()
                .map(entity -> {
                    if (this.freezeEntities) {
                        var originalEntity = entity;
                        entity = entity.getType().create(client.world, SpawnReason.LOAD);

                        entity.copyFrom(originalEntity);
                        entity.copyPositionAndRotation(originalEntity);
                        entity.tick();
                    }

                    return new DynamicRenderInfo.EntityEntry(
                            entity,
                            client.getEntityRenderDispatcher().getLight(entity, 0)
                    );
                }).toList();

        var blockEntities = new HashMap<BlockPos, BlockEntity>();

        int currentBlockIndex = 0;
        int blocksToBuild = (this.to.getX() - this.from.getX() + 1)
                * (this.to.getY() - this.from.getY() + 1)
                * (this.to.getZ() - this.from.getZ() + 1);

        for (var pos : BlockPos.iterate(this.from, this.to)) {
            currentBlockIndex++;
            this.buildProgress = currentBlockIndex / (float) blocksToBuild;

            var state = world.getBlockState(pos);
            if (state.isAir()) continue;

            var renderPos = pos.subtract(from);
            if (world.getBlockEntity(pos) != null) {
                blockEntities.put(renderPos, world.getBlockEntity(pos));
            }

            if (!world.getFluidState(pos).isEmpty()) {
                var fluidState = world.getFluidState(pos);
                var fluidLayer = RenderLayers.getFluidLayer(fluidState);

                matrices.push();
                matrices.translate(-(pos.getX() & 15), -(pos.getY() & 15), -(pos.getZ() & 15));
                matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());

                blockRenderManager.renderFluid(pos, world, new FluidVertexConsumer(this.getOrCreateBuilder(allocatorStorage, builderStorage, fluidLayer), matrices.peek().getPositionMatrix()), state, fluidState);

                matrices.pop();
            }

            matrices.push();
            matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());

            final var model = blockRenderManager.getModel(state);
            if (renderContext != null) {
                renderContext.tessellateBlock(state, pos, model, matrices);
            } else {
	            blockRenderManager.getModelRenderer().render(this.world, model, state, pos, matrices, blockLayer -> this.getOrCreateBuilder(allocatorStorage, builderStorage, blockLayer), cull, state.getRenderingSeed(pos), OverlayTexture.DEFAULT_UV);
            }

            matrices.pop();
        }

        this.bufferStorage.forEach((renderLayer, buffers) -> buffers.close());
        this.bufferStorage.clear();

        builderStorage.forEach((renderLayer, bufferBuilder) -> {
            var built = bufferBuilder.endNullable();
            if (built == null) return;

            if (renderLayer.isTranslucent()) {
                var camera = client.gameRenderer.getCamera();
                built.sortQuads(allocatorStorage.get(renderLayer), VertexSorter.byDistance((float) camera.getPos().x - (float) from.getX(), (float) camera.getPos().y - (float) from.getY(), (float) camera.getPos().z - (float) from.getZ()));
            }

	        GpuBuffer vertexBuffer = RenderSystem.getDevice()
		            .createBuffer(
				            () -> "WorldMesher vertex buffer",
				            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
				            built.getBuffer()
		            );

	        ByteBuffer indices = built.getSortedBuffer();
	        GpuBuffer indexBuffer = indices != null
		            ? RenderSystem.getDevice()
		            .createBuffer(
				            () -> "WorldMesher index buffer",
				            GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
				            indices
		            )
		            : null;

            var discardedBuffer = this.bufferStorage.put(renderLayer, new Buffers(vertexBuffer, indexBuffer, built.getDrawParameters().indexCount(), built.getDrawParameters().indexType()));
            if (discardedBuffer != null) {
                discardedBuffer.close();
            }
        });

        var entities = HashMultimap.<Vec3d, DynamicRenderInfo.EntityEntry>create();
        for (var entityEntry : entitiesList) {
            entities.put(
                    entityEntry.entity().getSyncedPos().subtract(this.from.getX(), this.from.getY(), this.from.getZ()),
                    entityEntry
            );
        }

        allocatorStorage.close();
        this.renderInfo = new DynamicRenderInfo(
                blockEntities, entities
        );
    }

    private VertexConsumer getOrCreateBuilder(BlockBufferAllocatorStorage allocatorStorage, Map<BlockRenderLayer, BufferBuilder> builderStorage, BlockRenderLayer layer) {
        return builderStorage.computeIfAbsent(layer, renderLayer ->
		        new BufferBuilder(allocatorStorage.get(layer), VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL));
    }

    public static class Builder {

        private final BlockRenderView world;
        private final TriFunction<PlayerEntity, BlockPos, BlockPos, List<Entity>> entitySupplier;

        private final BlockPos origin;
        private final BlockPos end;
        private boolean cull = true;
        private boolean useGlobalNeighbors = false;
        private boolean freezeEntities = false;

        private Runnable startAction = () -> {
        };
        private Runnable endAction = () -> {
        };

        @Deprecated(forRemoval = true)
        public Builder(BlockRenderView world, BlockPos origin, BlockPos end, Function<PlayerEntity, List<Entity>> entitySupplier) {
            this.world = world;
            this.origin = origin;
            this.end = end;
            this.entitySupplier = (player, $, $$) -> entitySupplier.apply(player);
        }

        public Builder(BlockRenderView world, BlockPos origin, BlockPos end, TriFunction<PlayerEntity, BlockPos, BlockPos, List<Entity>> entitySupplier) {
            this.world = world;
            this.origin = origin;
            this.end = end;
            this.entitySupplier = entitySupplier;
        }

        public Builder(World world, BlockPos origin, BlockPos end) {
            this(world, origin, end, (except, min, max) -> world.getOtherEntities(except, Box.enclosing(min, max), entity -> !(entity instanceof PlayerEntity)));
        }

        public Builder(BlockRenderView world, BlockPos origin, BlockPos end) {
            this(world, origin, end, (except, min, max) -> List.of());
        }

        public Builder disableCulling() {
            this.cull = false;
            return this;
        }

        public Builder useGlobalNeighbors() {
            this.useGlobalNeighbors = true;
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

            return new WorldMesh(world, start, target, cull, useGlobalNeighbors, freezeEntities, startAction, endAction, entitySupplier);
        }
    }

    public enum MeshState {
        NEW(false, false),
        BUILDING(true, false),
        REBUILDING(true, true),
        READY(false, true),
        CORRUPT(false, false);

        public final boolean isBuildStage;
        public final boolean canRender;

        MeshState(boolean buildStage, boolean canRender) {
            this.isBuildStage = buildStage;
            this.canRender = canRender;
        }
    }

}
