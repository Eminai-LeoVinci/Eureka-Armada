package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountedToData;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;
import traben.entity_model_features.models.animation.EMFAnimationEntityContext;
import traben.entity_model_features.models.animation.state.EMFEntityRenderState;

/**
 * Ship-frame awareness for EMF's animation variables (Fresh Animations etc.).
 *
 * <p>EMF feeds animation packs three motion channels that are all WORLD-frame:
 * <ul>
 *   <li>{@code pos_x/pos_y/pos_z} — {@code getEntityX/Y/Z()}, the interpolated world position.
 *       Packs differentiate these across frames for jump/fall/land detection (FA's cow slow-"walks"
 *       in place and the FA player rig squat-bobs on any ship whose world Y drifts).</li>
 *   <li>{@code move_forward/move_strafing} — the entity's velocity projected on its yaw and
 *       NORMALIZED to a ±1 direction ({@code processMove}), so even millimetre-scale drift yields a
 *       full-scale value. For non-local entities EMF derives velocity from raw world position deltas
 *       (= full ship carry); FA's player rig thrusts the pelvis "forward" from it while standing
 *       still on a moving ship.</li>
 *   <li>{@code limb_swing/limb_speed} — vanilla {@code walkAnimation}, already ship-relative via
 *       the walk_animation_ship feature. Untouched here.</li>
 * </ul>
 *
 * <p>For an entity currently CARRIED by a ship (dragging info, same gate the carry pipeline uses)
 * this mixin re-expresses the first two channels in the ship frame: positions become shipyard-local
 * (stable while standing on a moving deck; deltas = movement relative to the deck) and move
 * direction is computed from the entity's per-tick displacement MINUS the ship's carry (the
 * prevTickTransform round-trip EntityDragger uses), with a small dead-zone so a still passenger
 * reads exactly zero. Off-ship entities and all other variables are untouched.
 *
 * <p>A rider MOUNTED to a ship (helm seat, passenger seat) is handled first and separately. The
 * dragging stamp is written by collision, which a seated rider never does, so the carry gate above
 * never fires for them -- the FA player rig kept bobbing at the helm in every perspective. A seated
 * rider is also the one case with an EXACT answer available: {@code ShipMountedToData} carries the
 * mount point already in ship coordinates, and it is constant while seated, so positions come
 * straight from it (no transform, no interpolation residue) and self-motion is exactly zero.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.animation.EMFAnimationEntityContext", remap = false)
public abstract class MixinEMFAnimationEntityContext {

    @Shadow
    @Nullable
    private static EMFEntityRenderState emfState;

    // Render-thread-only scratch (EMF evaluates animations on the render thread).
    @Unique
    private static final Vector3d vs_eureka$scratchA = new Vector3d();
    @Unique
    private static final Vector3d vs_eureka$scratchB = new Vector3d();

    /** The state's entity, or null when EMF is animating something that isn't one (GUI, item, ...). */
    @Unique
    @Nullable
    private static Entity vs_eureka$stateEntity() {
        final EMFEntityRenderState state = emfState;
        if (state == null) {
            return null;
        }
        return state.emfEntity() instanceof Entity entity ? entity : null;
    }

    /**
     * The state's entity's mount point on a ship it is SEATED on, already in ship coordinates, or
     * null when it isn't riding a ship. Constant for as long as the rider stays in the seat, whatever
     * the ship is doing, which is exactly the "position" and "movement" an animation pack should see.
     */
    @Unique
    @Nullable
    private static ShipMountedToData vs_eureka$shipMountData() {
        final Entity entity = vs_eureka$stateEntity();
        return entity == null ? null : VSGameUtilsKt.getShipMountedToData(entity, null);
    }

    /**
     * The ship currently carrying the state's entity, or null. Uses the same dragging-info gate as
     * the carry pipeline (stamped by collision for the local player and by the drag keep-alive for
     * mobs/remotes on the client), so it engages exactly while the entity rides a ship. Riders SEATED
     * on a ship are handled by {@link #vs_eureka$shipMountData()} instead -- they never collide with the
     * deck, so this gate never sees them.
     */
    @Unique
    @Nullable
    private static LoadedShip vs_eureka$carryingShip() {
        final Entity entity = vs_eureka$stateEntity();
        if (entity == null) {
            return null;
        }
        if (!(entity instanceof IEntityDraggingInformationProvider provider)) {
            return null;
        }
        final EntityDraggingInformation info = provider.getDraggingInformation();
        if (info == null || !info.isEntityBeingDraggedByAShip()) {
            return null;
        }
        final Long shipId = info.getLastShipStoodOn();
        if (shipId == null) {
            return null;
        }
        final var level = entity.level();
        if (level == null) {
            return null;
        }
        return VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips().getById(shipId);
    }

    /** Interpolated world position of the state's entity into {@code dest} (EMF's own lerp). */
    @Unique
    private static Vector3d vs_eureka$interpWorldPos(final EMFEntityRenderState state, final Vector3d dest) {
        final float delta = EMFAnimationEntityContext.getTickDelta();
        return dest.set(
            Mth.lerp(delta, state.prevX(), state.x()),
            Mth.lerp(delta, state.prevY(), state.y()),
            Mth.lerp(delta, state.prevZ(), state.z()));
    }

    /**
     * Shipyard-local position for pos_x/pos_y/pos_z while carried: stable on a moving deck, so
     * packs' frame-to-frame deltas mean "movement relative to the ship", not the ship's own flight.
     * Uses the render transform when available so it is smooth per frame.
     */
    @Unique
    @Nullable
    private static Vector3d vs_eureka$shipLocalPos() {
        // Seated on a ship: the mount point is already ship-local and exact.
        final ShipMountedToData mounted = vs_eureka$shipMountData();
        if (mounted != null) {
            return vs_eureka$scratchA.set(mounted.getMountPosInShip());
        }
        final LoadedShip ship = vs_eureka$carryingShip();
        if (ship == null) {
            return null;
        }
        final EMFEntityRenderState state = emfState;
        if (state == null) {
            return null;
        }
        final ShipTransform transform = ship instanceof ClientShip clientShip
            ? clientShip.getRenderTransform() : ship.getTransform();
        final Vector3d world = vs_eureka$interpWorldPos(state, vs_eureka$scratchA);
        return transform.getWorldToShip().transformPosition(world);
    }

    // remap = false on these five: they target EMF's own methods, which are never obfuscated, and on this
    // version the mixin annotation processor treats an unresolved mapping for an @Inject as a hard error
    // (the @Shadow above only warns). The sibling EMF mixins declare the same thing at class level.
    @Inject(at = @At("HEAD"), method = "getEntityX", cancellable = true, require = 0, remap = false)
    private static void vs_eureka$shipLocalX(final CallbackInfoReturnable<Float> cir) {
        final Vector3d local = vs_eureka$shipLocalPos();
        if (local != null) {
            cir.setReturnValue((float) local.x);
        }
    }

    @Inject(at = @At("HEAD"), method = "getEntityY", cancellable = true, require = 0, remap = false)
    private static void vs_eureka$shipLocalY(final CallbackInfoReturnable<Float> cir) {
        final Vector3d local = vs_eureka$shipLocalPos();
        if (local != null) {
            cir.setReturnValue((float) local.y);
        }
    }

    @Inject(at = @At("HEAD"), method = "getEntityZ", cancellable = true, require = 0, remap = false)
    private static void vs_eureka$shipLocalZ(final CallbackInfoReturnable<Float> cir) {
        final Vector3d local = vs_eureka$shipLocalPos();
        if (local != null) {
            cir.setReturnValue((float) local.z);
        }
    }

    /**
     * Ship-relative per-tick displacement (world-oriented) for move_forward/move_strafing while
     * carried: current position minus where pure ship carry would have put last tick's position
     * (the prevTickTransform round-trip). Returns null when not carried; zero-length means "still
     * relative to the deck".
     */
    @Unique
    @Nullable
    private static Vector3d vs_eureka$shipRelativeDisplacement() {
        // Seated on a ship: no self-motion by definition. Zero falls into the getters' dead-zone.
        if (vs_eureka$shipMountData() != null) {
            return vs_eureka$scratchB.zero();
        }
        final LoadedShip ship = vs_eureka$carryingShip();
        if (ship == null) {
            return null;
        }
        final EMFEntityRenderState state = emfState;
        if (state == null) {
            return null;
        }
        // carriedOnly = shipToWorld_now(worldToShip_prev(prevPos)): last tick's position moved by
        // the ship's own tick motion alone. actual - carriedOnly = the entity's own movement.
        final Vector3d carried = vs_eureka$scratchA.set(state.prevX(), state.prevY(), state.prevZ());
        ship.getPrevTickTransform().getWorldToShip().transformPosition(carried);
        ship.getTransform().getShipToWorld().transformPosition(carried);
        return vs_eureka$scratchB.set(state.x() - carried.x, state.y() - carried.y, state.z() - carried.z);
    }

    @Inject(at = @At("HEAD"), method = "getMoveForward", cancellable = true, require = 0, remap = false)
    private static void vs_eureka$shipRelativeMoveForward(final CallbackInfoReturnable<Float> cir) {
        if (emfState == null || EMFAnimationEntityContext.isInGui()) {
            return; // match the vanilla getter's GUI/absent-state behavior
        }
        final Vector3d rel = vs_eureka$shipRelativeDisplacement();
        if (rel == null) {
            return;
        }
        // Same math as EMF: project on yaw, normalized to +-1; dead-zone so a still passenger
        // (or sub-walking drift) reads exactly zero instead of a full-scale direction.
        final double horizontalSq = rel.x * rel.x + rel.z * rel.z;
        if (horizontalSq < 1.0E-4) {
            cir.setReturnValue(0.0F);
            return;
        }
        final double rad = Math.toRadians(90.0F - emfState.yaw());
        final double component = rel.x * Math.cos(rad) - rel.z * Math.sin(rad);
        cir.setReturnValue((float) (-component / Math.sqrt(horizontalSq)));
    }

    @Inject(at = @At("HEAD"), method = "getMoveStrafe", cancellable = true, require = 0, remap = false)
    private static void vs_eureka$shipRelativeMoveStrafe(final CallbackInfoReturnable<Float> cir) {
        if (emfState == null || EMFAnimationEntityContext.isInGui()) {
            return;
        }
        final Vector3d rel = vs_eureka$shipRelativeDisplacement();
        if (rel == null) {
            return;
        }
        final double horizontalSq = rel.x * rel.x + rel.z * rel.z;
        if (horizontalSq < 1.0E-4) {
            cir.setReturnValue(0.0F);
            return;
        }
        final double rad = Math.toRadians(90.0F - emfState.yaw());
        final double component = rel.x * Math.sin(rad) + rel.z * Math.cos(rad);
        cir.setReturnValue((float) (-component / Math.sqrt(horizontalSq)));
    }
}
