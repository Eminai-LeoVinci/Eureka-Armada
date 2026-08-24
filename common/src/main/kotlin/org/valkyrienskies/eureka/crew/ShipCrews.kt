package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.util.StringUtil
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaGroup
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.pirate.PirateHelm
import org.valkyrienskies.eureka.shipwright.ShipwrightProfession
import org.valkyrienskies.mod.common.executeIf
import org.valkyrienskies.mod.common.getShipMountedTo
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider
import java.util.UUID

/**
 * Sneak+C: sign a villager on, read a ship's articles off its wheel, or pick out the crew on the deck you are
 * standing on.
 *
 * Berths are the captain's and are counted per SHIP. With eight of them you can crew one hull to six and
 * another to eight; it is a limit on any one vessel at a time, not a global budget. That is why nothing here
 * keeps a running total: the count is the answer to a question asked about a particular ship, and it is
 * computed on the spot every time it is asked.
 *
 * The membership itself lives on the ship's crew-station helm -- see [CrewRoster] and [CrewStations] -- so a
 * ship's crew is the INTERSECTION of that roster with the villagers actually standing on that ship. A crew
 * member is someone who is both signed on and here.
 *
 * What a crew member DOES is still deliberately nothing. This is the roster and the recruiting; duties come
 * later, and building them on a roster that already works beats guessing at both at once.
 */
object ShipCrews {

    private val logger by org.valkyrienskies.mod.util.logger()

    /**
     * One key, three meanings, arbitrated here rather than on the client.
     *
     * The order is by how deliberate each aim is. A villager under the crosshair is unmistakably a choice about
     * that villager. A wheel under the crosshair is a choice about that ship's articles. Standing somewhere is
     * merely where you happen to be, so it is the fallback -- and the one that answers "which of these
     * villagers are mine" by marking them, rather than by making you read a list and match names.
     */
    @JvmStatic
    fun gesture(level: ServerLevel, player: ServerPlayer) {
        val villager = lookedAtVillager(level, player)
        if (villager != null) {
            toggleRecruit(level, player, villager)
            return
        }
        val helm = lookedAtHelm(level, player)
        if (helm != null) {
            showArticles(level, player, helm)
            return
        }
        markCrewOnDeck(level, player)
    }

    /**
     * The same key with the crew-all modifier held (CTRL+Sneak+C), and the controller's crouch + D-pad Left.
     *
     * Reads exactly as [gesture] does, in the same most-deliberate-first order, and differs in one place: the
     * fallback. Where the plain key marks the crew you already have, this signs on the crew you do not --
     * everyone standing in the ship's influence. Aiming remains an override, because pointing at somebody is
     * always a statement about that somebody: a villager under the crosshair is still toggled one at a time,
     * and a wheel under it still opens the articles.
     *
     * That is why the pad and the keyboard send the same action rather than two. A controller cannot tell you
     * what it is looking at any more than a keyboard can -- only the server's raycast knows -- so "hire
     * everyone, unless I am pointing at someone in particular" has to be one decision made in one place.
     */
    @JvmStatic
    fun gestureAll(level: ServerLevel, player: ServerPlayer) {
        val villager = lookedAtVillager(level, player)
        if (villager != null) {
            toggleRecruit(level, player, villager)
            return
        }
        val helm = lookedAtHelm(level, player)
        if (helm != null) {
            showArticles(level, player, helm)
            return
        }
        hireEveryoneAboard(level, player)
    }

    // region recruiting

    private fun toggleRecruit(level: ServerLevel, player: ServerPlayer, villager: Villager) {
        // The berth limit is a fact about a SHIP, and the articles are kept on a ship's wheel, so both questions
        // have the same precondition: the villager has to be standing on one.
        val ship = shipUnder(level, villager)
        if (ship == null) {
            PathMessages.send(player, "Walk them aboard first -- crew are signed on at the wheel.", PathMessages.Kind.ERROR)
            return
        }

        val station = CrewStations.stationOf(level, ship)
        if (station == null) {
            PathMessages.send(
                player,
                "${ShipCrew.name(ship)} has no wheel keeping articles. Look at a helm and press the crew key.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val ledger = CrewLedger.get(level.server)
        // A wheel that still carries a pre-ledger roster hands it over before anything is read off the ledger,
        // so crew signed on under the old scheme are already present when the checks below run.
        ledger.adoptLegacy(level.server, station)
        val existing = ledger.crewOf(villager.uuid)?.let { ledger.crew(it) }

        // Discharging works from ANY wheel, not just the one they signed at. Their own wheel may be at the
        // bottom of the sea, and a villager who could never be released would be a villager nobody could ever
        // use again -- the one way this rule could strand something permanently.
        if (existing != null && existing.captain == player.uuid) {
            // Both of these have to happen before the entry is struck out: the berth is what says which
            // default name is being handed back, and the name is what the message calls them by.
            val paidOff = CrewNames.displayName(villager)
            CrewNames.clearDefault(villager, ledger.slotOf(villager.uuid))
            ledger.payOff(villager.uuid)
            // No profession change on the way out either, for the same reason there is none on the way in. A
            // Crewman who is paid off keeps the job they took at the wheel; if the captain wants them out of
            // the role as well, breaking or moving the helm is what does it -- the same as any workstation.
            PathMessages.send(
                player,
                "Paid off $paidOff, a ${professionName(villager)}, from ${existing.name}.",
                PathMessages.Kind.GOOD
            )
            return
        }

        if (existing != null) {
            PathMessages.send(
                player,
                "That one already sails with ${existing.name}, under another captain.",
                PathMessages.Kind.ERROR
            )
            return
        }

        if (villager.isBaby) {
            PathMessages.send(player, "They're too young to sign on.", PathMessages.Kind.ERROR)
            return
        }

        // Below the discharge path on purpose: a shipwright signed on before the rule changed can always be
        // paid off, whatever the config says now.
        if (!EurekaConfig.SERVER.shipwrightCrew &&
            villager.villagerData.profession().`is`(ShipwrightProfession.PROFESSION_KEY)
        ) {
            PathMessages.send(
                player,
                "A shipwright's place is the bench, not a berth -- they will not sign on.",
                PathMessages.Kind.ERROR
            )
            return
        }

        // NOTE: nothing below touches the villager's profession, and that is the rule now. See the note above
        // nameTheCrew for why signing the articles and taking a job are two different things.
        //
        // A wheel with no crew of this captain's on it raises one here rather than refusing. This is the first
        // moment a crew is actually needed -- minting one at placement would burn a name on every throwaway
        // test helm, and would give a three-wheeled ship three separate crews.
        val crew = crewAt(level, player, station)
        val crewName = crew.name

        val slots = CrewData.slots(player)
        val signed = ledger.berths(crew.id).size
        if (signed >= slots) {
            PathMessages.send(
                player,
                "No berth for them -- $crewName already musters $signed of your $slots.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val name = signOn(ledger, crew.id, villager)
        PathMessages.send(
            player,
            "Signed on $name, a ${professionName(villager)}, " +
                "to $crewName. Crew: ${signed + 1}/$slots.",
            PathMessages.Kind.GOOD
        )
    }

    /**
     * Write one villager onto crew [crewId]'s articles, and return the name they now answer to.
     *
     * The berth number is an identity, not a reservation: it names them and fixes their row in the manifest,
     * and it is deliberately NOT what the crew limit is counted from -- so the caller checks for room before
     * calling, and this never refuses.
     */
    private fun signOn(ledger: CrewLedger, crewId: UUID, villager: Villager): String {
        val berth = ledger.freeSlot(crewId, EurekaConfig.SERVER.crewSlotsMax)
        CrewNames.applyDefault(villager, berth)
        // Written down from the moment they sign on, so a crew member is recoverable even if the very next
        // thing that happens is their chunk unloading. The copy is refreshed whenever they are next in hand.
        ledger.sign(crewId, villager.uuid, berth, CrewNames.displayName(villager), CrewSnapshot.capture(villager))
        return CrewNames.displayName(villager)
    }

    /**
     * Sign on everybody standing in the ship's influence at once.
     *
     * ## Why the whole influence, and not just the deck
     * The influence border is the region that MOVES with the ship -- anyone inside it is carried along when
     * she sails, which is the only definition of "aboard" that a player can actually see (VS2 draws it) and
     * the only one that matches what happens when the ship gets under way. Someone standing on the gunwale or
     * a step off the stern is coming with you whether they signed anything or not, so they are exactly who
     * this should be offering a berth to. [CrewMuster.villagersIn] already sweeps that region: its
     * `DECK_MARGIN` is two blocks a face, which is what `influenceExtend` defaults to.
     *
     * ## One-directional, unlike the single-villager gesture
     * Pointing at one villager TOGGLES them, because you are unmistakably talking about that one. This hires
     * and never pays off: a gesture that signed on some and dismissed others in the same press would be
     * impossible to predict from the outside, and undoing it would mean pressing it again and getting the
     * exact inverse. Anyone already on the articles is simply left where they are.
     *
     * Everything it declined to do is counted and reported, because a captain who asked for "everyone" needs
     * to know who did not come -- particularly the ones left ashore for want of a berth.
     */
    private fun hireEveryoneAboard(level: ServerLevel, player: ServerPlayer) {
        val ship = shipUnder(level, player)
        if (ship == null) {
            PathMessages.send(
                player,
                "Stand aboard the ship you are crewing -- this signs on everyone she carries.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val station = CrewStations.stationOf(level, ship)
        if (station == null) {
            PathMessages.send(
                player,
                "${ShipCrew.name(ship)} has no wheel keeping articles. Look at a helm and press the crew key.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val ledger = CrewLedger.get(level.server)
        ledger.adoptLegacy(level.server, station)

        val crew = crewAt(level, player, station)
        val crewName = crew.name

        val slots = CrewData.slots(player)
        var signed = ledger.berths(crew.id).size

        var hired = 0
        var alreadyOurs = 0
        var spokenFor = 0
        var tooYoung = 0
        var atTheBench = 0
        var noBerth = 0

        for (villager in CrewMuster.villagersIn(level, ship.worldAABB)) {
            val existing = ledger.crewOf(villager.uuid)
            if (existing != null) {
                if (ledger.crew(existing)?.captain == player.uuid) alreadyOurs++ else spokenFor++
                continue
            }
            if (villager.isBaby) {
                tooYoung++
                continue
            }
            if (!EurekaConfig.SERVER.shipwrightCrew &&
                villager.villagerData.profession().`is`(ShipwrightProfession.PROFESSION_KEY)
            ) {
                atTheBench++
                continue
            }
            // Checked per villager rather than once up front: the count climbs as this loop fills berths, and
            // the moment it meets the ceiling everyone left is left ashore.
            if (signed >= slots) {
                noBerth++
                continue
            }
            signOn(ledger, crew.id, villager)
            signed++
            hired++
        }

        val skipped = buildList {
            if (alreadyOurs > 0) add("$alreadyOurs already signed")
            if (spokenFor > 0) add("$spokenFor under another captain")
            if (tooYoung > 0) add("$tooYoung too young")
            if (atTheBench > 0) add("$atTheBench needed at the bench")
            if (noBerth > 0) add("$noBerth left for want of a berth")
        }
        val tail = if (skipped.isEmpty()) "" else " (${skipped.joinToString(", ")})"

        when {
            hired > 0 -> PathMessages.send(
                player,
                "Signed on $hired ${if (hired == 1) "hand" else "hands"} to $crewName. " +
                    "Crew: $signed/$slots.$tail",
                PathMessages.Kind.GOOD
            )
            skipped.isEmpty() -> PathMessages.send(
                player,
                "Nobody aboard ${ShipCrew.name(ship)} to sign on.",
                PathMessages.Kind.WARN
            )
            else -> PathMessages.send(
                player,
                "Nobody new signed on$tail.",
                PathMessages.Kind.WARN
            )
        }
    }

    /**
     * ## Signing the articles does not give anybody a job, and never did anybody a favour by trying
     *
     * Recruiting used to set the Crewman profession on any villager who was unemployed, because the helm had
     * been taken OUT of `minecraft:acquirable_job_site` -- it carries 32 tickets against an ordinary bench's
     * one, so as an acquirable site it was the most claimable job for a long way in every direction and hired
     * every villager who briefly lost their own workstation.
     *
     * That traded one problem for a worse one. It made "sign on" and "change career" the same gesture, so the
     * only villagers who could join a crew without being altered were ones who already had a trade -- and the
     * unemployed, who are exactly who you recruit, were quietly converted. A crew is meant to be *people*: a
     * blacksmith and a librarian sail as a blacksmith and a librarian.
     *
     * So the two are separated properly. The tag is restored, which makes the helm the one and only thing that
     * grants Crewman -- a villager walks to a wheel and takes the job, the way every other profession in the
     * game is taken. Signing the articles is a line in the [CrewLedger] and nothing else, and paying somebody
     * off is the same line struck out: neither reads or writes a profession.
     *
     * The known cost is the one the tag comment records. A helm out-hires nearby benches, and
     * `crewmanHelmPoiTickets` / `crewmanHelmPoiRange` are the dials for that.
     */
    /**
     * The crew [player] keeps at this wheel, raising a fresh one if they have never kept any here.
     *
     * The wheel holds a binding per captain (see `ShipHelmBlockEntity.crewBindings`), so this is a map read in
     * the ordinary case. A binding pointing at a crew that no longer exists -- everyone aboard was paid off,
     * and the ledger dropped the empty crew -- is treated as no binding at all and replaced, rather than
     * leaving the captain unable to recruit at a wheel that looks perfectly normal.
     *
     * A new crew is ANNOUNCED rather than raised quietly. The name is not load-bearing any more -- the wheel
     * remembers which crew it calls, so nobody has to retype anything -- but it is what the captain will see
     * in the helm menu's list, and a crew that appeared in that list unannounced is a puzzle.
     */
    private fun crewAt(
        level: ServerLevel,
        player: ServerPlayer,
        station: ShipHelmBlockEntity
    ): CrewLedger.Crew {
        val ledger = CrewLedger.get(level.server)
        // Through bindingFor, so a wheel from before crew ids adopts its name-keyed crew here rather than
        // raising a second one beside it and leaving the first unreachable.
        ledger.bindingFor(station, player.uuid)?.let { bound -> ledger.crew(bound)?.let { return it } }

        val generated = CrewNameGenerator.generate(level.random) { candidate ->
            ledger.nameTaken(player.uuid, candidate)
        }
        val crew = ledger.create(player.uuid, generated)
        station.bindCrew(player.uuid, crew.id)
        PathMessages.send(
            player,
            "This wheel kept no crew of yours, so they are now $generated. " +
                "Rename them from the crew menu, and pick them from the helm's crew list.",
            PathMessages.Kind.WARN
        )
        return crew
    }

    // endregion

    // region the articles

    /**
     * Open the crew manifest for the ship this [helm] belongs to.
     *
     * Reading the articles off the wheel is the natural gesture -- it is where they are kept -- and it also
     * works from the quay, which standing on the deck does not.
     *
     * A wheel on a ship with no crew station CLAIMS the role here rather than reporting an error. That is the
     * one action a player would otherwise have no way to perform, and it is exactly what someone aiming at a
     * wheel and asking about crew is trying to do.
     *
     * The manifest is a screen, and [printArticles] below is what happens when it cannot be opened -- a client
     * the payload cannot reach still gets the roster, in chat, rather than a key that does nothing.
     */
    /**
     * The helm menu's book: open the articles exactly as the crew key does, for a player whose only
     * credential is an open helm container. The helm menu's own validity check is unconditional -- a
     * container over a shipyard block cannot use vanilla's distance rule -- so this is the one road into
     * the articles that has to carry its own reach guard: arm's length of the wheel's WORLD position, or
     * standing on the ship (armada included), the same gate every manifest action applies.
     */
    @JvmStatic
    fun openArticles(level: ServerLevel, player: ServerPlayer, helm: ShipHelmBlockEntity) {
        if (!withinBookReach(level, player, helm)) return
        showArticles(level, player, helm)
    }

    /**
     * The manifest's Back button: reopen the helm menu the book was pressed in. The same door swings both
     * ways -- and carries the same guard, because this request too arrives from a plain screen with no
     * container behind it to vouch for anything.
     */
    @JvmStatic
    /**
     * Call a crew to the ship the wheel at [helm] steers, charging passage. The helm menu's Summon button.
     *
     * Everything that decides whether they come lives in [CrewSummon.bring]; this is the gate in front of it.
     * Reach is the same test the crew book uses, because it is the same gesture -- a captain standing at their
     * own wheel -- and a summon arriving from anywhere else is a packet nobody typed.
     *
     * The ship has to EXIST. Calling a crew to a hull that has not been assembled has nowhere to put them: the
     * wheel is a block on the ground, there is no deck to stand on and no holds to pay out of. That case is
     * covered instead by picking the crew and pressing Assemble, which brings them aboard as the ship is made.
     */
    fun summonCrew(level: ServerLevel, player: ServerPlayer, helm: Long, crewId: UUID) {
        val wheel = level.getBlockEntity(BlockPos.of(helm)) as? ShipHelmBlockEntity ?: return
        if (!withinBookReach(level, player, wheel)) return
        if (PirateHelm.gated(wheel.blockState)) {
            PirateHelm.deny(player)
            return
        }
        // The articles hang from ONE wheel per ship, and that is the wheel a crew is bound to. Summoning at a
        // spare helm would bind the crew to a block nothing else reads, and they would not be found again.
        val ship = CrewStations.shipOf(level, wheel)
        if (ship == null) {
            PathMessages.send(
                player,
                "Assemble her first -- a crew needs a deck to stand on.",
                PathMessages.Kind.ERROR
            )
            return
        }
        // An assembled wheel is filed at SHIPYARD coordinates, so its own blockPos is exactly the address the
        // muster needs to place people around. No conversion, and nothing to get one block wrong.
        val station = CrewStations.stationOf(level, ship) ?: wheel

        val refused = CrewSummon.bring(level, player, ship, station, crewId, station.blockPos.asLong())
        if (refused != null) {
            PathMessages.send(player, refused, PathMessages.Kind.ERROR)
            return
        }
        // The list is a snapshot and does not poll, so the row that just became "aboard" has to be re-sent or
        // the menu goes on offering to charge for a crew already standing on the deck.
        // Built against the wheel the captain is ACTUALLY looking at, not the articles wheel -- the roll is
        // keyed on it, and a roll keyed on a different helm is one the menu quietly ignores.
        CrewRoll.sender(player, CrewRoll.build(player, wheel))
    }

    /**
     * Send [player] one crew's articles for the Crews tab, whether or not that crew is on this ship.
     *
     * Reach-guarded on the WHEEL the book was opened at, not on the crew: the crew may be anywhere, and the
     * thing being authorised is a captain reading their own papers at their own wheel.
     */
    fun requestCrewRoster(level: ServerLevel, player: ServerPlayer, helm: Long, crewId: UUID) {
        val wheel = level.getBlockEntity(BlockPos.of(helm)) as? ShipHelmBlockEntity ?: return
        if (!withinBookReach(level, player, wheel)) return
        CrewRoll.roster(level, player, crewId)?.let { CrewRoll.rosterSender(player, it) }
    }

    /** Rename a crew by id, from the Crews tab. Works on a crew that is nowhere near this ship. */
    fun renameCrew(level: ServerLevel, player: ServerPlayer, helm: Long, crewId: UUID, raw: String) {
        val wheel = level.getBlockEntity(BlockPos.of(helm)) as? ShipHelmBlockEntity ?: return
        if (!withinBookReach(level, player, wheel)) return
        val ledger = CrewLedger.get(level.server)
        val crew = ledger.crew(crewId) ?: return
        if (crew.captain != player.uuid) return
        val cleaned = StringUtil.filterText(raw).trim().take(HelmNames.MAX_NAME_LENGTH)
        if (cleaned.isEmpty()) return
        ledger.rename(crewId, cleaned)
        CrewRoll.sender(player, CrewRoll.build(player, wheel))
        CrewRoll.roster(level, player, crewId)?.let { CrewRoll.rosterSender(player, it) }
    }

    /**
     * Strike a crew off and destroy every member, wherever they are. There is no way back from this.
     *
     * The captain's "pay them off", applied to a whole crew at once, and it is deliberately final: the
     * articles go, the entities go, and anybody who cannot be reached right now is TOMBSTONED so they are
     * removed the moment their chunk loads. A crew half-deleted -- struck off the books but still walking a
     * deck somewhere with nobody's name on them -- would be worse than either outcome.
     *
     * Seated gunners are unseated first, while they still exist to be dismounted, so no orphan seat is left
     * for the reconcile sweep to find. The books are settled BEFORE the entities are touched, so no berth can
     * be left pointing at a villager that has already gone.
     */
    fun disbandCrew(level: ServerLevel, player: ServerPlayer, helm: Long, crewId: UUID) {
        val wheel = level.getBlockEntity(BlockPos.of(helm)) as? ShipHelmBlockEntity ?: return
        if (!withinBookReach(level, player, wheel)) return
        val ledger = CrewLedger.get(level.server)
        val crew = ledger.crew(crewId) ?: return
        if (crew.captain != player.uuid) return

        val name = crew.name
        val berths = ledger.disband(crewId)
        var destroyed = 0
        var unreachable = 0
        for (berth in berths) {
            GunStations.unseat(level, berth.villager)
            val villager = CrewMuster.findAnywhere(level.server, berth.villager)
            if (villager == null) {
                ledger.tombstone(berth.villager)
                unreachable++
                continue
            }
            // `discard`, not `kill`: nobody died. There is nothing to drop and no experience to award -- the
            // crew simply stops existing, which is what deleting a crew was asked to mean.
            villager.discard()
            destroyed++
        }
        // Any wheel still pointing at them now points at nothing, which resolves to "no crew" everywhere and
        // needs no sweep of its own -- see CrewLedger.bindingFor.
        logger.info("[crew] disband crew='$name' berths=${berths.size} destroyed=$destroyed away=$unreachable")
        PathMessages.send(
            player,
            if (unreachable == 0) "$name are gone -- $destroyed struck off the articles."
            else "$name are gone -- $destroyed struck off, $unreachable will be when their chunks load.",
            PathMessages.Kind.WARN
        )
        CrewRoll.sender(player, CrewRoll.build(player, wheel))
    }

    fun openHelm(level: ServerLevel, player: ServerPlayer, helm: ShipHelmBlockEntity) {
        if (!withinBookReach(level, player, helm)) return
        // Pirate gate, door 10 of 14: the manifest's Back button is its own road to the menu.
        if (PirateHelm.gated(helm.blockState)) {
            PirateHelm.deny(player)
            return
        }
        // A TICK later, not now. This is called with the manifest still open, and opening a menu on top of
        // one that is still closing loses the new menu's opening sync -- the helm came back with every
        // readout blank, no ship name and "No crew", looking for all the world like an unassembled wheel,
        // and closing it and walking back to the same wheel put it right. One tick of daylight between the
        // close and the open is all it wants.
        val server = level.server
        val openAt = server.overworld().gameTime + 1
        server.executeIf({ server.overworld().gameTime >= openAt }) { player.openMenu(helm) }
    }

    /** Arm's length of the wheel's WORLD position, or standing on its ship (armada included). */
    private fun withinBookReach(level: ServerLevel, player: ServerPlayer, helm: ShipHelmBlockEntity): Boolean {
        val ship = CrewStations.shipOf(level, helm)
        val at = helm.blockPos
        val world = ship?.shipToWorld?.transformPosition(Vector3d(at.x + 0.5, at.y + 0.5, at.z + 0.5))
            ?: Vector3d(at.x + 0.5, at.y + 0.5, at.z + 0.5)
        val dx = player.x - world.x
        val dy = player.y - world.y
        val dz = player.z - world.z
        if (dx * dx + dy * dy + dz * dz <= BOOK_REACH_SQ) return true
        return ship != null &&
            ShipCrew.standingOn(player)?.let { it in ArmadaGroup.idsOf(level, ship) } == true
    }

    private fun showArticles(level: ServerLevel, player: ServerPlayer, helm: ShipHelmBlockEntity) {
        // Pirate gate, door 8 of 14: Sneak+C at the wheel and the helm menu's book both land here.
        if (PirateHelm.gated(helm.blockState)) {
            PirateHelm.deny(player)
            return
        }
        val ship = CrewStations.shipOf(level, helm)
        if (ship == null) {
            PathMessages.send(player, "Assemble the ship first -- articles are kept by a ship's wheel.", PathMessages.Kind.ERROR)
            return
        }

        var station = CrewStations.stationOf(level, ship)
        if (station == null) {
            if (!CrewStations.claim(level, helm)) {
                PathMessages.send(player, "This wheel isn't part of a ship.", PathMessages.Kind.ERROR)
                return
            }
            station = helm
            PathMessages.send(
                player,
                "This wheel now keeps ${ShipCrew.name(ship)}'s articles. Sign your crew on.",
                PathMessages.Kind.GOOD
            )
        } else if (station !== helm) {
            // Every helm steers; only one holds the crew. Saying so here is the whole explanation of why a
            // second wheel did not give them more berths.
            PathMessages.send(
                player,
                "${ShipCrew.name(ship)}'s articles are kept at another wheel; this one only steers.",
                PathMessages.Kind.GOOD
            )
        }

        if (CrewManifest.sender(player, CrewManifest.build(level, player, station))) return
        printArticles(level, player, ship, station)
    }

    /**
     * The roster in chat -- what a client that cannot receive the manifest payload gets instead.
     *
     * This was the whole of the crew key's second meaning before the manifest existed, and it is kept rather
     * than deleted because a key that silently does nothing is worse than a key that answers plainly.
     */
    private fun printArticles(
        level: ServerLevel, player: ServerPlayer, ship: LoadedServerShip, station: ShipHelmBlockEntity
    ) {
        val slots = CrewData.slots(player)
        // One query answers both questions -- who is signed on, and who is merely standing on the deck.
        val villagers = ShipCrew.villagersAboard(level, ship)
        val crew = crewOf(level, ship, station, player.uuid)
        val free = (slots - crew.size).coerceAtLeast(0)

        // The detail goes to CHAT, not the stacking HUD: PathHud holds six entries and drops the oldest, so a
        // roster of any length would eat its own head and bury whatever else was on screen. The one-line
        // summary still goes to the HUD, so the answer is readable without opening chat -- the same split
        // ArmadaCommand and PathCommand already make.
        val msg = Component.literal("Crew of ${ShipCrew.name(ship)}").withStyle(ChatFormatting.AQUA)
        msg.append(
            Component.literal("\n  ${crew.size} signed on -- ${crew.size}/$slots berths, $free free")
                .withStyle(ChatFormatting.GRAY)
        )
        if (crew.isEmpty()) {
            msg.append(Component.literal("\n    (nobody signed on)").withStyle(ChatFormatting.GRAY))
        } else {
            crew.groupingBy { professionName(it) }.eachCount()
                .entries.sortedByDescending { it.value }
                .forEach { (profession, count) ->
                    msg.append(Component.literal("\n    $count x $profession").withStyle(ChatFormatting.GRAY))
                }
        }

        // Everyone else on deck: passengers, other captains' crew, villagers who wandered aboard. Worth saying,
        // because "there are villagers here but none of them are yours" is otherwise indistinguishable from
        // "there are no villagers here". A ship may carry any number of them; only the crew count against
        // berths.
        val unsigned = villagers.size - crew.size
        if (unsigned > 0) {
            msg.append(
                Component.literal("\n  $unsigned other villager${if (unsigned == 1) "" else "s"} aboard")
                    .withStyle(ChatFormatting.DARK_GRAY)
            )
        }
        player.sendSystemMessage(msg)

        PathMessages.send(player, "Crew: ${crew.size}/$slots aboard ${ShipCrew.name(ship)}.", PathMessages.Kind.GOOD)
    }

    // endregion

    // region marking the deck

    /**
     * Mark this ship's crew so they can be told apart at a glance.
     *
     * The list is deliberately scoped to the deck the player is standing on. Crews are per ship and a player is
     * free to hop between hulls, so "who is crew" only has an answer relative to somewhere -- and here is the
     * only place the gesture can mean.
     *
     * Toggles, like the route-hide key: pressing it again puts the markers away rather than making the player
     * wait one out. Recruiting someone new while they are up does not update them; press twice to refresh.
     */
    private fun markCrewOnDeck(level: ServerLevel, player: ServerPlayer) {
        if (CrewMarkers.clearIfShowing(player)) {
            PathMessages.send(player, "Crew markers off.", PathMessages.Kind.GOOD)
            return
        }

        val ship = shipUnder(level, player)
        if (ship == null) {
            PathMessages.send(
                player,
                "Look at a villager to sign them on, or at a wheel to read its articles.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val station = CrewStations.stationOf(level, ship)
        if (station == null) {
            PathMessages.send(
                player,
                "${ShipCrew.name(ship)} has no wheel keeping articles. Look at a helm and press the crew key.",
                PathMessages.Kind.ERROR
            )
            return
        }

        val crew = crewOf(level, ship, station, player.uuid)
        if (crew.isEmpty()) {
            PathMessages.send(
                player,
                "Nobody signed on aboard ${ShipCrew.name(ship)}. Look at a villager to sign them on.",
                PathMessages.Kind.ERROR
            )
            return
        }

        CrewMarkers.show(player, crew.map { it.id })
        PathMessages.send(
            player,
            "Marked ${crew.size} crew aboard ${ShipCrew.name(ship)}.",
            PathMessages.Kind.GOOD
        )
    }

    // endregion

    // region lookups

    /**
     * [owner]'s crew currently aboard [ship] -- the ones there is something to mark or count on this deck.
     *
     * Membership is the ledger's, being aboard is the world's, and this is the intersection. An unnamed wheel
     * names no crew at all, so it has nobody: there is no key to look them up under.
     */
    private fun crewOf(
        level: ServerLevel,
        ship: LoadedServerShip,
        station: ShipHelmBlockEntity,
        owner: UUID
    ): List<Villager> {
        val crewId = station.boundCrew(owner) ?: return emptyList()
        val ledger = CrewLedger.get(level.server)
        return ShipCrew.villagersAboard(level, ship).filter { ledger.crewOf(it.uuid) == crewId }
    }

    /**
     * The villager under the crosshair, or null.
     *
     * There is no entity raycast anywhere else in this mod -- `ShipFollows.lookedAtShip` picks BLOCKS -- so
     * this is built from the two vanilla halves. `Entity.pick` runs first purely as the occlusion clamp: VS2
     * wraps `Level.clip`, so it stops on a ship's hull as well as on terrain, and you cannot sign on someone
     * through a bulkhead.
     *
     * A villager on a deck stands at ordinary WORLD coordinates -- VS2 drags entities rather than parking them
     * in the shipyard -- so the ray needs no ship transform, whether the player is aboard or on the quay.
     */
    private fun lookedAtVillager(level: ServerLevel, player: ServerPlayer): Villager? {
        val eye = player.eyePosition
        val far = eye.add(player.getViewVector(1.0f).scale(REACH))

        val blocked = player.pick(REACH, 1.0f, false)
        val limit = if (blocked.type != HitResult.Type.MISS) blocked.location else far

        val hit = ProjectileUtil.getEntityHitResult(
            level, player, eye, limit, AABB(eye, limit).inflate(1.0),
            { it is Villager && it.isAlive }, 0.0f
        ) ?: return null
        return hit.entity as? Villager
    }

    /**
     * The ship's-wheel block entity under the crosshair, of any wood, or null.
     *
     * Reach here is the player's own BLOCK interaction range rather than the recruiting reach, so "close enough
     * to open its manifest" is the same distance as "close enough to mount it or break it". A wheel you cannot
     * touch does not answer questions about its crew.
     *
     * VS2 wraps `Level.clip`, so a hit on a ship's block reports its SHIPYARD position -- which is exactly the
     * coordinate the block entity lives at, so this needs no transform of its own. The same property is what
     * `ShipFollows.lookedAtShip` relies on.
     */
    private fun lookedAtHelm(level: ServerLevel, player: ServerPlayer): ShipHelmBlockEntity? {
        val hit = player.pick(player.blockInteractionRange(), 1.0f, false)
        if (hit.type != HitResult.Type.BLOCK || hit !is BlockHitResult) return null
        return level.getBlockEntity(hit.blockPos) as? ShipHelmBlockEntity
    }

    /**
     * The ship an entity is standing on or seated in, not normalized to an armada parent.
     *
     * `ShipCrew.villagersAboard` tests membership against the whole group anyway, so promoting a child to its
     * parent here would only discard which deck they are actually on -- and the roster header is nicer for
     * naming the hull you are stood on rather than the flagship it happens to be welded to.
     */
    private fun shipUnder(level: ServerLevel, entity: Entity): LoadedServerShip? {
        val world = level.shipObjectWorld
        return (getShipMountedTo(entity) as? LoadedServerShip)
            ?: (entity as? IEntityDraggingInformationProvider)
                ?.draggingInformation?.lastShipStoodOn
                ?.let { world.loadedShips.getById(it) }
    }

    /** The localised profession name, which reads "Crewman" for ours and "Farmer" for a vanilla one. */
    private fun professionName(villager: Villager): String =
        villager.villagerData.profession().value().name().string

    // endregion

    /**
     * How far you can sign someone on from. A little past vanilla's block reach -- close enough that you are
     * plainly addressing a particular person, and not so close that you must stand on their toes.
     */
    private const val REACH = 6.0

    /** The book's reach: the manifest actions' arm's-length rule, generous next to [REACH]'s aim gate. */
    private const val BOOK_REACH_SQ = 16.0 * 16.0
}
