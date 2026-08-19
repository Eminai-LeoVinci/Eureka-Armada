package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.ship.ShipIntegrity;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Keeps every ship's live block count honest, one block change at a time.
 *
 * Same choke point VS2 uses to keep ship MASS honest (its own {@code MixinLevelChunk}): every path that
 * changes a block -- a cannonball's punch, fire eating a deck, a shipwright's repair, a player's hands --
 * lands in {@code LevelChunk.setBlockState} eventually, so a tail hook here sees all of it and nothing has
 * to poll. {@link ShipIntegrity#onSetBlock} does the actual filtering (server side, on a ship, air-to-solid
 * transitions only) and it is cheap enough to run on every world block change, which is what this is.
 *
 * The {@code executeOrSchedule} guard is copied from VS2's hook for the same documented reason: this method
 * is sometimes invoked off the game thread, and the count must only ever move on it.
 */
@Mixin(LevelChunk.class)
public abstract class MixinShipBlockCensus {

    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("TAIL"))
    private void vs_eureka$countShipBlocks(final BlockPos pos, final BlockState state, final int flags,
        final CallbackInfoReturnable<BlockState> cir) {
        final BlockState prevState = cir.getReturnValue();
        // Null means the write was a no-op (same state as before); nothing was gained or lost.
        if (prevState == null) {
            return;
        }
        VSGameUtilsKt.executeOrSchedule(level, () -> ShipIntegrity.INSTANCE.onSetBlock(level, pos.immutable(), prevState, state));
    }
}
