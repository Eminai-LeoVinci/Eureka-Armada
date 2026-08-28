package org.valkyrienskies.eureka.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.pirate.PirateHelm;

/**
 * Explosions cannot pop a PIRATE wheel -- a creeper on deck or a lucky TNT raft must not hand out the
 * conquest for free.
 *
 * <p>1.21.1 guards this in the block's own {@code onExplosionHit}; 1.20.1 Fabric has no per-state hook on
 * the block, so the verdict is given where the explosion asks it: {@code shouldBlockExplode}, answered
 * {@code false} for an inviolable wheel. Ray physics are untouched -- only the wheel itself is spared,
 * exactly what the 1.21.1 hook did. Cannon fire is guarded separately in CannonDamage (it removes blocks
 * directly, never through the explosion path), and deck fires in MixinShipFireContainment.
 */
@Mixin(ExplosionDamageCalculator.class)
public abstract class MixinExplosionPirateHelm {

    @Inject(method = "shouldBlockExplode", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$sparePirateHelm(final Explosion explosion, final BlockGetter blockGetter,
        final BlockPos pos, final BlockState state, final float power,
        final CallbackInfoReturnable<Boolean> cir) {
        if (PirateHelm.inviolable(state)) {
            cir.setReturnValue(false);
        }
    }
}
