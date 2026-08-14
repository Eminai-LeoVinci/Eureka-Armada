package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.StringUtil
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.trading.MerchantOffer
import org.joml.Vector3d
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.cannon.ShipGuns
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.path.PathMessages
import java.util.UUID

/**
 * The ship's list of who is aboard, and the two things a captain can ask about one of them.
 *
 * The manifest is what SHIFT+C aimed at a wheel opens. It is deliberately a snapshot rather than a live view:
 * everything that can change it -- signing someone on, trading with them, levelling them up -- needs the screen
 * closed first, because the crew key does not fire while a screen is open and trading requires right-clicking
 * the villager. So reopening IS the refresh, and there is no timer, no per-player open-screen bookkeeping on
 * the server, and no packet traffic while a manifest sits on screen.
 *
 * ## Rows are the intersection, not the roster
 * A row is built for each villager who is BOTH on this helm's articles and standing on this ship -- the same
 * `crewOf` intersection the berth count is taken from. That is what stops the manifest from ever disagreeing
 * with the number in the corner of the screen, and it means every row has a live server-side entity behind it,
 * so a row can always be asked about. A crew member who has wandered ashore is simply not listed and their
 * berth shows empty; they are back in their old row the moment they walk back aboard, because the berth number
 * lives on the articles rather than on the villager.
 *
 * ## Why the trade lines carry whole stacks
 * Item ids named a trade and stopped there. A crewman selling an enchanted sword is selling a PARTICULAR
 * sword, and its enchantments live in the stack's data components -- so the card can only show what the trade
 * screen shows if the stack itself travels. That needs a `RegistryFriendlyByteBuf`, which the byte-array
 * payload style does not hand out, so the wire builds one from the player's own `registryAccess()`.
 */
object CrewManifest {

    // region what travels

    /** One berth on the manifest. [slot] is the berth number, so a row's position survives the crew moving. */
    data class Row(
        val slot: Int,
        val villager: UUID,
        val entityId: Int,
        val profession: String,
        val villagerType: String,
        val level: Int,
        val name: String,
        /** Shown on the row itself, so a captain can read a whole crew's jobs without opening eight cards. */
        val duty: CrewDuty
    )

    data class Snapshot(
        val ship: String,
        val helm: Long,
        val berths: Int,
        val maxBerths: Int,
        val rows: List<Row>
    )

    /**
     * One trade line, carrying whole [ItemStack]s rather than item ids.
     *
     * Ids were enough to name a trade and nothing more. A crewman selling an enchanted sword is selling a
     * PARTICULAR sword, and the enchantments live in the stack's data components -- so the stack itself has to
     * travel if the card is to show what the trade screen shows. [costB] is `ItemStack.EMPTY` when the trade
     * has a single ingredient, which is most of them.
     */
    data class Offer(
        val costA: ItemStack,
        val costB: ItemStack,
        val result: ItemStack,
        val uses: Int,
        val maxUses: Int,
        val outOfStock: Boolean
    )

    data class Detail(
        val villager: UUID,
        val name: String,
        val profession: String,
        val level: Int,
        val xp: Int,
        val offers: List<Offer>,
        /**
         * Whether there was a living villager to read this card off.
         *
         * False means the crew member could not be found anywhere on the server -- an unloaded chunk, or a
         * dimension nobody is standing in -- so the card was built from the written copy the articles carry.
         * That knows who they are but cannot know what they are selling: trades change every time somebody
         * buys. The card says as much rather than showing an empty list, which would read as "no trades".
         */
        val aboard: Boolean,

        /** What they have been told to do. The assignment button on the card cycles this. */
        val duty: CrewDuty,

        /**
         * The ship's own tally, carried on the card so the Station line can say something true.
         *
         * These describe the VESSEL, not this crew member, and that is deliberate. Until guns carry their
         * bow-relative numbers there is no non-arbitrary way to say WHICH gun somebody is at -- but "four of
         * six guns manned" is the fact a captain actually acts on, and it answers both questions a gunner's
         * card raises: do I need another berth, or another cannon?
         */
        val guns: Int,
        val gunners: Int,
        val fireParty: Int
    )

    // endregion

    // region the fabric shims

    /**
     * Installed by the loader layer at init, the same volatile-hook pattern `PathMessages.sender` uses -- and
     * for the same reason: :common has fabric-loader on its classpath but not the networking API.
     *
     * Both return whether the push actually went out, so a caller can fall back to something a client without
     * the mod's channels can still read. That is not a theoretical case: it is what keeps the crew key from
     * doing nothing at all on a connection the payload cannot cross.
     */
    @Volatile
    @JvmField
    var sender: (ServerPlayer, Snapshot) -> Boolean = { _, _ -> false }

    @Volatile
    @JvmField
    var detailSender: (ServerPlayer, Detail) -> Boolean = { _, _ -> false }

    // endregion

    // region building it

    /**
     * Everything [player] can see about the crew filed under [station]'s name.
     *
     * Rows come from the LEDGER, not from who happens to be standing on the deck. That is the whole point of
     * the ledger: a crew member left ashore is still on the articles and still holds their berth, and a list
     * that quietly dropped them would disagree with the number in its own corner the moment anybody wandered
     * off.
     *
     * Live villagers are matched in by uuid where they can be found, which is what fills in profession, level
     * and the entity the head icon is drawn from. A berth with nobody to match falls back to the name and slot
     * the ledger carries -- enough to list them honestly as crew who are not here.
     */
    fun build(level: ServerLevel, player: ServerPlayer, station: ShipHelmBlockEntity): Snapshot {
        val ship = CrewStations.shipOf(level, station)
        val key = crewKey(player, station)
        val berths = if (key == null) emptyList() else CrewLedger.get(level.server).crew(key)

        // Anyone this crew member could be read off, wherever they are standing. Deliberately not limited to
        // the ship: a crew member on the quay is as readable as one on the deck, and looking only at the deck
        // is what made somebody who had stepped ashore appear in the list as a nameless, rankless, faceless row
        // -- the client draws a real head by rendering the real entity, so having their entity id is the whole
        // difference between a face and a blank.
        val present = HashMap<UUID, Villager>()
        if (ship != null) for (crew in ShipCrew.villagersAboard(level, ship)) present[crew.uuid] = crew
        for (berth in berths) {
            if (berth.villager in present) continue
            CrewMuster.findAnywhere(level.server, berth.villager)?.let { present[berth.villager] = it }
        }

        // Opening the manifest is the most reliable moment a whole crew is in hand at once, so it is where the
        // written copies are brought up to date. A snapshot that is one look-at-the-articles old is close
        // enough to be worth rebuilding from, and this costs nothing anybody can feel.
        if (key != null) {
            val ledger = CrewLedger.get(level.server)
            for (berth in berths) {
                present[berth.villager]?.let { ledger.updateSnapshot(berth.villager, CrewSnapshot.capture(it)) }
            }
        }

        val rows = berths
            .map { berth -> rowFor(berth, present[berth.villager]) }
            .sortedBy { it.slot }

        return Snapshot(
            ship = station.helmName?.string ?: (if (ship == null) "" else ShipCrew.name(ship)),
            helm = station.blockPos.asLong(),
            berths = CrewData.slots(player),
            maxBerths = EurekaConfig.SERVER.crewSlotsMax,
            rows = rows
        )
    }

    /** The ledger key this wheel makes for this captain, or null while the wheel is unnamed. */
    fun crewKey(player: ServerPlayer, station: ShipHelmBlockEntity): CrewLedger.Key? {
        val name = station.helmName ?: return null
        return CrewLedger.Key(player.uuid, HelmNames.keyOf(name), HelmNames.variantOf(station.blockState))
    }

    private fun rowFor(berth: CrewLedger.Berth, villager: Villager?): Row {
        // NO_ENTITY rather than a real id: the screen draws its head icon by rendering the entity, and asks
        // the client for it by id. There is nothing here to render for somebody who is not here.
        //
        // What they ARE is still known, though, because the written copy carries it -- so an absent crew member
        // is listed with their trade and their rank rather than as a blank line. A row that said nothing but a
        // name was indistinguishable from a broken one, which is exactly how it read.
        if (villager == null) {
            val papers = CrewSnapshot.papers(berth.snapshot)
            return Row(
                slot = berth.slot,
                villager = berth.villager,
                entityId = NO_ENTITY,
                profession = papers?.profession ?: NO_PROFESSION,
                villagerType = papers?.type ?: DEFAULT_TYPE,
                level = papers?.level ?: 0,
                name = berth.name,
                // Off the BERTH, not off the villager, so somebody ashore still shows the job they hold. That
                // is the point of keeping duties on the articles -- walking off the deck is not resigning.
                duty = berth.duty
            )
        }
        val data = villager.villagerData
        return Row(
            slot = berth.slot,
            villager = villager.uuid,
            entityId = villager.id,
            profession = keyOf(data.profession(), NO_PROFESSION),
            villagerType = keyOf(data.type(), DEFAULT_TYPE),
            level = data.level(),
            name = CrewNames.displayName(villager),
            duty = berth.duty
        )
    }

    /**
     * The full detail of one crew member, or null if [player] has no business asking.
     *
     * Trades are fetched here rather than ridden along with every snapshot because a full crew's worth of them
     * would be an order of magnitude larger than the list itself, and they are only ever looked at one at a
     * time. Note that reading `offers` builds the offer list if the villager has never been traded with -- the
     * same thing right-clicking them does, and harmless, but it is a side effect and worth knowing.
     */
    fun detailFor(level: ServerLevel, player: ServerPlayer, station: ShipHelmBlockEntity, villager: UUID): Detail? {
        val berth = berthOf(level, player, station, villager) ?: return null
        val crew = CrewMuster.findAnywhere(level.server, villager)
        val muster = musterOf(level, station)

        // Nobody to ask: answer from the articles instead of not answering at all. Silence here left the card
        // showing "Reading the articles..." for as long as it was open, which is what a crew member who had
        // died or walked ashore looked like -- a row that would not open, could not be renamed, and showed no
        // rank. It was one missing answer wearing three costumes.
        if (crew == null) {
            val papers = CrewSnapshot.papers(berth.snapshot)
            return Detail(
                villager = villager,
                name = berth.name,
                profession = papers?.profession ?: NO_PROFESSION,
                level = papers?.level ?: 0,
                xp = papers?.xp ?: 0,
                offers = emptyList(),
                aboard = false,
                duty = berth.duty,
                guns = muster.guns,
                gunners = muster.gunners,
                fireParty = muster.fireParty
            )
        }

        val data = crew.villagerData
        return Detail(
            villager = villager,
            name = CrewNames.displayName(crew),
            profession = keyOf(data.profession(), NO_PROFESSION),
            level = data.level(),
            xp = crew.villagerXp,
            offers = crew.offers.map { offerOf(it) },
            aboard = true,
            duty = berth.duty,
            guns = muster.guns,
            gunners = muster.gunners,
            fireParty = muster.fireParty
        )
    }

    /** What a vessel has to work with: guns bolted on, and hands told off to the two duties. */
    private data class Muster(val guns: Int, val gunners: Int, val fireParty: Int)

    /**
     * Count [station]'s vessel once, for a card that needs all three numbers.
     *
     * Counted per CARD rather than per manifest: the gun census walks chunks and the duty counts need the
     * villagers aboard, and neither is worth doing eight times over to fill in a list nobody has opened a row
     * of. A card is opened one at a time and closed again, which makes it exactly the right grain.
     *
     * Counts every gunner aboard whoever signed them on -- the same rule that fires the guns. A card that
     * counted only its own captain's would report four of six guns manned on a ship where all six are.
     */
    private fun musterOf(level: ServerLevel, station: ShipHelmBlockEntity): Muster {
        val ship = CrewStations.shipOf(level, station) ?: return Muster(0, 0, 0)
        val ledger = CrewLedger.get(level.server)
        var gunners = 0
        var fireParty = 0
        for (crew in ShipCrew.villagersAboard(level, ship)) {
            when (ledger.dutyOf(crew.uuid)) {
                CrewDuty.GUNNER -> gunners++
                CrewDuty.FIREFIGHTER -> fireParty++
                CrewDuty.NONE -> Unit
            }
        }
        return Muster(ShipGuns.count(level, ship), gunners, fireParty)
    }

    private fun offerOf(offer: MerchantOffer): Offer = Offer(
        costA = offer.costA,
        costB = offer.costB,
        result = offer.result,
        uses = offer.uses,
        maxUses = offer.maxUses,
        outOfStock = offer.isOutOfStock
    )

    // endregion

    // region renaming

    /**
     * Rename one crew member. Returns true if anything changed.
     *
     * A blank name is not a rejection -- it puts the berth's default back, which is the only way a player who
     * has renamed someone can undo it. Everything else is sanitised the way vanilla sanitises an anvil: strip
     * what cannot be typed, trim, and cap the length, so a manifest row stays a row and a nameplate stays a
     * plate.
     */
    fun rename(
        level: ServerLevel, player: ServerPlayer, station: ShipHelmBlockEntity, villager: UUID, raw: String
    ): Boolean {
        val ledger = CrewLedger.get(level.server)
        berthOf(level, player, station, villager) ?: return false
        val clean = StringUtil.filterText(raw).trim().take(MAX_NAME_LENGTH)

        // Renaming used to require the crew member to be standing here, which made a name something you could
        // only change while looking at them -- and left an ashore crew member's row permanently unrenamable.
        // The articles are the authority on what somebody is called, so the ledger is always written; the
        // entity is brought into line if it can be reached, and by `CrewMuster.answerTo` if it cannot.
        val crew = CrewMuster.findAnywhere(level.server, villager)
        if (crew != null) {
            if (clean.isEmpty()) {
                crew.setCustomName(null)
                CrewNames.applyDefault(crew, ledger.slotOf(villager))
            } else {
                crew.setCustomName(Component.literal(clean))
            }
            CrewSnapshot.capture(crew)?.let { ledger.updateSnapshot(villager, it) }
        }
        // Read back off the entity where there is one, rather than from `clean`, which is blank in the reset
        // case and would file them under no name at all.
        ledger.renameMember(
            villager,
            if (crew != null) CrewNames.displayName(crew)
            else clean.ifEmpty { CrewNames.defaultFor(ledger.slotOf(villager)) }
        )
        // SynchedEntityData carries DATA_CUSTOM_NAME to every tracking client on its own, so there is nothing
        // to push here beyond the manifest the caller refreshes.
        return true
    }

    /**
     * Discharge one crew member from the articles, whether or not they are here.
     *
     * The manifest needs this because the world can take a crew member away in ways nobody chose: a berth held
     * by somebody who has died, or who is standing in a chunk that no longer exists, is a berth that could not
     * otherwise be freed -- paying somebody off is a gesture aimed at a villager, and there is no villager to
     * aim at. So the button works off the articles alone.
     *
     * Their default name is taken back if they can be reached, exactly as the crew key does it, so a paid-off
     * villager stops looking like crew. A name the player chose is theirs and stays.
     */
    fun dismiss(level: ServerLevel, player: ServerPlayer, station: ShipHelmBlockEntity, villager: UUID): String? {
        val berth = berthOf(level, player, station, villager) ?: return null
        val ledger = CrewLedger.get(level.server)
        CrewMuster.findAnywhere(level.server, villager)?.let { CrewNames.clearDefault(it, berth.slot) }
        ledger.payOff(villager)
        return berth.name
    }

    // endregion

    // region what the client asks for

    /**
     * Answer a client's request for one crew member's detail.
     *
     * Silence is the response to anything that fails a check. There is no error to report, because every route
     * to a failure here is either a manifest that has gone stale under the player -- they walked away, the
     * wheel was mined, the crew member wandered off -- or a client asking about somebody who was never theirs.
     * The first resolves itself when they reopen; the second deserves nothing.
     */
    fun requestDetail(level: ServerLevel, player: ServerPlayer, helm: Long, villager: UUID) {
        val station = stationAt(level, player, helm) ?: return
        val detail = detailFor(level, player, station, villager) ?: return
        detailSender(player, detail)
    }

    /** Rename one crew member and hand back a manifest that reflects it. */
    fun requestRename(level: ServerLevel, player: ServerPlayer, helm: Long, villager: UUID, raw: String) {
        val station = stationAt(level, player, helm) ?: return
        if (!rename(level, player, station, villager, raw)) return
        sender(player, build(level, player, station))
    }

    /**
     * Put one crew member on a duty, and hand back both halves of what the screen is showing.
     *
     * Two payloads because the card and the list behind it both change: the row grows a job label, and the
     * card's own Assignment and Station lines are on the card. The card is refreshed second so it lands after
     * the manifest that would otherwise close it.
     *
     * Only the captain who signed somebody on may re-task them, which falls out of [berthOf] rather than being
     * checked here -- the same gate that guards renaming and dismissal.
     */
    fun requestDuty(level: ServerLevel, player: ServerPlayer, helm: Long, villager: UUID, duty: CrewDuty) {
        val station = stationAt(level, player, helm) ?: return
        val berth = berthOf(level, player, station, villager) ?: return
        if (berth.duty == duty) return

        CrewLedger.get(level.server).setDuty(villager, duty)
        PathMessages.send(player, orderFor(berth.name, duty), PathMessages.Kind.GOOD)

        sender(player, build(level, player, station))
        detailFor(level, player, station, villager)?.let { detailSender(player, it) }
    }

    /** What to tell a captain they have just done. Named plainly -- these are orders, not settings. */
    private fun orderFor(name: String, duty: CrewDuty): String = when (duty) {
        CrewDuty.GUNNER -> "$name is at the guns."
        CrewDuty.FIREFIGHTER -> "$name has the fire watch."
        CrewDuty.NONE -> "$name is off duty."
    }

    /** Discharge one crew member and hand back a manifest without them. */
    fun requestDismiss(level: ServerLevel, player: ServerPlayer, helm: Long, villager: UUID) {
        val station = stationAt(level, player, helm) ?: return
        val name = dismiss(level, player, station, villager) ?: return
        val crewName = station.helmName?.string ?: "the articles"
        PathMessages.send(player, "Paid off $name from $crewName.", PathMessages.Kind.GOOD)
        sender(player, build(level, player, station))
    }

    // endregion

    // region lookups

    /**
     * The helm a client's manifest is asking about, re-resolved and re-validated from scratch.
     *
     * The position rides both client-to-server payloads rather than the server keeping a handle open, so a
     * stale, guessed or forged one is simply a lookup that fails. The checks are the three that matter: it is
     * still a helm, it still holds articles, and the player is still standing next to it.
     */
    fun stationAt(level: ServerLevel, player: ServerPlayer, helm: Long): ShipHelmBlockEntity? {
        val station = level.getBlockEntity(BlockPos.of(helm)) as? ShipHelmBlockEntity ?: return null
        if (!station.isCrewStation) return null

        // Helms live at SHIPYARD coordinates once assembled, so the player's world position has to be compared
        // against where the wheel actually is, not against the address it is filed under.
        val ship = CrewStations.shipOf(level, station) ?: return null
        val pos = station.blockPos
        val world = ship.shipToWorld.transformPosition(
            Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        )
        val dx = player.x - world.x
        val dy = player.y - world.y
        val dz = player.z - world.z
        if (dx * dx + dy * dy + dz * dz > REACH_SQ) return null
        return station
    }

    /**
     * [villager]'s berth if they are on the crew [station] names for [player], or null.
     *
     * The one authorisation check every card action shares, and deliberately the ledger's alone. Being aboard
     * used to be required as well, on the reasoning that reading trades and renaming both need the live entity
     * -- but that made every action on the card fail together the moment a crew member stepped ashore, when
     * what was wanted was for each to do as much as it honestly can.
     */
    private fun berthOf(
        level: ServerLevel, player: ServerPlayer, station: ShipHelmBlockEntity, villager: UUID
    ): CrewLedger.Berth? {
        val key = crewKey(player, station) ?: return null
        val ledger = CrewLedger.get(level.server)
        if (ledger.crewOf(villager) != key) return null
        return ledger.crew(key).firstOrNull { it.villager == villager }
    }

    private fun <T : Any> keyOf(holder: Holder<T>, fallback: String): String =
        holder.unwrapKey().map { it.identifier().toString() }.orElse(fallback)

    // endregion

    /**
     * "There is no entity behind this row."
     *
     * Entity ids are non-negative, so this cannot collide with a real one. It means the crew member is on the
     * articles but not loaded near the wheel -- ashore, or in a chunk nobody is standing in.
     */
    const val NO_ENTITY = -1

    const val NO_PROFESSION = "minecraft:none"

    private const val DEFAULT_TYPE = "minecraft:plains"

    /** Vanilla's anvil allows 50; a manifest row and a nameplate both read better shorter than that. */
    const val MAX_NAME_LENGTH = 32

    /**
     * How far a player may drift from the wheel with its manifest open. Generously past block reach -- the
     * screen was opened within mounting distance and a step backwards should not close it -- but bounded, so a
     * client cannot keep one open from across the world. A moving ship carries the wheel and the player alike,
     * so this is a relative distance and sailing does not trip it.
     */
    private const val REACH_SQ = 16.0 * 16.0
}
