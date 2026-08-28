package org.valkyrienskies.eureka.fabric.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.fabric.client.ClientCrewMarkers;
import org.valkyrienskies.eureka.fabric.client.CrewPlates;

/**
 * Gives every marked crew member a name tag, and paints its backdrop the crew colour.
 *
 * <p>Vanilla labels only the entity under the crosshair or a mob wearing a name tag item. Crew are
 * neither, so {@code shouldShowName} answers yes for anyone on {@link ClientCrewMarkers}' list --
 * from that point the name is drawn by the ordinary name-tag path, which is why it billboards, sorts
 * by distance, draws through the hull and survives shaders without a line of rendering code of ours.
 *
 * <p>A full crew read from across the water is a wall of text: vanilla's own cutoff is 64 blocks and
 * the range gate here only ever tightens it -- beyond it a crew member shows no tag at all, crosshair
 * included, exactly as the modern branch behaves.
 *
 * <p>The backdrop: {@code renderNameTag} computes one background colour for every plate in the game
 * (pure black at whatever Text Background Opacity says). {@link CrewPlates#tint} keeps vanilla's
 * alpha byte and replaces only the hue, and only while the flag raised at HEAD says this plate is a
 * crew one -- so a crew plate is exactly as solid as every other name tag on screen. Raising and
 * lowering the flag around the call is safe for the same reason it was on the modern branch: it is
 * one synchronous chain on the render thread, one entity at a time.
 *
 * <p>(1.21.11 does all this through its render-state architecture -- an extract-phase flag carried on
 * EntityRenderState into a NameTagFeatureRenderer submit. 1.21.1 renders directly from the entity,
 * so the same three moves land on shouldShowName/renderNameTag instead, and the state-carrier duck
 * plus its Storage mixin are simply not needed.)
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererCrewNameTag<T extends Entity> {

    @Inject(method = "shouldShowName", at = @At("HEAD"), cancellable = true)
    private void vs_eureka$plateTheCrew(final T entity, final CallbackInfoReturnable<Boolean> cir) {
        if (!ClientCrewMarkers.INSTANCE.contains(entity.getId())) {
            return;
        }
        final double range = EurekaConfig.CLIENT.getCrewNameplateRange();
        final Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        cir.setReturnValue(entity.distanceToSqr(cam) <= range * range);
    }

    @Inject(method = "renderNameTag", at = @At("HEAD"))
    private void vs_eureka$markPlate(final T entity, final Component displayName, final PoseStack poseStack,
        final MultiBufferSource bufferSource, final int packedLight,
        final CallbackInfo ci) {
        CrewPlates.setActive(ClientCrewMarkers.INSTANCE.contains(entity.getId()));
    }

    @Inject(method = "renderNameTag", at = @At("RETURN"))
    private void vs_eureka$clearPlate(final T entity, final Component displayName, final PoseStack poseStack,
        final MultiBufferSource bufferSource, final int packedLight,
        final CallbackInfo ci) {
        CrewPlates.setActive(false);
    }

    /**
     * Both drawInBatch calls in renderNameTag get the tint: the background pass carries the box colour
     * and the foreground pass passes zero, which {@link CrewPlates#tint} leaves alone -- so no ordinal.
     */
    @ModifyArg(
        method = "renderNameTag",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZ"
                + "Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;"
                + "Lnet/minecraft/client/gui/Font$DisplayMode;II)I"
        ),
        index = 8
    )
    private int vs_eureka$crewPlateColour(final int backgroundColor) {
        return CrewPlates.tint(backgroundColor);
    }
}
