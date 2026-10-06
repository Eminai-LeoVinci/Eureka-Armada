package org.valkyrienskies.eureka.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.config.VSGameConfig;

/**
 * Lets mob pathfinding see ship decks.
 *
 * <p>Every pathfinding evaluator (walking, swimming, flying) reads the world through a {@link PathNavigationRegion},
 * and that reads the WORLD. A ship's blocks live far away in the shipyard, so over a deck the region reports sky:
 * every path for a mob aboard either fails or drops to the ground below, and the mob just stands there. So where
 * the world cell is air but a ship's block occupies that position, report the ship's block. One overlay here makes
 * every evaluator ship-aware.
 *
 * <p>Follows VS2's own {@code aiOnShips} setting. A reentry guard stops the overlay's own block reads from recursing.
 * Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
 */
@Mixin(PathNavigationRegion.class)
public abstract class MixinPathNavigationRegionShips {

    @Shadow
    @Final
    protected Level level;

    @Unique
    private static final ThreadLocal<Boolean> vs_eureka$inOverlay = ThreadLocal.withInitial(() -> false);

    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"), cancellable = true)
    private void vs_eureka$overlayShipBlock(final BlockPos pos, final CallbackInfoReturnable<BlockState> cir) {
        if (!VSGameConfig.SERVER.getAiOnShips() || vs_eureka$inOverlay.get() || !cir.getReturnValue().isAir()) {
            return;
        }
        vs_eureka$inOverlay.set(true);
        try {
            for (final Ship ship : VSGameUtilsKt.getShipsIntersecting(this.level, new AABB(pos))) {
                final Vector3d shipPos = ship.getTransform().getWorldToShip().transformPosition(
                    new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), new Vector3d());
                final BlockState shipState = this.level.getBlockState(BlockPos.containing(shipPos.x, shipPos.y, shipPos.z));
                if (!shipState.isAir()) {
                    cir.setReturnValue(shipState);
                    return;
                }
            }
        } finally {
            vs_eureka$inOverlay.set(false);
        }
    }
}
