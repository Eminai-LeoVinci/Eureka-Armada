package org.valkyrienskies.eureka.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

/**
 * Stand ANY mounted creature at its ship mount, not just the player.
 *
 * <p>A rider on a {@link ShipMountingEntity} is standing at a station -- a helmsman at the wheel, a
 * stationed gunner at a cannon -- unless the seat is a reconnect PASSENGER seat, which genuinely seats
 * them. The player half of this is Eureka's PlayerEntityModelMixin (riding=false + arms to the wheel);
 * this covers everyone else: a villager rendered through {@code VillagerRenderer} was still folded into
 * the sitting pose, so a gun deck's crew sat on the planks at their guns.
 *
 * <p>1.21.11 does this by carrying a standing flag on the root {@code EntityRenderState}
 * (feature.ship_mount_pose.MixinEntityRenderState there); 1.21.1 has no render states, and Fabric has no
 * {@code Entity.shouldRiderSit} either -- that is a NeoForge patch method, absent from the vanilla class
 * this jar ships. What every model DOES key off is {@code EntityModel.riding}, which
 * {@code LivingEntityRenderer.render} writes exactly once -- so the write is wrapped and the sit is
 * cleared at its source for ship-mount riders, before any {@code setupAnim} ever sees it.
 *
 * <p>Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingEntityRendererStandAtMount {

    @WrapOperation(
        method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/model/EntityModel;riding:Z",
            opcode = Opcodes.PUTFIELD
        )
    )
    private void vs_eureka$standAtShipMount(final EntityModel<?> model, final boolean riding,
        final Operation<Void> original, @Local(argsOnly = true) final LivingEntity entity) {
        final Entity vehicle = entity.getVehicle();
        // Official VS2 has no passenger seats, so every ShipMountingEntity is a station. Armada's DeckSeat is not
        // one, so whoever sits in it keeps sitting.
        final boolean standing = vehicle instanceof ShipMountingEntity;
        original.call(model, riding && !standing);
    }
}
