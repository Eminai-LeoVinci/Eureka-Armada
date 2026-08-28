package org.valkyrienskies.eureka.cannon

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.armada.ArmadaGroup
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Every gun aboard a vessel, counted when somebody asks and never kept.
 *
 * ## Why this is a scan rather than a tally
 * Balloons and floaters keep a running count on [org.valkyrienskies.eureka.ship.EurekaShipControl], incremented
 * by the block as it is placed and decremented as it is removed. That works because the answer is wanted every
 * physics tick and being one out for a moment costs nothing but a little lift.
 *
 * Guns are the other case entirely. The answer is wanted a few times a minute at most -- when a broadside is
 * ordered, or when a manifest is opened -- and being one out means a gun that will not fire, or a crew member
 * assigned to a gun that is not there. A counter has to be right about every way a block can leave the world:
 * broken, exploded, burned through, bottled, disassembled, replaced by the auto-shipwright, or carried off in a
 * repair. Every one of those is a place the tally can drift, and a drifted tally is invisible until a fight.
 *
 * So the guns are found by looking. It is cheap because it walks CHUNKS rather than blocks: a ship's block
 * entities are already sitting in a map per chunk, and a hull spanning four chunks each way is sixteen small
 * map iterations rather than the hundred thousand block reads its volume would cost.
 *
 * ## One entry per gun, not per block
 * A cannon is two blocks and only the breech carries a [CannonBlockEntity], so filtering for the block entity
 * counts each gun exactly once with nothing to de-duplicate.
 *
 * ## The whole armada, one gun deck
 * Welded ships are one vessel, so a gun on a child hull is one of the flagship's guns and is manned from the
 * same crew. Same rule balloons, engines and anchors already follow.
 */
object ShipGuns {

    /**
     * Every gun aboard [ship] and anything welded to it, readiest first.
     *
     * The order is what decides which guns get crewed when there are fewer gunners than guns, so it is not
     * arbitrary: a gun that is **loaded and off its cooldown** sorts ahead of one that is not. That is the
     * difference between three gunners manning the loaded port battery and three gunners standing at whichever
     * guns happened to be found first while the enemy is to port.
     *
     * Ties fall back to shipyard position, which is stable across calls -- so a volley fires the same guns in
     * the same order every time rather than shuffling under the player. When cannons gain their bow-relative
     * numbers, that is the tie-break to replace: this is the only place gun ORDER is decided.
     */
    fun aboard(level: ServerLevel, ship: LoadedServerShip): List<CannonBlockEntity> {
        val ships = level.shipObjectWorld.loadedShips
        val guns = ArrayList<CannonBlockEntity>()

        for (id in ArmadaGroup.idsOf(level, ship)) {
            val hull = ships.getById(id) ?: continue
            val aabb = hull.shipAABB ?: continue

            // Chunk coordinates, so this is a 2D sweep of a 3D box -- the y range is inside each chunk's own
            // block-entity map and costs nothing extra to cover.
            val minChunkX = aabb.minX() shr 4
            val maxChunkX = aabb.maxX() shr 4
            val minChunkZ = aabb.minZ() shr 4
            val maxChunkZ = aabb.maxZ() shr 4

            for (chunkX in minChunkX..maxChunkX) {
                for (chunkZ in minChunkZ..maxChunkZ) {
                    // getChunkNow, never getChunk: a ship's own chunks are loaded for as long as the ship is,
                    // so a miss here means a chunk that is not part of this hull and must not be forced in.
                    val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                    for (blockEntity in chunk.blockEntities.values) {
                        if (blockEntity !is CannonBlockEntity) continue
                        // A chunk is 16x16 and the hull's box rarely is, so the corners can hold blocks
                        // belonging to a neighbouring ship in the same shipyard region.
                        if (!inside(aabb, blockEntity.blockPos)) continue
                        guns.add(blockEntity)
                    }
                }
            }
        }

        val now = level.gameTime
        return guns.sortedWith(
            compareByDescending<CannonBlockEntity> { it.loaded && it.readyAt <= now }
                .thenBy { it.blockPos.x }
                .thenBy { it.blockPos.z }
                .thenBy { it.blockPos.y }
        )
    }

    /** How many guns are aboard, when only the count is wanted and the guns themselves are not. */
    fun count(level: ServerLevel, ship: LoadedServerShip): Int = aboard(level, ship).size

    private fun inside(aabb: org.joml.primitives.AABBic, pos: BlockPos): Boolean =
        pos.x >= aabb.minX() && pos.x <= aabb.maxX() &&
            pos.y >= aabb.minY() && pos.y <= aabb.maxY() &&
            pos.z >= aabb.minZ() && pos.z <= aabb.maxZ()
}
