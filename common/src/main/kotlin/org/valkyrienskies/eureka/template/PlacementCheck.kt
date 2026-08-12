package org.valkyrienskies.eureka.template

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate

/**
 * Would this ship fit here?
 *
 * Ship-in-a-bottle asks this at the top of its arc: if the hull cannot appear without cutting into something,
 * the bottle falls back down and the player keeps it. The shipwright asks the same question about its build
 * pier. Both need an answer BEFORE anything is written, because a half-placed ship is not a state either
 * feature can recover from -- [StructureTemplate.placeInWorld] has no dry run and no rollback.
 *
 * ## What counts as "occupied"
 * Not "is there a block here" but "would placing here destroy something". Water, lava, grass, snow layers and
 * air all answer no: vanilla already treats them as replaceable when a player places a block, and a ship
 * settling into the sea is the normal case rather than a collision. Anything else is somebody's build, or the
 * seabed, and the ship does not get to overwrite it.
 *
 * Air *in the template* is skipped entirely. A hull is mostly its own interior, and requiring that empty space
 * be clear too would refuse almost every placement for no reason -- the blocks that never get written cannot
 * destroy anything.
 */
object PlacementCheck {

    sealed interface Result
    /** Nothing in the way. */
    data object Fits : Result
    /** [at] is the first world position that would have been overwritten. */
    class Blocked(val at: BlockPos, val by: String) : Result
    /** The hull would sit partly outside the world's build height. */
    class OutOfWorld(val at: BlockPos) : Result

    /**
     * Test [template] with its corner at [corner], writing nothing.
     *
     * Stops at the first obstruction. The caller only needs somewhere to point the player at, and a ship-sized
     * scan that keeps going after the answer is settled is wasted work on the server thread.
     */
    fun test(level: ServerLevel, template: StructureTemplate, corner: BlockPos): Result {
        val cursor = BlockPos.MutableBlockPos()
        for (palette in template.palettes) {
            for (info in palette.blocks()) {
                if (info.state.isAir) continue

                cursor.set(
                    corner.x + info.pos.x,
                    corner.y + info.pos.y,
                    corner.z + info.pos.z
                )

                // Placing outside build height is the crash the disassembler already guards against -- blocks
                // land in a chunk section that does not exist. Refuse for the same reason, before writing.
                if (level.isOutsideBuildHeight(cursor.y)) return OutOfWorld(cursor.immutable())

                // An ungenerated chunk cannot be holding anything of anyone's.
                if (!level.hasChunkAt(cursor)) continue

                val existing = level.getBlockState(cursor)
                if (!existing.canBeReplaced()) {
                    return Blocked(cursor.immutable(), existing.block.name.string)
                }
            }
        }
        return Fits
    }
}
