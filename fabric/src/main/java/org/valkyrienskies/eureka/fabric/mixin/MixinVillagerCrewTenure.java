package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.crew.CrewProfession;

/**
 * Signing articles counts as work, so a Crewman keeps the job when the helm sails away.
 *
 * <p>Assembling a ship takes the helm out of the world and rebuilds it at shipyard coordinates. For the
 * villager standing next to it that is a workstation that ceased to exist: {@code ValidateNearbyPoi} erases
 * the job site, and then two behaviours race. {@code AcquirePoi} wants to find the helm again at its new
 * coordinates -- which it can, because VS2 makes the POI manager sweep ship chunks -- while
 * {@code ResetProfession} wants to strip any profession from a villager with no job site, no trading XP and
 * no level. Whichever runs first wins, which is exactly why assembly cleared the profession SOME of the time
 * and not others.
 *
 * <p>Rather than override the reset, remove its precondition: give a new Crewman a single point of trading
 * XP. {@code ResetProfession} only ever fires at {@code getVillagerXp() == 0}, so from the moment the job is
 * taken the race has no losing branch, at assembly or at anything else that moves a helm. One XP is well
 * below the ten a Novice needs, so nothing levels up early, and the mechanism is vanilla's own rule that a
 * villager who has done work keeps their trade.
 *
 * <p>Seeded here rather than at the point of hiring because vanilla assigns the profession from inside a
 * behaviour lambda, which has no stable name to inject into. {@code setVillagerData} covers every route that
 * SETS a profession -- job site, spawn egg, a cure, {@code /data} -- so it is both the stabler target and the
 * more complete one. Loading from disk is the one route that does not go through it, and has its own arm
 * below; between them the seed is unconditional.
 */
@Mixin(Villager.class)
public class MixinVillagerCrewTenure {

    @Inject(method = "setVillagerData", at = @At("TAIL"))
    private void vs_eureka$signArticles(final VillagerData data, final CallbackInfo ci) {
        vs_eureka$seedTenure((Villager) (Object) this, data);
    }

    /**
     * The same seed again on load, because loading does not go through {@code setVillagerData}.
     *
     * {@code readAdditionalSaveData} writes the entity-data slot DIRECTLY and only then reads {@code Xp}, so
     * the injection above cannot fire for a villager coming off disk, and even if it could its work would be
     * overwritten a line later. Without this arm the fix would only ever apply to Crewmen hired after it
     * shipped: every one that already exists in a save was written with 0 XP, would be stripped on the next
     * load, and nothing would heal it. This is what makes existing crews survive the update.
     *
     * TAIL, so it runs after {@code Xp} has been read and its value is the one that was actually saved.
     */
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void vs_eureka$signArticlesOnLoad(final ValueInput input, final CallbackInfo ci) {
        final Villager self = (Villager) (Object) this;
        vs_eureka$seedTenure(self, self.getVillagerData());
    }

    @Unique
    private static void vs_eureka$seedTenure(final Villager villager, final VillagerData data) {
        if (!data.profession().is(CrewProfession.PROFESSION_KEY)) {
            return;
        }
        if (villager.level().isClientSide() || villager.getVillagerXp() != 0) {
            return;
        }
        villager.setVillagerXp(1);
    }
}
