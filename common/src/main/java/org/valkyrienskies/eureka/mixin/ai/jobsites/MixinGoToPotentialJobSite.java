package org.valkyrienskies.eureka.mixin.ai.jobsites;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.GoToPotentialJobSite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.ship.ShipJobSites;

/**
 * The walk an unemployed villager makes toward a workstation it wants, when that workstation is aboard a ship.
 *
 * <p>{@code tick} re-issues the walk-and-look target at the potential job site's position every single tick,
 * and it was issuing the raw shipyard position. That this ever worked at all was an accident of another
 * mixin: {@code MoveToTargetSink.reachedTarget} is ship-translated, so a villager who happened to already be
 * standing at the workstation's WORLD position counted as arrived and took the job -- while one a deck-length
 * away pathed toward the shipyard, failed, and burned its twelve-hundred-tick timeout. Translating the
 * position here makes the approach an actual approach.
 *
 * <p>{@code tick} is overloaded (the generic {@code Behavior} bridge takes {@code LivingEntity}); the
 * descriptor pins the villager one, which is where the position is read.
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(GoToPotentialJobSite.class)
public class MixinGoToPotentialJobSite {

    @WrapOperation(
        method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/Villager;J)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/GlobalPos;pos()Lnet/minecraft/core/BlockPos;"
        )
    )
    private BlockPos valkyrienskies$approachTheWorldPosition(final GlobalPos site,
        final Operation<BlockPos> original, @Local(argsOnly = true) final ServerLevel level) {
        return ShipJobSites.toWorld(level, original.call(site));
    }
}
