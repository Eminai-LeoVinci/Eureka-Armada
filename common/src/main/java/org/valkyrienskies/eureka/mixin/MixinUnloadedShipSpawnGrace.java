package org.valkyrienskies.eureka.mixin;

import java.util.Iterator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.primitives.AABBd;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.core.internal.world.VsiClientShipWorld;
import org.valkyrienskies.eureka.ship.ShipSpawnGrace;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;
import org.valkyrienskies.mod.mixinducks.feature.tickets.PlayerKnownShipsDuck;

/**
 * VS2's "is this entity touching a ship that isn't loaded yet?" check, with ships in their spawn grace left out (see
 * {@link ShipSpawnGrace}). While no ship is in its grace, VS2's own check runs untouched. While one is, this repeats
 * VS2's check step for step (same ship query, same known-ship and loaded-chunk tests, through
 * {@link EntityShipCollisionUtilsAccessor}) and skips the graced ships.
 *
 * <p>Our VS2 build did this inside VS2; it lives in Armada now so Armada runs on the official release.
 */
@Mixin(value = EntityShipCollisionUtils.class, remap = false)
public abstract class MixinUnloadedShipSpawnGrace {

    @Inject(method = "isCollidingWithUnloadedShips", at = @At("HEAD"), cancellable = true)
    private static void vs_eureka$skipShipsInSpawnGrace(final Entity entity, final CallbackInfoReturnable<Boolean> cir) {
        final Level level = entity.level();
        final long now = level.getGameTime();
        if (!ShipSpawnGrace.anyActive(now)) {
            return;
        }
        if (!(level instanceof ServerLevel) && !level.isClientSide()) {
            return;
        }
        // A client that has not synced its ships yet is frozen by VS2 regardless; leave that to VS2.
        if (level.isClientSide() && VSGameUtilsKt.getShipObjectWorld(level) instanceof final VsiClientShipWorld world
            && !world.isSyncedWithServer()) {
            return;
        }
        final EntityShipCollisionUtilsAccessor vs2 =
            (EntityShipCollisionUtilsAccessor) (Object) EntityShipCollisionUtils.INSTANCE;
        final AABB bb = entity.getBoundingBox();
        final AABBd box = new AABBd(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
        final Iterator<Ship> ships = vs2.vs_eureka$shipsTouching(level, box).iterator();
        while (ships.hasNext()) {
            final Ship ship = ships.next();
            if (ShipSpawnGrace.isGraced(ship.getId(), now)) {
                continue;
            }
            if (entity instanceof final PlayerKnownShipsDuck known && !known.vs_isKnownShip(ship.getId())) {
                cir.setReturnValue(true);
                return;
            }
            if (!vs2.vs_eureka$chunksLoaded(ship, new AABBd(box).transform(ship.getWorldToShip()), level)) {
                cir.setReturnValue(true);
                return;
            }
        }
        cir.setReturnValue(false);
    }
}
