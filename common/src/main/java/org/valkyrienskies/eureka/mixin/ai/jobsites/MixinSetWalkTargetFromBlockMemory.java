package org.valkyrienskies.eureka.mixin.ai.jobsites;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.SetWalkTargetFromBlockMemory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.ship.ShipJobSites;

/**
 * Where to actually walk, for a job site aboard a ship.
 *
 * <p>This is the behaviour that turns a JOB_SITE memory into a {@link net.minecraft.world.entity.ai.memory
 * .WalkTarget}, and it was handing the pathfinder a raw shipyard coordinate. The mob then set off toward a
 * point millions of blocks away, failed, re-targeted and set off again -- the relentless walking that made a
 * helm look like it had a gravity well.
 *
 * <p>Unlike the other two goals, this one is patched at the SOURCE rather than at each comparison. Every use of
 * the position in here derives from {@code GlobalPos.pos()} -- the two distance gates and both flavours of walk
 * target -- and nothing in this method consults the POI index, so converting once at the top is both smaller
 * and harder to get half-right than wrapping four call sites individually.
 *
 * <p>The target is a lambda and so carries an intermediary name; it is the trigger body of
 * {@code SetWalkTargetFromBlockMemory.create}.
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(SetWalkTargetFromBlockMemory.class)
public class MixinSetWalkTargetFromBlockMemory {

    @WrapOperation(
        method = "method_47101",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/GlobalPos;pos()Lnet/minecraft/core/BlockPos;"
        )
    )
    private static BlockPos valkyrienskies$walkToTheWorldPosition(final GlobalPos site,
        final Operation<BlockPos> original, @Local(argsOnly = true) final ServerLevel level) {
        return ShipJobSites.toWorld(level, original.call(site));
    }
}
