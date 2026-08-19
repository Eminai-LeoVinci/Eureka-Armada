package org.valkyrienskies.eureka.shipwright

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.cannon.GunLabels
import org.valkyrienskies.eureka.template.ShipTemplate
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.util.logger

/**
 * Putting a damaged ship back the way its plans say it should be.
 *
 * ## Repair is a comparison, not a rebuild
 * The ship stays exactly where it is and keeps being the ship it was -- its id, its name, its crew, its
 * cargo. All that happens is that blocks the plans describe and the hull no longer has get written back. A
 * captain who has been through a fight wants their ship *mended*, not replaced by an identical one with a
 * different id and an empty hold.
 *
 * ## Which hull, and is it really that hull
 * Three questions have to agree before a repair is allowed, and they are asked in cheapening order so a
 * mismatch costs as little as possible:
 *
 *  1. **Dimensions.** A hull must FIT INSIDE the plans, which is one integer comparison per axis. Not match
 *     them: a damaged hull measures smaller than its plans by definition, so demanding equality turned away
 *     every ship actually worth mending. Bigger than the plans is what disqualifies a hull, because no amount
 *     of damage grows a ship.
 *  2. **Name.** The plans are filed under a ship's name, and that is the identity the whole shelf is keyed on.
 *  3. **Shape.** At least [matchPercent] of the plans' non-air blocks must already be in place, counted as a
 *     total rather than per block-type. A ship stripped of its entire rigging is still that ship; a per-type
 *     rule would reject it for being nought percent wool.
 *
 * The threshold is what stops a shipwright quietly turning one vessel into another. Below it, the honest answer
 * is that this is not the ship on the page -- and the player would rather be told that than watch their hull be
 * overwritten by somebody else's.
 *
 * ## Where the blocks are
 * Both sides live in the ship's own space, so a repair compares block for block without transforming anything.
 * What it cannot do is assume the two are laid down from the same corner: the plans were drawn round a whole
 * ship and the hull is measured round a damaged one, and damage moves that corner. [align] is what finds the
 * corner the plans actually belong on, and everything -- the match, the bill, the blocks written back -- hangs
 * off its answer.
 */
object ShipRepair {

    private val logger by logger()

    /**
     * How much of a hull must still match its plans before a shipwright accepts it as the same ship, as a
     * percentage.
     *
     * Read fresh every time rather than captured at load, so a `/reload` of the config takes effect on the
     * next hull looked at rather than the next restart -- and so a hand-edited file can never leave a stale
     * value behind somewhere.
     *
     * The clamp is what makes the two useless ends unreachable. At 0 every hull matches every set of plans,
     * which turns the shipwright into a machine for quietly rebuilding one ship as another; at 100 only a
     * ship with nothing wrong with it qualifies, and a ship with nothing wrong with it does not need
     * repairing. Both are answers to the question "what is the same vessel?" that stop it meaning anything,
     * so the range is 1 to 99 and out-of-range values are pulled to the nearest end rather than refused --
     * a config typo should cost a captain a surprising threshold, not a shipwright who will not work.
     */
    val matchPercent: Int
        get() = EurekaConfig.SERVER.shipwrightRepairPercentage.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** [matchPercent] as the 0..1 fraction the comparison actually runs against. */
    val matchThreshold: Float get() = matchPercent / 100.0f

    /**
     * How far from the bench a shipwright can see a ship, and work on it.
     *
     * Wide enough that a captain can moor a whole armada off a harbor and have all of it listed at once, which
     * is the point -- a repair yard that could only see one hull at a time would make bringing a fleet in
     * pointless.
     */
    const val REACH = 100.0

    /**
     * What a shipwright makes of a hull compared with a set of plans.
     *
     * [missing] is the repair bill: every block the plans call for that the hull does not currently have,
     * counted in the items a player would have to carry. It is empty on a ship in perfect repair, which is the
     * same thing as saying there is nothing to do.
     */
    class Assessment(
        /** Whether the hull is small enough to BE these plans -- see the fit test in [assess]. */
        val fits: Boolean,
        val nameMatches: Boolean,
        /** 0..1 of the plans' non-air blocks already correct. */
        val match: Float,
        val missing: Map<Item, Int>,
        /** Shipyard positions that need writing, with the state to write. Empty unless [accepted]. */
        val repairs: List<Pair<BlockPos, BlockState>>
    ) {
        val accepted: Boolean get() = fits && nameMatches && match >= matchThreshold
        val sound: Boolean get() = accepted && missing.isEmpty()

        /** Why a shipwright turned this away, or null if it did not. */
        val refusal: String?
            get() = when {
                !fits -> "That hull is bigger than these plans describe."
                !nameMatches -> "These plans are for a different ship."
                match < matchThreshold ->
                    "Too little of that hull matches these plans -- ${(match * 100).toInt()}% of it, and a " +
                        "shipwright needs $matchPercent%. This is not the same ship."
                else -> null
            }
    }

    /** A hull's tight block bounds: the corner it is measured from, and how big it actually is. */
    class Bounds(val min: BlockPos, val width: Int, val height: Int, val length: Int, val blocks: Int)

    /**
     * Where the plans' own (0,0,0) belongs in the shipyard -- the corner the whole comparison hangs off.
     *
     * ## Why this cannot simply be the wreck's corner
     * It used to be, and that was a real bug rather than a simplification. Both sides measure a TIGHT box
     * round the solid blocks they can see: `ShipTemplate.capture` did it to a whole ship, [bounds] does it to
     * what is left of one. So the moment damage takes a block off the minimum face -- the lowest X, Y or Z
     * anywhere on the hull -- the wreck's corner stops being the corner the plans were drawn from, and every
     * block in the plans gets compared one step out of place.
     *
     * A real case: a 29x88x98 ship came back 28x88x98, having lost its outermost column. Compared from its
     * own corner it scored 29% and was turned away as a stranger. Shifted one block, the same hull and the
     * same plans agreed 81%. Nothing was wrong with the ship, the threshold, or the plans -- the two were
     * being laid on top of each other askew.
     *
     * ## Finding it
     * Damage only ever removes blocks, so the wreck sits somewhere INSIDE the plans and each axis can only be
     * short by the difference between the two spans. That difference IS the search: an axis that still
     * measures full width offers exactly one candidate, and a normal wreck offers a handful in total. Each is
     * scored on a sample of the plans' blocks and the best-fitting corner wins, nearest-first so an
     * unambiguous hull keeps the corner it already had.
     */
    private fun align(level: ServerLevel, template: StructureTemplate, hull: Bounds): BlockPos {
        val size = template.size
        val xs = candidates(hull.width, size.x)
        val ys = candidates(hull.height, size.y)
        val zs = candidates(hull.length, size.z)

        // Nothing to hunt for: every axis still measures its full span, so the wreck's corner IS the plans'.
        // The common case, and it costs nothing.
        if (xs.size == 1 && ys.size == 1 && zs.size == 1) return hull.min

        val infos = template.palettes.firstOrNull()?.blocks()?.filter { !it.state.isAir }.orEmpty()
        if (infos.isEmpty()) return hull.min
        val step = maxOf(1, infos.size / ALIGN_SAMPLE)
        val sample = infos.filterIndexed { index, _ -> index % step == 0 }

        var best = BlockPos.ZERO
        var bestHits = -1
        val cursor = BlockPos.MutableBlockPos()

        for (dx in xs) {
            for (dy in ys) {
                for (dz in zs) {
                    var hits = 0
                    for (info in sample) {
                        cursor.set(
                            hull.min.x + info.pos.x + dx,
                            hull.min.y + info.pos.y + dy,
                            hull.min.z + info.pos.z + dz
                        )
                        if (!level.hasChunkAt(cursor)) continue
                        if (level.getBlockState(cursor).block == info.state.block) hits++
                    }
                    // Strictly better, and the candidates run nearest-first, so a tie keeps the smaller shift.
                    if (hits > bestHits) {
                        bestHits = hits
                        best = BlockPos(dx, dy, dz)
                    }
                }
            }
        }

        if (best != BlockPos.ZERO) {
            logger.info(
                "[shipwright] plans laid ${best.x},${best.y},${best.z} off the hull's own corner " +
                    "(${hull.width}x${hull.height}x${hull.length} of ${size.x}x${size.y}x${size.z}); " +
                    "$bestHits of ${sample.size} sampled blocks agree there"
            )
        }
        return hull.min.offset(best.x, best.y, best.z)
    }

    /**
     * How far the plans might have to slide along one axis, nearest first.
     *
     * Zero when the hull still spans as much as the plans do, and one step per block it has lost off that
     * axis -- all of them negative, because a wreck can only have lost its lower face, never grown past it.
     * A hull measuring LONGER than its plans gets the single zero candidate: it is about to be refused for
     * not fitting, and there is no sense hunting for the corner of a ship this is not.
     */
    private fun candidates(hullSpan: Int, templateSpan: Int): List<Int> {
        val reach = (templateSpan - hullSpan).coerceIn(0, ALIGN_REACH)
        return (0..reach).map { -it }
    }

    /**
     * The box a hull's **solid blocks** occupy, which is not the same thing as its `shipAABB`.
     *
     * `shipAABB` is the ship's voxel volume and is routinely larger than the hull inside it. A template, by
     * contrast, is built from the blocks actually found -- see [ShipTemplate.capture], which computes its own
     * min and max while walking them. Comparing one against the other is comparing two different measurements
     * of the same ship, and it reports every hull as the wrong size by however much slack its AABB carries.
     *
     * This is where the anchor is hunted from, not the anchor itself: on an undamaged hull the two are the
     * same block, which is exactly why using this directly worked for so long. See [align].
     */
    fun bounds(level: ServerLevel, ship: LoadedServerShip): Bounds? {
        val aabb = ship.shipAABB ?: return null

        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        var blocks = 0

        val cursor = BlockPos.MutableBlockPos()
        for (x in aabb.minX()..aabb.maxX()) {
            for (y in aabb.minY()..aabb.maxY()) {
                for (z in aabb.minZ()..aabb.maxZ()) {
                    cursor.set(x, y, z)
                    if (!level.hasChunkAt(cursor)) continue
                    if (level.getBlockState(cursor).isAir) continue
                    blocks++
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                }
            }
        }
        if (blocks == 0) return null
        return Bounds(
            BlockPos(minX, minY, minZ),
            maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1,
            blocks
        )
    }

    /**
     * The nearest assembled ship to [at] within [REACH], or null.
     *
     * Nearest rather than first found, because a busy harbor has several and the one being asked about is the
     * one in front of the shipwright.
     */
    fun nearby(level: ServerLevel, at: BlockPos): LoadedServerShip? {
        val dimension = level.dimensionId
        var best: LoadedServerShip? = null
        var bestDistance = REACH * REACH

        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dimension) continue
            if (ship.shipAABB == null) continue

            val position = ship.transform.position
            val dx = position.x() - (at.x + 0.5)
            val dy = position.y() - (at.y + 0.5)
            val dz = position.z() - (at.z + 0.5)
            val distance = dx * dx + dy * dy + dz * dz
            if (distance < bestDistance) {
                bestDistance = distance
                best = ship
            }
        }
        return best
    }

    /**
     * Compare [ship] against the plans named [template], writing nothing.
     *
     * Walks the template once. Blocks that already match cost nothing; blocks that do not are counted into the
     * bill and remembered so a paid repair does not have to work any of it out again.
     */
    fun assess(
        level: ServerLevel,
        ship: LoadedServerShip,
        plans: ShipwrightLedger.Plans
    ): Assessment? {
        val template: StructureTemplate = ShipTemplate.find(level, plans.template) ?: return null
        val hull = bounds(level, ship) ?: return null

        val size = template.size
        // Fits INSIDE the plans, not matches them exactly. A damaged hull measures smaller than its plans by
        // definition -- that is what damage IS -- so demanding equality rejected every ship worth mending and
        // let through only the ones with nothing wrong. What genuinely disqualifies a hull is being BIGGER
        // than the plans describe: no amount of damage grows a ship, so that is a different vessel.
        val fits = hull.width <= size.x && hull.height <= size.y && hull.length <= size.z
        val nameMatches = ship.slug == plans.shipName
        val anchor = align(level, template, hull)

        var wanted = 0
        var correct = 0
        val missing = LinkedHashMap<Item, Int>()
        val repairs = ArrayList<Pair<BlockPos, BlockState>>()

        val cursor = BlockPos.MutableBlockPos()
        for (palette in template.palettes) {
            for (info in palette.blocks()) {
                if (info.state.isAir) continue
                wanted++

                cursor.set(
                    anchor.x + info.pos.x,
                    anchor.y + info.pos.y,
                    anchor.z + info.pos.z
                )
                // Compared by BLOCK, not by full state. A door left open or a stair turned round is wear, not
                // damage, and charging a player a fresh door for having opened one would be absurd.
                if (level.getBlockState(cursor).block == info.state.block) {
                    correct++
                    continue
                }

                repairs.add(cursor.immutable() to info.state)
                val item = itemFor(info.state)
                if (item != null) missing[item] = (missing[item] ?: 0) + 1
            }
        }

        val match = if (wanted == 0) 1.0f else correct.toFloat() / wanted.toFloat()
        return Assessment(fits, nameMatches, match, missing, repairs)
    }

    /**
     * The repairs in the order a shipwright works: keel up.
     *
     * Lowest layer first, bow to stern within it, port to starboard within that -- so a pot that cannot cover
     * everything mends the hull at the waterline before it mends the crow's nest. The bow is read off the
     * crew-station helm exactly as [org.valkyrienskies.eureka.cannon.GunLabels] reads it for gun names; a ship
     * with no wheel to say which end is forward falls back to its shipyard axes, which keeps the order total
     * and stable even if it no longer means "bow".
     */
    fun ordered(level: ServerLevel, ship: LoadedServerShip, assessment: Assessment): List<Pair<BlockPos, BlockState>> {
        val forward = GunLabels.forwardOf(level, ship) ?: Direction.SOUTH
        val starboard = forward.clockWise
        return assessment.repairs.sortedWith(
            compareBy<Pair<BlockPos, BlockState>> { it.first.y }
                .thenByDescending { it.first.dot(forward) }
                .thenBy { it.first.dot(starboard) }
        )
    }

    /** What one pass of the trowel actually did: blocks placed, materials spent, blocks still wanting. */
    class Outcome(val placed: Int, val consumed: Map<Item, Int>, val remaining: Int)

    /**
     * Write as much of [repairs] back into the hull as [pot] will pay for.
     *
     * Two passes, and the split is load-bearing. Costed blocks go first, in the keel-up order handed in,
     * each one spending its item from the pot or being skipped -- skipped, not stopped at, because the pot
     * may still hold the makings of blocks further along. Free blocks (door uppers, bed heads -- the halves
     * whose partner carried the whole price) go second, each only once its partner actually stands, so a
     * head processed before its foot is never skipped on a full pot and never floated on an empty one.
     *
     * A full pot places everything, which is why this is the ONLY apply: a complete repair is a partial
     * repair that never runs dry.
     *
     * `UPDATE_CLIENTS` and nothing more, matching how a ship's blocks are written everywhere else in the mod:
     * these are shipyard positions, and asking for neighbour updates across a hull being rebuilt block by
     * block is how you get a cascade of falling gravel and popped-off torches inside somebody's ship.
     */
    fun apply(level: ServerLevel, repairs: List<Pair<BlockPos, BlockState>>, pot: MutableMap<Item, Int>): Outcome {
        val consumed = LinkedHashMap<Item, Int>()
        var placed = 0

        val free = ArrayList<Pair<BlockPos, BlockState>>()
        for ((pos, state) in repairs) {
            val item = itemFor(state)
            if (item == null) {
                free.add(pos to state)
                continue
            }
            val held = pot[item] ?: 0
            if (held <= 0) continue

            // Cooled on the way in. A replacement engine is a NEW engine with an empty block entity, and
            // writing back the heat the old one died with gives a glowing gauge over an empty firebox --
            // which only corrects itself once somebody adds fuel and the next tick overwrites the state.
            // Bottled and blueprinted ships deliberately do not do this; see ShipTemplate.cooled.
            level.setBlock(pos, ShipTemplate.cooled(state), Block.UPDATE_CLIENTS)
            pot[item] = held - 1
            consumed[item] = (consumed[item] ?: 0) + 1
            placed++
        }

        for ((pos, state) in free) {
            if (!partnerStands(level, pos, state)) continue
            level.setBlock(pos, ShipTemplate.cooled(state), Block.UPDATE_CLIENTS)
            placed++
        }

        return Outcome(placed, consumed, repairs.size - placed)
    }

    /**
     * Whether the block that paid for [state] is actually in the world.
     *
     * A door's upper half stands on its lower; a bed's head lies against its foot, which is one step behind
     * the way the head faces. Anything else that came through free has no partner to wait for.
     */
    private fun partnerStands(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean {
        if (state.hasProperty(DOUBLE_HALF) && state.getValue(DOUBLE_HALF) == UPPER) {
            return level.getBlockState(pos.below()).block == state.block
        }
        if (state.hasProperty(BED_PART) && state.getValue(BED_PART) == HEAD) {
            val facing = state.getValue(BED_FACING)
            return level.getBlockState(pos.relative(facing.opposite)).block == state.block
        }
        return true
    }

    private fun BlockPos.dot(direction: Direction): Int =
        x * direction.stepX + y * direction.stepY + z * direction.stepZ

    /**
     * What one block costs to replace, mirroring [org.valkyrienskies.eureka.template.BillOfMaterials].
     *
     * The upper half of a door and the head of a bed are the same purchase as their partner, so only one end is
     * charged for -- and a block with no item form costs nothing rather than appearing on a bill as "air".
     */
    private fun itemFor(state: BlockState): Item? {
        if (state.isAir) return null
        if (state.hasProperty(DOUBLE_HALF) && state.getValue(DOUBLE_HALF) == UPPER) return null
        if (state.hasProperty(BED_PART) && state.getValue(BED_PART) == HEAD) return null
        val item = state.block.asItem()
        return if (item == Items.AIR) null else item
    }

    private val DOUBLE_HALF =
        net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF
    private val UPPER = net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
    private val BED_PART = net.minecraft.world.level.block.state.properties.BlockStateProperties.BED_PART
    private val HEAD = net.minecraft.world.level.block.state.properties.BedPart.HEAD
    private val BED_FACING = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING

    /** The ends of the match range, both excluded -- see [matchPercent] for why neither is a usable answer. */
    private const val MIN_PERCENT = 1
    private const val MAX_PERCENT = 99

    /**
     * The most [align] will shift the plans along one axis while hunting for the corner.
     *
     * A cap on work, not on meaning. The candidates come from the size difference, so an ordinary wreck
     * offers one or two per axis and this is never reached; it only bites on a hull so comprehensively
     * dismantled that the answer to "is this the same ship" was going to be no regardless.
     */
    private const val ALIGN_REACH = 16

    /** How many of the plans' blocks [align] scores a candidate corner with. Enough to be decisive, cheaply. */
    private const val ALIGN_SAMPLE = 512
}
