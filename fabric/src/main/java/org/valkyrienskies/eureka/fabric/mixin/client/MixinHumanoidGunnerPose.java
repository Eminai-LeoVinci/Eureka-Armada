package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseRenderState;

/**
 * A mounted gun crew STANDS at its cannon, whatever creature it is.
 *
 * <p>{@code MixinVillagerGunnerPose} handles villagers, but a villager only ever LOOKED right by
 * accident of its model: the vanilla villager model has no seated pose. Every humanoid monster --
 * zombie, skeleton, piglin -- renders through {@link HumanoidRenderState}, whose {@code isPassenger}
 * is what {@code HumanoidModel.setupAnim} keys the leg-bend sit on, so an egg-mounted skeleton sat
 * cross-legged in the air over its gun. Same cure the helm rider uses ({@code MixinAvatarRenderer}):
 * kill the trigger at extract time -- {@code isPassenger = false} -- and raise the ship-mount standing
 * flag so EMF packs (Fresh Animations) get their {@code is_riding} override too.
 *
 * <p>Keyed on {@code !isController()}, exactly as the villager mixin is -- the controller flag is never
 * synced, so on the client it reads false for every seat, and any ShipMountingEntity a MOB rides is a
 * gunner seat by construction (nothing else mounts mobs). Only Mobs are touched here. The
 * TAIL of this class's extract is the earliest correct spot -- the base extract runs first and this
 * class is what writes {@code isPassenger}, so anything earlier gets overwritten.
 */
@Mixin(HumanoidMobRenderer.class)
public abstract class MixinHumanoidGunnerPose {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Mob;"
            + "Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;F)V",
        at = @At("TAIL")
    )
    private void vs_eureka$standTheGunner(final Mob mob, final HumanoidRenderState state,
        final float partialTick, final CallbackInfo ci) {
        final Entity vehicle = mob.getVehicle();
        final boolean standing = vehicle instanceof ShipMountingEntity seat && !seat.isController();
        if (standing) {
            state.isPassenger = false;
        }
        // Unconditional: render states are reused across entities, and nothing else writes this flag
        // for monsters -- a set-only write would leak one gunner's pose onto every zombie after him.
        ((ShipMountPoseRenderState) (Object) state).vs$setShipMountStanding(standing);
    }
}
