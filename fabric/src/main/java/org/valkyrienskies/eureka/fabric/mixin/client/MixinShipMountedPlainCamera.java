package org.valkyrienskies.eureka.fabric.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.client.HelmCamera;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountedToData;

/**
 * While {@link MixinGameRendererHelmCamera} has the flag up (only for the length of VS2's camera hook, only in the
 * helm's plain F5 slots), VS2's "is this entity mounted to a ship?" answers no for the local player, so VS2's camera
 * hook keeps the vanilla camera. Render thread only: in singleplayer the integrated server asks the same question on
 * its own thread at the same moment, and must always get the true answer.
 */
@Mixin(value = VSGameUtilsKt.class, remap = false)
public abstract class MixinShipMountedPlainCamera {

    @Inject(method = "getShipMountedToData", at = @At("HEAD"), cancellable = true)
    private static void vs_eureka$plainCameraSeesNoMount(final Entity passenger, final Float partialTicks,
        final CallbackInfoReturnable<ShipMountedToData> cir) {
        if (HelmCamera.hideMount && RenderSystem.isOnRenderThread() && passenger == Minecraft.getInstance().player) {
            cir.setReturnValue(null);
        }
    }
}
