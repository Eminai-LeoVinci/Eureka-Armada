package org.valkyrienskies.eureka.mixin.ai.jobsites;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.StrollToPoi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.ship.ShipJobSites;

/**
 * The periodic visit to the workstation itself, for a job site aboard a ship.
 *
 * <p>This is the fine half of vanilla's work rhythm: where {@link net.minecraft.world.entity.ai.behavior
 * .SetWalkTargetFromBlockMemory} only reels a villager to within nine blocks, this one walks them right up to
 * the block every eighty-odd ticks, which is what makes a librarian actually stand at the lectern. Its
 * proximity gate AND its walk target both read the raw shipyard position, so aboard a ship the gate never
 * opened -- one more of the frozen-crew behaviours -- and had it somehow fired, the walk target would have
 * pointed at the shipyard.
 *
 * <p>One conversion at {@code GlobalPos.pos()} fixes both reads at once, the same source-patch shape as
 * {@link MixinSetWalkTargetFromBlockMemory}.
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(StrollToPoi.class)
public class MixinStrollToPoi {

    @WrapOperation(
        method = "method_47156",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/GlobalPos;pos()Lnet/minecraft/core/BlockPos;"
        )
    )
    private static BlockPos valkyrienskies$visitTheWorldPosition(final GlobalPos site,
        final Operation<BlockPos> original, @Local(argsOnly = true) final ServerLevel level) {
        return ShipJobSites.toWorld(level, original.call(site));
    }
}
