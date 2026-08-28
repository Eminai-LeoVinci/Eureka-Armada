package org.valkyrienskies.eureka.cannon

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.gameevent.GameEvent
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.pirate.PirateHelm
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.compat.voxy.VoxyLodRefresh
import kotlin.math.floor

/**
 * Taking a measured bite out of whatever the shot hit.
 *
 * ## Two shapes, because there are two kinds of round
 * [punch] is the solid shot: a flood fill from the point of impact, eating its way through whatever it reached.
 * [burst] is the bursting charge: a sphere centred where the round stopped, everything inside it gone. Both are
 * handed the same already-rolled block count and differ only in what they spend it on -- a tally of blocks, or
 * a radius. Which one a round gets is the round's own business; see [org.valkyrienskies.eureka.item.Load.bursts].
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
     * Destroy [count] blocks outward from [origin], showing the break effect for at most [effects] of them,
     * and return how many effects were actually spent.
     *
     * Breadth-first, so damage is a hole centred on the point of impact rather than a line bored through the
     * hull or a scatter of unconnected gaps. Air is stepped through but never counted: a round that clips the
     * edge of a deck should still take its full bite out of the timber it reaches, and counting air would let
     * a glancing hit spend its whole allowance on nothing.
     *
     * ## Why the effects are rationed
     * Vanilla's `destroyBlock` fires a break effect per block -- a puff of block particles and a sound, each
     * a packet to every client in range. Fine for a pickaxe, which breaks one; a netherite round breaks a
     * dozen in a single tick, and an armor-piercing one does that four times over. Thirty simultaneous
     * break effects is a visible hitch, and it buys nothing: past the first few the puffs land on top of
     * each other inside one hole and no player can tell four from thirty.
     *
     * So the blocks past the ration are removed QUIETLY -- the same work `destroyBlock` does (fluid-aware,
     * so a hole in a flooded hull fills with water rather than air, and the game event still fires for
     * anything listening) minus the effect nobody could see anyway. The hole is identical; only the noise
     * about it is capped.
     *
     * Nothing drops. A cannon is a weapon, not a quarry, and a gun that returned the hull it removed would be
     * the cheapest mining tool in the game -- point it at a mountain and collect. Putting a ship back together
     * is the shipwright's job, which is exactly the loop the repair feature exists to close.
     */
    fun punch(level: ServerLevel, origin: BlockPos, count: Int, effects: Int = Int.MAX_VALUE): Int {
        if (count <= 0) return 0

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
                // A pirate's wheel is inviolable while its crew lives. Without this, one lucky round pops
                // the conquest objective off the hull and skips the whole fight -- and hands nobody an item,
                // which reads as a bug twice over.
                if (PirateHelm.inviolable(state)) continue
                taken.add(pos)
            }

            if (seen.size > SEARCH_LIMIT) break
            for (face in Direction.entries) {
                val next = pos.relative(face)
                if (seen.add(next)) queue.add(next)
            }
        }

        val shown = minOf(taken.size, maxOf(effects, 0))
        for ((index, pos) in taken.withIndex()) {
            if (index < shown) level.destroyBlock(pos, false) else remove(level, pos)
        }
        markLods(level, origin, taken)
        return shown
    }

    /**
     * Detonate at [origin]: destroy everything destructible inside a sphere, showing the break effect for at
     * most [effects] of them, and return how many effects were actually spent.
     *
     * ## Why a sphere is a different weapon from a bite
     * [punch] is a solid shot -- a lump of metal arriving fast, eating its way into whatever it met, walking
     * through air for free because a round that clips the edge of a deck should still take its full bite out of
     * the timber beyond. Right for a ball; wrong for a bursting charge, which does not travel anywhere after it
     * goes off. It detonates where it stopped, and what it destroys is simply what was close enough.
     *
     * So this takes the round's rolled count and spends it on a RADIUS rather than on a block tally: everything
     * inside dies, and nothing outside does, however much of the sphere turns out to be open air. A shell buried
     * in a hillside eats about its whole count; one that lands on the surface leaves a crater of the same width
     * with only the ground half taken. That is how creepers and TNT read, and it is what makes a crater a crater.
     *
     * ## Why not a flood fill with a radius bolted on
     * [punch]'s breadth-first walk expands by MANHATTAN distance, so its hole is an octahedron -- visibly pointy
     * once it is more than a few blocks across -- and its search ceiling truncates part-way through a shell, in
     * whatever order [Direction.entries] happened to queue. Past a couple of hundred blocks that leftover partial
     * shell is a lopsided streak rather than a bowl, which is the gash a big charge used to leave. A straight
     * scan of the enclosing box has neither problem and is cheaper besides: no queue, no visited set.
     *
     * ## Shipyard space throughout, exactly as [punch]
     * [origin] is whatever `BlockHitResult.getBlockPos` handed back, so a hit on a hull is a shipyard position
     * and every offset from it is a real neighbour on that hull. The sphere is carved in the space the ball
     * actually struck; it does not reach across into another. A shell that craters the seabed beside a ship
     * leaves the ship alone, which is the same rule the solid shot has always followed.
     */
    fun burst(level: ServerLevel, origin: BlockPos, count: Int, effects: Int = Int.MAX_VALUE): Int {
        if (count <= 0) return 0

        val radius = blastRadius(count)
        val reach = radius * radius
        val span = floor(radius).toInt()

        val taken = ArrayList<BlockPos>()
        val cursor = BlockPos.MutableBlockPos()

        for (dx in -span..span) {
            for (dy in -span..span) {
                for (dz in -span..span) {
                    if ((dx * dx + dy * dy + dz * dz).toDouble() > reach) continue
                    cursor.set(origin.x + dx, origin.y + dy, origin.z + dz)
                    // Never generate ground at the rim of a big blast: a shell landing at the edge of the
                    // loaded area would otherwise drag new chunks into being just to blow them up.
                    if (!level.hasChunkAt(cursor)) continue

                    val state = level.getBlockState(cursor)
                    if (state.isAir) continue
                    // The same two refusals the solid shot makes, and for the same reasons -- bedrock and its
                    // kin report a negative hardness, and a pirate's wheel is inviolable while its crew lives.
                    if (state.getDestroySpeed(level, cursor) < 0) continue
                    if (PirateHelm.inviolable(state)) continue

                    taken.add(cursor.immutable())
                }
            }
        }

        // Nearest first, so the handful of break effects the round can still afford land in the middle of the
        // crater where somebody is looking, rather than at whichever corner of the scan box came first.
        taken.sortBy { it.distSqr(origin) }

        val shown = minOf(taken.size, maxOf(effects, 0))
        for ((index, pos) in taken.withIndex()) {
            if (index < shown) level.destroyBlock(pos, false) else remove(level, pos)
        }
        markLods(level, origin, taken)
        return shown
    }

    /**
     * How wide a sphere [count] blocks buys.
     *
     * An operator-set radius wins outright; otherwise the count is spent on volume. The continuous answer --
     * `cbrt(3n / 4pi)` -- is only a seed, because block positions are a lattice and a lattice ball's size jumps
     * in steps: 1, 7, 19, 27, 33, 57, 81, 93, 123. Taken literally the continuous radius rounds those steps
     * DOWN, and a four-block charge would come out as a radius of 0.98 and destroy exactly one block. So the
     * seed is stepped outward to the first sphere that actually holds the count, which errs the other way --
     * a rolled 100 fills a 123-cell ball -- and that is the right way to err: a small charge still has to read
     * as a burst, and half of any surface hit is air the sphere never gets to collect anyway.
     *
     * The ceiling is the only thing that says no. It exists because the carve is a cube's worth of block reads
     * and writes in a single tick, and it is deliberately set far above anything the damage ladders can reach.
     */
    fun blastRadius(count: Int): Double {
        val cap = EurekaConfig.SERVER.cannonExplosiveMaxBlastRadius.coerceAtLeast(0.0)
        val fixed = EurekaConfig.SERVER.cannonExplosiveBlastRadius
        if (fixed > 0.0) return fixed.coerceAtMost(cap)
        if (count <= 1) return 0.0

        // Snapped down to a step boundary before the walk starts, so every answer is a clean multiple of the
        // step and rises with the count -- unsnapped, a seed that already satisfied its count was returned
        // as-is, and a four-block charge could come out WIDER than a five-block one while carving the same
        // seven cells.
        var radius = (floor(Math.cbrt(count * 3.0 / (4.0 * Math.PI)) / RADIUS_STEP) * RADIUS_STEP)
            .coerceAtLeast(RADIUS_STEP)
        while (radius < cap) {
            if (cellsWithin(radius) >= count) return radius
            radius += RADIUS_STEP
        }
        return cap
    }

    /** How many block positions a sphere of [radius] encloses, counted rather than estimated. */
    private fun cellsWithin(radius: Double): Int {
        val reach = radius * radius
        val span = floor(radius).toInt()
        var cells = 0
        for (dx in -span..span) {
            for (dy in -span..span) {
                for (dz in -span..span) {
                    if ((dx * dx + dy * dy + dz * dz).toDouble() <= reach) cells++
                }
            }
        }
        return cells
    }

    /**
     * Take a block out without the break effect.
     *
     * Everything [net.minecraft.world.level.Level.destroyBlock] does except the `levelEvent` that spawns the
     * puff and the sound -- the fluid-aware write, so breaking a waterlogged block leaves water exactly as
     * vanilla would, and the game event, so nothing listening for destruction is fooled by the quiet. Drops
     * are the one part deliberately left out, because a cannon never drops anything anyway.
     */
    private fun remove(level: ServerLevel, pos: BlockPos) {
        val state = level.getBlockState(pos)
        if (state.isAir) return
        val fluid = level.getFluidState(pos)
        level.setBlock(pos, fluid.createLegacyBlock(), Block.UPDATE_ALL)
        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(null, state))
    }

    /**
     * Set [count] fires among whatever is still standing around [origin].
     *
     * ## Called after [punch], and that ordering is the whole design
     * There is no bookkeeping here about which blocks survived, because by the time this runs the destroyed
     * ones are already air -- "the survivors" is simply what the world now contains. Running it the other way
     * round would let a round light a block and then break it, turning fire into a second helping of damage
     * rather than a different kind of harm.
     *
     * ## Fire goes in the air beside a block, not into it
     * Minecraft has no "this block is burning" state: a fire is its own block standing next to something that
     * will carry it. So this looks for empty space where a fire could actually survive, which is vanilla's own
     * test and already accounts for what the neighbouring block is made of -- a stone hull gives a round
     * almost nothing to catch, and a timber one gives it plenty.
     */
    fun kindle(level: ServerLevel, origin: BlockPos, count: Int) {
        if (count <= 0) return

        var lit = 0
        val litPositions = ArrayList<BlockPos>(count)
        val seen = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()

        queue.add(origin)
        seen.add(origin)

        while (queue.isNotEmpty() && lit < count) {
            val pos = queue.removeFirst()

            if (level.getBlockState(pos).isAir && BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) {
                level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL)
                lit++
                litPositions.add(pos)
            }

            if (seen.size > SEARCH_LIMIT) break
            for (face in Direction.entries) {
                val next = pos.relative(face)
                if (seen.add(next)) queue.add(next)
            }
        }

        markLods(level, origin, litPositions)
    }

    /**
     * Push the crater into Voxy's distant LODs, when the operator asked for it (/armada cannons voxy-lod).
     *
     * WORLD hits only: [origin] is shipyard coordinates for a hit on a ship (see the class doc), and ship
     * chunks have no world LODs to refresh -- VoxyLodRefresh filters shipyard chunks anyway, but one lookup
     * here spares it the whole batch. The refresh itself is VS2's batched two-pass flush, so a broadside's
     * craters coalesce into one ingest per touched chunk, and it costs nothing when Voxy is not installed.
     */
    private fun markLods(level: ServerLevel, origin: BlockPos, changed: List<BlockPos>) {
        if (!EurekaConfig.SERVER.cannonballVoxyLodUpdates || changed.isEmpty()) return
        if (level.getShipManagingPos(origin) != null) return
        val chunks = HashSet<Long>()
        for (pos in changed) {
            if (chunks.add(ChunkPos.asLong(pos.x shr 4, pos.z shr 4))) {
                VoxyLodRefresh.mark(level, pos.x shr 4, pos.z shr 4)
            }
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

    /**
     * How finely [blastRadius] walks outward looking for the first sphere big enough. Small enough that the
     * chosen sphere is never much bigger than the count asked for; large enough that the walk is a handful of
     * counts, not hundreds.
     */
    private const val RADIUS_STEP = 0.25
}
