package org.valkyrienskies.eureka.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.eureka.armada.ArmadaClientBonds;
import org.valkyrienskies.mod.common.world.RaycastUtilsKt;
import org.valkyrienskies.mod.mixinducks.feature.clip_replace.ClipContextDuck;

/**
 * Keeps the armada out of its own third-person camera.
 *
 * <p>VS2's ship-mounted camera pulls back from the player and then raycasts, shrinking the pullback to whatever it
 * hits so the camera never ends up inside a wall. That raycast (VS2's MixinCamera) already ignores the ship the
 * player is riding -- it passes that ship as the clip's {@code skipShip} -- but it knows nothing about armadas, so
 * every OTHER ship in the formation still reads as solid terrain and slams the camera in the moment a sibling ship
 * drifts behind the player.
 *
 * <p>An armada flies as one vessel, so the fix is to widen that existing "don't clip on the ship I'm riding" rule
 * to "don't clip on any ship in the armada I'm riding". Only the camera passes a {@code skipShip}, so keying off it
 * scopes this to exactly that raycast and leaves crosshair picking, projectiles and every other clip untouched --
 * blocks on a sibling ship stay targetable as normal.
 *
 * <p>Costs nothing when this client knows of no bonds, and nothing on the integrated server's side of a raycast
 * (the bond registry is client state, hence the isClientSide guard as well as the client-only mixin).
 */
@Mixin(value = RaycastUtilsKt.class, remap = false)
public abstract class MixinRaycastUtils {

    @ModifyExpressionValue(
        method = "clipIncludeShipsImpl",
        at = @At(value = "INVOKE", target = "Lorg/valkyrienskies/mod/common/VSGameUtilsKt;getShipsIntersecting"),
        require = 1
    )
    private static Iterable<Ship> vs_eureka$skipArmadaForShipCamera(final Iterable<Ship> ships, final Level level,
        final ClipContext ctx) {

        if (!level.isClientSide() || !ArmadaClientBonds.INSTANCE.hasBonds() || !(ctx instanceof ClipContextDuck)) {
            return ships;
        }
        // Set only by the ship-mounted camera; every other clip leaves it null and is left alone.
        final Long mounted = ((ClipContextDuck) ctx).getRayCastExtraParameter().getSkipShip();
        if (mounted == null) {
            return ships;
        }

        final List<Ship> outsiders = new ArrayList<>();
        for (final Ship ship : ships) {
            if (!ArmadaClientBonds.INSTANCE.sharesArmadaWith(mounted, ship.getId())) {
                outsiders.add(ship);
            }
        }
        return outsiders;
    }
}
