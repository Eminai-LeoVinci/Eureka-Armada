package org.valkyrienskies.eureka.mixin.ai.jobsites;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.ValidateNearbyPoi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.ship.ShipJobSites;

/**
 * "Is my job site still there?" -- which vanilla only asks once the mob is NEAR it.
 *
 * <p>The proximity gate is the same cross-space comparison as {@code WorkAtPoi}'s, and getting it wrong has a
 * nastier consequence than merely not working: a villager that is never "near" its job site is never told the
 * job site has gone, so the memory is never erased, {@code LoseJobOnSiteLoss} never fires, and the profession
 * sticks forever. That is why a Crewman kept its coat after its wheel had been broken.
 *
 * <p><b>Only the distance test is translated, deliberately.</b> This behaviour also asks the POI index whether
 * a record still exists at that position, and the index is keyed by the REAL block position -- which for a
 * workstation aboard a ship is the shipyard one. Translating the lookup too would ask about a position the
 * index has never heard of and delete every job site on every ship.
 *
 * <p>The target is a lambda, so it carries an intermediary name rather than a readable one; the enclosing
 * method is {@code ValidateNearbyPoi.create}'s trigger body.
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(ValidateNearbyPoi.class)
public class MixinValidateNearbyPoi {

    @WrapOperation(
        method = "method_47187",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;closerToCenterThan(Lnet/minecraft/core/Position;D)Z"
        )
    )
    private static boolean valkyrienskies$measureJobSiteInWorldSpace(final BlockPos jobSite,
        final Position position, final double distance, final Operation<Boolean> original,
        @Local(argsOnly = true) final ServerLevel level) {
        // A site whose ship has been taken apart can never be walked to, so nobody can ever be near enough to
        // discover it is gone. Report "near" and let the existence check below do its job -- it finds nothing
        // at that address and the memory is dropped, which is the only way these mobs get released.
        if (ShipJobSites.isOrphaned(level, jobSite)) {
            return true;
        }
        return original.call(ShipJobSites.toWorld(level, jobSite), position, distance);
    }
}
