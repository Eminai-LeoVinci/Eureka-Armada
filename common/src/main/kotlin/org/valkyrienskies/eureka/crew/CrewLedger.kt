package org.valkyrienskies.eureka.crew

import org.valkyrienskies.eureka.util.nbt.*

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.eureka.EurekaMod
import java.util.UUID

/**
 * Every crew in the world, filed under who captains them, what they are called and what wood their wheel is.
 *
 * ## Why the crew stopped living on the block
 * The roster used to be block-entity data, which made "break the wheel and the articles are torn up" free --
 * no listener, no cleanup pass, no list outliving the thing it described. That was the right shape while a
 * crew belonged to a helm. It is the wrong shape now that a crew is meant to SURVIVE its helm: a captain who
 * loses a wheel to lava has not dismissed anybody, and re-naming a replacement wheel is supposed to get them
 * back.
 *
 * So the crew left the block. What a wheel carries is one id -- see `ShipHelmBlockEntity.crewBindings` -- and
 * the crew are looked up by it. That is what keeps the rest of the feature cheap: the binding is one string in
 * the item's `custom_data`, and two wheels holding the same id are the same crew without anything having to
 * synchronise them.
 *
 * ## The id
 * A crew is identified by a minted [UUID] and nothing else. It used to be identified by what it was CALLED, on
 * the wood it was called that on, which made the name load-bearing in three unhappy ways: renaming a wheel had
 * to re-file every crew on it, an ANVIL rename orphaned them in silence because no server code of ours ever
 * ran, and a crew could never board a ship whose wheel was cut from a different tree. An id has none of those
 * problems -- the name became a label a captain can change anywhere, and the wood stopped mattering at all.
 *
 * The CAPTAIN is still recorded, because a crew belongs to somebody. It is what stops a stolen wheel mustering
 * its old owner's crew, and what the helm menu filters its list by.
 *
 * ## Global, not per-dimension
 * Unlike [org.valkyrienskies.eureka.path.PathStore], which is deliberately one store per level because a
 * nether route has no business being offered in the overworld, this hangs off the OVERWORLD's storage and is
 * shared. A wheel can be carried through a portal, and a crew that stayed behind in the dimension they were
 * signed on in would be a bug rather than a rule.
 */
class CrewLedger : SavedData() {

    /**
     * One crew: who owns them, what they are called, and who is signed on.
     *
     * [id] is minted once and never changes. [name] is a label and nothing more -- renaming is a field write,
     * so it cannot orphan anybody, cannot collide with another crew, and can happen on an anvil where no
     * server code of ours runs at all.
     *
     * [berths] is the live list and is held by reference through [copy], so renaming does not disturb the crew.
     */
    data class Crew(
        val id: UUID,
        val captain: UUID,
        val name: String,
        val berths: MutableList<Berth> = mutableListOf()
    )

    /**
     * One signed-on crew member. [slot] is their berth number, which names them and fixes their row.
     *
     * [name] is carried here as well as on the villager because the manifest has to be able to list somebody
     * who is not standing in front of it. A crew member left ashore, or in an unloaded chunk, is still on the
     * articles and still has to appear in the list -- and there is nothing to read a name off when the entity
     * is not there. Kept in step by every path that renames one.
     */
    data class Berth(
        val villager: UUID,
        val slot: Int,
        val name: String,
        /** Enough to rebuild them if the villager cannot be found when they are mustered. See [CrewSnapshot]. */
        val snapshot: CompoundTag? = null,
        /**
         * Whether this crew member was deliberately taken out of the world rather than merely lost track of.
         *
         * Set when a ship is taken apart and its crew are stood down into the articles. The distinction matters
         * for exactly one thing: rebuilding a crew member who is merely UNREACHABLE has to tombstone the old id
         * in case the original turns up later, whereas one who was stood down was destroyed on purpose and can
         * never come back. Tombstoning them anyway would work, but it would leave an entry that nothing ever
         * clears, growing by a whole crew every time a ship is rebuilt.
         */
        val ashore: Boolean = false,
        /**
         * What they have been told to do. See [CrewDuty] for why this is one value rather than a set.
         *
         * Kept on the BERTH rather than on the villager, like everything else the articles record. A duty is an
         * order given to somebody serving on a particular crew, so it belongs where the crew is written down --
         * which also means it survives its holder walking ashore, dying and being rebuilt from a snapshot, or
         * being struck by lightning and rekeyed, none of which are decisions to stop being a gunner.
         */
        val duty: CrewDuty = CrewDuty.NONE,
        /**
         * The gun this gunner is seated at: the packed SHIPYARD position of the cannon's breech, or null.
         *
         * A live address, valid only while that exact cannon block stands. It dies with the gun (that is the
         * "relieved when destroyed" rule) and it dies with the ASSEMBLY -- a rebuilt ship gets fresh shipyard
         * coordinates, which is the ship bottle's old lesson -- so anything that packs the ship up clears this
         * and leans on [stationLabel] to find the gun again.
         */
        val station: Long? = null,
        /**
         * The gun's bow-relative label ("L2") at the moment of assignment. The durable half of the binding.
         *
         * Survives stand-down precisely because the position cannot: labels are re-derived from geometry, so
         * after a disassemble/reassemble or a bottle cycle the same label finds the same gun on the new
         * addresses and the gunner re-seats himself. Cleared only when the gunner is genuinely relieved --
         * the gun destroyed, the duty changed, or the captain unassigning the station.
         */
        val stationLabel: String? = null,
        /**
         * The captain's "do not touch". A locked berth keeps its duty, its station and its gun's settings
         * through every BULK order -- re-laying the gun crew, posting the fire watch, laying elevations --
         * and refuses the card's own controls too: only Unlock (and Back) answer. The one thing bulk
         * still does for a locked gunner is SUPPLY the gun, with whatever it is already set to hold.
         *
         * Appended last on purpose: the loader builds berths positionally, and a trailing default is the
         * only place a new field cannot silently mis-bind an eight-argument constructor call.
         */
        val locked: Boolean = false,
        /**
         * Where this crew member was standing when the ship was last packed up: an offset from the
         * crew-station wheel, in the ship's own block frame.
         *
         * An OFFSET and not a position, for the reason [station] cannot be trusted across an assembly --
         * shipyard coordinates are re-dealt every time a hull is built, so the only durable way to say
         * "here" is to say it relative to something that is re-found on the other side. The wheel is that
         * something: the articles already hang from it, and a muster already knows where it landed.
         *
         * This is what makes a ship a place rather than a vehicle full of passengers. A shopkeeper stood in
         * his window, a smith at his anvil and a gunner at his breech all come back to the same spot after a
         * disassembly or a bottle, instead of being tipped out in a heap on the wheel -- which, before this
         * existed, killed forty-three of a sixty-seven crew to entity cramming in one reassembly, and struck
         * every one of them off the articles as they died.
         *
         * Appended after [locked] for that field's own reason: positional loading makes the tail the only
         * safe place to grow.
         */
        val post: Vec3? = null
    )

    private val crews = LinkedHashMap<UUID, Crew>()

    /**
     * Villagers that have been rebuilt from a snapshot, and whose ORIGINAL must be destroyed on sight.
     *
     * A crew member in an unloaded chunk cannot be moved, so mustering copies them from the articles and gives
     * the copy a new id. The original is still out there, and the moment its chunk loads there would be two of
     * the same crew member -- one of them holding trades a player may already have used. Recording the dead id
     * here lets the copy be the only one: whatever turns up under the old id is removed as it loads.
     *
     * Persisted, because the chunk that has the stale copy may not load for a very long time.
     */
    private val tombstoned = HashSet<UUID>()

    /**
     * Villager -> the crew they serve on. Derived, never persisted: rebuilt from [crews] on load, because a
     * second stored copy of the same fact is a second thing that can be wrong.
     *
     * This index is the whole of the one-crew-at-a-time rule. Without it the question "is this villager
     * already signed on somewhere?" would be a scan of every crew in the world on every recruit.
     */
    private val byVillager = HashMap<UUID, UUID>()

    // region reading

    /**
     * The berths of crew [id], oldest first. Empty for a crew that does not exist.
     *
     * A COPY, deliberately. Callers walk this list and pay people off as they go -- mustering does exactly that
     * when a crew has outgrown its berths -- and handing out the live list would turn that into a concurrent
     * modification of the thing being iterated.
     */
    fun berths(id: UUID): List<Berth> = crews[id]?.berths?.toList() ?: emptyList()

    /** Crew [id] in full -- owner, name and berths -- or null if there is no such crew. */
    fun crew(id: UUID): Crew? = crews[id]

    /** The crew [villager] already serves on, or null if they are nobody's. */
    fun crewOf(villager: UUID): UUID? = byVillager[villager]

    /** What crew [id] is called, or null if there is no such crew. */
    fun nameOf(id: UUID): String? = crews[id]?.name

    /**
     * Every crew [captain] owns, oldest first. This is what the helm menu's dropdown lists.
     *
     * Crews with nobody on them are left out. [payOff] drops a crew as its last berth goes, so anything still
     * here has somebody signed on -- and a captain has no use for an empty name in a list of who they can call.
     */
    fun crewsOf(captain: UUID): List<Crew> =
        crews.values.filter { it.captain == captain && it.berths.isNotEmpty() }

    /**
     * Whether [captain] already has a crew of this name. Used to keep generated names distinct.
     *
     * Folded through [HelmNames.keyOf], so "Motley Crew Anchor" and "motley crew  anchor" count as the same
     * name being taken. Nothing REFUSES a duplicate any more -- names are labels now -- but a generator that
     * hands out the same name twice makes a dropdown nobody can read.
     */
    fun nameTaken(captain: UUID, name: String): Boolean {
        val folded = HelmNames.keyOf(name)
        return crews.values.any { it.captain == captain && HelmNames.keyOf(it.name) == folded }
    }

    /** The berth [villager] holds, or [CrewRoster.NO_SLOT]. */
    fun slotOf(villager: UUID): Int {
        val id = byVillager[villager] ?: return CrewRoster.NO_SLOT
        return crews[id]?.berths?.firstOrNull { it.villager == villager }?.slot ?: CrewRoster.NO_SLOT
    }

    /**
     * The lowest berth number [key] is not already using, or [CrewRoster.NO_SLOT] past [max].
     *
     * Lowest-free rather than next-highest so that paying somebody off and signing somebody else on reuses the
     * empty berth, instead of marching the numbers up and leaving gaps in the manifest.
     */
    fun freeSlot(id: UUID, max: Int): Int {
        val taken = crews[id]?.berths?.mapTo(HashSet()) { it.slot } ?: return 0
        for (slot in 0 until max) if (slot !in taken) return slot
        return CrewRoster.NO_SLOT
    }

    // endregion

    // region writing

    /**
     * Mint a new crew for [captain] called [name], and return it.
     *
     * The id is the crew from here on. Nothing else about a crew is fixed -- the name can change, every berth
     * on it can change -- so this is the one moment a crew comes into existence.
     */
    fun create(captain: UUID, name: String): Crew {
        val crew = Crew(UUID.randomUUID(), captain, name)
        crews[crew.id] = crew
        setDirty()
        return crew
    }

    /**
     * Put crew [id] under a new [name]. Returns whether there was a crew to rename.
     *
     * A field write, and that is the whole point of keying on an id. This used to be `renameAll`, which had to
     * move every crew on a wheel to a new key and REFUSE when the destination was occupied, because the name
     * was the address. Nothing can collide now, so nothing has to be refused.
     */
    fun rename(id: UUID, name: String): Boolean {
        val crew = crews[id] ?: return false
        if (crew.name == name) return true
        crews[id] = crew.copy(name = name)
        setDirty()
        return true
    }

    /** Sign [villager] onto crew [id] at [slot]. Caller has already established they are nobody else's. */
    fun sign(id: UUID, villager: UUID, slot: Int, name: String, snapshot: CompoundTag? = null) {
        val crew = crews[id] ?: return
        crew.berths.add(Berth(villager, slot, name, snapshot))
        byVillager[villager] = id
        setDirty()
    }

    /** Refresh the written copy of [villager], so the fallback is never far behind the real one. */
    fun updateSnapshot(villager: UUID, snapshot: CompoundTag?) {
        if (snapshot == null) return
        edit(villager) { it.copy(snapshot = snapshot) }
    }

    /**
     * Record that [villager] has been taken out of the world on purpose, keeping [snapshot] to rebuild them.
     *
     * The caller destroys the entity; this is the paperwork. Both halves have to happen together, so the
     * caller must have a snapshot IN HAND before it discards anybody -- a crew member removed from the world
     * without one is simply gone.
     *
     * The live [Berth.station] address goes with them. A stand-down means the ship is being packed up, and
     * the shipyard position dies with the assembly -- left in place it points at a deleted ship, and the
     * first reconcile after the REASSEMBLY reads that dead address as "the gun is gone" and relieves the
     * gunner, label and all. The LABEL is the half that outlives the hull, and keeping it while dropping the
     * address is exactly what walks a gunner back to his own gun when the ship exists again.
     */
    fun standDown(villager: UUID, snapshot: CompoundTag, post: Vec3? = null) {
        // A post that could not be measured leaves the remembered one alone rather than erasing it: not
        // knowing where somebody is standing today is no reason to forget where they belong.
        edit(villager) { it.copy(snapshot = snapshot, ashore = true, station = null, post = post ?: it.post) }
    }

    /** What [villager] has been told to do, or [CrewDuty.NONE] if they are nobody's crew. */
    fun dutyOf(villager: UUID): CrewDuty {
        val id = byVillager[villager] ?: return CrewDuty.NONE
        return crews[id]?.berths?.firstOrNull { it.villager == villager }?.duty ?: CrewDuty.NONE
    }

    /**
     * Put [villager] on [duty], taking them off whatever they were doing. Silent if they are not on any crew.
     *
     * Any station goes with the old duty, label included: a duty change is an order to do something ELSE, so
     * there is no gun to remember. [GunStations] notices the cleared address on its next pass and unseats them.
     */
    fun setDuty(villager: UUID, duty: CrewDuty) {
        edit(villager) { it.copy(duty = duty, station = null, stationLabel = null) }
    }

    /** The berth [villager] holds, in full, or null. The station machinery reads bindings through this. */
    fun berthOf(villager: UUID): Berth? {
        val id = byVillager[villager] ?: return null
        return crews[id]?.berths?.firstOrNull { it.villager == villager }
    }

    /** Seat [villager] at a gun: the live shipyard address and the label that outlives it. */
    fun setStation(villager: UUID, station: Long, label: String) {
        edit(villager) { it.copy(station = station, stationLabel = label) }
    }

    /** Relieve [villager] of their gun entirely -- destroyed, unassigned, or re-tasked. */
    fun clearStation(villager: UUID) {
        edit(villager) { it.copy(station = null, stationLabel = null) }
    }

    /**
     * Set or lift the captain's "do not touch" on one berth. See [Berth.locked] for what it shields;
     * this is the whole write path -- the Lock button, and nothing else, moves it.
     */
    fun setLocked(villager: UUID, locked: Boolean) {
        edit(villager) { it.copy(locked = locked) }
    }

    /**
     * Every villager on ANY crew currently bound to a station or a remembered label.
     *
     * The reconcile pass starts from this rather than from ships, so a gunner whose ship is unloaded costs
     * one map read a second and a binding can never be orphaned by its ship's absence.
     */
    fun stationedBerths(): List<Berth> {
        val out = ArrayList<Berth>()
        for (crew in crews.values) {
            for (berth in crew.berths) {
                if (berth.station != null || berth.stationLabel != null) out.add(berth)
            }
        }
        return out
    }

    /** Whether [villager]'s berth is waiting for them to be rebuilt rather than found. */
    fun isAshore(villager: UUID): Boolean {
        val id = byVillager[villager] ?: return false
        return crews[id]?.berths?.firstOrNull { it.villager == villager }?.ashore == true
    }

    private inline fun edit(villager: UUID, change: (Berth) -> Berth) {
        val id = byVillager[villager] ?: return
        val list = crews[id]?.berths ?: return
        val index = list.indexOfFirst { it.villager == villager }
        if (index < 0) return
        list[index] = change(list[index])
        setDirty()
    }

    /**
     * Re-file a berth under a new villager id, for a crew member rebuilt from their snapshot.
     *
     * The berth, the name and the written copy all follow; only the identity changes. The OLD id is tombstoned
     * in the same breath, because the only reason to rebuild somebody is that the original could not be
     * reached -- and it must not come back as a second copy.
     */
    fun replaceVillager(old: UUID, new: UUID) {
        if (!rekey(old, new)) return
        tombstoned.add(old)
        setDirty()
    }

    /**
     * Move a berth from one entity id to another WITHOUT tombstoning the old one.
     *
     * For a crew member the game itself replaced -- struck by lightning, zombified, cured -- where the original
     * entity is genuinely gone rather than merely out of reach. Tombstoning there would arm a trap for an id
     * that can never come back, and the berth, the name and the written copy should simply follow them through
     * whatever they turned into.
     *
     * Returns whether there was a berth to move.
     */
    fun rekey(old: UUID, new: UUID): Boolean {
        val id = byVillager.remove(old) ?: return false
        val list = crews[id]?.berths ?: return false
        val index = list.indexOfFirst { it.villager == old }
        // Whatever they are now, they are here: a berth that is standing in front of somebody is not waiting
        // for them to be rebuilt.
        if (index >= 0) list[index] = list[index].copy(villager = new, ashore = false)
        byVillager[new] = id
        setDirty()
        return true
    }

    /** Note that [villager] has been found in the world, so the berth is no longer waiting on a rebuild. */
    fun markAboard(villager: UUID) {
        if (!isAshore(villager)) return
        edit(villager) { it.copy(ashore = false) }
    }

    /** Whether [villager] is a stale copy that mustering has already replaced. */
    fun isTombstoned(villager: UUID): Boolean = tombstoned.contains(villager)

    /**
     * Mark [villager] for destruction on sight, without there being a berth to move.
     *
     * For disbanding: a crew member in an unloaded chunk cannot be discarded now, and a crew deleted "from
     * existence" must not walk back out of the ground a week later when somebody flies over. The tombstone
     * sweep removes them as their chunk loads.
     */
    fun tombstone(villager: UUID) {
        if (tombstoned.add(villager)) setDirty()
    }

    /**
     * Strike a whole crew off, and hand back the berths that were on it.
     *
     * The caller destroys the entities -- this is the paperwork, and it goes first so that a berth cannot be
     * left pointing at a villager that no longer exists. Returns empty for a crew that is already gone.
     */
    fun disband(id: UUID): List<Berth> {
        val crew = crews.remove(id) ?: return emptyList()
        for (berth in crew.berths) byVillager.remove(berth.villager)
        setDirty()
        return crew.berths.toList()
    }

    /** Forget a tombstone once the stale copy has actually been destroyed. */
    fun clearTombstone(villager: UUID) {
        if (tombstoned.remove(villager)) setDirty()
    }

    /** Record that [villager] is now called [name], so the manifest can list them while they are away. */
    fun renameMember(villager: UUID, name: String) {
        val id = byVillager[villager] ?: return
        val list = crews[id]?.berths ?: return
        val index = list.indexOfFirst { it.villager == villager }
        if (index < 0 || list[index].name == name) return
        list[index] = list[index].copy(name = name)
        setDirty()
    }

    /** Discharge [villager] from whatever crew they are on. Returns the crew they left, or null. */
    fun payOff(villager: UUID): UUID? {
        val id = byVillager.remove(villager) ?: return null
        val crew = crews[id]
        crew?.berths?.removeIf { it.villager == villager }
        // A crew with nobody on it is not a crew, and the id goes with the last berth. Under the old name key
        // an empty crew was worth keeping, because a captain could retype the name and find it again; nobody
        // retypes a UUID. A wheel still carrying a dead binding simply finds no crew, and says so.
        if (crew != null && crew.berths.isEmpty()) crews.remove(id)
        setDirty()
        return id
    }

    /** [captain]'s crew called [name], matched forgivingly through [HelmNames.keyOf], or null. */
    fun findByName(captain: UUID, name: String): Crew? {
        val folded = HelmNames.keyOf(name)
        return crews.values.firstOrNull { it.captain == captain && HelmNames.keyOf(it.name) == folded }
    }

    /**
     * Find [captain]'s crew called [name], or mint one. Folded through [HelmNames.keyOf] when matching.
     *
     * The bridge from the old world to the new. Every wheel already out there carries a NAME and nothing else,
     * so the only way to work out which crew it means is to look one up by what it is called -- exactly once,
     * after which the wheel holds the id and the name is free to change.
     */
    fun findOrCreate(captain: UUID, name: String): Crew = findByName(captain, name) ?: create(captain, name)

    /**
     * The crew [station] means for [captain] -- adopting a name-keyed one if this wheel has no binding yet.
     *
     * **This is the whole migration for wheels already standing in a world.** Crews used to be found by what
     * the wheel was CALLED, so every wheel out there carries a name and no id at all. The first time anybody
     * asks this wheel about its crew, the name is used once to find them, the id is written onto the wheel,
     * and the name is never load-bearing again.
     *
     * The wheel also hands back the real CASING while it is here: the old ledger only ever stored the folded
     * lookup key, so a migrated crew is called "Black Pearl" rather than "black pearl" from this moment on.
     * See [refreshName], which will not do anything else.
     *
     * Returns null for a wheel with no name and no binding -- a blank wheel nobody has crewed -- and for a
     * binding pointing at a crew that no longer exists, which is treated as no binding rather than as an
     * error: everyone was paid off, the empty crew was dropped, and the wheel simply keeps nobody now.
     */
    fun bindingFor(station: org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity, captain: UUID): UUID? {
        station.boundCrew(captain)?.let { if (crews.containsKey(it)) return it }
        val name = station.helmName?.string ?: return null
        val found = findByName(captain, name) ?: return null
        station.bindCrew(captain, found.id)
        refreshName(found.id, name)
        return found.id
    }

    /**
     * Correct crew [id]'s recorded name to [name] if the two differ only in case or spacing.
     *
     * Migration repair, and nothing else. The ledger used to store the FOLDED name -- lower-cased, whitespace
     * collapsed -- because that was the lookup key and no display name was ever needed. Now that the name is a
     * label a captain reads off a dropdown, "black pearl" is the wrong answer where the wheel plainly says
     * "Black Pearl". A wheel meeting its crew is the moment the real casing is in hand.
     *
     * Guarded on the folded forms matching, so this can only ever repair a name, never replace one.
     */
    fun refreshName(id: UUID, name: String) {
        val crew = crews[id] ?: return
        if (crew.name == name) return
        if (HelmNames.keyOf(crew.name) != HelmNames.keyOf(name)) return
        crews[id] = crew.copy(name = name)
        setDirty()
    }

    /**
     * Take over a wheel's old block-entity roster, so crew signed on before the ledger existed are not lost.
     *
     * Runs the first time a wheel with a legacy roster gets a name -- which is the first moment there IS a crew
     * to file them under. The roster is cleared afterwards so this can only happen once and the two can never
     * disagree.
     */
    fun adoptLegacy(server: MinecraftServer, station: org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity) {
        val name = station.helmName ?: return
        if (station.crew.isEmpty) return
        for (entry in station.crew.entries()) {
            // Anyone the ledger already knows keeps the crew they are on; the old roster is the stale copy.
            if (byVillager.containsKey(entry.villager)) continue
            // The old roster recorded an OWNER per member, so each one joins that player's crew of this
            // wheel's name -- minting it if this is the first of them. The wheel is bound to it in the same
            // breath, which is what stops the next adoption having to look it up by name all over again.
            val crew = findOrCreate(entry.owner, name.string)
            station.bindCrew(entry.owner, crew.id)
            // The old roster recorded who and which berth, never what they were called, so the name has to come
            // off the villager -- and it MUST be tried, because the ledger's name is authoritative from here
            // on: mustering writes it back onto the entity. Filing everyone under the berth default would
            // rename somebody's hand-named crew the first time their ship assembled. They are almost always
            // right there when this runs, since it takes a captain at the wheel to trigger it.
            val live = CrewMuster.findAnywhere(server, entry.villager)
            crew.berths.add(
                Berth(
                    entry.villager,
                    entry.slot,
                    if (live != null) CrewNames.displayName(live) else CrewNames.defaultFor(entry.slot),
                    live?.let { CrewSnapshot.capture(it) }
                )
            )
            byVillager[entry.villager] = crew.id
        }
        station.crew.replaceAll(emptyList())
        station.setChanged()
        setDirty()
    }

    /**
     * Give a captain's same-named crews distinct names, once, at load.
     *
     * Only migration can produce two: dropping the wood from the key turned "Salty Dogs on oak" and "Salty
     * Dogs on spruce" into two crews of one name. They are NOT merged -- that would double-berth a hull and
     * could not be undone -- so the later one is suffixed instead, and the captain can rename it to whatever
     * they meant. Ordering is the file's, so the same file always suffixes the same crew.
     */
    private fun disambiguate() {
        val seen = HashSet<Pair<UUID, String>>()
        for (crew in crews.values.toList()) {
            var candidate = crew.name
            var n = 1
            while (!seen.add(crew.captain to HelmNames.keyOf(candidate))) {
                n++
                candidate = "${crew.name} ($n)"
            }
            if (candidate != crew.name) crews[crew.id] = crew.copy(name = candidate)
        }
    }

    // endregion

    // 1.21.1: SavedData persists through this override (the modern branch is codec-driven via SavedDataType).
    override fun save(tag: CompoundTag, provider: HolderLookup.Provider): CompoundTag = saveToTag(tag)

    fun saveToTag(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for (crew in crews.values) {
            if (crew.berths.isEmpty()) continue
            val entry = CompoundTag()
            // UUIDs as strings rather than through a codec: this file is a few dozen entries at most, so the
            // handful of extra bytes buys a save anyone can read and hand-edit when a crew needs rescuing.
            entry.putString(ID_KEY, crew.id.toString())
            entry.putString(CAPTAIN_KEY, crew.captain.toString())
            entry.putString(NAME_KEY, crew.name)
            val members = ListTag()
            for (berth in crew.berths) {
                val member = CompoundTag()
                member.putString(VILLAGER_KEY, berth.villager.toString())
                member.putInt(SLOT_KEY, berth.slot)
                member.putString(MEMBER_NAME_KEY, berth.name)
                berth.snapshot?.let { member.put(SNAPSHOT_KEY, it) }
                // Written only when true, so the ordinary case leaves no trace in the file and a berth saved
                // before this existed reads back as what it was: somebody expected to be out there somewhere.
                if (berth.ashore) member.putBoolean(ASHORE_KEY, true)
                // Same treatment, and the same reasoning: most of a crew has no duty, so writing NONE would
                // put a line in the file for every berth in the world to say nothing at all.
                if (berth.duty != CrewDuty.NONE) member.putString(DUTY_KEY, berth.duty.id)
                berth.station?.let { member.putLong(STATION_KEY, it) }
                berth.stationLabel?.let { member.putString(STATION_LABEL_KEY, it) }
                if (berth.locked) member.putBoolean(LOCKED_KEY, true)
                berth.post?.let {
                    member.putDouble(POST_X_KEY, it.x)
                    member.putDouble(POST_Y_KEY, it.y)
                    member.putDouble(POST_Z_KEY, it.z)
                }
                members.add(member)
            }
            entry.put(BERTHS_KEY, members)
            list.add(entry)
        }
        tag.put(CREWS_KEY, list)

        val stones = ListTag()
        for (id in tombstoned) {
            val stone = CompoundTag()
            stone.putString(VILLAGER_KEY, id.toString())
            stones.add(stone)
        }
        tag.put(TOMBSTONES_KEY, stones)
        return tag
    }

    companion object {
        const val SAVED_DATA_ID = "${EurekaMod.MOD_ID}_crews"

        private const val CREWS_KEY = "crews"
        private const val ID_KEY = "id"
        private const val CAPTAIN_KEY = "captain"
        private const val NAME_KEY = "name"
        /** Read, never written. The wood a crew's wheel was cut from, back when that was part of the key. */
        private const val VARIANT_KEY = "variant"
        private const val BERTHS_KEY = "berths"
        private const val VILLAGER_KEY = "villager"
        private const val SLOT_KEY = "slot"
        private const val MEMBER_NAME_KEY = "member_name"
        private const val SNAPSHOT_KEY = "snapshot"
        private const val ASHORE_KEY = "ashore"
        private const val DUTY_KEY = "duty"
        private const val STATION_KEY = "station"
        private const val STATION_LABEL_KEY = "station_label"
        private const val LOCKED_KEY = "locked"
        // Three keys rather than a list: a hand-edited articles file is meant to be readable, and "post_y"
        // says which number is the height without anyone counting commas.
        private const val POST_X_KEY = "post_x"
        private const val POST_Y_KEY = "post_y"
        private const val POST_Z_KEY = "post_z"
        private const val TOMBSTONES_KEY = "tombstones"

        private val TYPE: SavedData.Factory<CrewLedger> = SavedData.Factory(
            { CrewLedger() },
            { tag, _ -> load(tag) },
            DataFixTypes.LEVEL
        )

        /**
         * The world's one crew ledger.
         *
         * Deliberately the OVERWORLD's storage whichever level asks, so a wheel carried into the nether finds
         * the same crew it left with.
         */
        fun get(server: MinecraftServer): CrewLedger =
            server.overworld().dataStorage.computeIfAbsent(TYPE, SAVED_DATA_ID)

        /** A hand-edited or truncated id drops one entry rather than taking the world's crews down with it. */
        private fun parseUuid(raw: String): UUID? =
            if (raw.isEmpty()) null else try { UUID.fromString(raw) } catch (ex: IllegalArgumentException) { null }

        /**
         * The id an old wood-keyed crew migrates to. Deterministic, and that is the whole requirement.
         *
         * A random id would be fine for one load and wrong for every other: restore a world from backup, or
         * load the same save on another copy of the server, and every wheel in the world would point at ids
         * that no longer exist. Deriving it from the old key means the migration always lands in the same
         * place, however many times it runs.
         */
        private fun legacyId(captain: UUID, foldedName: String, variant: String): UUID =
            UUID.nameUUIDFromBytes("vs_eureka:crew|$captain|$foldedName|$variant".toByteArray(Charsets.UTF_8))

        /** "black pearl" -> "Black Pearl". Best-effort repair of a name the old ledger only kept folded. */
        private fun titleCase(folded: String): String =
            folded.split(' ')
                .joinToString(" ") { word ->
                    if (word.isEmpty()) word else word[0].uppercaseChar() + word.substring(1)
                }

        fun load(tag: CompoundTag): CrewLedger {
            val ledger = CrewLedger()
            val list = tag.getListOpt(CREWS_KEY).orElse(ListTag())
            for (element in list) {
                val entry = element as? CompoundTag ?: continue
                val captain = parseUuid(entry.getStringOpt(CAPTAIN_KEY).orElse("")) ?: continue
                val name = entry.getStringOpt(NAME_KEY).orElse("")
                if (name.isEmpty()) continue

                // Migration. A file written before crews had ids carries a wood VARIANT instead, and its NAME
                // is the folded lookup key rather than anything a captain ever typed. The id is minted from
                // all three so the same old file always migrates to the same ids -- reloading a backup has to
                // land on the crews the world already knows, or every wheel out there points at nothing. The
                // name is title-cased so a dropdown is legible until a wheel meets its crew and hands the real
                // casing back; see [refreshName].
                val stored = parseUuid(entry.getStringOpt(ID_KEY).orElse(""))
                val id = stored ?: legacyId(captain, name, entry.getStringOpt(VARIANT_KEY).orElse(""))
                val display = if (stored != null) name else titleCase(name)

                val berths = mutableListOf<Berth>()
                for (memberTag in entry.getListOpt(BERTHS_KEY).orElse(ListTag())) {
                    val member = memberTag as? CompoundTag ?: continue
                    val villager = parseUuid(member.getStringOpt(VILLAGER_KEY).orElse("")) ?: continue
                    val slot = member.getIntOpt(SLOT_KEY).orElse(CrewRoster.NO_SLOT)
                    // A berth written before names were carried falls back to the one the slot implies, which
                    // is what that crew member is actually called unless somebody renamed them.
                    val memberName = member.getStringOpt(MEMBER_NAME_KEY)
                        .orElse(CrewNames.defaultFor(slot))
                    val snapshot = member.getCompoundOpt(SNAPSHOT_KEY).orElse(null)
                    val ashore = member.getBooleanOpt(ASHORE_KEY).orElse(false)
                    val duty = CrewDuty.byId(member.getStringOpt(DUTY_KEY).orElse(CrewDuty.NONE.id))
                    val station = member.getLongOpt(STATION_KEY).orElse(null)
                    val stationLabel = member.getStringOpt(STATION_LABEL_KEY).orElse(null)?.ifEmpty { null }
                    val locked = member.getBooleanOpt(LOCKED_KEY).orElse(false)
                    // All three or none: a half-written post would put somebody on the keel or in the mast.
                    val postX = member.getDoubleOpt(POST_X_KEY).orElse(null)
                    val postY = member.getDoubleOpt(POST_Y_KEY).orElse(null)
                    val postZ = member.getDoubleOpt(POST_Z_KEY).orElse(null)
                    val post = if (postX != null && postY != null && postZ != null) Vec3(postX, postY, postZ) else null
                    berths.add(
                        Berth(villager, slot, memberName, snapshot, ashore, duty, station, stationLabel, locked, post)
                    )
                }
                if (berths.isEmpty()) continue

                // Two entries under one id -- a hand-edited file, or two old wood-variant crews that migrated
                // onto the same id -- are folded together rather than one silently replacing the other, and a
                // villager already berthed keeps the berth that was read first.
                val existing = ledger.crews[id]
                if (existing == null) {
                    ledger.crews[id] = Crew(id, captain, display, berths)
                } else {
                    for (berth in berths) {
                        if (existing.berths.none { it.villager == berth.villager }) existing.berths.add(berth)
                    }
                }
                // The reverse index is rebuilt here rather than stored, so it cannot disagree with the crews
                // it indexes. A villager listed under two crews in a hand-edited file resolves to the last
                // one read, which is a definite answer rather than a corrupt one.
                for (berth in ledger.crews.getValue(id).berths) ledger.byVillager[berth.villager] = id
            }

            ledger.disambiguate()

            for (element in tag.getListOpt(TOMBSTONES_KEY).orElse(ListTag())) {
                val stone = element as? CompoundTag ?: continue
                parseUuid(stone.getStringOpt(VILLAGER_KEY).orElse(""))?.let { ledger.tombstoned.add(it) }
            }
            return ledger
        }
    }
}
