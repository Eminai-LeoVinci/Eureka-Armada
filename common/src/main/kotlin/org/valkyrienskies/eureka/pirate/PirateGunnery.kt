package org.valkyrienskies.eureka.pirate

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaGroup
import org.valkyrienskies.eureka.cannon.AutoGunnery
import org.valkyrienskies.eureka.cannon.ShipGuns
import org.valkyrienskies.eureka.crew.GunnerMounts
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * A raider's gunnery: WHO the pillagers shoot at, and the rhythm they shoot in.
 *
 * How to shoot is [AutoGunnery]'s, shared with the villager gun crews a captain can put on the same
 * footing ([FireAtWill]) -- one aim point per gun, one powder measure walked lightest-first, one hand's
 * tremble. What is particular to a pirate is the enemy: a hull with PEOPLE aboard it, which is what keeps
 * a raider from shelling an empty derelict, and its own quarry above all others.
 */
object PirateGunnery {

    /**
     * One chase's gunnery bookkeeping. It lives ON the [PirateShips.PirateChase] and nowhere else, so
     * the guns go quiet by the same door the pursuit does: chase retired, state gone, nothing to leak.
     */
    class State {
        /** The enemy hull under fire, 0 for none. Re-picked on the scan clock, not per shot. */
        var targetShip: Long = 0L
        var nextScanAt: Long = 0L
        var nextShotAt: Long = 0L
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
        if (state.targetShip == 0L) return
        if (now < state.nextShotAt) return

        val target = level.shipObjectWorld.loadedShips.getById(state.targetShip)
        if (target == null) {
            state.targetShip = 0L
            return
        }
        // The whole-hull fallback, for a gun whose own bore geometry cannot be read (an unloaded chunk
        // mid-solve). Every gun that CAN be read gets its own aim point below.
        val fallback = shipCentre(target)
        if (fallback == null) {
            state.targetShip = 0L
            return
        }

        for (gun in ShipGuns.aboard(level, pirate)) {
            if (!gun.readyBy(now)) continue
            val gunner = GunnerMounts.gunnerAt(level, gun.blockPos)
            if (gunner == null || !gunner.isAlive) continue
            val aim = AutoGunnery.aimFor(level, gun, target) ?: fallback
            val refusal = AutoGunnery.fireAt(
                level, gun, aim, cfg.pirateCannonJitterBlocks,
                cfg.pirateCannonBearingToleranceDegrees, consume = !cfg.pirateCannonInfiniteAmmo,
                cooldownTicks = (cfg.pirateFireAtWillFireRateSeconds * 20.0).toLong()
            )
            if (refusal == null) {
                state.nextShotAt = now + cfg.pirateCannonStaggerTicks.toLong().coerceAtLeast(1L)
                return
            }
            // This gun could not take the shot (off the bore line, or dry) -- ask the next one.
        }
        // Nobody could bear this tick -- the hull is still turning. No point walking every breech again
        // for a few ticks; the ship's own manoeuvre is what changes the answer.
        state.nextShotAt = now + IDLE_RETRY_TICKS
    }


    /**
     * The enemy: a loaded hull, not of this armada, not a fellow pirate, with a PLAYER aboard, whose
     * NEAREST PLANKING is inside engage range -- closest first, except the chase's own quarry always
     * outranks the field. "Aboard" is [ShipCrew]'s live carry-state test, never the stored worldAABB,
     * which this manager has already caught being seconds stale.
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
            // A crew makes a hull worth shooting at; the HULL is what the range is then measured to. Where
            // those people are standing on it is no business of the range gate -- see [hullDistanceSq].
            if (playersAboard(level, candidate).isEmpty()) return null
            val nearest = AutoGunnery.hullDistanceSq(candidate, centre) ?: return null
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
                val distSq = AutoGunnery.hullDistanceSq(leader, centre) ?: shipCentre(leader)?.distanceToSqr(centre)
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

    /** The hull's live centre. Kept as a name here because PirateShips and the zone wireframe ask for it. */
    fun shipCentre(ship: LoadedServerShip): Vec3? = AutoGunnery.shipCentre(ship)

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


    /** VS2's per-face influence default, mirrored server-side -- the same margin the chase wakes on. */
    private const val INFLUENCE_MARGIN = 2.0
}
