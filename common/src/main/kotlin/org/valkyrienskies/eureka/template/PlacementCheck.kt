package org.valkyrienskies.eureka.template

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.valkyrienskies.eureka.EurekaMod

/**
 * Would this ship fit here?
 *
 * Ship-in-a-bottle asks this at the top of its arc: if the hull cannot appear without cutting into something,
 * the bottle falls back down and the player keeps it. The shipwright asks the same question about its build
 * pier. Both need an answer BEFORE anything is written, because a half-placed ship is not a state either
 * feature can recover from -- [StructureTemplate.placeInWorld] has no dry run and no rollback.
 *
 * ## What counts as "occupied"
 * Not "is there a block here" but "would placing here destroy something". Water, lava, snow layers and air
 * all answer no -- vanilla already treats them as replaceable when a player places a block, and a ship
 * settling into the sea is the normal case rather than a collision. Wild vegetation answers no as well, by
 * the [SETTLE_THROUGH] tag, because a plant the hull would flatten anyway is no reason to refuse the hull.
 * Anything else is somebody's build, or the seabed, and the ship does not get to overwrite it.
 *
 * Air *in the template* is skipped entirely. A hull is mostly its own interior, and requiring that empty space
 * be clear too would refuse almost every placement for no reason -- the blocks that never get written cannot
 * destroy anything.
 */
object PlacementCheck {

    /**
     * Vegetation a hull is allowed to settle through: kelp, seagrass, grass, ferns, flowers, vines.
     *
     * Vanilla's own `canBeReplaced` is nearly the right question and stops just short of it. Water, lava,
     * grass and snow answer yes, but kelp does not -- so a bottle thrown at any ordinary stretch of ocean
     * came back refused with "kelp is in the way", over a plant the hull would have mown flat on its way in
     * had it been allowed to land at all. Refusing to place because of something that cannot survive the
     * placement is a rule with nothing behind it.
     *
     * A tag rather than a hardcoded list because that is what tags are for -- a pack can add the seagrass
     * equivalent from whatever mod it ships, without this file knowing the block exists. Crops and saplings
     * in a farm row are deliberately NOT in it by default: a wild sapling is scenery, but a planted field is
     * somebody's build, and the difference is not one this check can see.
     */
    private val SETTLE_THROUGH: TagKey<Block> =
        TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "ship_may_settle_through"))

    /** Whether [state] is something a hull may overwrite rather than be refused by. */
    private fun yields(state: BlockState): Boolean =
        state.canBeReplaced() || state.`is`(SETTLE_THROUGH)

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
                if (!yields(existing)) {
                    return Blocked(cursor.immutable(), existing.block.name.string)
                }
            }
        }
        return Fits
    }

    /**
     * Whether a solid box of [size] at [corner] is entirely replaceable, by the same rule [test] uses.
     *
     * Where [test] asks about a hull's own blocks, this asks about a *region* — which is what a clearance skirt
     * is. The shipwright needs a delivered ship to stand clear of docks and piers, and the only honest
     * definition of "too close" is the same one that defines "in the way": whatever the ship is not allowed to
     * overwrite is also what it is not allowed to touch.
     *
     * Every cell counts here, including the ones a hull would leave empty. That is deliberate — a skirt with a
     * jetty running through the middle of it is not clear just because the ship's own interior is hollow there.
     */
    fun isClear(level: ServerLevel, size: Vec3i, corner: BlockPos): Boolean {
        val cursor = BlockPos.MutableBlockPos()
        for (x in 0 until size.x) {
            for (y in 0 until size.y) {
                for (z in 0 until size.z) {
                    cursor.set(corner.x + x, corner.y + y, corner.z + z)
                    if (level.isOutsideBuildHeight(cursor.y)) return false
                    if (!level.hasChunkAt(cursor)) continue
                    if (!yields(level.getBlockState(cursor))) return false
                }
            }
        }
        return true
    }
}
