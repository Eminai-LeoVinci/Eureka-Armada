package org.valkyrienskies.eureka.pirate

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import org.valkyrienskies.eureka.EurekaBlocks
import org.valkyrienskies.eureka.blockentity.EngineBlockEntity

/**
 * The reference pirate sloop, built from code rather than from a file.
 *
 * ## Why this exists at all
 * A pirate hull ships as a vanilla `StructureTemplate` `.nbt`, and a structure carries the DataVersion of
 * the game that wrote it. DataFixerUpper only ever upgrades, so a template authored on 1.21.11 and dropped
 * into 1.21.1 or 1.20.1 is being read by a game OLDER than the file: the block palette mostly survives,
 * because block state names have been stable, and everything with an item in it does not. That is exactly
 * the damage that was seen -- pillagers arriving with empty hands on 1.21.1 (item components are newer than
 * that game), no pillagers and no coal at all on 1.20.1 (which predates components entirely), and a wheel
 * whose block entity could not be read back.
 *
 * So a template cannot be carried between versions. It has to be AUTHORED on each one, with
 * `/vs pirate capture`, whose whole job is to write a native file. What was missing was the ship to point
 * that command at: rebuilding a test hull by hand, identically, three times, is the sort of task that
 * quietly produces three different hulls and a day of chasing differences that were never in the code.
 *
 * This builds the same sloop every time, out of the running game's own blocks and entities, so the template
 * captured from it is native by construction on every version.
 *
 * ## The hull
 * Five wide, five tall, ten long, laid out from the caller's own facing so "forward" means what they expect:
 *
 * ```
 *   y=4   bulwark rail around the rim; the wheel aft, the crew on deck
 *   y=3   deck planking, with one hatch forward down into the hold
 *   y=1-2 hold: five engines on the centreline, floaters stacked over them
 *   y=0   floor
 * ```
 *
 * The wheel goes down NORMAL, not PIRATE, which is deliberate and the opposite of what it will end up as.
 * A marked wheel refuses its own menu ([PirateHelm.gated]) -- that is the whole point of the mark -- so a
 * hull built pirate could never be assembled, and assembly is the step that has to happen before a capture
 * can read it. `capture` borrows the wheel and marks it for the duration, which is where the black hub in
 * the finished template comes from.
 *
 * The crew are armed here rather than through `finalizeSpawn`, because vanilla's spawn finaliser rolls
 * enchantments and equipment at random and a test hull that differs from run to run is not a test hull.
 */
object PirateTestHull {

    /** Hull length, beam and height in blocks -- the sloop the pirate feature was first proved on. */
    const val LENGTH = 10
    const val BEAM = 5
    const val HEIGHT = 5

    private const val ENGINES = 5
    private const val CREW = 3

    /** How far ahead of the caller the bow sits. Far enough not to build the floor through their feet. */
    private const val STANDOFF = 3

    class Built(val origin: BlockPos, val forward: Direction, val blocks: Int, val crew: Int)

    /**
     * Lay the sloop down in front of [player], unassembled.
     *
     * Returns what was built, for the report. The caller then assembles her at the wheel and runs
     * `/vs pirate capture` -- the two steps are deliberately left to a human, because assembling is itself
     * one of the things a test hull is for.
     */
    fun build(level: ServerLevel, player: ServerPlayer): Built {
        val forward = player.direction
        val right = forward.clockWise
        val origin = player.blockPosition().relative(forward, STANDOFF)

        fun at(f: Int, r: Int, y: Int): BlockPos =
            origin.relative(forward, f).relative(right, r).above(y)

        // Air first, and a block of clearance all round. A hull touching the ground is a hull whose
        // assembly has to decide where the ship ends and the hillside begins -- a decision the classifier
        // can make but which has no business being part of a controlled test.
        var placed = 0
        for (f in -1..LENGTH) {
            for (r in -3..3) {
                for (y in -1..HEIGHT) {
                    level.setBlock(at(f, r, y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)
                }
            }
        }

        fun put(f: Int, r: Int, y: Int, state: BlockState) {
            level.setBlock(at(f, r, y), state, Block.UPDATE_ALL)
            placed++
        }

        val hull = Blocks.DARK_OAK_PLANKS.defaultBlockState()
        val deck = Blocks.OAK_PLANKS.defaultBlockState()

        for (f in 0 until LENGTH) {
            for (r in -2..2) {
                put(f, r, 0, hull)                              // floor
                if (f != 1 || r != -1) put(f, r, 3, deck)       // deck, less one hatch forward
                val rim = r == -2 || r == 2 || f == 0 || f == LENGTH - 1
                if (rim) {
                    put(f, r, 1, hull)                          // hold sides and ends
                    put(f, r, 2, hull)
                    put(f, r, 4, hull)                          // bulwark
                }
            }
        }

        // Five engines in a row on the centreline, each with a FULL STACK of coal blocks rather than the
        // one block she was first built with -- a wrong amount that only shows itself at capture time. An
        // engine burns its fuel ITEM into a tick counter and empties the slot, and the block entity writes
        // a FuelSlot tag only when that slot is NOT empty. So a hull captured after her engines had caught
        // went into the template carrying no coal at all, and a raider generated from it would run out the
        // remainder of one burn and go dead in the water. Sixty-three blocks stay in the slot however long
        // she is sailed before the capture, so fuel is never what decides whether the template is good.
        for (i in 0 until ENGINES) {
            val f = 2 + i
            put(f, 0, 1, EurekaBlocks.ENGINE.get().defaultBlockState().setValue(HORIZONTAL_FACING, forward))
            (level.getBlockEntity(at(f, 0, 1)) as? EngineBlockEntity)?.let {
                it.fuel = ItemStack(Items.COAL_BLOCK, 64)
                it.setChanged()
            }
        }

        // Floaters over them, filling the middle of the hold -- "floaters in her centre", as she was built
        // the first time. Lift is deliberately generous: a test hull that will not swim tests nothing.
        for (f in 2..7) {
            for (r in -1..1) {
                put(f, r, 2, EurekaBlocks.FLOATER.get().defaultBlockState())
            }
        }

        // The wheel, aft on the centreline, facing the way she sails.
        put(
            8, 0, 4,
            EurekaBlocks.OAK_SHIP_HELM.get().defaultBlockState().setValue(HORIZONTAL_FACING, forward)
        )

        val crew = listOf(Triple(4, -1, 4), Triple(4, 1, 4), Triple(6, 0, 4))
            .count { (f, r, y) -> pillager(level, at(f, r, y)) }

        return Built(origin, forward, placed, crew)
    }

    /**
     * One pillager, armed and told to stay.
     *
     * Persistence matters more than it looks: an unpersisted raider standing on a hull that has not been
     * assembled yet is an ordinary mob in an ordinary chunk, and despawning between the build and the
     * capture would silently produce a template with a smaller complement than the one that was authored.
     */
    private fun pillager(level: ServerLevel, pos: BlockPos): Boolean {
        val raider = EntityType.PILLAGER.create(level) as? Mob ?: return false
        raider.setPos(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        raider.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.CROSSBOW))
        raider.setPersistenceRequired()
        return level.addFreshEntity(raider)
    }
}
