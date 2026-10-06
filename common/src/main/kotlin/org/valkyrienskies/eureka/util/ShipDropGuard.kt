package org.valkyrienskies.eureka.util

import net.minecraft.core.BlockPos
import org.joml.primitives.AABBic

/**
 * No item drops from a ship's own blocks while VS2 moves or deletes them.
 *
 * VS2 clears blocks through ordinary block updates, so anything that depends on a neighbour breaks with a drop as
 * that neighbour goes: the second half of a bed, door or tall flower on ASSEMBLY (the ship already has its copy of
 * both halves), and every torch, ladder, lantern and door on DELETION (the bottle, the claim list or the loot
 * already has them). Either way the drop is a duplicate. Which bed halves break on assembly depends on the order
 * VS2 walks the blocks, which is why it was north/south beds and not east/west ones.
 *
 * [during] marks the ship's positions for the length of one VS2 call, and MixinBlockAssemblyNoDrops lets a block
 * at one of those positions break without dropping. Everything outside the area drops as normal, so a torch on an
 * outside wall still pops off with its item. Server thread only.
 */
object ShipDropGuard {

    private val active = ThreadLocal<((BlockPos) -> Boolean)?>()

    /** Run [run] with no drops from any of [positions]. */
    fun <T> during(positions: Set<BlockPos>, run: () -> T): T = guard({ positions.contains(it) }, run)

    /** Run [run] with no drops from anywhere inside [box] (inclusive block bounds). */
    fun <T> during(box: AABBic, run: () -> T): T = guard({
        it.x >= box.minX() && it.x <= box.maxX() && it.y >= box.minY() && it.y <= box.maxY() &&
            it.z >= box.minZ() && it.z <= box.maxZ()
    }, run)

    private fun <T> guard(test: (BlockPos) -> Boolean, run: () -> T): T {
        val previous = active.get()
        active.set(test)
        try {
            return run()
        } finally {
            active.set(previous)
        }
    }

    /** Is [pos] a ship block being moved or deleted right now? */
    @JvmStatic
    fun suppresses(pos: BlockPos): Boolean = active.get()?.invoke(pos) == true
}
