package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.client.ShipCameraZoom;

/**
 * While VS2's ship-mounted third-person camera is showing, the scroll wheel zooms it instead of changing hotbar slot
 * (see {@link ShipCameraZoom}). Anywhere else, with a screen open, on foot, or in first person, the wheel is vanilla's.
 */
@Mixin(MouseHandler.class)
public abstract class MixinMouseHandlerShipZoom {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$zoomShipCamera(final long window, final double xOffset, final double yOffset,
        final CallbackInfo ci) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || !ShipCameraZoom.isShipCameraActive(mc)) {
            return;
        }
        if (yOffset != 0.0) {
            ShipCameraZoom.scroll(yOffset > 0.0 ? 1.0 : -1.0);
        }
        ci.cancel();
    }
}
