package org.valkyrienskies.eureka.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.core.Direction;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.util.EntityDraggingInformation;
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider;

/**
 * The height a walking mob aims its feet at, read off the ship's deck instead of the world grid.
 *
 * <p>Path waypoints sit at whole-block heights. Vanilla corrects that with {@code getGroundY}: it looks at the block
 * under the waypoint and aims at its real top, so a mob on bottom slabs aims half a block down, not at the next whole
 * block. Under a ship that block is air (the deck lives in the shipyard), so vanilla aims at the raw whole-block
 * height. A ship rarely floats at a whole-block height, and on a slab deck that height can sit more than a step above
 * the mob's feet, which MoveControl reads as a step to jump: villagers hopped the whole way across a slab deck with
 * every walk. Here the deck block under the waypoint (or in it, for a slab or step the mob will stand in) gives the
 * floor, carried back to world height through the ship. Real steps still read as steps.
 *
 * <p>Only for a mob VS2 is carrying, and only where the world itself has no floor under the waypoint.
 */
@Mixin(PathNavigation.class)
public abstract class MixinPathNavigationGroundYShips {

    @Shadow
    @Final
    protected Mob mob;

    @Shadow
    @Final
    protected Level level;

    @Inject(method = "getGroundY(Lnet/minecraft/world/phys/Vec3;)D", at = @At("RETURN"), cancellable = true)
    private void vs_eureka$groundYOnDeck(final Vec3 waypoint, final CallbackInfoReturnable<Double> cir) {
        if (!(this.mob instanceof final IEntityDraggingInformationProvider provider)) {
            return;
        }
        final EntityDraggingInformation info = provider.getDraggingInformation();
        final Long shipId = info.getLastShipStoodOn();
        if (shipId == null || !info.isEntityBeingDraggedByAShip()) {
            return;
        }
        if (!this.level.getBlockState(BlockPos.containing(waypoint).below()).isAir()) {
            return; // a real world floor: vanilla already measured it
        }
        final Ship ship = VSGameUtilsKt.getShipObjectWorld(this.level).getAllShips().getById(shipId);
        if (ship == null) {
            return;
        }
        // The centre of the world cell under the waypoint, in the ship's frame.
        final Vector3dc under = ship.getTransform().getWorldToShip()
            .transformPosition(new Vector3d(waypoint.x, waypoint.y - 0.5, waypoint.z), new Vector3d());
        final BlockPos below = BlockPos.containing(under.x(), under.y(), under.z());
        // The waypoint's own cell first (a slab or stair step the feet will stand in), then the one under it.
        double top = vs_eureka$top(below.above());
        if (Double.isNaN(top)) {
            top = vs_eureka$top(below);
        }
        if (Double.isNaN(top)) {
            return;
        }
        final Vector3dc floor = ship.getTransform().getShipToWorld()
            .transformPosition(new Vector3d(under.x(), top, under.z()), new Vector3d());
        cir.setReturnValue(floor.y());
    }

    /** The top of [pos]'s collision in the ship's frame, or NaN if it has none. */
    private double vs_eureka$top(final BlockPos pos) {
        final BlockState state = this.level.getBlockState(pos);
        if (state.isAir()) {
            return Double.NaN;
        }
        final VoxelShape shape = state.getCollisionShape(this.level, pos);
        return shape.isEmpty() ? Double.NaN : pos.getY() + shape.max(Direction.Axis.Y);
    }
}
