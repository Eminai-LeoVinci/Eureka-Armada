package org.valkyrienskies.eureka.mixin.ai.villagers;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.ai.behavior.SetWalkTargetFromBlockMemory;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Fixes villagers (and other home/job-site walkers) on a ship walking toward the shipyard region instead of toward
 * their bed / job site at night (or during the day for the job site).
 *
 * <p>The behavior's tick lambda ({@code method_47101}) reads the home/job {@link GlobalPos} from memory and uses its
 * raw {@link GlobalPos#pos()} (a SHIPYARD {@link BlockPos}, millions of blocks away) in three places: the
 * {@code distManhattan} near/far decision, the {@code DefaultRandomPos.getPosTowards} "toward" vector (far branch), and
 * the near-branch {@code WalkTarget(BlockPos)}. Because the shipyard pos is astronomically far from the villager's
 * world position, the far branch always fires and wanders the villager toward the shipyard direction (west+south),
 * never to the bed.</p>
 *
 * <p>We wrap {@link GlobalPos#pos()} inside that lambda to return the world-converted bed position. All four call sites
 * of {@code GlobalPos.pos()} (two distance checks, the toward vector, and the near-branch target) therefore see the
 * nearby WORLD bed: the distance is now small, the near branch fires, and it writes a {@code WalkTarget} at the world
 * bed which {@code MoveToTargetSink} can path to. For off-ship / land homes {@code toWorldCoordinates} is a no-op
 * pass-through (target-keyed via {@code getShipManagingPos}), so land villagers are byte-identical.</p>
 *
 * <p>1.20.1: the tick lambda keeps the same intermediary name {@code method_47101} and the same four
 * {@code GlobalPos.pos()} call sites as 1.21.11; only the {@code Villager} package differs
 * ({@code net.minecraft.world.entity.npc.Villager}).</p>
 */
// Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
@Mixin(SetWalkTargetFromBlockMemory.class)
public class MixinSetWalkTargetFromBlockMemory {
    @WrapOperation(method = "method_47101", require = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/core/GlobalPos;pos()Lnet/minecraft/core/BlockPos;"))
    private static BlockPos onGlobalPos(GlobalPos instance, Operation<BlockPos> original,
        @Local(argsOnly = true) Villager villager) {
        final BlockPos shipyardPos = original.call(instance);
        return BlockPos.containing(VSGameUtilsKt.toWorldCoordinates(villager.level(), shipyardPos));
    }
}
