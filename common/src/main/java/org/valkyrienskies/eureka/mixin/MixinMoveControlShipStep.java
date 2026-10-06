package org.valkyrienskies.eureka.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Stops mobs on ships hopping whenever the deck moves under them.
 *
 * <p>Vanilla's move control jumps when the block at the mob's own position has a collision shape (a step to climb).
 * VS2 wraps that check to report the ship's block when the world cell is empty, so a mob standing on a deck sees the
 * deck it is standing on as a step and jumps, over and over while the ship climbs or dives. This wraps the same call
 * from OUTSIDE VS2's wrapper (priority 1100, applied after it): when the shape came from a ship, it only counts if the
 * block's top is more than half a block above the mob's feet in the ship's frame, which is a real step. The deck under
 * the mob reads about zero and no longer triggers a jump; climbing a raised block on the deck still works.
 *
 * <p>Our VS2 build used to do this inside VS2's own wrapper; it lives in Armada now.
 */
@Mixin(value = MoveControl.class, priority = 1100)
public abstract class MixinMoveControlShipStep {

    /** A ship block counts as a step only if its top is this far above the mob's feet (ship frame). */
    @Unique
    private static final double VS_EUREKA$STEP_THRESHOLD = 0.5;

    @Shadow
    @Final
    protected Mob mob;

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape vs_eureka$onlyRealStepsOnShips(final BlockState state, final BlockGetter getter, final BlockPos pos,
        final Operation<VoxelShape> original) {
        final VoxelShape result = original.call(state, getter, pos);
        // Empty, or a real WORLD block: nothing for us to judge.
        if (result.isEmpty() || !state.getCollisionShape(getter, pos).isEmpty()) {
            return result;
        }
        final double cx = pos.getX() + 0.5;
        final double cy = pos.getY() + 0.5;
        final double cz = pos.getZ() + 0.5;
        final AABBd cell = new AABBd(cx - 0.5, cy - 0.5, cz - 0.5, cx + 0.5, cy + 0.5, cz + 0.5);
        for (final Ship ship : VSGameUtilsKt.getShipsIntersecting(this.mob.level(), cell)) {
            final Vector3dc cellShip = ship.getTransform().getWorldToShip().transformPosition(new Vector3d(cx, cy, cz), new Vector3d());
            final BlockPos shipPos = BlockPos.containing(cellShip.x(), cellShip.y(), cellShip.z());
            final BlockState shipState = this.mob.level().getBlockState(shipPos);
            if (shipState.isAir()) {
                continue;
            }
            final VoxelShape shipShape = shipState.getCollisionShape(this.mob.level(), shipPos);
            if (shipShape.isEmpty()) {
                continue;
            }
            final double deckTop = shipPos.getY() + shipShape.max(Direction.Axis.Y);
            final Vector3dc feet = ship.getTransform().getWorldToShip()
                .transformPosition(new Vector3d(this.mob.getX(), this.mob.getY(), this.mob.getZ()), new Vector3d());
            if (deckTop - feet.y() > VS_EUREKA$STEP_THRESHOLD) {
                return result;
            }
        }
        return Shapes.empty();
    }
}
