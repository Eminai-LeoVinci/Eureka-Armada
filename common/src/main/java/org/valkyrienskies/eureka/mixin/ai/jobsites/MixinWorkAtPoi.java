package org.valkyrienskies.eureka.mixin.ai.jobsites;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.WorkAtPoi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.ship.ShipJobSites;

/**
 * "Am I standing at my workstation?" -- asked about a job site that may be aboard a ship.
 *
 * <p>The check is a plain {@code jobSite.closerToCenterThan(villager.position(), 1.73)}, and for a workstation
 * in the shipyard the two operands are in different spaces, so it can never be true. A villager on a ship could
 * be touching its wheel and still never work at it.
 *
 * <p>Only the comparison is translated. Nothing in this method looks the position up in the POI index, so
 * moving it into world space is safe -- see {@code MixinValidateNearbyPoi} for the case where it is not.
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(WorkAtPoi.class)
public class MixinWorkAtPoi {

    @WrapOperation(
        method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/npc/Villager;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;closerToCenterThan(Lnet/minecraft/core/Position;D)Z"
        )
    )
    private boolean valkyrienskies$measureJobSiteInWorldSpace(final BlockPos jobSite, final Position position,
        final double distance, final Operation<Boolean> original,
        @Local(argsOnly = true) final ServerLevel level) {
        return original.call(ShipJobSites.toWorld(level, jobSite), position, distance);
    }
}
