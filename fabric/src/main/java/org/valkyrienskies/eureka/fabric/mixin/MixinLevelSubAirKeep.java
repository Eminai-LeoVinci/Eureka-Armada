package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.valkyrienskies.eureka.EurekaBlocks;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Air next to sub air is sub air.
 *
 * <p>A hull's dry interior is filled once, at assembly. Break a block inside it afterwards and vanilla puts
 * plain air where it stood -- one wet cell in a dry room, in which a player swims and drowns. So every air
 * state written into the shipyard in place of a real block is checked against its six neighbours, and if any
 * of them is sub air the new cell becomes sub air too. Air replacing air (the fill and the clear themselves)
 * passes through untouched, and nothing outside the shipyard is ever looked at.
 */
@Mixin(Level.class)
public abstract class MixinLevelSubAirKeep {

    @ModifyVariable(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"),
        argsOnly = true
    )
    private BlockState vs_eureka$keepSubAir(final BlockState state, final BlockPos pos, final BlockState ignored,
        final int flags, final int recursionLeft) {
        if (!state.isAir()) {
            return state;
        }
        final Level self = (Level) (Object) this;
        if (self.isClientSide()) {
            return state; // the server decides; the client only ever hears the answer
        }
        final Block subAir = EurekaBlocks.INSTANCE.getSUB_AIR().get();
        if (state.getBlock() == subAir) {
            return state;
        }
        if (!VSGameUtilsKt.isBlockInShipyard(self, pos)) {
            return state;
        }
        if (self.getBlockState(pos).isAir()) {
            return state; // air replacing air: a fill, a clear, or nothing
        }
        for (final Direction d : Direction.values()) {
            if (self.getBlockState(pos.relative(d)).getBlock() == subAir) {
                return subAir.defaultBlockState();
            }
        }
        return state;
    }
}
