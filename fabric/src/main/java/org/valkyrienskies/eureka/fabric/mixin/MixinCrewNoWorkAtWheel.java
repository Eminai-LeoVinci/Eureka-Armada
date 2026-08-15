package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.WorkAtPoi;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.crew.CrewProfession;

/**
 * A Crewman does not work the wheel.
 *
 * <p>One third of the crew's wide berth (with {@link MixinCrewNoWheelVisit} and
 * {@link MixinCrewStrollAnywhere}). {@code WorkAtPoi} is the sixty-tick "working" animation a villager runs
 * while standing at its workstation, and its {@code start()} is also the one place a villager's trades
 * restock. Neither belongs at a helm: a wheel is not a lectern, and a Crewman's trades are meant to restock
 * in HARBOR, once harbors exist -- so blocking the behaviour outright is not just crowd control, it is the
 * rule that reserves restocking for the harbor visit.
 *
 * <p>Keyed on the PROFESSION rather than on the job-site block so a Sneak+C-rostered librarian sailing as
 * crew still works their own lectern like any villager. Profession and job site cannot disagree in the other
 * direction: only a helm can mint a Crewman.
 *
 * <p>{@code checkExtraStartConditions} is the behaviour's only entrance, so cancelling it here means
 * {@code canStillUse} and {@code start} never run for a Crewman at all.
 */
@Mixin(WorkAtPoi.class)
public class MixinCrewNoWorkAtWheel {

    @Inject(
        method = "checkExtraStartConditions(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/npc/villager/Villager;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void vs_eureka$crewDoNotWorkTheWheel(final ServerLevel level, final Villager villager,
        final CallbackInfoReturnable<Boolean> cir) {
        if (villager.getVillagerData().profession().is(CrewProfession.PROFESSION_KEY)) {
            cir.setReturnValue(false);
        }
    }
}
