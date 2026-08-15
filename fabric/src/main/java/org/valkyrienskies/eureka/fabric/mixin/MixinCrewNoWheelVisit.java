package org.valkyrienskies.eureka.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.StrollToPoi;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.crew.CrewProfession;

/**
 * A Crewman never walks up to the wheel.
 *
 * <p>{@code StrollToPoi} is the behaviour that periodically marches a working villager right onto its
 * workstation block -- correct for a librarian and a lectern, and exactly the thing that made a
 * thirty-two-strong crew take turns standing on the helm the captain is trying to click. Failing its
 * proximity gate makes the behaviour simply never start for a Crewman; every other villager keeps the
 * vanilla visit.
 *
 * <p>This composes with VS2's {@code MixinStrollToPoi}, which wraps a different instruction in the same
 * lambda ({@code GlobalPos.pos()}, translating shipyard to world): by the time this gate is consulted the
 * position is already in world space, and this wrap only decides whether the answer is allowed to be yes.
 */
@Mixin(StrollToPoi.class)
public class MixinCrewNoWheelVisit {

    @WrapOperation(
        method = "method_47156",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;closerToCenterThan(Lnet/minecraft/core/Position;D)Z"
        )
    )
    private static boolean vs_eureka$crewKeepOffTheWheel(final BlockPos pos, final Position mobPosition,
        final double distance, final Operation<Boolean> original,
        @Local(argsOnly = true) final PathfinderMob mob) {
        if (mob instanceof Villager villager
            && villager.getVillagerData().profession().is(CrewProfession.PROFESSION_KEY)) {
            return false;
        }
        return original.call(pos, mobPosition, distance);
    }
}
