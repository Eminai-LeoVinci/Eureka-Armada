package org.valkyrienskies.eureka.pirate

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaGroup
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.cannon.CannonFire
import org.valkyrienskies.eureka.cannon.CannonShot
import org.valkyrienskies.eureka.cannon.CannonSolver
import org.valkyrienskies.eureka.cannon.PowderCharge
import org.valkyrienskies.eureka.cannon.ShipGuns
import org.valkyrienskies.eureka.crew.GunnerMounts
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * The pirate's gun-laying: what a crew of hands does between the ship bringing the broadside to bear and
 * the ball leaving the muzzle.
 *
 * The ship aims (the pursuit AI's business), the solver computes ([CannonSolver]'s), the gun fires
 * ([CannonFire.fireAimed]'s). What lives HERE is the judgment in between: which powder measure to spend
 * on which shot, and the scatter that keeps a machine gunner from boring one hole.
 *
 * ## Charges walked lightest-first
 * A close target is a one-powder shot and a distant one costs three -- the same economy a player gunner
 * runs at the breech, because template stock is finite by design: whatever a fight does not burn is the
 * boarders' loot. The walk tries each measure the magazine can afford, solving under THAT measure's
 * drag, gravity and speed ceiling, and the first that reaches wins.
 */
object PirateGunnery {

    // DEV ONLY: the [gunnery] census trace -- strip with the ROADMAP 6c sweep.
    private val log by org.valkyrienskies.mod.util.logger()

    /** One gun's answer: the arc, and the powder measure that flies it. */
    class Lay(val solution: CannonSolver.GunSolution, val charge: PowderCharge)

    /**
     * One chase's gunnery bookkeeping. It lives ON the [PirateShips.PirateChase] and nowhere else, so
     * the guns go quiet by the same door the pursuit does: chase retired, state gone, nothing to leak.
     */
    class State {
        /** The enemy hull under fire, 0 for none. Re-picked on the scan clock, not per shot. */
        var targetShip: Long = 0L
        var nextScanAt: Long = 0L
        var nextShotAt: Long = 0L
        /** DEV ONLY: throttle for the [census] trace. */
        var nextCensusAt: Long = 0L
    }

    /**
     * One pirate's guns, one tick: pick the enemy, and if the rhythm allows, walk the batteries
     * readiest-first until one gun takes the shot.
     *
     * ## The gunner gate
     * A gun only speaks while a LIVING mounted gunner holds it ([GunnerMounts.gunnerAt]) -- which is the
     * boarders' counterplay, and the whole reason crew matter: pick the gunners off and the battery falls
     * silent, deck by deck, without a block of the ship changing.
     *
     * ## One gun per stagger
     * [EurekaConfig.Server.pirateCannonStaggerTicks] paces the broadside exactly as [CrewDuties]' volley
     * paces a player's -- a rolling thunder rather than one synchronized clap, and a natural rate limit.
     *
     * (No locked-berth check here on purpose: `CrewLedger.Berth.locked` is a player captain's order to
     * their own villager crew, and no pirate hull has either.)
     */
    fun tick(level: ServerLevel, pirate: LoadedServerShip, chase: PirateShips.PirateChase, now: Long) {
        val cfg = EurekaConfig.SERVER
        if (!cfg.pirateGunneryEnabled) return
        val state = chase.gunnery

        if (now >= state.nextScanAt) {
            state.nextScanAt = now + SCAN_INTERVAL
            state.targetShip = pickTarget(level, pirate, chase.leaderId)
        }
        if (state.targetShip == 0L) {
            census(level, pirate, state, now, whyNoTarget(level, pirate))
            return
        }
        if (now < state.nextShotAt) return

        val target = level.shipObjectWorld.loadedShips.getById(state.targetShip)
        if (target == null) {
            state.targetShip = 0L
            return
        }
        val aim = aimPoint(level, pirate, target)
        if (aim == null) {
            state.targetShip = 0L
            return
        }

        var guns = 0
        var manned = 0
        var lastRefusal: String? = null
        for (gun in ShipGuns.aboard(level, pirate)) {
            guns++
            if (gun.readyAt > now) {
                lastRefusal = "cooling"
                continue
            }
            val gunner = GunnerMounts.gunnerAt(level, gun.blockPos)
            if (gunner == null || !gunner.isAlive) {
                lastRefusal = "no living gunner mounted"
                continue
            }
            manned++
            val refusal = fireAt(level, gun, aim, cfg.pirateCannonJitterBlocks)
            if (refusal == null) {
                state.nextShotAt = now + cfg.pirateCannonStaggerTicks.toLong().coerceAtLeast(1L)
                return
            }
            lastRefusal = refusal.string
            // This gun could not take the shot (off the bore line, or dry) -- ask the next one.
        }
        // Nobody could bear this tick -- the hull is still turning. No point walking every breech again
        // for a few ticks; the ship's own manoeuvre is what changes the answer.
        state.nextShotAt = now + IDLE_RETRY_TICKS
        census(
            level, pirate, state, now,
            "$guns guns, $manned manned, none fired (last: ${lastRefusal ?: "no guns aboard"})"
        )
    }

    /** DEV ONLY: "no target" has several authors; name the one that applies, with the numbers. */
    private fun whyNoTarget(level: ServerLevel, pirate: LoadedServerShip): String {
        val own = ArmadaGroup.idsOf(level, pirate)
        val centre = shipCentre(pirate) ?: return "no target: this hull has no centre (unloaded?)"
        var crewed = 0
        var nearestSq = Double.MAX_VALUE
        for (candidate in level.shipObjectWorld.loadedShips) {
            if (candidate.id in own || candidate.chunkClaimDimension != level.dimensionId) continue
            if (PirateShips.isPirate(candidate.id)) continue
            val nearest = playersAboard(level, candidate).minOfOrNull { it.position().distanceToSqr(centre) }
                ?: continue
            crewed++
            if (nearest < nearestSq) nearestSq = nearest
        }
        if (crewed > 0) {
            return "no target: nearest crewed hull is %.1f blocks off, engage range %.1f"
                .format(Math.sqrt(nearestSq), EurekaConfig.SERVER.pirateCannonEngageRange)
        }

        // DEV ONLY: nobody qualified, and "no player aboard anything" is not a believable answer while a
        // captain is circling in plain sight -- so say what was actually looked at. Ship by ship: who was
        // skipped and why, how far off it was, and what VS2 thinks each player is standing on.
        val roll = StringBuilder("no target. loaded hulls:")
        var listed = 0
        for (candidate in level.shipObjectWorld.loadedShips) {
            if (listed++ >= 6) {
                roll.append(" ...")
                break
            }
            val centreOf = shipCentre(candidate)
            val dist = centreOf?.distanceTo(centre) ?: -1.0
            val why = when {
                candidate.id in own -> "own"
                candidate.chunkClaimDimension != level.dimensionId -> "otherDim"
                PirateShips.isPirate(candidate.id) -> "pirate"
                else -> "crew=${playersAboard(level, candidate).size}"
            }
            roll.append(" [%d %s %.0fm]".format(candidate.id, why, dist))
        }
        roll.append(" | players:")
        for (player in level.players()) {
            roll.append(
                " [%s on=%s %.0fm]".format(
                    player.name.string, ShipCrew.standingOn(player)?.toString() ?: "ground",
                    player.position().distanceTo(centre)
                )
            )
        }
        return roll.toString()
    }

    /**
     * DEV ONLY: a throttled one-liner saying why a pursuing pirate is not shooting. Silence had four
     * possible authors -- no target, no gunners, dry magazines, nothing bearing -- and no way to tell
     * them apart from the deck. Strip with the ROADMAP 6c sweep.
     */
    private fun census(level: ServerLevel, pirate: LoadedServerShip, state: State, now: Long, why: String) {
        if (now < state.nextCensusAt) return
        state.nextCensusAt = now + CENSUS_INTERVAL
        log.info("[gunnery] ship {}: {}", pirate.id, why)
    }

    /**
     * The enemy: a loaded hull, not of this armada, not a fellow pirate, with a PLAYER aboard, inside
     * engage range -- nearest first, except the chase's own quarry always outranks the field. "Aboard" is
     * [ShipCrew]'s live carry-state test, never the stored worldAABB, which this manager has already
     * caught being seconds stale.
     */
    private fun pickTarget(level: ServerLevel, pirate: LoadedServerShip, preferredId: Long?): Long {
        val range = EurekaConfig.SERVER.pirateCannonEngageRange
        val rangeSq = range * range
        val centre = shipCentre(pirate) ?: return 0L
        val own = ArmadaGroup.idsOf(level, pirate)

        fun qualifies(candidate: LoadedServerShip): Double? {
            if (candidate.id in own) return null
            if (candidate.chunkClaimDimension != level.dimensionId) return null
            if (PirateShips.isPirate(candidate.id)) return null
            // Range to the PEOPLE, not between hull centres. Two big ships fighting alongside can have
            // sixty blocks between their centres while their rails are ten apart -- centre-to-centre had
            // the guns declaring an enemy out of range that was close enough to board.
            val nearest = playersAboard(level, candidate).minOfOrNull { it.position().distanceToSqr(centre) }
                ?: return null
            return if (nearest <= rangeSq) nearest else null
        }

        // The ship this pirate is CHASING outranks the field, and unlike the field it does not have to
        // have anyone standing on it. The chase already settled that this hull is the enemy -- so a
        // captain who leaps aboard to fight hand-to-hand, or falls in the sea, does not thereby make
        // their own ship invisible to the guns. (Found the hard way: the census showed the only player
        // in the world standing on the PIRATE's own deck, so nothing was shootable and the broadside
        // sat silent while a perfectly good quarry sailed alongside.)
        preferredId?.let { id ->
            val leader = level.shipObjectWorld.loadedShips.getById(id)
            if (leader != null && leader.id !in own && !PirateShips.isPirate(leader.id) &&
                leader.chunkClaimDimension == level.dimensionId
            ) {
                val distSq = playersAboard(level, leader).minOfOrNull { it.position().distanceToSqr(centre) }
                    ?: shipCentre(leader)?.distanceToSqr(centre)
                if (distSq != null && distSq <= rangeSq) return id
            }
        }

        var best = 0L
        var bestSq = Double.MAX_VALUE
        for (candidate in level.shipObjectWorld.loadedShips) {
            val distSq = qualifies(candidate) ?: continue
            if (distSq < bestSq) {
                bestSq = distSq
                best = candidate.id
            }
        }
        return best
    }

    /**
     * Where to lay the guns: the aboard player nearest this pirate -- the deck is where the fight is --
     * and failing that the hull itself, so a quarry whose captain has stepped off is still shot at
     * rather than quietly forgiven.
     */
    private fun aimPoint(level: ServerLevel, pirate: LoadedServerShip, target: LoadedServerShip): Vec3? {
        val centre = shipCentre(pirate) ?: return null
        val nearest = playersAboard(level, target).minByOrNull { it.position().distanceToSqr(centre) }
        return nearest?.position()?.add(0.0, 0.5, 0.0) ?: shipCentre(target)
    }

    /**
     * Who counts as being aboard [ship] for gunnery -- and it must be the SAME rule the chase wakes on,
     * or a ship can be worth hunting across an ocean and not worth shooting at once you catch it. Which
     * is exactly what the field test found: pirates in close pursuit, guns silent, the census reporting
     * no enemy "with a player aboard" about the very ship they were chasing.
     *
     * The chase's own test ([PirateShips] trespass) is VS2's influence box -- the hull's world AABB plus
     * a margin -- which counts the captain hovering at the rail or flying alongside their own deck. The
     * carry-state test is kept as well, since it is exact for anyone actually standing or seated: a
     * helmsman in the wheel's seat is carried but need not be inside the box the instant it is read.
     */
    private fun playersAboard(level: ServerLevel, ship: LoadedServerShip): List<ServerPlayer> {
        val group = ArmadaGroup.idsOf(level, ship)
        val box = ship.worldAABB
        return level.players().filter { player ->
            if (!player.isAlive || player.isSpectator) return@filter false
            if (ShipCrew.standingOn(player) in group) return@filter true
            box != null &&
                player.x >= box.minX() - INFLUENCE_MARGIN && player.x <= box.maxX() + INFLUENCE_MARGIN &&
                player.y >= box.minY() - INFLUENCE_MARGIN && player.y <= box.maxY() + INFLUENCE_MARGIN &&
                player.z >= box.minZ() - INFLUENCE_MARGIN && player.z <= box.maxZ() + INFLUENCE_MARGIN
        }
    }

    /** The hull's live centre: shipyard box middle through the live transform, never the stored worldAABB. */
    fun shipCentre(ship: LoadedServerShip): Vec3? {
        val box = ship.shipAABB ?: return null
        val centre = org.joml.Vector3d(
            (box.minX() + box.maxX() + 1) * 0.5,
            (box.minY() + box.maxY() + 1) * 0.5,
            (box.minZ() + box.maxZ() + 1) * 0.5
        )
        ship.shipToWorld.transformPosition(centre)
        return Vec3(centre.x, centre.y, centre.z)
    }

    // region range spheres (dev wireframe)

    /** One published engage-range sphere, world-space. Immutable snapshot; the render thread only reads. */
    class Sphere(val x: Double, val y: Double, val z: Double, val radius: Double)

    /** Feed the "/vs cannon-range" wireframe. Same shape as PirateShips' zone publishing, deliberately. */
    @Volatile
    @JvmStatic
    var publishRanges = false
        private set

    @JvmStatic
    fun setPublishRanges(enabled: Boolean) {
        publishRanges = enabled
        if (!enabled) publishedRanges.clear()
    }

    private val publishedRanges = java.util.concurrent.ConcurrentHashMap<String, List<Sphere>>()

    @JvmStatic
    fun publishedRanges(dimension: String): List<Sphere> = publishedRanges[dimension] ?: emptyList()

    fun publish(dimension: String, spheres: List<Sphere>) {
        publishedRanges[dimension] = spheres
    }

    fun clearPublished() {
        if (publishedRanges.isNotEmpty()) publishedRanges.clear()
    }

    // endregion

    /** Twice a second, like the zone scan: targets change at ship speed, not tick speed. */
    private const val SCAN_INTERVAL = 10L

    /** How long to sit out after a walk of the batteries found nobody bearing. */
    private const val IDLE_RETRY_TICKS = 10L

    /** DEV ONLY: how often the silence census may speak -- twice a second is plenty to read. */
    private const val CENSUS_INTERVAL = 40L

    /** VS2's per-face influence default, mirrored server-side -- the same margin the chase wakes on. */
    private const val INFLUENCE_MARGIN = 2.0

    /**
     * The best affordable arc from [gun] to [target], or null when no measure in the magazine carries
     * that far or the target is off the bore line. Cooldown is NOT checked here -- readiness is the
     * caller's rhythm; this is pure "could she reach".
     */
    fun lay(level: ServerLevel, gun: CannonBlockEntity, target: Vec3): Lay? {
        for (charge in PowderCharge.entries) {
            if (gun.powderCount < charge.powder) continue
            val solution = CannonSolver.solveForGun(
                level, gun, target,
                drag = charge.drag,
                gravity = charge.gravity,
                maxSpeed = charge.speed,
                bearingToleranceDegrees = EurekaConfig.SERVER.pirateCannonBearingToleranceDegrees
            ) ?: continue
            return Lay(solution, charge)
        }
        return null
    }

    /**
     * Solve and fire one gun at [target], jittered by [jitterBlocks]. Returns null on a shot away, or
     * the reason there wasn't one -- either the solver's silence ("cannot bear") or the gun's own
     * refusal (cooling, no shot in the breech).
     *
     * The breech's powder measure is WRITTEN with the chosen charge before firing, because everything
     * downstream -- powder cost, reload, and the drag/gravity pair synced to the client so it can fly
     * the same arc -- reads the measure off the breech. The AI setting the measure it solved with is
     * the same gesture as a player choosing it at the breech, just made per shot.
     */
    fun fireAt(
        level: ServerLevel,
        gun: CannonBlockEntity,
        target: Vec3,
        jitterBlocks: Double,
        consume: Boolean = !EurekaConfig.SERVER.pirateCannonInfiniteAmmo
    ): Component? {
        if (CannonShot.loadOf(gun.shot) == null) {
            return Component.translatable("info.vs_eureka.cannon_no_shot")
        }
        val aimPoint = if (jitterBlocks > 0.0) jitter(level, target, jitterBlocks) else target
        val lay = lay(level, gun, aimPoint)
            ?: return Component.literal("cannot bear")
        gun.powderCharge = lay.charge
        return CannonFire.fireAimed(
            level, gun.blockPos, lay.solution.pitchDegrees, lay.solution.speed, consume
        )
    }

    /**
     * The deliberate hand-tremble: a uniform scatter in a horizontal disc plus half a block of height,
     * applied to the AIM POINT before solving -- so the error flows through the whole arc the way a real
     * mislay would, rather than being pasted onto the muzzle direction afterwards.
     */
    private fun jitter(level: ServerLevel, target: Vec3, radius: Double): Vec3 {
        val random = level.random
        val angle = random.nextDouble() * 2.0 * Math.PI
        val reach = radius * Math.sqrt(random.nextDouble())
        return Vec3(
            target.x + Math.cos(angle) * reach,
            target.y + (random.nextDouble() - 0.5),
            target.z + Math.sin(angle) * reach
        )
    }
}
