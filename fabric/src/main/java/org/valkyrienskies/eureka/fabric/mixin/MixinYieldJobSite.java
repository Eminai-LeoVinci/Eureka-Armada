package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.behavior.YieldJobSite;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.crew.CrewProfession;

/**
 * The second place vanilla enforces one villager per workstation -- and the one that stops a crew forming in
 * the first place.
 *
 * <p>{@link MixinPoiCompetitorScan} handles two villagers who both already hold the site. This handles the
 * approach: an unemployed villager walking toward a helm checks whether anyone nearby wants it, finds the
 * Crewman who is already standing there, gives up its claim and turns around. So the helm never got past its
 * first hire, and the villager that turned around had a berth spent on it that was never released.
 *
 * <p>Reporting "nobody else wants this" for a helm leaves {@code findFirst()} empty, so the walker keeps its
 * potential job site and arrives. Every other workstation still yields exactly as vanilla intends.
 */
@Mixin(YieldJobSite.class)
public class MixinYieldJobSite {

    @Inject(method = "nearbyWantsJobsite", at = @At("HEAD"), cancellable = true)
    private static void vs_eureka$helmsEmployACrew(final Holder<PoiType> poiType, final Villager villager,
        final BlockPos pos, final CallbackInfoReturnable<Boolean> cir) {
        if (poiType.is(CrewProfession.POI_KEY)) {
            cir.setReturnValue(false);
        }
    }
}
