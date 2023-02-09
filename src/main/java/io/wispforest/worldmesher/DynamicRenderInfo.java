package io.wispforest.worldmesher;

import com.google.common.collect.ImmutableMap;
import net.fabricmc.loader.impl.lib.sat4j.core.Vec;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

public class DynamicRenderInfo {

    public static DynamicRenderInfo EMPTY = new DynamicRenderInfo(ImmutableMap.of(), ImmutableMap.of());

    protected Map<BlockPos, BlockEntity> blockEntities;
    protected Map<Vec3d, EntityEntry> entities;

    public DynamicRenderInfo(Map<BlockPos, BlockEntity> blockEntities, Map<Vec3d, EntityEntry> entities) {
        this.blockEntities = ImmutableMap.copyOf(blockEntities);
        this.entities = ImmutableMap.copyOf(entities);
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

    public Map<Vec3d, EntityEntry> entities() {
        return this.entities;
    }

    public boolean isEmpty() {
        return this.blockEntities.isEmpty() && this.entities.isEmpty();
    }

    public record EntityEntry(Entity entity, int light) {}

}
