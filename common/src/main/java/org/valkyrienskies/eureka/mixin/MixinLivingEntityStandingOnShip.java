package org.valkyrienskies.eureka.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

/**
 * Keeps a mob that is standing STILL on a ship registered as being on that ship.
 *
 * <p>VS2 only marks a mob as on a ship when it collides with one while moving, and the mark lapses after 25 ticks.
 * A mob that stands still, like a gun crew held at its cannon, stops being re-marked, so VS2 stops sending its
 * ship-relative motion and the client falls back to vanilla's delayed position updates. On a ship under way the
 * mob then lags behind and snaps back, further the faster the ship goes.
 *
 * <p>So every server tick, a non-player mob that is genuinely standing on a ship (a solid ship block under its
 * feet) is re-marked on that ship, and the normal VS2 sync and carry stay engaged. Once it is off the hull it is
 * simply not refreshed, and the usual expiry lets it go.
 *
 * <p>Our VS2 build used to carry this fix itself; it lives here now so Armada runs on the official release.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntityStandingOnShip {

    @Inject(method = "tick", at = @At("TAIL"))
    private void vs_eureka$keepStandingMobOnShip(final CallbackInfo ci) {
        final LivingEntity self = (LivingEntity) (Object) this;
        final Level level = self.level();
        if (level.isClientSide() || self instanceof Player) {
            return;
        }
        if (!(self instanceof final IEntityDraggingInformationProvider provider)) {
            return;
        }
        if (VSGameUtilsKt.getShipObjectWorld(level).getAllShips().size() == 0) {
            return;
        }
        final Ship ship = vs_eureka$shipStandingOn(self, level);
        if (ship == null) {
            return;
        }
        final EntityDraggingInformation info = provider.getDraggingInformation();
        final Long current = info.getLastShipStoodOn();
        if (current == null || !current.equals(ship.getId())) {
            info.setLastShipStoodOn(ship.getId());
        }
        // Keep the drag window open every tick. This setter also clears shouldImpulseMovement, so a mob that was
        // just re-marked is carried without a one-off velocity jolt.
        info.setTicksSinceStoodOnShip(0);
    }

    /** The ship whose block is directly under [self]'s feet, or null. */
    @Unique
    private static Ship vs_eureka$shipStandingOn(final LivingEntity self, final Level level) {
        final AABB box = self.getBoundingBox();
        final double gx = self.getX();
        final double gy = box.minY - 0.5;
        final double gz = self.getZ();
        // Only ships whose world box reaches the feet are worth transforming into.
        final AABB feet = new AABB(box.minX, box.minY - 1.5, box.minZ, box.maxX, box.minY + 0.1, box.maxZ);
        for (final Ship ship : VSGameUtilsKt.getShipsIntersecting(level, feet)) {
            final Vector3dc local = ship.getTransform().getWorldToShip()
                .transformPosition(new Vector3d(gx, gy, gz));
            if (!level.getBlockState(BlockPos.containing(local.x(), local.y(), local.z())).isAir()) {
                return ship;
            }
            // Fences and other tall edges: check one block lower too.
            final Vector3dc below = ship.getTransform().getWorldToShip()
                .transformPosition(new Vector3d(gx, gy - 1.0, gz));
            final BlockPos belowPos = BlockPos.containing(
                Math.round(below.x()), Math.round(below.y()), Math.round(below.z()));
            if (!level.getBlockState(belowPos).isAir()) {
                return ship;
            }
        }
        return null;
    }
}
