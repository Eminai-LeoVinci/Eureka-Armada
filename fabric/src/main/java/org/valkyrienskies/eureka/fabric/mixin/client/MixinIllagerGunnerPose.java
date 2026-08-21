package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.renderer.entity.IllagerRenderer;
import net.minecraft.client.renderer.entity.state.IllagerRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseRenderState;

/**
 * {@code MixinHumanoidGunnerPose}'s twin for the illager family. Pillagers and vindicators do not
 * render through {@code HumanoidMobRenderer} -- {@link IllagerRenderer} extracts its own state and
 * writes its own {@code isPassenger} -- so the pirate gun crews themselves need this second, otherwise
 * identical, hook. See the humanoid mixin for the full story.
 */
@Mixin(IllagerRenderer.class)
public abstract class MixinIllagerGunnerPose {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/monster/illager/AbstractIllager;"
            + "Lnet/minecraft/client/renderer/entity/state/IllagerRenderState;F)V",
        at = @At("TAIL")
    )
    private void vs_eureka$standTheGunner(final AbstractIllager illager, final IllagerRenderState state,
        final float partialTick, final CallbackInfo ci) {
        final Entity vehicle = illager.getVehicle();
        final boolean standing = vehicle instanceof ShipMountingEntity seat && !seat.isController();
        if (standing) {
            state.isPassenger = false;
        }
        ((ShipMountPoseRenderState) (Object) state).vs$setShipMountStanding(standing);
    }
}
