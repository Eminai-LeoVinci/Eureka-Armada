package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.valkyrienskies.eureka.client.ShipCameraZoom;

/**
 * Scales VS2's ship-mounted third-person pull-back by the scroll zoom (see {@link ShipCameraZoom}).
 *
 * <p>{@code setupWithShipMounted} is VS2's own method, added to {@link Camera} by VS2's camera mixin, so this applies
 * after it (priority 1100). Its only double local is {@code dist}, the pull-back: stored once from the ship's size
 * and again when floored at 4. Scaling it at that second store lands before VS2 clips the camera against terrain,
 * so zooming out still stops at walls.
 */
@Mixin(value = Camera.class, priority = 1100)
public abstract class MixinCameraShipZoom {

    @ModifyVariable(method = "setupWithShipMounted", at = @At(value = "STORE", ordinal = 1), ordinal = 0, remap = false)
    private double vs_eureka$scaleShipCameraDistance(final double dist) {
        return dist * ShipCameraZoom.getMultiplier();
    }
}
