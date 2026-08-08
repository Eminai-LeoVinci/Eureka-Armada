package org.valkyrienskies.eureka.fabric

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
// `object` is a Kotlin keyword, and Fabric's object-builder API has it as a package segment -- hence the
// backticks. Without them this is a parse error, not an unresolved import.
import net.fabricmc.fabric.api.`object`.builder.v1.trade.TradeOfferHelper
import net.fabricmc.fabric.api.`object`.builder.v1.world.poi.PointOfInterestHelper
import net.minecraft.world.InteractionResult
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.crew.CrewBerths
import org.valkyrienskies.eureka.crew.CrewMarkers
import org.valkyrienskies.eureka.crew.CrewProfession
import org.valkyrienskies.eureka.crew.CrewTrades

/**
 * The three halves of the crew system that need Fabric API, and therefore cannot live in :common (which has
 * fabric-loader on its classpath but not the API).
 *
 * The profession itself is NOT here -- it is plain vanilla registry work and lives in [CrewProfession].
 */
object CrewRegistrationsFabric {

    /**
     * Call from `onInitialize`, AFTER `EurekaMod.init()`.
     *
     * Two ordering constraints, both real:
     *  - after `EurekaBlocks.register()`, because [PointOfInterestHelper] walks each block's state definition
     *    and so needs the Block instances to exist;
     *  - after `EurekaConfigLoader.loadOrCreate()`, because the ticket count is read here exactly once and
     *    baked into every POI record created from then on.
     *
     * `EurekaMod.init()` does both, in that order, so "after init" is the whole rule.
     */
    @JvmStatic
    fun register() {
        // A helm is a job site that employs a CREW. Vanilla's own MEETING point already ships 32 tickets, so
        // the number is not exotic -- what is unusual is a *workstation* having more than one.
        //
        // Exactly one call site, deliberately: PoiTypes.registerBlockStates throws
        // "<state> is defined in more than one PoI type" on a duplicate, and it is wrapped in Util.pauseInIde,
        // so a second registration is a hard startup crash rather than a warning.
        PointOfInterestHelper.register(
            CrewProfession.POI_ID,
            EurekaConfig.SERVER.crewmanHelmPoiTickets,
            EurekaConfig.SERVER.crewmanHelmPoiRange,
            *CrewProfession.helmBlocks()
        )

        // The listings themselves are vanilla and live in :common; only this registration call needs the API.
        for (level in CrewTrades.MIN_LEVEL..CrewTrades.MAX_LEVEL) {
            TradeOfferHelper.registerVillagerOffers(CrewProfession.PROFESSION_KEY, level) { factories ->
                factories.addAll(CrewTrades.listings(level))
            }
        }

        CrewAttachments.install()
        registerHeartOnHelm()

        // Markers are a per-player toggle held on the server, so a player who logs out with them up has to be
        // forgotten. Otherwise their next session starts with the server believing markers are already showing
        // and their first press toggles OFF -- a key that appears to do nothing.
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            CrewMarkers.forget(handler.player.uuid)
        }
    }

    /**
     * The crouching half of "offer a Heart of the Sea to a helm". The standing half is
     * `ShipHelmBlock.useItemOn`; see `CrewBerths` for why one gesture needs two arms.
     *
     * Fires at the HEAD of `ServerPlayerGameMode.useItemOn`, ahead of the check that skips block interaction
     * for a crouching player with a full hand -- which is the only point at which a crouched click on a helm
     * can still be seen.
     *
     * Scoped as tightly as it can be, because this callback runs for EVERY block a player right-clicks: the
     * crouch test and the item test are both cheap and both come before the world read, and a non-heart click
     * costs two field reads. Deliberately server-only. The client fires the same event, but the berth count
     * lives on the server, and the client sends the interaction packet regardless of what it decided locally.
     */
    private fun registerHeartOnHelm() {
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            // Standing clicks are the block's own business -- handling them here as well would spend two
            // hearts on one press.
            if (level.isClientSide || !player.isSecondaryUseActive) return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            if (stack.isEmpty) return@register InteractionResult.PASS
            val pos = hit.blockPos
            if (level.getBlockState(pos).block !is ShipHelmBlock) return@register InteractionResult.PASS
            CrewBerths.offerHeart(level, pos, player, stack)
        }
    }
}
