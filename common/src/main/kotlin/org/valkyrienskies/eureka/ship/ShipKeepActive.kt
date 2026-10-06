package org.valkyrienskies.eureka.ship

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.api.SeatedControllingPlayer
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLevelFromDimensionId
import org.valkyrienskies.mod.common.playerWrapper
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.MinecraftPlayer
import org.valkyrienskies.mod.util.logger
import java.util.UUID
import kotlin.math.floor

/**
 * The per-ship "Keep Active" flag. Present means on.
 *
 * Its own attachment because official VS2's ShipSettings has no such field. Persisted, so the choice survives a
 * reload.
 */
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE
)
@JsonIgnoreProperties(ignoreUnknown = true)
class KeepActiveFlag {
    // Never read; Jackson won't serialize a class with no properties.
    @Suppress("unused")
    private var on = true
}

/** Whether this ship keeps simulating with nobody near it. See [ShipKeepActive]. */
var LoadedServerShip.keepActive: Boolean
    get() = getAttachment(KeepActiveFlag::class.java) != null
    set(value) {
        if (value == keepActive) return
        if (value) setAttachment(KeepActiveFlag()) else removeAttachment(KeepActiveFlag::class.java)
    }

/**
 * Keeps "active" ships simulating when no player is near them.
 *
 * A ship is active when [keepActive] is set, a player is seated piloting it, or a player is on or touching it.
 * VS2 only simulates a ship some player is watching, so every tick each active ship is handed to one real
 * player through VS2's `MinecraftPlayer.forceWatchingShips` (the nearest in its dimension), which makes VS2
 * treat that player as watching it wherever they are. The world chunks under the ship are force-loaded too, so
 * the ship's surroundings and anything aboard keep ticking. Both follow the ship as it moves and are released
 * when it stops being active.
 *
 * It needs a player online in the ship's dimension: with nobody there, nothing can be told to watch it.
 *
 * This replaces the manager our VS2 build used to carry, which slipped a fake observer into vs-core's player
 * list from inside VS2's server tick. forceWatchingShips is the supported way to do the same thing.
 */
object ShipKeepActive {

    private val logger by logger()

    /** Padding (blocks) around a ship's world box for the "a player is aboard or touching it" test. */
    private const val OCCUPY_MARGIN = 2.0

    /** World chunks force-loaded per active ship. */
    private val forced = HashMap<Long, ForcedEntry>()

    /**
     * Refcount per force-loaded world chunk, per dimension. Vanilla's forced ticket is one per chunk, so without
     * this two overlapping ships would un-force each other's chunks and one would stall.
     */
    private val worldForceRefs = HashMap<String, Long2IntOpenHashMap>()

    private class ForcedEntry(val dimensionId: String) {
        val chunks = LongOpenHashSet()
        var loggable = false
    }

    /**
     * Last known position of each keep-active ship. Lets an unloaded one still be handed to a player, so VS2
     * loads it back instead of it sitting frozen until someone sails over.
     */
    private val lastKnown = HashMap<Long, LastPos>()

    private class LastPos(val dimensionId: String, val x: Double, val y: Double, val z: Double)

    /** The ship ids this manager put into each player's forceWatchingShips, so it only ever removes its own. */
    private val handedOut = HashMap<UUID, MutableSet<Long>>()

    /** Run once per server tick. */
    @JvmStatic
    fun tick(server: MinecraftServer) {
        val shipWorld = server.shipObjectWorld
        val activeIds = HashSet<Long>()
        // ship id -> where to watch it from
        val watch = HashMap<Long, LastPos>()

        for (ship in shipWorld.loadedShips) {
            val manual = ship.keepActive
            if (!manual) lastKnown.remove(ship.id)
            val seated = ship.getAttachment(SeatedControllingPlayer::class.java)
            val piloted = seated != null
            // While someone is at the helm, it says which way the bow is; the influence border uses that.
            seated?.let { ShipInfluenceOrientation.observeForward(ship.id, it.seatInDirection) }
            val level = server.getLevelFromDimensionId(ship.chunkClaimDimension) ?: continue
            val occupied = !manual && !piloted && playersAboard(ship, level)
            if (!manual && !piloted && !occupied) continue
            activeIds.add(ship.id)

            val aabb = ship.worldAABB
            val centre = LastPos(
                ship.chunkClaimDimension,
                (aabb.minX() + aabb.maxX()) * 0.5,
                (aabb.minY() + aabb.maxY()) * 0.5,
                (aabb.minZ() + aabb.maxZ()) * 0.5
            )
            watch[ship.id] = centre
            if (manual) lastKnown[ship.id] = centre

            forceChunksUnder(ship, level, manual || piloted)
        }

        // Keep-active ships that have unloaded: keep handing them out from where they were last seen.
        if (lastKnown.isNotEmpty()) {
            val it = lastKnown.entries.iterator()
            while (it.hasNext()) {
                val (id, pos) = it.next()
                if (id in watch) continue
                if (shipWorld.allShips.getById(id) == null) {
                    it.remove()
                    continue
                }
                watch[id] = pos
            }
        }

        if (forced.isNotEmpty()) {
            for (id in forced.keys.filter { it !in activeIds }) {
                val wasLoggable = forced[id]?.loggable == true
                release(id, server)
                if (wasLoggable) logger.info("Ship $id no longer kept active; released its world chunks")
            }
        }

        handOut(server, watch)
    }

    /** Give each watched ship to the nearest player in its dimension, and take back anything no longer needed. */
    private fun handOut(server: MinecraftServer, watch: Map<Long, LastPos>) {
        val players = server.playerList.players
        val wanted = HashMap<UUID, MutableSet<Long>>()
        for ((shipId, pos) in watch) {
            val player = nearestPlayer(players, pos) ?: continue
            wanted.getOrPut(player.uuid) { HashSet() }.add(shipId)
        }

        for (player in players) {
            val set = (player.playerWrapper as? MinecraftPlayer)?.forceWatchingShips ?: continue
            val had = handedOut[player.uuid] ?: emptySet()
            val want = wanted[player.uuid] ?: emptySet()
            if (had == want) continue
            // Only ever take back what this manager added: other mods use the same set.
            for (id in had) if (id !in want) set.remove(id)
            for (id in want) if (id !in had) set.add(id)
            if (want.isEmpty()) handedOut.remove(player.uuid) else handedOut[player.uuid] = HashSet(want)
        }
        // Players who logged out take their wrapper, and its set, with them.
        if (handedOut.size > players.size) {
            val online = players.mapTo(HashSet()) { it.uuid }
            handedOut.keys.retainAll(online)
        }
    }

    private fun nearestPlayer(players: List<ServerPlayer>, pos: LastPos): ServerPlayer? {
        var best: ServerPlayer? = null
        var bestDist = Double.MAX_VALUE
        for (p in players) {
            if (p.isSpectator || p.level().dimensionId != pos.dimensionId) continue
            val d = p.distanceToSqr(pos.x, pos.y, pos.z)
            if (d < bestDist) {
                bestDist = d
                best = p
            }
        }
        return best
    }

    private fun forceChunksUnder(ship: LoadedServerShip, level: ServerLevel, loggable: Boolean) {
        val desired = worldChunksUnder(ship)
        val firstActivation = ship.id !in forced
        val entry = forced.getOrPut(ship.id) { ForcedEntry(ship.chunkClaimDimension) }
        entry.loggable = loggable

        val dIt = desired.iterator()
        while (dIt.hasNext()) {
            val packed = dIt.nextLong()
            if (entry.chunks.add(packed)) forceWorldChunk(level, entry.dimensionId, packed)
        }
        val cIt = entry.chunks.iterator()
        while (cIt.hasNext()) {
            val packed = cIt.nextLong()
            if (!desired.contains(packed)) {
                unforceWorldChunk(level, entry.dimensionId, packed)
                cIt.remove()
            }
        }

        if (firstActivation && loggable) {
            logger.info("Keeping ship ${ship.id} active; ${entry.chunks.size} world chunks force-loaded")
        }
    }

    private fun release(id: Long, server: MinecraftServer) {
        val entry = forced.remove(id) ?: return
        val level = server.getLevelFromDimensionId(entry.dimensionId) ?: return
        val it = entry.chunks.iterator()
        while (it.hasNext()) unforceWorldChunk(level, entry.dimensionId, it.nextLong())
    }

    /** Server stopping: release every ticket before vanilla's shutdown chunk drain runs. */
    @JvmStatic
    fun clearAll(server: MinecraftServer) {
        for (id in forced.keys.toList()) release(id, server)
        worldForceRefs.clear()
        lastKnown.clear()
        for (player in server.playerList.players) {
            val set = (player.playerWrapper as? MinecraftPlayer)?.forceWatchingShips ?: continue
            handedOut[player.uuid]?.let { set.removeAll(it) }
        }
        handedOut.clear()
    }

    private fun forceWorldChunk(level: ServerLevel, dim: String, packed: Long) {
        val refs = worldForceRefs.getOrPut(dim) { Long2IntOpenHashMap() }
        val n = refs.get(packed)
        // updateChunkForced, unlike setChunkForced, never writes the saved forced-chunk list, so nothing leaks
        // across a restart.
        if (n == 0) level.chunkSource.updateChunkForced(ChunkPos(packed), true)
        refs.put(packed, n + 1)
    }

    private fun unforceWorldChunk(level: ServerLevel, dim: String, packed: Long) {
        val refs = worldForceRefs[dim] ?: return
        val n = refs.get(packed)
        if (n <= 1) {
            refs.remove(packed)
            if (refs.isEmpty()) worldForceRefs.remove(dim)
            level.chunkSource.updateChunkForced(ChunkPos(packed), false)
        } else {
            refs.put(packed, n - 1)
        }
    }

    private fun worldChunksUnder(ship: LoadedServerShip): LongOpenHashSet {
        val aabb = ship.worldAABB
        val minCX = (floor(aabb.minX()).toInt() shr 4) - 1
        val maxCX = (floor(aabb.maxX()).toInt() shr 4) + 1
        val minCZ = (floor(aabb.minZ()).toInt() shr 4) - 1
        val maxCZ = (floor(aabb.maxZ()).toInt() shr 4) + 1
        val out = LongOpenHashSet()
        for (cx in minCX..maxCX) for (cz in minCZ..maxCZ) out.add(ChunkPos.asLong(cx, cz))
        return out
    }

    private fun playersAboard(ship: LoadedServerShip, level: ServerLevel): Boolean {
        val players = level.players()
        if (players.isEmpty()) return false
        val aabb = ship.worldAABB
        val minX = aabb.minX() - OCCUPY_MARGIN
        val maxX = aabb.maxX() + OCCUPY_MARGIN
        val minY = aabb.minY() - OCCUPY_MARGIN
        val maxY = aabb.maxY() + OCCUPY_MARGIN
        val minZ = aabb.minZ() - OCCUPY_MARGIN
        val maxZ = aabb.maxZ() + OCCUPY_MARGIN
        for (p in players) {
            if (p.x in minX..maxX && p.y in minY..maxY && p.z in minZ..maxZ) return true
        }
        return false
    }
}
