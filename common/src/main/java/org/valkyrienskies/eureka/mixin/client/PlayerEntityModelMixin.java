package org.valkyrienskies.eureka.mixin.client;

import java.lang.reflect.Method;
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
    // must re-run those copies, or the second skin layer detaches from the posed limbs. (1.20.1's
    // PlayerModel copies these in setupAnim's body, before our TAIL override, so a resync is needed --
    // same as 1.21.1; only 1.21.11 rebuilt them as children and needs no resync.)
    @Shadow public ModelPart leftSleeve;
    @Shadow public ModelPart rightSleeve;
    @Shadow public ModelPart leftPants;
    @Shadow public ModelPart rightPants;

    // Helm rider stands at the wheel. Gate PURELY on the vehicle type -- every ShipMountingEntity a
    // player rides is a helm seat EXCEPT a reconnect/sit passenger seat (which keeps the vanilla sitting
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
    // arm/leg rotations (idle bob / attack / crouch / AnimationUtils.bobModelPart); on 1.20.1 setupAnim
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

    // ShipMountingEntity.vs$isPassengerSeat() is a custom VS2-120 addition (the A23 seat rework) and is
    // NOT on the official VS2 2.4.10 API that Eureka is compiled against (the one-jar strategy), so we
    // can't call it directly without breaking the build. Resolve it reflectively against the runtime
    // seat class (cached): on our custom VS2 it separates a reconnect/sit passenger seat (keeps the
    // vanilla sitting pose) from a helm seat (standing, arms-forward pose); on official VS2 the method
    // is absent, so every ShipMountingEntity a player rides is treated as a helm seat.
    @Unique private static Method vs$isPassengerSeatMethod;
    @Unique private static boolean vs$isPassengerSeatResolved;

    @Unique
    private boolean vs$isStandingHelmRider(final T livingEntity) {
        final Entity vehicle = livingEntity.getVehicle();
        return vehicle instanceof ShipMountingEntity && !vs$isPassengerSeat(vehicle);
    }

    @Unique
    private static boolean vs$isPassengerSeat(final Entity seat) {
        if (!vs$isPassengerSeatResolved) {
            try {
                vs$isPassengerSeatMethod = seat.getClass().getMethod("vs$isPassengerSeat");
            } catch (final NoSuchMethodException e) {
                vs$isPassengerSeatMethod = null; // official VS2: no passenger-seat concept -> helm seat
            }
            vs$isPassengerSeatResolved = true;
        }
        if (vs$isPassengerSeatMethod == null) {
            return false;
        }
        try {
            return (Boolean) vs$isPassengerSeatMethod.invoke(seat);
        } catch (final ReflectiveOperationException e) {
            return false;
        }
    }

}
