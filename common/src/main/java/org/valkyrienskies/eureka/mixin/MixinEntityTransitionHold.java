package org.valkyrienskies.eureka.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.valkyrienskies.eureka.ship.TransitionHold;

/**
 * Stops anything in a hull's footprint falling through the deck while the hull swaps between world and
 * shipyard (see {@link TransitionHold}). Only the downward part of the movement is clamped, so walking and the
 * camera stay free.
 */
@Mixin(Entity.class)
public abstract class MixinEntityTransitionHold {

    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 vs_eureka$holdThroughShipTransition(final Vec3 movement) {
        if (movement.y < 0.0 && TransitionHold.shouldHoldGravity((Entity) (Object) this)) {
            final Entity self = (Entity) (Object) this;
            final Vec3 dm = self.getDeltaMovement();
            self.setDeltaMovement(dm.x, 0.0, dm.z);
            return new Vec3(movement.x, 0.0, movement.z);
        }
        return movement;
    }
}
