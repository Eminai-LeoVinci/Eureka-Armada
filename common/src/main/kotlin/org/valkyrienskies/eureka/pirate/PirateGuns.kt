package org.valkyrienskies.eureka.pirate

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.AABB
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity

/**
 * Turns the cannons of a newly adopted pirate site into raiders' guns.
 *
 * A pirate wheel is marked by its blockstate and a pirate crew by an entity tag; a pirate GUN is marked by a
 * flag on its block entity ([CannonBlockEntity.pirate]), which is the same place her powder and shot live and
 * therefore travels everywhere they do -- assembly into a ship, a template save, a bottling, the placement of
 * the next generated hull.
 *
 * The stamp is what keeps the cannon economy honest. Guns are meant to be won out of a raider's holds, and a
 * conquered prize carrying sixty of them bolted to her decks would otherwise be a mine: break the deck,
 * pocket sixty cannons, never open a loot table again. A stamped gun drops nothing at all -- no gun and no
 * magazine -- so a prize is worth taking for the ship she is rather than for the iron she is made of.
 */
object PirateGuns {

    /**
     * Stamp every cannon in [box], and report how many were newly marked.
     *
     * Swept by chunk rather than block by block, exactly as [PirateLoot] sweeps for containers: a box twice
     * the size of the hull costs the same as a tight one, and being generous is what caught the guns a
     * wheel-centred box had been missing on a big ship.
     */
    fun stampAll(level: ServerLevel, box: AABB): Int {
        var stamped = 0
        val minChunkX = Math.floorDiv(Math.floor(box.minX).toInt(), 16)
        val maxChunkX = Math.floorDiv(Math.floor(box.maxX).toInt(), 16)
        val minChunkZ = Math.floorDiv(Math.floor(box.minZ).toInt(), 16)
        val maxChunkZ = Math.floorDiv(Math.floor(box.maxZ).toInt(), 16)

        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                // getChunkNow, never getChunk: the site being adopted is loaded by definition, and forcing a
                // neighbouring chunk in to go looking for guns would be a worse bug than missing one.
                val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                for (blockEntity in chunk.blockEntities.values) {
                    if (blockEntity !is CannonBlockEntity || blockEntity.pirate) continue
                    val pos = blockEntity.blockPos
                    if (!box.contains(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)) continue
                    blockEntity.pirate = true
                    blockEntity.setChanged()
                    stamped++
                }
            }
        }
        return stamped
    }
}
