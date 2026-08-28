package org.valkyrienskies.eureka.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;

/**
 * Weather does not snow on the shipyard.
 *
 * <p>A ship's blocks live in shipyard chunks, and those chunks block-tick like any other -- so every
 * snowfall quietly stacked layers onto every assembled deck in the dimension, which the player then met
 * as a white ship after any long flight through a storm (and, before the helm probe learned better, as a
 * sunken helmsman). Snow belongs to the world; a ship only carries what her captain put aboard.
 *
 * <p>Ice is deliberately left alone: shouldFreeze is gated on water the ship actually carries, which is
 * cargo like anything else.
 */
@Mixin(ServerLevel.class)
public abstract class MixinNoSnowOnShips {

    @Redirect(
        method = "tickChunk",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;shouldSnow(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"
        )
    )
    private boolean vs_eureka$noSnowOnShips(final Biome biome, final LevelReader reader, final BlockPos pos) {
        if (VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        return biome.shouldSnow(reader, pos);
    }
}
