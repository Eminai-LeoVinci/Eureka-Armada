package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.ai.behavior.BehaviorUtils
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.util.ShipFireBurn
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The fire party, actually running.
 *
 * The first version of this duty simulated the crew member's trip as a deadline, on the grounds that a villager
 * could not be trusted to walk anywhere on a moving hull. That ground is gone: VS2's pathfinding stack now bakes
 * ship blocks into the navigation grid in world space and re-anchors a path to the hull's live transform every
 * tick, so a walk target on the deck is just a walk target. What replaces the deadline is the real thing -- the
 * firefighter RUNS to the flame at villager panic speed, and the fire goes out because somebody is standing at
 * it, not because a timer said they would have been.
 *
 * ## How the villager is steered
 * By their own brain. A fire job holds the villager's WALK_TARGET memory -- re-asserted with a freshly derived
 * world position every half second, so the target rides the moving deck -- and vanilla's MoveToTargetSink does
 * all of the walking. That one memory is also the whole priority system: every behaviour that would take the
 * villager somewhere else (the helm tether, strolls, even panic's flee) starts only when WALK_TARGET is absent,
 * so keeping it present IS the "drop everything, there is a fire" rule, with nothing to arbitrate.
 *
 * ## What a firefighter answers
 * Fires within [EurekaConfig.Server.fireWatchHorizontalBlocks] along the deck and
 * [EurekaConfig.Server.fireWatchVerticalBlocks] above or below, from wherever the crew member happens to be
 * standing. The vertical is deliberately the short axis: a flame at the masthead is a burning sail, and no deck
 * hand with a bucket was ever the answer to that. Claims are strictly one villager to one fire -- a hand walks
 * PAST a burning block to reach their own -- because a claim each is what spreads a party across a spreading
 * fire instead of bunching them on the nearest corner of it.
 */
object FireBrigade {

    /**
     * One crew member on their way to one fire.
     *
     * The fire is held at SHIPYARD coordinates and never compared raw against a world position -- every check
     * that involves the villager derives the fire's world-space centre from the hull's live transform first.
     * That is also why nothing here caches a world position: the ship moves, and the shipyard address is the
     * only part of "where" that stays true.
     */
    private class Job(
        val dimension: ResourceKey<Level>,
        /** The hull the fire is on, for the doused tally. The ship itself is re-resolved every tick. */
        val shipId: Long,
        val fire: BlockPos,
        /**
         * When to stop trying. A fire behind a sealed bulkhead is unreachable and the navigator cannot say so;
         * this deadline -- generous, and scaled by how far the run was -- is the escape hatch. Given up quietly:
         * the fire usually settles the matter itself by burning through.
         */
        val giveUpAt: Long,
        var nextAssertAt: Long,
        var lastAssertAt: Long
    )

    /** Villager -> their claim. The values double as the claimed-fire set, which is the one-to-one rule. */
    private val working = HashMap<UUID, Job>()

    /** Villager -> when they may take the next fire. The breather between douses, not a scheduling queue. */
    private val resting = HashMap<UUID, Long>()

    /** Villager -> packed fire -> when to forgive it. Fires they gave up on, so the next pick tries another. */
    private val snubbed = HashMap<UUID, HashMap<Long, Long>>()

    /** Dimension -> ship -> fires put out since the last report, so a blaze reads as one line, not six. */
    private val doused = HashMap<ResourceKey<Level>, HashMap<Long, Int>>()

    /** Per world tick: walk every job forward every tick; look for new fires on a once-a-second clock. */
    fun tick(level: ServerLevel) {
        tickJobs(level)
        if (level.gameTime % SCAN_INTERVAL == 0L) scan(level)
    }

    /** Drop everything in flight. Runtime-only state, and in single player this singleton outlives the world. */
    fun reset() {
        working.clear()
        resting.clear()
        snubbed.clear()
        doused.clear()
    }

    // region on their way

    /**
     * Advance every job in this dimension. Runs every tick, and stays cheap by construction: with no fires the
     * map is empty and this is one size check, and with fires it is a few map reads per crew member running.
     */
    private fun tickJobs(level: ServerLevel) {
        if (working.isEmpty()) return
        val now = level.gameTime
        val reach = EurekaConfig.SERVER.fireWatchDouseBlocks
        val iterator = working.entries.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val job = entry.value
            if (job.dimension != level.dimension()) continue

            val villager = level.getEntity(entry.key) as? Villager
            if (villager == null || !villager.isAlive) {
                iterator.remove()
                continue
            }

            // The fire may have burned through, been broken, or been rained on while they were on their way.
            // Released at once and without a breather -- the next scan hands them the next nearest flame, which
            // is what keeps a party reactive against a fire that is eating blocks faster than they can run.
            val ship = level.getLoadedShipManagingPos(job.fire) as? LoadedServerShip
            if (ship == null || level.getBlockState(job.fire).block !is BaseFireBlock) {
                dropWalkTarget(villager)
                iterator.remove()
                continue
            }

            val at = worldOf(ship, job.fire)

            // Arrival first, so the navigator stopping at the flame is never answered with a fresh march order.
            // Close enough is a plain distance, not a footing rule: a fire one deck below their feet is doused
            // through the planks, which reads as leaning over the hatch with the bucket.
            if (villager.position().distanceToSqr(at) <= reach * reach) {
                douse(level, villager, job, at)
                iterator.remove()
                continue
            }

            if (now >= job.giveUpAt) {
                snubbed.getOrPut(entry.key) { HashMap() }[job.fire.asLong()] = now + SNUB_TICKS
                dropWalkTarget(villager)
                iterator.remove()
                continue
            }

            // Keep the march order standing. Re-writing the memory does not restart the walk -- MoveToTargetSink
            // re-reads it every tick and only re-paths when the target has moved more than two blocks, which on
            // a hull under way is exactly when it should. The fast path answers the sink ERASING the memory (it
            // does, whenever a path dies); the floor under it keeps a truly unwalkable target from becoming a
            // once-a-tick path solve.
            val absent = !villager.brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)
            if (now >= job.nextAssertAt || (absent && now >= job.lastAssertAt + REASSERT_FLOOR)) {
                if (villager.isSleeping) villager.stopSleeping()
                BehaviorUtils.setWalkAndLookTargetMemories(
                    villager, BlockPos.containing(at.x, at.y, at.z), RUN_SPEED, closeEnough(reach)
                )
                job.lastAssertAt = now
                job.nextAssertAt = now + REASSERT_TICKS
            }
        }
    }

    /** The douse itself, unchanged from the deadline era: the block goes, the smoke says where it was. */
    private fun douse(level: ServerLevel, villager: Villager, job: Job, at: Vec3) {
        level.removeBlock(job.fire, false)
        ShipFireBurn.forget(level, job.fire)

        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8f, 1.0f)
        level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 12, 0.3, 0.3, 0.3, 0.02)

        doused.getOrPut(job.dimension) { HashMap() }.merge(job.shipId, 1, Int::plus)
        dropWalkTarget(villager)
        resting[villager.uuid] = level.gameTime + restTicks()
    }

    /**
     * Give the villager their feet back. Erasing WALK_TARGET is the whole hand-off: the sink stops of its own
     * accord, and the ordinary behaviours -- which have been locked out purely by the memory's presence -- may
     * start again on their next go.
     */
    private fun dropWalkTarget(villager: Villager) {
        villager.brain.eraseMemory(MemoryModuleType.WALK_TARGET)
    }

    // endregion

    // region the watch

    /**
     * The once-a-second look around: report, tidy, audit, then send free hands to unclaimed fires.
     *
     * Assignment deliberately follows [tickJobs] within the same tick, for the reason the old watch doused
     * before it looked -- a fire put out this tick is out of the world before the burning list is built, so a
     * freshly freed hand is never sent straight back to it.
     */
    private fun scan(level: ServerLevel) {
        val now = level.gameTime
        reportDoused(level)
        resting.entries.removeIf { it.value <= now }
        snubbed.values.forEach { fires -> fires.values.removeIf { it <= now } }
        snubbed.entries.removeIf { it.value.isEmpty() }

        val ledger = CrewLedger.get(level.server)
        auditDuties(level, ledger)

        // The tracked list is a superset -- entries outlive the fires that made them -- so every position is
        // checked against the world before anybody is sent to it.
        val burning = HashMap<Long, MutableList<BlockPos>>()
        for (packed in ShipFireBurn.tracked(level)) {
            val pos = BlockPos.of(packed)
            if (level.getBlockState(pos).block !is BaseFireBlock) continue
            val ship = level.getLoadedShipManagingPos(pos) as? LoadedServerShip ?: continue
            burning.getOrPut(ship.id) { mutableListOf() }.add(pos)
        }
        if (burning.isEmpty()) return

        val horizontal = EurekaConfig.SERVER.fireWatchHorizontalBlocks
        val vertical = EurekaConfig.SERVER.fireWatchVerticalBlocks
        val ships = level.shipObjectWorld.loadedShips
        val claimed = working.values.mapTo(HashSet()) { it.fire.asLong() }

        for ((id, fires) in burning) {
            val ship = ships.getById(id) as? LoadedServerShip ?: continue
            // The expensive call -- an AABB entity query over the whole armada -- and the reason assignment
            // stays on this clock while the jobs themselves advance every tick.
            val party = ShipCrew.villagersAboard(level, ship)
                .filter { ledger.dutyOf(it.uuid) == CrewDuty.FIREFIGHTER }

            for (crew in party) {
                if (working.containsKey(crew.uuid)) continue
                if ((resting[crew.uuid] ?: 0L) > now) continue
                // A seated villager is a stationed gunner; their berth says firefighter only in error, and
                // either way they cannot walk.
                if (crew.vehicle != null) continue

                val snubs = snubbed[crew.uuid]
                val from = crew.position()
                var best: BlockPos? = null
                var bestSq = Double.MAX_VALUE

                // Nearest first, in WORLD space: the fire is at shipyard coordinates and the villager is
                // standing on the deck at ordinary ones, so the two are not comparable until one is moved.
                for (fire in fires) {
                    val packed = fire.asLong()
                    if (packed in claimed) continue
                    if (snubs != null && (snubs[packed] ?: 0L) > now) continue

                    val flame = worldOf(ship, fire)
                    val dx = flame.x - from.x
                    val dy = flame.y - from.y
                    val dz = flame.z - from.z
                    if (dx * dx + dz * dz > horizontal * horizontal) continue
                    if (abs(dy) > vertical) continue

                    val sq = dx * dx + dy * dy + dz * dz
                    if (sq < bestSq) {
                        bestSq = sq
                        best = fire
                    }
                }

                val target = best ?: continue
                claimed.add(target.asLong())
                working[crew.uuid] = Job(
                    dimension = level.dimension(),
                    shipId = ship.id,
                    fire = target,
                    giveUpAt = now + GIVE_UP_BASE + (sqrt(bestSq) * GIVE_UP_PER_BLOCK).toLong(),
                    nextAssertAt = now,
                    lastAssertAt = now
                )
            }
        }
    }

    /** Reported per ship per pass rather than per fire, so a blaze does not push everything else off the HUD. */
    private fun reportDoused(level: ServerLevel) {
        val tally = doused.remove(level.dimension()) ?: return
        val ships = level.shipObjectWorld.loadedShips
        for ((id, count) in tally) {
            val ship = ships.getById(id) as? LoadedServerShip ?: continue
            ShipCrew.tell(
                level, ship,
                "Fire party put out $count fire${if (count == 1) "" else "s"}.",
                PathMessages.Kind.GOOD
            )
        }
    }

    /** A crew member re-assigned mid-run stops running. The ledger is the authority; this just catches up. */
    private fun auditDuties(level: ServerLevel, ledger: CrewLedger) {
        val iterator = working.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.dimension != level.dimension()) continue
            if (ledger.dutyOf(entry.key) == CrewDuty.FIREFIGHTER) continue
            (level.getEntity(entry.key) as? Villager)?.let { dropWalkTarget(it) }
            iterator.remove()
        }
    }

    // endregion

    /** A shipyard block's centre, in world space, which is where the villager and the smoke both need it. */
    private fun worldOf(ship: LoadedServerShip, pos: BlockPos): Vec3 {
        val world = ship.shipToWorld.transformPosition(
            Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        )
        return Vec3(world.x, world.y, world.z)
    }

    private fun restTicks(): Long = (EurekaConfig.SERVER.fireWatchRestSeconds * 20.0).toLong().coerceAtLeast(1L)

    /**
     * Where the navigator may stop: a block inside the douse reach, so stopping never strands them short of it,
     * capped at two so a generous reach still reads as walking UP TO the fire rather than loitering across the
     * deck from it. A reach tuned under one block sends them right onto the flame -- their singed feet are the
     * configurer's own arrangement.
     */
    private fun closeEnough(reach: Double): Int = (reach - 1.0).toInt().coerceIn(0, 2)

    /** How often the watch looks for new fires. Once a second against a burn-through of three at worst. */
    private const val SCAN_INTERVAL = 20L

    /** Villager panic speed: the 0.5 brain walk times the 1.5 flee multiplier. Literally running as if chased. */
    private const val RUN_SPEED = 0.75f

    /** How often the march order is re-derived from the hull's live transform while they run. */
    private const val REASSERT_TICKS = 10L

    /** The soonest an ERASED walk target is re-asserted -- the cap on how hard an unwalkable target is retried. */
    private const val REASSERT_FLOOR = 5L

    /** The fixed part of the give-up deadline: ten seconds of grace before distance is even counted. */
    private const val GIVE_UP_BASE = 200L

    /** And half a second more per block of the run, so a long dash is never mistaken for a failed one. */
    private const val GIVE_UP_PER_BLOCK = 10.0

    /** How long a given-up fire is held against trying again -- most fires burn through well inside it. */
    private const val SNUB_TICKS = 600L
}
