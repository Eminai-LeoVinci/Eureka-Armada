package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;
import traben.entity_model_features.models.animation.EMFAnimationEntityContext;
import traben.entity_model_features.models.animation.state.EMFEntityRenderState;

/**
 * Entity Model Features (EMF) compat: the helmsman STANDS at the wheel under Fresh Animations.
 *
 * <p>Fresh Animations' player rig sits whenever the CEM variable {@code is_riding} is true, and EMF answers that
 * from the live entity's vehicle, so a helm rider sat even though Armada poses the vanilla model standing (EMF draws
 * its own geometry and never reads that pose). For a rider of a helm seat (a VS2 ShipMountingEntity; deck seats are
 * left sitting):
 * <ul>
 *   <li>{@code isRiding() = false}, so the rig stands;</li>
 *   <li>{@code isOnGround() = true}, because once riding is off FA watches for falls, and a rider carried by a moving
 *   ship keeps changing height: without this it replays its landing squat whenever the ship climbs or dives.</li>
 * </ul>
 *
 * <p>Ported from the 1.21.11 build's MixinEMFStandAtHelm; 1.20.1 has no render states, so the rider is found from
 * the entity EMF is animating. @Pseudo + string target: a no-op without EMF.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.EMFAnimationEntityContext", remap = false)
public abstract class MixinEMFStandAtHelm {

    @Unique
    private static boolean vs_eureka$isHelmRider() {
        final EMFEntityRenderState state = EMFAnimationEntityContext.getEmfState();
        if (state == null || !(state.emfEntity() instanceof final Entity entity)) {
            return false;
        }
        return entity.getVehicle() instanceof ShipMountingEntity;
    }

    @Inject(method = "isRiding", at = @At("HEAD"), cancellable = true)
    private static void vs_eureka$standAtHelm(final CallbackInfoReturnable<Boolean> cir) {
        if (vs_eureka$isHelmRider()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isOnGround", at = @At("HEAD"), cancellable = true)
    private static void vs_eureka$onGroundAtHelm(final CallbackInfoReturnable<Boolean> cir) {
        if (vs_eureka$isHelmRider()) {
            cir.setReturnValue(true);
        }
    }
}
