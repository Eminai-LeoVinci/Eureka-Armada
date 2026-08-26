package org.valkyrienskies.eureka.mixin.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

@Mixin(PlayerModel.class)
public abstract class PlayerEntityModelMixin<T extends LivingEntity> extends HumanoidModel<T> {

    public PlayerEntityModelMixin(final ModelPart model) {
        super(model);
    }

    // PlayerModel's outer skin layers (sleeves/pants) are sibling parts positioned each frame by
    // setupAnim via copyFrom(baseArm/baseLeg). When we override the base arm/leg pose for the helm we
    // must re-run those copies, or the second skin layer detaches from the posed limbs. (1.21.11's
    // PlayerModel rebuilt these as children, so its ship_mount_pose mixin needs no resync; 1.21.1 does.)
    @Shadow public ModelPart leftSleeve;
    @Shadow public ModelPart rightSleeve;
    @Shadow public ModelPart leftPants;
    @Shadow public ModelPart rightPants;

    // Helm rider stands at the wheel. Gate PURELY on the vehicle type -- every ShipMountingEntity a
    // player rides is a helm seat EXCEPT a reconnect passenger seat (which keeps the vanilla sitting
    // pose). The old air/slab block probe read the seat's blockPosition and regressed slab-mounted
    // riders to seated (a slab is not air); removed. riding=false straightens the seated leg-bend at
    // its source, before HumanoidModel.setupAnim runs. Mirrors VS2 1.21.11 ship_mount_pose.
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At(value = "HEAD"))
    public void vs$standAtHelmHead(final T livingEntity,
                                   final float swing,
                                   final float g,
                                   final float tick,
                                   final float i,
                                   final float j,
                                   final CallbackInfo info) {
        if (vs$isStandingHelmRider(livingEntity)) {
            this.riding = false;
        }
    }

    // Arms reach forward onto the wheel, legs straight. Applied at TAIL because setupAnim's body writes
    // arm/leg rotations (idle bob / attack / crouch / AnimationUtils.bobModelPart); on 1.21.1 setupAnim
    // is the last pose step before the draw, so a TAIL write persists. Arm xRot = -1.4F mirrors VS2's
    // 1.21.11 MixinModel. The trailing copyFrom calls re-sync the overlay layers (see fields above).
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At(value = "TAIL"))
    public void vs$standAtHelmTail(final T livingEntity,
                                   final float swing,
                                   final float g,
                                   final float tick,
                                   final float i,
                                   final float j,
                                   final CallbackInfo info) {
        if (!vs$isStandingHelmRider(livingEntity)) {
            return;
        }
        this.rightLeg.visible = true;
        this.leftLeg.visible = true;
        this.rightLeg.xRot = 0.0F; this.rightLeg.yRot = 0.0F; this.rightLeg.zRot = 0.0F;
        this.leftLeg.xRot = 0.0F;  this.leftLeg.yRot = 0.0F;  this.leftLeg.zRot = 0.0F;
        this.rightArm.xRot = -1.4F; this.rightArm.yRot = 0.0F; this.rightArm.zRot = 0.0F;
        this.leftArm.xRot = -1.4F;  this.leftArm.yRot = 0.0F;  this.leftArm.zRot = 0.0F;
        // Re-snapshot the outer skin layers onto the posed base limbs (PlayerModel did this earlier in
        // its body, before our override) so the second layer follows the helm pose instead of detaching.
        this.leftSleeve.copyFrom(this.leftArm);
        this.rightSleeve.copyFrom(this.rightArm);
        this.leftPants.copyFrom(this.leftLeg);
        this.rightPants.copyFrom(this.rightLeg);
    }

    @Unique
    private boolean vs$isStandingHelmRider(final T livingEntity) {
        final Entity vehicle = livingEntity.getVehicle();
        return vehicle instanceof ShipMountingEntity
            && !((ShipMountingEntity) vehicle).vs$isPassengerSeat();
    }

}
