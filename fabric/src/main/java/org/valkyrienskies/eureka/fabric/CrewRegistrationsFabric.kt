package org.valkyrienskies.eureka.fabric

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.world.entity.npc.villager.Villager
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.valkyrienskies.eureka.crew.CrewNames
import org.valkyrienskies.eureka.path.PathMessages
// `object` is a Kotlin keyword, and Fabric's object-builder API has it as a package segment -- hence the
// backticks. Without them this is a parse error, not an unresolved import.
import net.fabricmc.fabric.api.`object`.builder.v1.trade.TradeOfferHelper
import net.fabricmc.fabric.api.`object`.builder.v1.world.poi.PointOfInterestHelper
import net.minecraft.world.InteractionResult
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.block.HelmMark
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.crew.CrewBerths
import org.valkyrienskies.eureka.crew.CrewLedger
import org.valkyrienskies.eureka.crew.CrewMarkers
import org.valkyrienskies.eureka.crew.CrewProfession
import org.valkyrienskies.eureka.crew.CrewTrades
import org.valkyrienskies.eureka.shipwright.ShipwrightProfession

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
        //
        // Pirate gate, door 13 of 14: only NORMAL-marked states are job sites. A pirate's wheel must never
        // employ a villager, and TAKEN is excluded with it -- POI membership is baked here once and cannot
        // follow the TAKEN->PIRATE flip when the crew respawns, so a claim made in that window would leave a
        // stale ticket on a hostile ship. Nothing is lost: conquest ends with a fresh NORMAL helm placed.
        PointOfInterestHelper.register(
            CrewProfession.POI_ID,
            EurekaConfig.SERVER.crewmanHelmPoiTickets,
            EurekaConfig.SERVER.crewmanHelmPoiRange,
            CrewProfession.helmBlocks()
                .flatMap { it.stateDefinition.possibleStates }
                .filter { it.getValue(EurekaProperties.MARK) == HelmMark.NORMAL }
        )

        // The shipwright's bench: an ORDINARY job site, one ticket, like a lectern. The helm above is the odd
        // one out with its crew-sized count, not this. Registered here rather than in a file of its own for
        // the same reason the helm is -- PointOfInterestHelper walks each block's state definition, so both
        // have to come after EurekaBlocks.register(), and one call site is easier to keep true than two.
        PointOfInterestHelper.register(
            ShipwrightProfession.POI_ID,
            1,
            SHIPWRIGHT_POI_RANGE,
            *ShipwrightProfession.benchBlocks()
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

        registerTombstoneSweep()
        registerDeathAndConversion()
    }

    /**
     * Strike a crew member off the articles when the world takes them, and carry a berth through a conversion.
     *
     * A berth held by somebody who no longer exists is not a harmless stale entry. The manifest lists them, so
     * the row is there; nothing can be read off them, so it has no rank and no trades; and nothing can be done
     * to them, so it cannot even be renamed. It reads exactly like a bug, it occupies a berth the captain has
     * paid a Heart of the Sea for, and until now the only cure was to know it had happened.
     *
     * Two ways to stop being the entity you were, and the rule that covers both is the same one: a berth is
     * held by a living villager or by nobody.
     *  - DEATH frees the berth, and the captain is told. They are owed the news -- a crew member can die a long
     *    way from anyone, and finding out by counting the manifest is not finding out.
     *  - CONVERSION replaces one entity with another. If what walks away is still a villager the berth follows
     *    them, name and number intact, re-keyed WITHOUT a tombstone because the old id is genuinely spent
     *    rather than merely out of reach. If it is a witch or a zombie, they are off the articles: keeping the
     *    berth would leave it held by something the manifest can never show and nobody can ever dismiss, which
     *    is the very thing this exists to prevent.
     */
    private fun registerDeathAndConversion() {
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
            if (entity !is Villager) return@register
            strikeOff(entity)
        }

        ServerLivingEntityEvents.MOB_CONVERSION.register { previous, converted, _ ->
            if (previous !is Villager) return@register
            val server = previous.level().server ?: return@register
            if (converted is Villager) {
                CrewLedger.get(server).rekey(previous.uuid, converted.uuid)
            } else {
                strikeOff(previous)
            }
        }
    }

    /** Free [villager]'s berth and tell their captain, if they held one. */
    private fun strikeOff(villager: Villager) {
        val server = villager.level().server ?: return
        val ledger = CrewLedger.get(server)
        // Read the name BEFORE striking them out: the berth is what the name is filed against, and the message
        // is the only place it is ever said out loud.
        val name = CrewNames.displayName(villager)
        // Read the crew BEFORE paying off: the last berth going takes the crew record with it, and the
        // captain to tell is on that record.
        val crew = ledger.crewOf(villager.uuid)?.let { ledger.crew(it) }
        ledger.payOff(villager.uuid)
        ledger.clearTombstone(villager.uuid)
        server.playerList.getPlayer(crew?.captain ?: return)?.let {
            PathMessages.send(it, "$name is lost. Their berth is free again.", PathMessages.Kind.WARN)
        }
    }

    /**
     * Destroy a crew member who has already been re-signed from the articles.
     *
     * Mustering cannot move a villager in a chunk nobody has loaded, so it rebuilds them from the written copy
     * and gives the copy a new id. The original is still out there. The moment its chunk does load there are
     * two of the same crew member -- and the stale one carries trades a player may already have used, which is
     * a duplication bug rather than a cosmetic one. So the dead id is recorded when the copy is made and the
     * original is removed the instant it appears.
     *
     * `discard` rather than `kill`: this is not a death. There is nothing to drop, no experience to award and
     * nobody to mourn -- the crew member did not die, they are standing on a deck somewhere with a new id.
     *
     * The tombstone is cleared as it fires, so the set only ever holds ids that have genuinely not turned up
     * yet, and a world where every stale copy has been swept carries none at all.
     */
    private fun registerTombstoneSweep() {
        ServerEntityEvents.ENTITY_LOAD.register { entity, level ->
            if (entity !is Villager) return@register
            val ledger = CrewLedger.get(level.server)
            if (!ledger.isTombstoned(entity.uuid)) return@register
            ledger.clearTombstone(entity.uuid)
            entity.discard()
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

    /**
     * How far a villager will wander from its bench to claim it, matching the helm's default.
     *
     * A plain constant rather than a config entry: the helm's range is tunable because a ship's deck varies
     * wildly in size, and a bench is a bench.
     */
    private const val SHIPWRIGHT_POI_RANGE = 1
}
