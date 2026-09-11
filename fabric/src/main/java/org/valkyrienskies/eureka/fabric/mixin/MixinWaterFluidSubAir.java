package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.armada.SubAir;

/**
 * No bubbles in a dry room. The little rising motes under water are {@code WaterFluid.animateTick} spawning
 * UNDERWATER particles around the player, at WORLD positions -- which inside a submerged hull are sub air.
 * VS2 has the same cancel for its sealed areas, gated on connectivity; this one reads the block.
 */
@Mixin(WaterFluid.class)
public abstract class MixinWaterFluidSubAir {

    @Inject(method = "animateTick", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$noBubblesInSubAir(final Level level, final BlockPos pos, final FluidState state,
        final RandomSource random, final CallbackInfo ci) {
        if (SubAir.INSTANCE.isShielded(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
            ci.cancel();
        }
    }
}
