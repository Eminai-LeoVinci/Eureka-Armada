package org.valkyrienskies.eureka.crew

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.UUIDUtil
import java.util.UUID

/**
 * Who is signed on at one helm, which berth they hold, and who signed them.
 *
 * The roster belongs to the WHEEL, not to the villagers and not to a captain's list. That is the whole design:
 * a ship's articles are a thing that exists at a particular helm, so breaking that helm tears them up and the
 * crew has to be picked again. Nothing else needs to be cleaned up when it happens, because there is nowhere
 * else the membership was written down.
 *
 * The owner travels with each entry because berths are still the CAPTAIN's -- one ship can carry two captains'
 * crews, each counted against their own berths -- while the limit is still the SHIP's.
 *
 * ## The berth number
 * [Entry.slot] is what the "3" in "Crewman3" means, and it is the row a crew member occupies in the manifest.
 * It is an IDENTITY, not a reservation: it never enters the berth count, which is still computed on the spot
 * from who is actually aboard. Numbers are handed out per captain, lowest free first, so paying someone off
 * and signing someone else on reuses the number rather than climbing forever.
 *
 * ## Why stale entries are harmless
 * A villager that dies, despawns, or simply walks off onto the next ship leaves its id sitting in this map.
 * That costs a UUID and nothing else, because the crew of a ship is never read from here alone: it is the
 * INTERSECTION of this roster with the villagers actually standing on that ship. Someone who is not aboard is
 * not crew, whatever the map says, and if they wander back they are crew again. That is also what lets a
 * villager be poached by a second ship without any cross-ship bookkeeping -- they end up in two rosters and
 * count in exactly one, the one they are standing on.
 */
class CrewRoster {

    private val marks = LinkedHashMap<UUID, Entry>()

    val isEmpty: Boolean get() = marks.isEmpty()

    val size: Int get() = marks.size

    /** The captain who signed [villager] on at this helm, or null if they are not on these articles. */
    fun ownerOf(villager: UUID): UUID? = marks[villager]?.owner

    /** The berth [villager] holds at this helm, or -1 if they are not on these articles. */
    fun slotOf(villager: UUID): Int = marks[villager]?.slot ?: NO_SLOT

    fun sign(villager: UUID, owner: UUID, slot: Int) {
        marks[villager] = Entry(villager, owner, slot)
    }

    /** Returns true if [villager] was actually on the articles. */
    fun payOff(villager: UUID): Boolean = marks.remove(villager) != null

    /**
     * The lowest berth number in `0 until max` that [owner] is not already using at this helm.
     *
     * Numbers are per captain, exactly as berths are, so two captains crewing one hull both count from one and
     * neither can exhaust the other's numbering. Returns [NO_SLOT] only if a captain somehow holds every
     * number, which the berth limit already prevents.
     */
    fun freeSlot(owner: UUID, max: Int): Int {
        val taken = marks.values.asSequence().filter { it.owner == owner }.map { it.slot }.toHashSet()
        for (slot in 0 until max) if (slot !in taken) return slot
        return NO_SLOT
    }

    fun entries(): List<Entry> = marks.values.toList()

    /**
     * Replace the whole roster, healing any entry that predates berth numbers.
     *
     * [Entry.slot] is an optional field precisely so an already-saved roster still decodes; the ones that come
     * back as [NO_SLOT] are handed numbers here, in the order they were written, which is the order they were
     * signed on.
     */
    fun replaceAll(entries: List<Entry>) {
        marks.clear()
        val healed = ArrayList<Entry>(entries.size)
        for (entry in entries) {
            if (entry.slot != NO_SLOT) marks[entry.villager] = entry else healed.add(entry)
        }
        for (entry in healed) {
            // freeSlot reads `marks`, so the already-numbered entries above are respected and each healed
            // entry is visible to the next one.
            marks[entry.villager] = entry.copy(slot = freeSlot(entry.owner, DEFAULT_MAX_SLOTS))
        }
    }

    data class Entry(val villager: UUID, val owner: UUID, val slot: Int)

    companion object {
        const val NO_SLOT = -1

        /**
         * The berth ceiling used only when healing a roster written before berth numbers existed. The live cap
         * is `EurekaConfig.SERVER.crewSlotsMax`; this constant exists so the codec stays free of config reads,
         * and healing past a lowered cap is harmless because the number is never counted against anything.
         */
        private const val DEFAULT_MAX_SLOTS = 32

        private val ENTRY_CODEC: Codec<Entry> = RecordCodecBuilder.create { instance ->
            instance.group(
                UUIDUtil.CODEC.fieldOf("villager").forGetter(Entry::villager),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Entry::owner),
                Codec.INT.optionalFieldOf("slot", NO_SLOT).forGetter(Entry::slot)
            ).apply(instance, ::Entry)
        }

        val CODEC: Codec<List<Entry>> = ENTRY_CODEC.listOf()
    }
}
