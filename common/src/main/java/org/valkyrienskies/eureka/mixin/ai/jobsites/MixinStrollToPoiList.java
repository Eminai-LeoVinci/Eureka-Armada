package org.valkyrienskies.eureka.mixin.ai.jobsites;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.StrollToPoiList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.ship.ShipJobSites;

/**
 * The rounds a villager makes of its secondary work sites, for job sites aboard a ship.
 *
 * <p>Farmers are the ones this matters for -- their secondary sites are the farmland blocks around the
 * composter -- but any profession with secondaries gets the same treatment. The trigger reads TWO positions,
 * the primary job site for its proximity gate and a random secondary for the walk target, and both arrive
 * through {@code GlobalPos.pos()}, so the one source conversion covers the pair. Untranslated, this was the
 * third of the frozen-crew gates ({@link MixinStrollAroundPoi} explains the shape of that bug).
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(StrollToPoiList.class)
public class MixinStrollToPoiList {

    @WrapOperation(
        method = "method_47160",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/GlobalPos;pos()Lnet/minecraft/core/BlockPos;"
        )
    )
    private static BlockPos valkyrienskies$makeRoundsAtWorldPositions(final GlobalPos site,
        final Operation<BlockPos> original, @Local(argsOnly = true) final ServerLevel level) {
        return ShipJobSites.toWorld(level, original.call(site));
    }
}
