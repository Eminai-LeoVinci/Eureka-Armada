package org.valkyrienskies.eureka.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.eureka.ship.ShipInfluenceOrientation;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.eureka.client.ShipGamepad;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

/**
 * A controller's D-pad climbs and dives at the helm: up climbs, down dives, in every camera view.
 *
 * <p>VS2 builds the helm's driving input in {@code ShipMountingEntity.sendDrivingPacket} from the keyboard alone:
 * jump for up and its "ship down" key for down. This ORs the D-pad into those two reads, so a pad flies the ship
 * with no keybind setup at all. Everything else about the packet is VS2's own. It only runs for the seat being
 * ridden, because that is the only seat VS2 sends driving input from.
 *
 * <p>The two reads are picked by order among the method's {@code KeyMapping.isDown} calls, which on 2.4.11 are
 * forward, back, left, right, JUMP, SHIP DOWN, cruise. Our VS2 build used to do this inside the method itself.
 *
 * <p>It also tells {@link ShipInfluenceOrientation} which way the bow is, every tick the seat is ridden: the
 * helm's thrust direction is the seat's opposite, the same convention VS2 uses for the helm.
 */
@Mixin(value = ShipMountingEntity.class, remap = false)
public abstract class MixinHelmDpadClimb {

    @Inject(method = "sendDrivingPacket", at = @At("HEAD"), remap = false)
    private void vs_eureka$learnBow(final CallbackInfo ci) {
        final ShipMountingEntity self = (ShipMountingEntity) (Object) this;
        final Ship ship = VSGameUtilsKt.getLoadedShipManagingPos(self.level(), self.blockPosition());
        if (ship != null) {
            ShipInfluenceOrientation.INSTANCE.observeForward(ship.getId(), self.getDirection().getOpposite());
        }
    }

    @ModifyExpressionValue(
        method = "sendDrivingPacket",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal = 4, remap = true),
        remap = false
    )
    private boolean vs_eureka$dpadClimbs(final boolean jumpDown) {
        return jumpDown || ShipGamepad.INSTANCE.dpadUp();
    }

    @ModifyExpressionValue(
        method = "sendDrivingPacket",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal = 5, remap = true),
        remap = false
    )
    private boolean vs_eureka$dpadDives(final boolean shipDownDown) {
        return shipDownDown || ShipGamepad.INSTANCE.dpadDown();
    }
}
