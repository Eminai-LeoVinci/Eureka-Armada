package org.valkyrienskies.eureka.path

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import org.joml.Vector3dc
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.mod.util.logger

/**
 * Every recorded route in one dimension, persisted with the world.
 *
 * ## Why world data and not a ship attachment
 * A route outlives the ship that recorded it -- you can scrap the survey boat and send a freighter round the
 * same loop -- so it can't hang off [org.valkyrienskies.eureka.armada.ArmadaShipControl]-style per-ship
 * attachment, which is where every other piece of Eureka state lives. Routes are world furniture, like a
 * waypoint or a road, so they live in world data.
 *
 * One store per LEVEL (not one global store keyed by dimension) because
 * [net.minecraft.world.level.storage.DimensionDataStorage] is already per-level: asking each level for its own
 * store gets per-dimension separation for free, with no dimension key to filter on and no way for a nether
 * route to be offered in the overworld by accident.
 *
 * ## 1.21.11 persistence
 * [SavedData] no longer has an overridable `save(...)`; persistence is codec-driven through a [SavedDataType]
 * supplied at the call site. The `CompoundTag.CODEC.xmap` round-trip below is the same shape VS2 uses for
 * `ShipSavedData` -- it keeps hand-written NBT while satisfying the codec-shaped API.
 */
class PathStore : SavedData() {

    private val paths = LinkedHashMap<Long, ShipPath>()
    private var nextId = 1L

    val all: Collection<ShipPath> get() = paths.values
    val isEmpty: Boolean get() = paths.isEmpty()

    fun byId(id: Long): ShipPath? = paths[id]

    /** Look a route up by id, or by a case-insensitive name match. */
    fun find(query: String): ShipPath? {
        query.toLongOrNull()?.let { id -> paths[id]?.let { return it } }
        return paths.values.firstOrNull { it.name.equals(query, ignoreCase = true) }
    }

    /** Build and store a route from finished control points. */
    fun create(dimension: String, control: DoubleArray, name: String? = null): ShipPath {
        val id = nextId++
        val path = ShipPath(id, name ?: "Route $id", dimension, control)
        paths[id] = path
        setDirty()
        return path
    }

    fun remove(id: Long): Boolean {
        val removed = paths.remove(id) != null
        if (removed) setDirty()
        return removed
    }

    fun rename(id: Long, name: String): Boolean {
        val path = paths[id] ?: return false
        path.name = name
        setDirty()
        return true
    }

    /**
     * The closest route to [pos] and its distance, or null if none is within [maxDistance].
     *
     * A full nearest-point search over every route, which is O(routes x points) -- fine, because this only
     * runs when a player presses play, never per tick.
     */
    fun nearest(pos: Vector3dc, maxDistance: Double): Pair<ShipPath, Double>? {
        var best: ShipPath? = null
        var bestDist = Double.MAX_VALUE
        for (path in paths.values) {
            val d = path.distanceTo(pos)
            if (d < bestDist) { bestDist = d; best = path }
        }
        val chosen = best ?: return null
        return if (bestDist <= maxDistance) chosen to bestDist else null
    }

    /** The closest route regardless of range, for reporting how far away the nearest one actually is. */
    fun nearestAny(pos: Vector3dc): Pair<ShipPath, Double>? = nearest(pos, Double.MAX_VALUE)

    fun saveToTag(tag: CompoundTag): CompoundTag {
        tag.putLong(NEXT_ID_KEY, nextId)
        val list = ListTag()
        for (path in paths.values) {
            val entry = CompoundTag()
            entry.putLong(ID_KEY, path.id)
            entry.putString(NAME_KEY, path.name)
            entry.putString(DIM_KEY, path.dimension)
            entry.putLongArray(POINTS_KEY, encodeDoubles(path.control))
            list.add(entry)
        }
        tag.put(PATHS_KEY, list)
        return tag
    }

    companion object {
        const val SAVED_DATA_ID = "${EurekaMod.MOD_ID}_paths"

        private const val PATHS_KEY = "paths"
        private const val NEXT_ID_KEY = "next_id"
        private const val ID_KEY = "id"
        private const val NAME_KEY = "name"
        private const val DIM_KEY = "dim"
        private const val POINTS_KEY = "pts"

        private val logger by logger()

        private val TYPE: SavedDataType<PathStore> = SavedDataType(
            SAVED_DATA_ID,
            { PathStore() },
            CompoundTag.CODEC.xmap({ load(it) }, { it.saveToTag(CompoundTag()) }),
            DataFixTypes.LEVEL
        )

        /** This level's route store, created on first use. */
        fun get(level: ServerLevel): PathStore = level.dataStorage.computeIfAbsent(TYPE)

        fun load(tag: CompoundTag): PathStore {
            val store = PathStore()
            store.nextId = tag.getLong(NEXT_ID_KEY).orElse(1L)

            val list = tag.getList(PATHS_KEY).orElse(ListTag())
            for (element in list) {
                val entry = element as? CompoundTag ?: continue
                val id = entry.getLong(ID_KEY).orElse(0L)
                val name = entry.getString(NAME_KEY).orElse("Route $id")
                val dim = entry.getString(DIM_KEY).orElse("")
                val raw = entry.getLongArray(POINTS_KEY).orElse(LongArray(0))
                if (raw.isEmpty()) continue

                // A malformed or truncated route must not take the world down with it -- drop the one route
                // and keep the rest. ShipPath's constructor rejects anything too short to be a loop.
                val path = try {
                    ShipPath(id, name, dim, decodeDoubles(raw))
                } catch (ex: Exception) {
                    logger.error("Dropping unreadable path '{}' (id {}): {}", name, id, ex.message)
                    continue
                }
                store.paths[id] = path
                if (id >= store.nextId) store.nextId = id + 1
            }
            return store
        }

        // NBT has byte/int/long array tags but no double array tag. Raw bits round-trip exactly and pack as
        // tightly as the doubles themselves, which beats a ListTag of DoubleTag on both counts.
        private fun encodeDoubles(values: DoubleArray) =
            LongArray(values.size) { java.lang.Double.doubleToRawLongBits(values[it]) }

        private fun decodeDoubles(values: LongArray) =
            DoubleArray(values.size) { java.lang.Double.longBitsToDouble(values[it]) }
    }
}
