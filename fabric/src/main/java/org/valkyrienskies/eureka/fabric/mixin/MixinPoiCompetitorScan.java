package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.behavior.PoiCompetitorScan;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.eureka.crew.CrewProfession;

/**
 * A helm employs a crew, so two villagers standing at the same wheel are not rivals.
 *
 * <p>Vanilla enforces one villager per workstation TWICE, and neither check consults the ticket count. This is
 * the first: any two villagers holding the same {@code JOB_SITE} position compete, and the one with less
 * trading XP has its memory erased outright. A ship helm is registered with many tickets ({@code
 * crewmanHelmPoiTickets}, 64 by default) precisely so that a whole crew can share it, and this check was
 * quietly collapsing that back to one -- which is why a second villager never took the job however long you
 * waited.
 *
 * <p>The erase is also a slow leak, and that is the more damaging half. {@code AcquirePoi} spends a ticket
 * when a villager claims a site; this erases the memory WITHOUT releasing it (unlike the bed branch of
 * {@code ValidateNearbyPoi}, which does release). So every lost duel burned one of the helm's berths
 * permanently. Two villagers cycling acquire-compete-erase drain a helm in a couple of minutes, after
 * which {@code Occupancy.HAS_SPACE} rejects it and the helm stops attracting anyone at all -- a helm that
 * worked when placed and then silently stopped.
 *
 * <p>Declining to compete is enough to fix both: with no rival in the stream, {@code selectWinner} is never
 * reduced over anything and nobody's memory is touched.
 *
 * <p><b>Helms only.</b> Every vanilla workstation still admits exactly one villager -- this returns early for
 * any POI that is not ours, so a lectern is a lectern.
 */
@Mixin(PoiCompetitorScan.class)
public class MixinPoiCompetitorScan {

    @Inject(method = "competesForSameJobsite", at = @At("HEAD"), cancellable = true)
    private static void vs_eureka$helmsEmployACrew(final GlobalPos jobSite, final Holder<PoiType> poiType,
        final Villager villager, final CallbackInfoReturnable<Boolean> cir) {
        if (poiType.is(CrewProfession.POI_KEY)) {
            cir.setReturnValue(false);
        }
    }
}
