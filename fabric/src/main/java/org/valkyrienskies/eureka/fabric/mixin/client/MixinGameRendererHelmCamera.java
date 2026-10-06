package org.valkyrienskies.eureka.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.client.HelmCamera;

/**
 * Wraps the same frustum call VS2 wraps to mount the camera to a ship, from OUTSIDE VS2's wrapper (priority 1100,
 * applied after VS2's, so outermost). In the helm's plain slots it raises a flag for exactly the length of VS2's
 * hook, during which {@link MixinShipMountedPlainCamera} tells VS2 the local player is not ship-mounted: VS2 then
 * steps aside and keeps the vanilla camera. See {@link HelmCamera}.
 */
@Mixin(value = GameRenderer.class, priority = 1100)
public abstract class MixinGameRendererHelmCamera {

    @WrapOperation(
        method = "renderLevel",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareCullFrustum(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;Lorg/joml/Matrix4f;)V")
    )
    private void vs_eureka$plainHelmCamera(final LevelRenderer levelRenderer, final PoseStack poseStack,
        final Vec3 cameraPos, final Matrix4f projection, final Operation<Void> original) {
        if (!HelmCamera.plainCamera(Minecraft.getInstance())) {
            original.call(levelRenderer, poseStack, cameraPos, projection);
            return;
        }
        HelmCamera.hideMount = true;
        try {
            original.call(levelRenderer, poseStack, cameraPos, projection);
        } finally {
            HelmCamera.hideMount = false;
        }
    }
}
