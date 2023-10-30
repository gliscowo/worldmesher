package io.wispforest.worldmesher.mixin;

import net.minecraft.client.render.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.ByteBuffer;

@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {
    @Accessor("buffer")
    ByteBuffer worldmesher$getBuffer();

    @Accessor("buffer")
    void worldmesher$setBuffer(ByteBuffer buffer);
}
