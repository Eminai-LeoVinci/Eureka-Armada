package org.valkyrienskies.eureka.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountedToData;

/**
 * Keeps a seated rider's name tag upright on a ship that is heeling or pitching.
 *
 * <p>VS2 draws a name tag inside the ship's transform and then undoes the ship's rotation so it stays upright, but
 * only for entities that live in ship space. A rider (a gunner or a player in a deck seat, a helmsman) lives in world
 * space and is drawn through the ship's transform because he is mounted, so VS2's check misses him and his tag tilts
 * with the hull. This undoes the rotation for exactly that case: mounted to a ship, not living in ship space. The two
 * conditions never overlap, so a tag is never straightened twice.
 *
 * <p>Our VS2 build did this inside VS2's own name-tag hook; it lives in Armada now.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererRiderNameTag {

    @WrapOperation(
        method = "renderNameTag(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V")
    )
    private void vs_eureka$straightenRiderTag(final PoseStack matrices, final float x, final float y, final float z,
        final Operation<Void> original, @Local(argsOnly = true) final Entity entity) {
        original.call(matrices, x, y, z);
        if (VSGameUtilsKt.getLoadedShipManagingPos(entity.level(), entity.blockPosition()) != null) {
            return; // lives in ship space: VS2 already straightens this one
        }
        final ShipMountedToData mounted = VSGameUtilsKt.getShipMountedToData(entity, null);
        if (mounted != null && mounted.getShipMountedTo() instanceof final ClientShip ship) {
            matrices.mulPose(new Quaternionf(ship.getRenderTransform().getShipToWorldRotation()).invert());
        }
    }
}
