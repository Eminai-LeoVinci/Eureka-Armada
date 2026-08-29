package org.valkyrienskies.eureka.crew

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaGroup
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.follow.ShipCrew
import java.util.UUID

/**
 * The list of crews a captain can call to a wheel, as the helm menu's dropdown needs it.
 *
 * Built server-side and sent whole, because the client has no ledger and never will: a crew is a row in a
 * `SavedData` on the server, and the only honest way for a screen to list one is to be told. The list is a
 * SNAPSHOT and does not poll -- the same rule the crew manifest follows -- so it is asked for when the menu
 * opens and again after anything that would change it.
 *
 * Only the asking captain's own crews are ever on it. That is the ownership rule made visible rather than
 * merely enforced: a captain cannot call another player's crew, so there is nothing to gain by listing them,
 * and a list of names you are not allowed to pick is worse than no list.
 */
object CrewRoll {

    /**
     * One crew, as a row.
     *
     * [present] is how many of this crew are actually FOUND standing on this hull right now. It is the
     * half the Summon button needs: [aboard] is a fact about the ARTICLES, and a binding outlives a
     * disassembly, a bottling and a relog on purpose, so a wheel goes on naming a crew while every one
     * of them is missing.
     *
     * [aboard] is what makes the row mean something beyond a name: it marks the crew this wheel already
     * keeps, which is the one that is free to call. [fare] is what calling this crew would cost right now,
     * carried rather than recomputed so the screen and the server cannot disagree about the price.
     */
    data class Entry(
        val id: UUID,
        val name: String,
        val heads: Int,
        val aboard: Boolean,
        val present: Int,
        val fare: Int
    )

    /**
     * Every crew [captain] owns, and which of them this wheel keeps. [helm] is the packed wheel position.
     *
     * [shipName] rides along because the helm menu has no other reliable way to learn it. The menu used to
     * read the wheel's own block entity, which is server-authoritative and correct -- but the CLIENT's copy of
     * it goes stale and stays stale: a block-entity update pushes only when the server's value changes, so
     * once the two disagree there is nothing left to correct them, and a wheel that missed one showed the
     * wrong name until the world was reloaded. On a hull with eight helms that meant eight wheels disagreeing
     * about what the ship was called.
     *
     * Sending it here is not a workaround so much as the pattern this screen already follows: the crew list,
     * the manifest and the holds tally all travel by payload for the same reason -- DataSlots carry 16 bits
     * and block entities do not reliably carry anything at all.
     */
    data class Roll(val helm: Long, val shipName: String, val entries: List<Entry>)

    /**
     * How a roll reaches its client. Filled in by the networking layer at registration.
     *
     * The same volatile-hook shim the manifest and the stores tally use: `:common` cannot see the Fabric
     * networking API, and this is the seam that lets it send anyway without either side depending on the
     * other. A no-op default means a roll asked for before registration is dropped rather than thrown.
     */
    @Volatile
    @JvmField
    var sender: (ServerPlayer, Roll) -> Unit = { _, _ -> }

    /** Client seam: ask the server for this wheel's roll. [Long] is the packed wheel position. */
    @Volatile
    @JvmField
    var clientAsk: (Long) -> Unit = { }

    /** Client seam: say which crew the list is showing. Null clears the pick. Costs nothing. */
    @Volatile
    @JvmField
    var clientSelect: (Long, UUID?) -> Unit = { _, _ -> }

    /** Client seam: call this crew to the ship, paying their passage. */
    @Volatile
    @JvmField
    var clientSummon: (Long, UUID) -> Unit = { _, _ -> }

    /**
     * One crew's articles, read-only, for the Crews tab.
     *
     * The rows are the SAME [CrewManifest.Row] the ship's own manifest draws, so the read-only roster is the
     * familiar one rather than a second thing that looks nearly like it. What differs is entirely on the
     * screen: the assignment controls are not built at all.
     */
    data class Roster(val id: UUID, val name: String, val fare: Int, val rows: List<CrewManifest.Row>)

    /** How a roster reaches its client. Filled in by the networking layer, like [sender]. */
    @Volatile
    @JvmField
    var rosterSender: (ServerPlayer, Roster) -> Unit = { _, _ -> }

    /** Client seam: ask for one crew's articles. */
    @Volatile
    @JvmField
    var clientRosterAsk: (Long, UUID) -> Unit = { _, _ -> }

    /** Client seam: strike a crew off and destroy its members. There is no way back from this. */
    @Volatile
    @JvmField
    var clientDisband: (Long, UUID) -> Unit = { _, _ -> }

    /** Client seam: rename a crew by id, whether or not they are on this ship. */
    @Volatile
    @JvmField
    var clientRenameCrew: (Long, UUID, String) -> Unit = { _, _, _ -> }

    /**
     * Draw up the roll for [captain] at [station].
     *
     * The fare is decided here exactly as [CrewSummon.bill] decides it -- free for the crew already aboard,
     * free in creative, otherwise a pearl a head -- so a row that reads "free" is free when the button is
     * pressed. Both read the same config value; neither guesses.
     */
    fun build(captain: ServerPlayer, station: ShipHelmBlockEntity): Roll {
        val server = captain.level().server ?: return Roll(station.blockPos.asLong(), "", emptyList())
        val ledger = CrewLedger.get(server)

        // The binding lives on the wheel that keeps the ARTICLES, which need not be the wheel the captain is
        // standing at: a hull carries as many helms as it likes and only one of them holds the crew. Reading
        // the clicked wheel meant a multi-helm ship answered "no crew" at every wheel but one, and the list
        // opened with nothing selected even while a crew stood on the deck.
        val articles = (captain.level() as? ServerLevel)
            ?.let { level -> CrewStations.shipOf(level, station)?.let { CrewStations.stationOf(level, it) } }
            ?: station
        val bound = ledger.bindingFor(articles, captain.uuid)
        val perHead = if (captain.abilities.instabuild) 0 else EurekaConfig.SERVER.crewPassagePearls

        // Which hulls count as "this ship": the whole welded group, so a crew standing on a child hull
        // reads as present at the flagship's wheel, exactly as gunnery and the roster already treat it.
        val here: Set<Long> = (captain.level() as? ServerLevel)
            ?.let { lvl -> CrewStations.shipOf(lvl, articles)?.let { ArmadaGroup.idsOf(lvl, it) }?.toSet() }
            ?: emptySet()

        val entries = ledger.crewsOf(captain.uuid).map { crew ->
            val heads = crew.berths.size
            val aboard = crew.id == bound
            // Counted by looking, never inferred from the binding: a berth whose villager cannot be found
            // at all, or who is found somewhere else, is NOT present.
            val present = if (here.isEmpty()) 0 else crew.berths.count { berth ->
                CrewMuster.findAnywhere(server, berth.villager)
                    ?.let { ShipCrew.standingOn(it) in here } == true
            }
            Entry(
                id = crew.id,
                name = crew.name,
                heads = heads,
                aboard = aboard,
                present = present,
                fare = if (aboard || perHead <= 0) 0 else heads * perHead
            )
        }
        // What the hull is called, read on the SERVER where it cannot be behind. The station wheel is the
        // MASTER and its name IS the ship's; the slug is the fallback for the beat before assembly settles
        // the name onto it, and for a hull answering to a generated name no wheel has adopted yet.
        val level = captain.level() as? ServerLevel
        val shipName = station.helmName?.string?.takeIf { it.isNotBlank() }
            ?: level?.let { CrewStations.shipOf(it, station) }?.slug?.replace('-', ' ')
            ?: ""

        return Roll(station.blockPos.asLong(), shipName, entries)
    }

    /**
     * The same list for a wheel held in the HAND, which has no position, no articles and no hull.
     *
     * A crew belongs to its CAPTAIN, not to any wheel -- [CrewLedger.crewsOf] is keyed on the player --
     * so the roll is complete even with nothing to build it against. Nobody reads as `aboard`, because
     * there is no deck to be aboard of, and every fare is therefore the plain per-head price.
     */
    fun buildInHand(captain: ServerPlayer): Roll {
        val server = captain.level().server ?: return Roll(CrewManifest.HELM_IN_HAND, "", emptyList())
        val perHead = if (captain.abilities.instabuild) 0 else EurekaConfig.SERVER.crewPassagePearls
        val entries = CrewLedger.get(server).crewsOf(captain.uuid).map { crew ->
            val heads = crew.berths.size
            Entry(
                id = crew.id,
                name = crew.name,
                heads = heads,
                aboard = false,
                // No deck to be aboard of, so nobody counts as present either.
                present = 0,
                fare = if (perHead <= 0) 0 else heads * perHead
            )
        }
        return Roll(CrewManifest.HELM_IN_HAND, "", entries)
    }

    /**
     * One crew's articles for [captain], or null if the crew is gone or is not theirs.
     *
     * The fare quoted here is the plain per-head price, NOT the "free because they are already aboard" one:
     * the Crews tab lists crews against no ship in particular, so what it can honestly say is what moving
     * them costs. The helm menu's own list, which does know which wheel is asking, still marks the crew
     * aboard as free.
     */
    fun roster(level: ServerLevel, captain: ServerPlayer, crewId: UUID): Roster? {
        val crew = CrewLedger.get(level.server).crew(crewId) ?: return null
        if (crew.captain != captain.uuid) return null
        val perHead = if (captain.abilities.instabuild) 0 else EurekaConfig.SERVER.crewPassagePearls
        return Roster(
            id = crew.id,
            name = crew.name,
            fare = (crew.berths.size * perHead).coerceAtLeast(0),
            rows = CrewManifest.rowsOf(level, captain, crewId)
        )
    }
}
