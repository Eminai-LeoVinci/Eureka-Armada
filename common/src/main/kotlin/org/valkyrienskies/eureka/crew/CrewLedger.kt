package org.valkyrienskies.eureka.crew

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
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
 * So the helm became a KEY rather than a container. What a wheel carries is one string -- its name -- and the
 * crew are looked up by [Key]. That is also what makes the rest of the feature cheap: nothing has to be
 * serialised onto the item, no block-entity payload rides the drop, and two wheels of the same name and wood
 * are the same crew without anything having to synchronise them.
 *
 * ## The key
 * All three parts earn their place. The CAPTAIN is what stops one player recovering another's crew by
 * guessing a name. The NAME is what a captain retypes from memory, so it is matched forgivingly (see
 * [HelmNames.keyOf]). The VARIANT -- the wheel's wood -- is a second secret that costs nothing to remember
 * and makes a guessed name useless on its own.
 *
 * ## Global, not per-dimension
 * Unlike [org.valkyrienskies.eureka.path.PathStore], which is deliberately one store per level because a
 * nether route has no business being offered in the overworld, this hangs off the OVERWORLD's storage and is
 * shared. A wheel can be carried through a portal, and a crew that stayed behind in the dimension they were
 * signed on in would be a bug rather than a rule.
 */
class CrewLedger : SavedData() {

    /** Who a crew belongs to, what they are called, and what their wheel is made of. */
    data class Key(val captain: UUID, val name: String, val variant: String)

    /**
     * One signed-on crew member. [slot] is their berth number, which names them and fixes their row.
     *
     * [name] is carried here as well as on the villager because the manifest has to be able to list somebody
     * who is not standing in front of it. A crew member left ashore, or in an unloaded chunk, is still on the
     * articles and still has to appear in the list -- and there is nothing to read a name off when the entity
     * is not there. Kept in step by every path that renames one.
     */
    data class Berth(val villager: UUID, val slot: Int, val name: String)

    private val crews = LinkedHashMap<Key, MutableList<Berth>>()

    /**
     * Villager -> the crew they serve on. Derived, never persisted: rebuilt from [crews] on load, because a
     * second stored copy of the same fact is a second thing that can be wrong.
     *
     * This index is the whole of the one-crew-at-a-time rule. Without it the question "is this villager
     * already signed on somewhere?" would be a scan of every crew in the world on every recruit.
     */
    private val byVillager = HashMap<UUID, Key>()

    // region reading

    /** The berths of the crew filed under [key], oldest first. Empty for a crew that does not exist. */
    fun crew(key: Key): List<Berth> = crews[key] ?: emptyList()

    /** The crew [villager] already serves on, or null if they are nobody's. */
    fun crewOf(villager: UUID): Key? = byVillager[villager]

    /** Whether [captain] already has a crew of this name and wood. Used to keep generated names distinct. */
    fun exists(captain: UUID, name: String, variant: String): Boolean =
        crews.containsKey(Key(captain, HelmNames.keyOf(name), variant))

    /** Whether ANY captain has crew filed under this name and wood -- the question a wheel asks about itself. */
    fun anyUnder(name: String, variant: String): Boolean {
        val nameKey = HelmNames.keyOf(name)
        return crews.any { (key, berths) -> key.name == nameKey && key.variant == variant && berths.isNotEmpty() }
    }

    /** The berth [villager] holds, or [CrewRoster.NO_SLOT]. */
    fun slotOf(villager: UUID): Int {
        val key = byVillager[villager] ?: return CrewRoster.NO_SLOT
        return crews[key]?.firstOrNull { it.villager == villager }?.slot ?: CrewRoster.NO_SLOT
    }

    /**
     * The lowest berth number [key] is not already using, or [CrewRoster.NO_SLOT] past [max].
     *
     * Lowest-free rather than next-highest so that paying somebody off and signing somebody else on reuses the
     * empty berth, instead of marching the numbers up and leaving gaps in the manifest.
     */
    fun freeSlot(key: Key, max: Int): Int {
        val taken = crews[key]?.mapTo(HashSet()) { it.slot } ?: return 0
        for (slot in 0 until max) if (slot !in taken) return slot
        return CrewRoster.NO_SLOT
    }

    // endregion

    // region writing

    /** Sign [villager] onto [key] at [slot]. Caller has already established they are nobody else's. */
    fun sign(key: Key, villager: UUID, slot: Int, name: String) {
        crews.getOrPut(key) { mutableListOf() }.add(Berth(villager, slot, name))
        byVillager[villager] = key
        setDirty()
    }

    /** Record that [villager] is now called [name], so the manifest can list them while they are away. */
    fun renameMember(villager: UUID, name: String) {
        val key = byVillager[villager] ?: return
        val list = crews[key] ?: return
        val index = list.indexOfFirst { it.villager == villager }
        if (index < 0 || list[index].name == name) return
        list[index] = list[index].copy(name = name)
        setDirty()
    }

    /** Discharge [villager] from whatever crew they are on. Returns the crew they left, or null. */
    fun payOff(villager: UUID): Key? {
        val key = byVillager.remove(villager) ?: return null
        val list = crews[key]
        list?.removeIf { it.villager == villager }
        // A crew with nobody in it is not a crew, but the KEY is kept alive by the wheel that carries the
        // name, so dropping the empty list costs nothing and keeps the file from filling with husks.
        if (list != null && list.isEmpty()) crews.remove(key)
        setDirty()
        return key
    }

    /**
     * Move a crew from one name to another, for a wheel being renamed.
     *
     * Refuses rather than merges when the destination already has a crew. Merging would be irreversible and
     * silent -- two crews walk in, one walks out, and no message could put them back -- whereas refusing costs
     * the captain one rename they have to think about. Moving ONTO an empty key is the ordinary case and is
     * what makes a rename keep the crew rather than strand them under the old name.
     */
    /**
     * Re-file every crew on a wheel that has just been renamed, whatever captain they belong to.
     *
     * A wheel's name changes for everybody at once, so moving only the renaming player's crew would orphan
     * anyone else's under a name no wheel answers to any more. Returns false and moves NOTHING if any single
     * crew would collide, because a rename that half-happened is worse than one that did not.
     */
    fun renameAll(oldName: String, newName: String, variant: String): Boolean {
        val from = HelmNames.keyOf(oldName)
        val to = HelmNames.keyOf(newName)
        if (from == to) return true

        val moving = crews.keys.filter { it.name == from && it.variant == variant }
        if (moving.isEmpty()) return true
        if (moving.any { crews[Key(it.captain, to, variant)]?.isNotEmpty() == true }) return false

        for (key in moving) {
            val berths = crews.remove(key) ?: continue
            val target = Key(key.captain, to, variant)
            crews[target] = berths
            for (berth in berths) byVillager[berth.villager] = target
        }
        setDirty()
        return true
    }

    /**
     * Take over a wheel's old block-entity roster, so crew signed on before the ledger existed are not lost.
     *
     * Runs the first time a wheel with a legacy roster gets a name -- which is the first moment there IS a key
     * to file them under. The roster is cleared afterwards so this can only happen once and the two can never
     * disagree.
     */
    fun adoptLegacy(station: org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity) {
        val name = station.helmName ?: return
        if (station.crew.isEmpty) return
        val nameKey = HelmNames.keyOf(name)
        val variant = HelmNames.variantOf(station.blockState)
        for (entry in station.crew.entries()) {
            // Anyone the ledger already knows keeps the crew they are on; the old roster is the stale copy.
            if (byVillager.containsKey(entry.villager)) continue
            val key = Key(entry.owner, nameKey, variant)
            crews.getOrPut(key) { mutableListOf() }
                .add(Berth(entry.villager, entry.slot, CrewNames.defaultFor(entry.slot)))
            byVillager[entry.villager] = key
        }
        station.crew.replaceAll(emptyList())
        station.setChanged()
        setDirty()
    }

    fun rename(from: Key, to: Key): Boolean {
        if (from == to) return true
        val moving = crews[from] ?: return true            // nothing to move; the rename is free
        if (crews[to]?.isNotEmpty() == true) return false  // would merge two crews
        crews.remove(from)
        crews[to] = moving
        for (berth in moving) byVillager[berth.villager] = to
        setDirty()
        return true
    }

    // endregion

    fun saveToTag(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((key, berths) in crews) {
            if (berths.isEmpty()) continue
            val entry = CompoundTag()
            // UUIDs as strings rather than through a codec: this file is a few dozen entries at most, so the
            // handful of extra bytes buys a save anyone can read and hand-edit when a crew needs rescuing.
            entry.putString(CAPTAIN_KEY, key.captain.toString())
            entry.putString(NAME_KEY, key.name)
            entry.putString(VARIANT_KEY, key.variant)
            val members = ListTag()
            for (berth in berths) {
                val member = CompoundTag()
                member.putString(VILLAGER_KEY, berth.villager.toString())
                member.putInt(SLOT_KEY, berth.slot)
                member.putString(MEMBER_NAME_KEY, berth.name)
                members.add(member)
            }
            entry.put(BERTHS_KEY, members)
            list.add(entry)
        }
        tag.put(CREWS_KEY, list)
        return tag
    }

    companion object {
        const val SAVED_DATA_ID = "${EurekaMod.MOD_ID}_crews"

        private const val CREWS_KEY = "crews"
        private const val CAPTAIN_KEY = "captain"
        private const val NAME_KEY = "name"
        private const val VARIANT_KEY = "variant"
        private const val BERTHS_KEY = "berths"
        private const val VILLAGER_KEY = "villager"
        private const val SLOT_KEY = "slot"
        private const val MEMBER_NAME_KEY = "member_name"

        private val TYPE: SavedDataType<CrewLedger> = SavedDataType(
            SAVED_DATA_ID,
            { CrewLedger() },
            CompoundTag.CODEC.xmap({ load(it) }, { it.saveToTag(CompoundTag()) }),
            DataFixTypes.LEVEL
        )

        /**
         * The world's one crew ledger.
         *
         * Deliberately the OVERWORLD's storage whichever level asks, so a wheel carried into the nether finds
         * the same crew it left with.
         */
        fun get(server: MinecraftServer): CrewLedger =
            server.overworld().dataStorage.computeIfAbsent(TYPE)

        /** Build the key a wheel's name and wood make for [captain]. Null when the wheel is unnamed. */
        fun keyFor(captain: UUID, name: net.minecraft.network.chat.Component?, variant: String): Key? {
            val text = name ?: return null
            return Key(captain, HelmNames.keyOf(text), variant)
        }

        /** A hand-edited or truncated id drops one entry rather than taking the world's crews down with it. */
        private fun parseUuid(raw: String): UUID? =
            if (raw.isEmpty()) null else try { UUID.fromString(raw) } catch (ex: IllegalArgumentException) { null }

        fun load(tag: CompoundTag): CrewLedger {
            val ledger = CrewLedger()
            val list = tag.getList(CREWS_KEY).orElse(ListTag())
            for (element in list) {
                val entry = element as? CompoundTag ?: continue
                val captain = parseUuid(entry.getString(CAPTAIN_KEY).orElse("")) ?: continue
                val name = entry.getString(NAME_KEY).orElse("")
                val variant = entry.getString(VARIANT_KEY).orElse("")
                if (name.isEmpty() || variant.isEmpty()) continue

                val key = Key(captain, name, variant)
                val berths = mutableListOf<Berth>()
                for (memberTag in entry.getList(BERTHS_KEY).orElse(ListTag())) {
                    val member = memberTag as? CompoundTag ?: continue
                    val villager = parseUuid(member.getString(VILLAGER_KEY).orElse("")) ?: continue
                    val slot = member.getInt(SLOT_KEY).orElse(CrewRoster.NO_SLOT)
                    // A berth written before names were carried falls back to the one the slot implies, which
                    // is what that crew member is actually called unless somebody renamed them.
                    val memberName = member.getString(MEMBER_NAME_KEY)
                        .orElse(CrewNames.defaultFor(slot))
                    berths.add(Berth(villager, slot, memberName))
                }
                if (berths.isEmpty()) continue

                ledger.crews[key] = berths
                // The reverse index is rebuilt here rather than stored, so it cannot disagree with the crews
                // it indexes. A villager listed under two crews in a hand-edited file resolves to the last
                // one read, which is a definite answer rather than a corrupt one.
                for (berth in berths) ledger.byVillager[berth.villager] = key
            }
            return ledger
        }
    }
}
