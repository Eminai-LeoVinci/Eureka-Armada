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
}
