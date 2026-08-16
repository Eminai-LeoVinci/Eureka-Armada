package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseRenderState;

/**
 * A stationed gunner STANDS behind his cannon.
 *
 * <p>The vanilla villager model has no seated pose at all, so with vanilla rendering a gunner on his seat
 * already stands. Fresh Animations does have one: EMF gates it on the CEM variable {@code is_riding}, read
 * from the live entity's vehicle -- which a seated gunner has -- and sat the whole gun deck down on the
 * planks. VS2's helm rider solved this exact fight already ({@code MixinEMFStandAtHelm} forces
 * {@code is_riding} false, and {@code is_on_ground} true so FA's landing-squat doesn't bob the rider on a
 * moving ship), keyed off the {@link ShipMountPoseRenderState} standing flag. This mixin's whole job is to
 * raise that flag for villagers riding a gunner seat.
 *
 * <p>Any NON-CONTROLLER {@link ShipMountingEntity} a villager rides is a gunner seat: controller seats are
 * the helm's and carry players, and no other code mounts villagers. Written UNCONDITIONALLY -- true or
 * false -- because render states are reused across entities, and a flag only ever set would leak one
 * gunner's standing pose to every villager drawn after him.
 */
@Mixin(VillagerRenderer.class)
public abstract class MixinVillagerGunnerPose {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/npc/villager/Villager;"
            + "Lnet/minecraft/client/renderer/entity/state/VillagerRenderState;F)V",
        at = @At("TAIL")
    )
    private void vs_eureka$standTheGunner(final Villager villager, final VillagerRenderState state,
        final float partialTick, final CallbackInfo ci) {
        final Entity vehicle = villager.getVehicle();
        final boolean standing = vehicle instanceof ShipMountingEntity
            && !((ShipMountingEntity) vehicle).isController();
        ((ShipMountPoseRenderState) (Object) state).vs$setShipMountStanding(standing);
    }
}
