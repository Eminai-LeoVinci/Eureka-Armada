package org.valkyrienskies.eureka.fabric.mixin.client;

import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;
import traben.entity_model_features.models.animation.EMFAnimationEntityContext;
import traben.entity_model_features.models.animation.state.EMFEntityRenderState;

/**
 * Entity Model Features (EMF) compat for the helm rider's arms-on-the-wheel pose.
 *
 * <p>Eureka's stand-at-helm pose reaches the rider's arms forward by writing the VANILLA model's arm
 * parts. An EMF custom player model (Fresh Animations' "FA+Player") draws its own geometry from its
 * own parts, so those writes never reach the drawn figure -- the helmsman stood at the wheel but with
 * his arms hanging at his sides.
 *
 * <p>Posing the vanilla model earlier would not help either: FA composes each arm rotation from its
 * own idle/movement/equip terms and only folds the vanilla rotation in while an item action (block,
 * aim, brush) is running, so outside those actions the vanilla value is discarded entirely.
 *
 * <p>So pose EMF's OWN parts, at the last moment before they draw. EMF's render path is
 * {@code EMFModelPartWithState.render -> root.oneTimeRunnable(); root.animate(); super.render(...)},
 * which makes the TAIL of {@code animate()} the exact analogue of a renderToBuffer HEAD: every pack
 * expression has run, nothing has drawn yet.
 *
 * <p>Whether the rider is standing at a mount is derived from the state's ENTITY (1.21.11 reads a
 * flag its render-state architecture carries; 1.21.1 has no render states): any
 * {@link ShipMountingEntity} vehicle that is not a reconnect passenger seat is a station being
 * stood at, the same condition the vanilla-side pose uses.
 *
 * <p>Parts are resolved by CEM name through the whole hierarchy rather than as direct children
 * (via {@link ModelPartAccessor}; 1.21.1 has no {@code createPartLookup}), so this holds however a
 * pack nests its rig, and each name is optional -- the same injector serves the player model (arms +
 * the skin's sleeve overlay) and any armour model, and simply finds nothing on a cape or elytra.
 * The first-person hand is left alone, matching vanilla, where that path renders the arm directly
 * rather than through the model.
 *
 * <p>String target + no EMF types in the mixin's own signature on purpose: a string target makes
 * this a soft mixin that no-ops when EMF is not installed.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.parts.EMFModelPartRoot", remap = false)
public abstract class MixinEMFArmsAtHelm {

    /** Matches the vanilla standing-helm pose. */
    @Unique
    private static final float VS$HELM_ARM_X_ROT = -1.4F;

    /** CEM part names posed onto the wheel: both arms plus the skin's sleeve overlay. */
    @Unique
    private static final String[] VS$HELM_ARM_PARTS =
        {"right_arm", "left_arm", "right_sleeve", "left_sleeve"};

    @Inject(method = "animate", at = @At("TAIL"))
    private void vs_eureka$reachForShipHelm(final CallbackInfo ci) {
        if (EMFAnimationEntityContext.isFirstPersonHand) {
            return;
        }
        final EMFEntityRenderState state = EMFAnimationEntityContext.getEmfState();
        if (state == null || !(state.emfEntity() instanceof Entity entity)) {
            return;
        }
        final Entity vehicle = entity.getVehicle();
        if (!(vehicle instanceof ShipMountingEntity)) {
            return;
        }
        final ModelPart self = (ModelPart) (Object) this;
        for (final String partName : VS$HELM_ARM_PARTS) {
            final ModelPart part = vs_eureka$findPart(self, partName);
            if (part != null) {
                part.xRot = VS$HELM_ARM_X_ROT;
                part.yRot = 0.0F;
                part.zRot = 0.0F;
            }
        }
    }

    /** Depth-first search of the part hierarchy by CEM name; null when the rig has no such part. */
    @Unique
    @Nullable
    private static ModelPart vs_eureka$findPart(final ModelPart root, final String name) {
        final Map<String, ModelPart> children = ((ModelPartAccessor) (Object) root).vs_eureka$children();
        final ModelPart direct = children.get(name);
        if (direct != null) {
            return direct;
        }
        for (final ModelPart child : children.values()) {
            final ModelPart found = vs_eureka$findPart(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
