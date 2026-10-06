package org.valkyrienskies.eureka.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.util.ShipDropGuard;

/**
 * A block that breaks because its neighbour changed (a bed or door losing its other half) is cleared QUIETLY while it
 * is one of a ship's own blocks being moved or deleted by VS2: no drop, no break particles, no sound. Nothing is
 * really breaking (the ship has, or the bottle saved, the real block), so the normal break would only show a
 * puff of particles over every bed on assembly. Same end state as the vanilla break: the block becomes whatever
 * fluid was in it. See {@link ShipDropGuard}.
 */
@Mixin(Block.class)
public abstract class MixinBlockAssemblyNoDrops {

    @WrapOperation(
        method = "updateOrDestroy(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;II)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/LevelAccessor;destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z")
    )
    private static boolean vs_eureka$noDropsWhileAssembling(final LevelAccessor level, final BlockPos pos,
        final boolean drop, final Entity breaker, final int recursionLeft, final Operation<Boolean> original) {
        if (ShipDropGuard.suppresses(pos)) {
            if (level.getBlockState(pos).isAir()) {
                return false;
            }
            return level.setBlock(pos, level.getFluidState(pos).createLegacyBlock(), Block.UPDATE_ALL, recursionLeft);
        }
        return original.call(level, pos, drop, breaker, recursionLeft);
    }
}
