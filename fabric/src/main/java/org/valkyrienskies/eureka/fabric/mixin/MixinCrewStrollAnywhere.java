package org.valkyrienskies.eureka.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.StrollAroundPoi;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.eureka.crew.CrewProfession;

/**
 * A Crewman's stroll works across the whole watch circle, not just at the wheel's foot.
 *
 * <p>Vanilla's work-hours wander ({@code StrollAroundPoi}) only fires within FOUR blocks of the job site,
 * because a vanilla villager is expected to be standing at its workstation. A Crewman deliberately is not:
 * {@link MixinCrewNoWheelVisit} and {@link MixinCrewNoWorkAtWheel} keep them off the wheel, and the walk
 * tether ({@code SetWalkTargetFromBlockMemory}, closeEnough 9) parks them nine blocks out. Left alone, that
 * combination re-creates the frozen-crew pile-up nine blocks from the helm -- outside the stroll gate, inside
 * the tether, with no behaviour left that ever moves them.
 *
 * <p>So for Crewmen the gate widens from 4 to {@link #CREW_WATCH_RADIUS}: anywhere inside it they take the
 * vanilla eight-block hop every couple hundred ticks, and the tether reels them back if a hop carries them
 * past Manhattan 9 from the helm. The two radii together are what "milling about on deck" is.
 */
@Mixin(StrollAroundPoi.class)
public class MixinCrewStrollAnywhere {

    /**
     * Comfortably outside the walk tether's 9 (Manhattan) plus one full 8-block hop of drift, so there is no
     * dead band between "close enough to stroll" and "far enough to be reeled back".
     */
    @Unique
    private static final double CREW_WATCH_RADIUS = 16.0;

    @WrapOperation(
        method = "method_47152",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;closerToCenterThan(Lnet/minecraft/core/Position;D)Z"
        )
    )
    private static boolean vs_eureka$crewStrollTheWholeDeck(final BlockPos pos, final Position mobPosition,
        final double distance, final Operation<Boolean> original,
        @Local(argsOnly = true) final PathfinderMob mob) {
        final boolean crew = mob instanceof Villager villager
            && villager.getVillagerData().profession().is(CrewProfession.PROFESSION_KEY);
        return original.call(pos, mobPosition, crew ? CREW_WATCH_RADIUS : distance);
    }
}
