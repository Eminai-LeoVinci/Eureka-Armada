package org.valkyrienskies.eureka.cannon

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * Taking a measured bite out of whatever the shot hit.
 *
 * ## Why not just call an explosion
 * A vanilla explosion decides for itself how much it destroys, from blast resistance and a radius. The whole
 * point of the cannonball tiers is that the *round* decides -- see [org.valkyrienskies.eureka.item.Cannonball]
 * -- so the count comes in already rolled and this only has to choose which blocks.
 *
 * ## Shipyard space throughout
 * [origin] is whatever `BlockHitResult.getBlockPos` handed back, which for a hit on a ship is a **shipyard**
 * position. Every neighbour walked from it is therefore a real neighbour on that hull, and the same code
 * works unchanged on a hull, on terrain, and on a ship that is currently rotated forty degrees -- because none
 * of it ever leaves the space the blocks actually live in.
 */
object CannonDamage {

    /**
     * Destroy [count] blocks outward from [origin].
     *
     * Breadth-first, so damage is a hole centred on the point of impact rather than a line bored through the
     * hull or a scatter of unconnected gaps. Air is stepped through but never counted: a round that clips the
     * edge of a deck should still take its full bite out of the timber it reaches, and counting air would let
     * a glancing hit spend its whole allowance on nothing.
     *
     * Nothing drops. A cannon is a weapon, not a quarry, and a gun that returned the hull it removed would be
     * the cheapest mining tool in the game -- point it at a mountain and collect. Putting a ship back together
     * is the shipwright's job, which is exactly the loop the repair feature exists to close.
     */
    fun punch(level: ServerLevel, origin: BlockPos, count: Int) {
        if (count <= 0) return

        val taken = ArrayList<BlockPos>(count)
        val seen = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()

        queue.add(origin)
        seen.add(origin)

        while (queue.isNotEmpty() && taken.size < count) {
            val pos = queue.removeFirst()
            val state = level.getBlockState(pos)

            if (!state.isAir) {
                // Bedrock and its kin report a negative hardness. A gun that could chew through those would
                // make the world's floor a suggestion.
                if (state.getDestroySpeed(level, pos) < 0) continue
                taken.add(pos)
            }

            if (seen.size > SEARCH_LIMIT) break
            for (face in Direction.entries) {
                val next = pos.relative(face)
                if (seen.add(next)) queue.add(next)
            }
        }

        for (pos in taken) {
            level.destroyBlock(pos, false)
        }
    }

    /**
     * A ceiling on how far the search will wander looking for something solid.
     *
     * Without it, a round that detonates in open air on a near-miss would flood-fill the sky until it found
     * [count] blocks -- which for netherite means walking twelve blocks' worth of empty space in every
     * direction and then punching a hole in whatever unlucky thing it met.
     */
    private const val SEARCH_LIMIT = 512
}
