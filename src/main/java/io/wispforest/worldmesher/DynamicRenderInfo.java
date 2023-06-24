package io.wispforest.worldmesher;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.function.BiConsumer;

public class DynamicRenderInfo {
    public static DynamicRenderInfo EMPTY = new DynamicRenderInfo(ImmutableMap.of(), List.of());

    protected Map<BlockPos, BlockEntity> blockEntities;
    @Deprecated(forRemoval = true)
    protected Map<Vec3d, EntityEntry> entities;
    protected Set<EntityEntry> entityEntries;

    @Deprecated(forRemoval = true)
    public DynamicRenderInfo(Map<BlockPos, BlockEntity> blockEntities, Map<Vec3d, EntityEntry> entities) {
        this.blockEntities = ImmutableMap.copyOf(blockEntities);
        this.entities = ImmutableMap.copyOf(entities);
        var builder = ImmutableSet.<EntityEntry>builder();
        entities.forEach((a, b) -> builder.add(new EntityEntry(b.entity, b.light, a)));
        this.entityEntries = builder.build();
    }

    public DynamicRenderInfo(Map<BlockPos, BlockEntity> blockEntities, Collection<EntityEntry> entities) {
        this.blockEntities = ImmutableMap.copyOf(blockEntities);
        this.entityEntries = ImmutableSet.copyOf(entities);
        var map = new HashMap<Vec3d, EntityEntry>() {
            @Override
            public void forEach(BiConsumer<? super Vec3d, ? super EntityEntry> action) {
                for (var entry : DynamicRenderInfo.this.entityEntries) {
                    action.accept(entry.pos, entry);
                }
            }
        };
        entities.forEach((a) -> map.put(a.pos, a));
        this.entities = Collections.unmodifiableMap(map);
    }

    /**
     * Use {@link #blockEntities()} instead
     */
    @Deprecated(forRemoval = true)
    public HashMap<BlockPos, BlockEntity> getBlockEntities() {
        return new HashMap<>(this.blockEntities);
    }

    /**
     * Use {@link #entities()} instead
     */
    @Deprecated(forRemoval = true)
    public HashMap<Vec3d, EntityEntry> getEntities() {
        return new HashMap<>(this.entities);
    }

    public Map<BlockPos, BlockEntity> blockEntities() {
        return this.blockEntities;
    }

    @Deprecated(forRemoval = true)
    public Map<Vec3d, EntityEntry> entities() {
        return this.entities;
    }

    public Set<EntityEntry> entityEntries() {
        return this.entityEntries;
    }

    public boolean isEmpty() {
        return this.blockEntities.isEmpty() && this.entityEntries.isEmpty();
    }

    public record EntityEntry(Entity entity, int light, Vec3d pos) {
        @Deprecated(forRemoval = true)
        public EntityEntry(Entity entity, int light) {
            this(entity, light, entity.getPos());
        }
    }

}
