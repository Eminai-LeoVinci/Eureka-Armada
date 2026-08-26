package org.valkyrienskies.eureka.fabric.mixin.client;

import java.util.Map;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reach the map that decides which chunk layer a block is built into.
 *
 * A block is SOLID unless it is in {@code TYPE_BY_BLOCK}, and SOLID discards alpha -- so any model with a
 * see-through texture renders its transparent pixels as opaque. That is what flattened the stonecutter's
 * saw blade and the knives on the Shipwright's Bench into solid grey slabs.
 *
 * Fabric API used to expose this as {@code BlockRenderLayerMap}, and that module is **gone** from Fabric API
 * for 1.21.11 -- there is no blockrenderlayer jar in 0.141.6 at all. Vanilla did not take the job over
 * either: {@code ItemBlockRenderTypes.getChunkRenderType} still reads a private static map with no setter,
 * and no vanilla model or blockstate file carries a render type. An accessor is what is left, and it is
 * exactly what the removed module did.
 *
 * The map is a plain {@code Maps.newHashMap()} rather than an immutable copy, so putting into it is safe;
 * see {@code ItemBlockRenderTypes}' static initialiser. Registered from the client entrypoint, once, before
 * any chunk is built.
 */
@Mixin(ItemBlockRenderTypes.class)
public interface MixinItemBlockRenderTypes {

    @Accessor("TYPE_BY_BLOCK")
    static Map<Block, ChunkSectionLayer> vs$typeByBlock() {
        throw new AssertionError();
    }
}
