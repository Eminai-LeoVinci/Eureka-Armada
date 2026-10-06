package org.valkyrienskies.eureka.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntityWalkOnShip {

    /**
     * Stop the walk animation from firing on a stationary entity that's just being carried by a ship.
     *
     * 2.4.99: Use SHIP-RELATIVE position delta as the walk-anim speed signal while on a ship.
     * 2.4.100: Gate on getLastShipStoodOn() != null instead of isEntityBeingDraggedByAShip().
     * 2.4.101: Drop reliance on getLastShipStoodOn() entirely — it returns null for REMOTE
     * entities on the observing client. Instead use VSGameUtilsKt.getShipsIntersecting() with
     * an AABB around the entity's feet, which is the same primitive used by
     * entity_collision/MixinEntity.getPosStandingOnFromShips. Works on both sides for both
     * local AND remote entities.
     *
     * Why the previous gates failed:
     * - 2.4.100 logs proved class_745 (RemotePlayer on observer) has shipId=null AND vanilla
     *   f=0.18+ rising. So both isEntityBeingDraggedByAShip() (requires ticksSinceStoodOnShip<25)
     *   AND lastShipStoodOn (set only by collide() which doesn't run for remote entities on
     *   observers) fail. The position-intersection lookup doesn't depend on any per-entity
     *   ticksSinceStoodOnShip counter or packet-synced field — it just asks "is this entity's
     *   foot AABB inside any ship's AABB right now".
     *
     * Why position-intersection is correct here:
     * - getShipsIntersecting is computed from the ship's current world-space AABB, which moves
     *   with the ship every tick on both server and observing client (the ship transform IS
     *   sync'd).
     * - When updateWalkAnimation runs for a remote player on the observer, the entity has
     *   already been interpolated to the server's latest world position (with ship-drag baked
     *   in). That post-interp position is inside the ship's current AABB envelope.
     * - When updateWalkAnimation runs for the local player, the entity's position is pre-
     *   EntityDragger (drag runs at Minecraft.tick @RETURN), so getX() reflects the previous
     *   tick's drag end. That position is still inside the ship's AABB if the player is on it.
     *
     * 2.4.97's deltaMovement approach failed because vanilla Entity.move() ends up either
     * zeroing deltaMovement on collided axes OR leaving residual ship-drag motion in it.
     * 2.4.99/2.4.100's lastShipStoodOn gate failed for remote entities because that field
     * isn't populated for them on observers.
     *
     * The ONLY truly clean signal for "did this entity try to move on the ship" is the delta
     * of its position expressed in SHIP-LOCAL coordinates. Ship motion translates and rotates
     * the world frame around the player; ship-local coordinates are invariant under that, so a
     * stationary-on-ship player has zero ship-local delta regardless of how the ship moves.
     */
    @Unique
    private Vector3dc vs_eureka$prevShipRelPos = null;
    @Unique
    private Long vs_eureka$prevShipRelId = null;

    @Inject(method = "updateWalkAnimation", at = @At("HEAD"), cancellable = true, require = 1)
    private void vs_eureka$shipRelativeWalkAnim(float f, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        // Asleep means not walking. A sleeper on a ship still gets tiny position corrections every tick (the ship
        // carrying him, his bed position being re-applied), which the walk animation reads as steps: legs swung about
        // 15 degrees each way, flickering as the corrections alternate. Legs at rest instead.
        //
        // update(0, 1), NOT setSpeed(0): the legs are drawn from a blend of this tick's speed and LAST tick's, and
        // setSpeed only changes this tick's. With the update skipped, last tick's value stayed frozen at whatever the
        // entity was doing when it lay down, and every frame's blend between it and zero flickered the legs at a
        // fixed angle until the entity was reloaded. update(0, 1) carries the old value over and lands on zero, so
        // both read zero from the next frame on. (Sleeping mobs never get here: MixinLivingEntitySleepOnShip skips
        // their travel(), which is what calls this, and settles their legs itself. This covers players.)
        if (self.isSleeping()) {
            self.walkAnimation.update(0.0F, 1.0F);
            vs_eureka$prevShipRelPos = null;
            ci.cancel();
            return;
        }

        // Runs for every LivingEntity every tick: bail to vanilla before any per-entity work
        // (AABB allocation + intersection query below) when the world has no ships at all.
        if (VSGameUtilsKt.getShipObjectWorld(self.level()).getAllShips().size() == 0) {
            if (vs_eureka$prevShipRelPos != null) {
                vs_eureka$prevShipRelPos = null;
                vs_eureka$prevShipRelId = null;
            }
            return;
        }

        // Fast path: trust lastShipStoodOn if set (local player / server-side path).
        Ship ship = null;
        Long shipId = null;
        boolean fromDragInfo = false;
        if (((Object) this) instanceof IEntityDraggingInformationProvider provider) {
            EntityDraggingInformation info = provider.getDraggingInformation();
            if (info != null) {
                Long candidate = info.getLastShipStoodOn();
                if (candidate != null) {
                    Ship s = VSGameUtilsKt.getShipObjectWorld(self.level())
                        .getAllShips().getById(candidate);
                    if (s != null) {
                        ship = s;
                        shipId = candidate;
                        fromDragInfo = true;
                    }
                }
            }
        }

        // Fallback: position-intersect ship lookup. Works for REMOTE entities on the observing
        // client, where lastShipStoodOn is null but the ship transform IS sync'd, so its AABB
        // moves with it and the entity's post-interp position is inside it.
        //
        // Candidates come from the SHARED per-tick foot query (one spatial query per entity per
        // tick, memoized -- see EntityShipCollisionUtils.shipsNearEntityFeet); since this site has
        // no block-under-feet validation of its own, each candidate's world AABB is re-tested
        // against the ORIGINAL tight foot box, so the verdict is identical to the old direct query
        // (the shared candidate box is a padded superset).
        if (ship == null) {
            final double minX = self.getX() - 0.4;
            final double minY = self.getY() - 1.0;
            final double minZ = self.getZ() - 0.4;
            final double maxX = self.getX() + 0.4;
            final double maxY = self.getY() + 0.1;
            final double maxZ = self.getZ() + 0.4;
            for (Ship s : VSGameUtilsKt.getShipsIntersecting(self.level(), new AABB(minX, minY, minZ, maxX, maxY, maxZ))) {
                final AABBdc shipAabb = s.getWorldAABB();
                if (shipAabb == null) {
                    continue; // no AABB yet -> the old direct query couldn't have returned it either
                }
                if (shipAabb.maxX() >= minX && shipAabb.minX() <= maxX
                    && shipAabb.maxY() >= minY && shipAabb.minY() <= maxY
                    && shipAabb.maxZ() >= minZ && shipAabb.minZ() <= maxZ) {
                    ship = s;
                    shipId = s.getId();
                    break;
                }
            }
        }

        if (ship == null) {
            if (vs_eureka$prevShipRelPos != null) {
                vs_eureka$prevShipRelPos = null;
                vs_eureka$prevShipRelId = null;
            }
            return;
        }

        Vector3dc currentShipRel = ship.getWorldToShip().transformPosition(
            new Vector3d(self.getX(), self.getY(), self.getZ()));

        float speedSignal = 0.0F;
        if (vs_eureka$prevShipRelPos != null && shipId.equals(vs_eureka$prevShipRelId)) {
            double dx = currentShipRel.x() - vs_eureka$prevShipRelPos.x();
            double dz = currentShipRel.z() - vs_eureka$prevShipRelPos.z();
            speedSignal = (float) Math.sqrt(dx * dx + dz * dz);
        }
        vs_eureka$prevShipRelPos = currentShipRel;
        vs_eureka$prevShipRelId = shipId;

        // Dead-zone: sub-walking ship-relative drift (the client's lerped entity position catching
        // up to a moving/turning ship frame) must not read as walking. Vanilla models rendered the
        // residual invisibly small (limb swing scales with the tiny speed), but animation packs
        // (Fresh Animations via EMF) key pronounced leg/hip cycles off ANY nonzero limb speed --
        // a still cow "walked in place" and the FA player rig hip-bobbed on a turning ship. Real
        // walking is >= ~0.05 blocks/tick ship-relative (sneak ~0.04); drift stays under ~0.02.
        if (speedSignal < 0.03F) {
            speedSignal = 0.0F;
        }

        // 1.21.1's WalkAnimationState.update takes (speed, scale); the third baby-scale argument is
        // 1.21.2+. This mirrors what vanilla updateWalkAnimation does on this version exactly.
        self.walkAnimation.update(Math.min(speedSignal * 4.0F, 1.0F), 0.4F);
        ci.cancel();
    }
}
