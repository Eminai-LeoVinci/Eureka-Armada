package org.valkyrienskies.eureka.ship

import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.armada.ArmadaGroup
import org.valkyrienskies.eureka.blockentity.EngineBlockEntity
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Every engine aboard a ship, armada-wide, in a deterministic order.
 *
 * The same chunk-walk as `ShipGuns.aboard` -- hull AABBs give chunk ranges, `getChunkNow` never forces a
 * chunk in, the corner check keeps a neighbouring ship's engine room out of this one's -- because the
 * alternative is the whole-volume block scan `ShipwrightYard.fuelOf` does, which reads every block of
 * every hull to find a dozen engines, and covers only one hull at that.
 *
 * The (x, z, y) sort is not tidiness: the refueller hands its remainder to "the first engines", and that
 * phrase has to mean the SAME engines every press, or the leftovers would wander the engine room one
 * refuel at a time.
 */
object ShipEngines {

    fun aboard(level: ServerLevel, ship: LoadedServerShip): List<EngineBlockEntity> {
        val ships = level.shipObjectWorld.loadedShips
        val engines = ArrayList<EngineBlockEntity>()

        for (id in ArmadaGroup.idsOf(level, ship)) {
            val hull = ships.getById(id) ?: continue
            val aabb = hull.shipAABB ?: continue

            val minChunkX = aabb.minX() shr 4
            val maxChunkX = aabb.maxX() shr 4
            val minChunkZ = aabb.minZ() shr 4
            val maxChunkZ = aabb.maxZ() shr 4

            for (chunkX in minChunkX..maxChunkX) {
                for (chunkZ in minChunkZ..maxChunkZ) {
                    val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                    for (blockEntity in chunk.blockEntities.values) {
                        if (blockEntity !is EngineBlockEntity) continue
                        val pos = blockEntity.blockPos
                        if (pos.x < aabb.minX() || pos.x > aabb.maxX() ||
                            pos.y < aabb.minY() || pos.y > aabb.maxY() ||
                            pos.z < aabb.minZ() || pos.z > aabb.maxZ()
                        ) {
                            continue
                        }
                        engines.add(blockEntity)
                    }
                }
            }
        }

        return engines.sortedWith(
            compareBy({ it.blockPos.x }, { it.blockPos.z }, { it.blockPos.y })
        )
    }
}
