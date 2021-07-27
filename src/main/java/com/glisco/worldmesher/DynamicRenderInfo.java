package com.glisco.worldmesher;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;

public class DynamicRenderInfo {

    protected HashMap<BlockPos, BlockEntity> blockEntities;
    protected HashMap<Vec3d, Entity> entities;

    public DynamicRenderInfo() {
        this.blockEntities = new HashMap<>();
        this.entities = new HashMap<>();
    }

    public DynamicRenderInfo(HashMap<BlockPos, BlockEntity> blockEntities, HashMap<Vec3d, Entity> entities) {
        this.blockEntities = new HashMap<>(blockEntities);
        this.entities = new HashMap<>(entities);
    }

    public HashMap<BlockPos, BlockEntity> getBlockEntities() {
        return blockEntities;
    }

    public HashMap<Vec3d, Entity> getEntities() {
        return entities;
    }

    public boolean isEmpty() {
        return blockEntities.isEmpty() && entities.isEmpty();
    }

    public Mutable toMutable() {
        return new Mutable(this);
    }

    public DynamicRenderInfo toImmutable() {
        return new DynamicRenderInfo(blockEntities, entities);
    }

    public static class Mutable extends DynamicRenderInfo {

        public Mutable() {
            super();
        }

        public Mutable(HashMap<BlockPos, BlockEntity> blockEntities, HashMap<Vec3d, Entity> entities) {
            super(blockEntities, entities);
        }

        private Mutable(DynamicRenderInfo parent) {
            super(parent.blockEntities, parent.entities);
        }

        public void clear() {
            blockEntities.clear();
            entities.clear();
        }

        public void addBlockEntity(BlockPos pos, BlockEntity entity) {
            this.blockEntities.put(pos, entity);
        }

        public void addEntity(Vec3d pos, Entity entity) {
            this.entities.put(pos, entity);
        }

    }

}
