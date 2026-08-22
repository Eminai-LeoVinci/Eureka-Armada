package org.valkyrienskies.eureka.pirate

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import org.valkyrienskies.eureka.EurekaMod

/**
 * Every pirate spawn site in one dimension, persisted with the world.
 *
 * ## Why world data and not the helm's NBT
 * The berth is the thing that OUTLIVES the ship built on it: conquer a pirate ship -- helm broken, hull
 * sailed away or sunk -- and thirty minutes later the site regenerates a fresh one. At that moment there is
 * no helm left to remember anything, so the durable record has to live with the world. The helm carries only
 * its own berth KEY ([org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity.pirateBerth]), which is how
 * a wheel finds its site again after assembly relocates it to the shipyard.
 *
 * [PathStore]'s idiom exactly: per-level via `dataStorage.computeIfAbsent`, codec-shaped persistence over
 * hand-written NBT, defensive loading that drops a bad entry rather than the world.
 *
 * [frozenShips] rides in the same store because a conquest freeze must survive a restart -- `isStatic`
 * persists on the ship itself, so a forgotten deadline would leave a hull frozen forever.
 */
class PirateStore : SavedData() {

    /** One pirate spawn site. [state] is [BERTHED] while a ship (assembled or not) belongs to it. */
    class Berth(
        val originPos: Long,
        val templateId: String,
        val sizeX: Int,
        val sizeY: Int,
        val sizeZ: Int,
        var state: Int = BERTHED,
        /**
         * Where this site's wheel was last seen, in WORLD coordinates -- which is not [originPos] the
         * moment a ship has sailed anywhere. The proximity ring is drawn here while the wheel is not
         * reporting, so a hull that chased somebody across an ocean and then drifted out of simulation
         * keeps its ring around HERSELF rather than snapping back to the beach she was generated on.
         */
        var lastPos: Long = originPos,
        /** gameTime deadline for site regeneration, or -1 while none is running. */
        var regenAt: Long = -1L,
        /** gameTime deadline for the crew respawn, or -1 while none is running. */
        var crewRespawnAt: Long = -1L
    )

    private val berths = LinkedHashMap<Long, Berth>()

    /** shipId -> gameTime deadline of its conquest freeze. */
    val frozenShips = LinkedHashMap<Long, Long>()

    val allBerths: Map<Long, Berth> get() = berths

    fun berth(id: Long): Berth? = berths[id]

    fun putBerth(id: Long, berth: Berth) {
        berths[id] = berth
        setDirty()
    }

    fun removeBerth(id: Long) {
        if (berths.remove(id) != null) setDirty()
    }

    /** Mutations to a [Berth]'s fields happen in place; callers say so here to reach the disk. */
    fun markDirty() = setDirty()

    fun saveToTag(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((id, berth) in berths) {
            val entry = CompoundTag()
            entry.putLong(ID_KEY, id)
            entry.putLong(ORIGIN_KEY, berth.originPos)
            entry.putLong(LAST_POS_KEY, berth.lastPos)
            entry.putString(TEMPLATE_KEY, berth.templateId)
            entry.putIntArray(SIZE_KEY, intArrayOf(berth.sizeX, berth.sizeY, berth.sizeZ))
            entry.putInt(STATE_KEY, berth.state)
            entry.putLong(REGEN_KEY, berth.regenAt)
            entry.putLong(RESPAWN_KEY, berth.crewRespawnAt)
            list.add(entry)
        }
        tag.put(BERTHS_KEY, list)

        val frozen = ListTag()
        for ((shipId, until) in frozenShips) {
            val entry = CompoundTag()
            entry.putLong(SHIP_KEY, shipId)
            entry.putLong(UNTIL_KEY, until)
            frozen.add(entry)
        }
        tag.put(FROZEN_KEY, frozen)
        return tag
    }

    companion object {
        const val SAVED_DATA_ID = "${EurekaMod.MOD_ID}_pirates"

        const val BERTHED = 0
        const val REGEN_WAIT = 1

        private const val BERTHS_KEY = "berths"
        private const val ID_KEY = "id"
        private const val ORIGIN_KEY = "origin"
        private const val LAST_POS_KEY = "last_pos"
        private const val TEMPLATE_KEY = "template"
        private const val SIZE_KEY = "size"
        private const val STATE_KEY = "state"
        private const val REGEN_KEY = "regen_at"
        private const val RESPAWN_KEY = "respawn_at"
        private const val FROZEN_KEY = "frozen"
        private const val SHIP_KEY = "ship"
        private const val UNTIL_KEY = "until"

        private val TYPE: SavedDataType<PirateStore> = SavedDataType(
            SAVED_DATA_ID,
            { PirateStore() },
            CompoundTag.CODEC.xmap({ load(it) }, { it.saveToTag(CompoundTag()) }),
            DataFixTypes.LEVEL
        )

        /** This level's pirate sites, created on first use. */
        fun get(level: ServerLevel): PirateStore = level.dataStorage.computeIfAbsent(TYPE)

        fun load(tag: CompoundTag): PirateStore {
            val store = PirateStore()
            for (element in tag.getList(BERTHS_KEY).orElse(ListTag())) {
                val entry = element as? CompoundTag ?: continue
                val id = entry.getLong(ID_KEY).orElse(0L)
                val template = entry.getString(TEMPLATE_KEY).orElse("")
                if (id == 0L || template.isEmpty()) continue // unreadable: drop the site, keep the world
                val size = entry.getIntArray(SIZE_KEY).orElse(intArrayOf(16, 16, 16))
                store.berths[id] = Berth(
                    originPos = entry.getLong(ORIGIN_KEY).orElse(id),
                    lastPos = entry.getLong(LAST_POS_KEY).orElse(entry.getLong(ORIGIN_KEY).orElse(id)),
                    templateId = template,
                    sizeX = size.getOrElse(0) { 16 },
                    sizeY = size.getOrElse(1) { 16 },
                    sizeZ = size.getOrElse(2) { 16 },
                    state = entry.getInt(STATE_KEY).orElse(BERTHED),
                    regenAt = entry.getLong(REGEN_KEY).orElse(-1L),
                    crewRespawnAt = entry.getLong(RESPAWN_KEY).orElse(-1L)
                )
            }
            for (element in tag.getList(FROZEN_KEY).orElse(ListTag())) {
                val entry = element as? CompoundTag ?: continue
                val ship = entry.getLong(SHIP_KEY).orElse(0L)
                if (ship != 0L) store.frozenShips[ship] = entry.getLong(UNTIL_KEY).orElse(0L)
            }
            return store
        }
    }
}
