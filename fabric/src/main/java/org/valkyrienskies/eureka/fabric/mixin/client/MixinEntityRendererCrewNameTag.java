package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.EurekaConfig;
import org.valkyrienskies.eureka.fabric.client.ClientCrewMarkers;
import org.valkyrienskies.eureka.fabric.client.CrewPlated;
import org.valkyrienskies.eureka.fabric.client.CrewPlates;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Gives every marked crew member a name tag, and tells the backdrop it is a crew one.
 *
 * <p>Vanilla fills {@code state.nameTag} only for an entity it would label anyway: the one under the crosshair,
 * or a mob wearing a name tag item. Crew are neither. Filling it here is the whole of "SHIFT+C shows the crew's
 * names" -- from this point on the name is drawn by {@code NameTagFeatureRenderer}, exactly as every other name
 * tag in the game is, which is why it billboards, sorts by distance, draws through the hull and survives
 * shaders without a line of rendering code of ours.
 *
 * <p>{@code nameTagAttachment} has to be filled alongside it. Vanilla sets the two together inside the branch we
 * are stepping around, and {@code Storage.add} returns immediately on a null attachment -- a name with nowhere
 * to hang is simply not drawn.
 *
 * <p>Injected at TAIL of the BASE method: subclasses call {@code super.extractRenderState} first and then set
 * their own fields, so this runs before anything downstream and after everything vanilla decided.
 *
 * <p>With the toggle off this is inert: {@link ClientCrewMarkers#contains} is a field read, and a crewman under
 * the crosshair still gets the ordinary black-backed tag. That is deliberate -- the toggle is a request to see
 * the crew our way, not a claim on how villagers are labelled the rest of the time.
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererCrewNameTag {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void vs_eureka$plateTheCrew(
        final Entity entity, final EntityRenderState state, final float partialTick, final CallbackInfo ci
    ) {
        if (!ClientCrewMarkers.INSTANCE.contains(entity.getId())) {
            ((CrewPlated) state).vs_eureka$setCrewPlate(false);
            return;
        }

        // A full crew read from across the water is a wall of text. Vanilla's own cutoff is 64 blocks; this one
        // only ever tightens it.
        final double range = EurekaConfig.CLIENT.getCrewNameplateRange();
        if (state.distanceToCameraSq > range * range) {
            ((CrewPlated) state).vs_eureka$setCrewPlate(false);
            state.nameTag = null;
            return;
        }

        state.nameTag = entity.getDisplayName();
        final Vec3 attachment = entity.getAttachments()
            .getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(partialTick));
        state.nameTagAttachment = attachment;
        ((CrewPlated) state).vs_eureka$setCrewPlate(attachment != null);
    }

    /**
     * Raise the crew flag for exactly the length of one submit.
     *
     * The colour is decided several frames-worth of indirection later, in a method that knows nothing but a
     * matrix and a string. This call and that one are the same synchronous chain on the render thread, one
     * entity at a time, so a plain flag spans them safely -- and lowering it on the way out means a nameplate
     * submitted by anything else can never inherit it.
     */
    @Inject(method = "submitNameTag", at = @At("HEAD"))
    private void vs_eureka$markPlate(
        final EntityRenderState state, final PoseStack pose, final SubmitNodeCollector collector,
        final CameraRenderState camera, final CallbackInfo ci
    ) {
        CrewPlates.setActive(((CrewPlated) state).vs_eureka$isCrewPlate());
    }

    @Inject(method = "submitNameTag", at = @At("RETURN"))
    private void vs_eureka$clearPlate(
        final EntityRenderState state, final PoseStack pose, final SubmitNodeCollector collector,
        final CameraRenderState camera, final CallbackInfo ci
    ) {
        CrewPlates.setActive(false);
    }
}
