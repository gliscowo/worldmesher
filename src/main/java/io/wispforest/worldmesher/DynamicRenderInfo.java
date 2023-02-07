package io.wispforest.worldmesher;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;

public class DynamicRenderInfo {

    protected HashMap<BlockPos, BlockEntity> blockEntities;
    protected HashMap<Vec3d, EntityEntry> entities;

    public DynamicRenderInfo() {
        this.blockEntities = new HashMap<>();
        this.entities = new HashMap<>();
    }

    public DynamicRenderInfo(HashMap<BlockPos, BlockEntity> blockEntities, HashMap<Vec3d, EntityEntry> entities) {
        this.blockEntities = new HashMap<>(blockEntities);
        this.entities = new HashMap<>(entities);
    }

    public HashMap<BlockPos, BlockEntity> getBlockEntities() {
        return blockEntities;
    }

    public HashMap<Vec3d, EntityEntry> getEntities() {
        return entities;
    }

    public boolean isEmpty() {
        return blockEntities.isEmpty() && entities.isEmpty();
    }

    public record EntityEntry(Entity entity, int light) {}

}
