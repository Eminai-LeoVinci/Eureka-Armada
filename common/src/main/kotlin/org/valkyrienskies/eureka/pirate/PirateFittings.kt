package org.valkyrienskies.eureka.pirate

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.AABB
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.blockentity.EngineBlockEntity

/**
 * Turns the fittings of a newly adopted pirate site into a raider's own.
 *
 * A pirate wheel is marked by its blockstate and a pirate crew by an entity tag; a pirate GUN or ENGINE is
 * marked by a flag on its block entity ([CannonBlockEntity.pirate], [EngineBlockEntity.pirate]), which is
 * the same place her powder, shot and coal live and therefore travels everywhere they do -- assembly into a
 * ship, a template save, a bottling, the placement of the next generated hull.
 *
 * The stamp is what keeps the economy honest, and it does the same job at both fittings. Guns are meant to
 * be won out of a raider's holds; a conquered prize carrying sixty of them bolted to her decks would
 * otherwise be a mine. Coal is meant to be dug; a hull authored with five engines filled to the brim so she
 * will sail for ever would otherwise be the cheapest colliery in the game. Neither pays out what it holds --
 * a taken gun yields a token four powder and two shot, a taken engine two coal -- and while her wheel still
 * stands, neither yields anything at all.
 *
 * It also buys her the other half of being a raider: bottomless magazines and bottomless bunkers while she
 * is fighting, so a long chase never ends because a pillager ran out of powder or coal. Both are config
 * switches (`pirateCannonInfiniteAmmo`, `pirateEngineInfiniteFuel`) and both still require the fitting to be
 * STOCKED -- an empty gun is silent and an empty engine is cold, whatever the stamp says.
 *
 * The stamp says nothing about who is USING her. A prize taken and sailed away answers her new captain on
 * real powder and real coal that they have to find; what she can never be is broken up for the powder and
 * coal already in her.
 */
object PirateFittings {

    /**
     * What a conquered engine yields, whatever her bunker actually holds.
     *
     * Deliberately NOT configurable, exactly as the gun's four powder and two shot are not: it is a rule
     * about what a prize is worth, not a difficulty knob. It lives here rather than on the block because
     * it is a fact about pirates, and because [org.valkyrienskies.eureka.block.EngineBlock] has no
     * companion to put it in on every version.
     */
    const val PRIZE_COAL = 2

    /**
     * Stamp every gun and engine in [box], and report how many were newly marked.
     *
     * Swept by chunk rather than block by block, exactly as [PirateLoot] sweeps for containers: a box twice
     * the size of the hull costs the same as a tight one, and being generous is what caught the guns a
     * wheel-centred box had been missing on a big ship. Both fittings ride the SAME sweep for the same
     * reason they share a rule -- two passes over the same chunks would be two chances to disagree about
     * which of them is aboard.
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
                // neighbouring chunk in to go looking for fittings would be a worse bug than missing one.
                val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                for (blockEntity in chunk.blockEntities.values) {
                    val pos = blockEntity.blockPos
                    if (!box.contains(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)) continue
                    when {
                        blockEntity is CannonBlockEntity && !blockEntity.pirate -> {
                            blockEntity.pirate = true
                            blockEntity.setChanged()
                            stamped++
                        }
                        blockEntity is EngineBlockEntity && !blockEntity.pirate -> {
                            blockEntity.pirate = true
                            blockEntity.setChanged()
                            stamped++
                        }
                    }
                }
            }
        }
        return stamped
    }
}
