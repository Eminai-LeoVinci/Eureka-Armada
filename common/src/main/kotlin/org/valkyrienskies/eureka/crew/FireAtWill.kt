package org.valkyrienskies.eureka.crew

import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.cannon.AutoGunnery
import org.valkyrienskies.eureka.cannon.GunLabels
import org.valkyrienskies.eureka.cannon.ShipGuns
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.ship.ShipIntegrity
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * The standing order that lets a captain's gun crews do what the pillagers do: lay their own guns.
 *
 * `CrewDuties.broadside` is the captain's voice -- Shift+G, every manned gun speaks at whatever elevation
 * it is set to, and the aim is the captain's problem. This is the other half: switch it on from the
 * Operations tab and the same crew work their own solutions, gun by gun, for as long as there is a raider
 * within reach. Shift+G keeps working exactly as it did while the order stands, because the auto-lay
 * writes the gun's real elevation -- a hand-called volley fires the arc the crew have already dialled in.
 *
 * ## The same arithmetic the pirates use
 * Everything about HOW a gun is laid lives in [AutoGunnery] and is shared, deliberately: one aim point per
 * gun (the nearest planking to its own bore line, so a long enemy is engaged along her whole side rather
 * than at whoever is standing on her), one powder measure walked lightest-first, one hand's tremble of
 * scatter. A villager gun crew is not a worse gun crew than a pillager one; they simply work for someone.
 *
 * ## What ends it
 * Three things, and only three. The captain calls it off. The guns cannot find a shot -- which is not an
 * end at all, just silence, since the enemy passing astern for ten seconds is not a reason to make anybody
 * re-issue an order. Or the ship enters freefall, which IS an end: a hull shot below her line is not
 * commanding anything, and the order is struck so a later repair does not quietly reopen fire.
 *
 * ## Who is the enemy
 * Pirate hulls, and nothing else -- a hull is a raider exactly while a pirate-marked wheel stands aboard
 * it ([EurekaShipControl.pirateHelms]). Never another captain's ship, never an armada mate, and never a
 * raider already in freefall: she is beaten, she is a prize, and putting more iron into her only costs
 * powder and blocks the boarders would rather have standing.
 */
object FireAtWill {

    /** Per world tick: every ship under the order gets one look at the enemy. Self-silences to a flag check. */
    fun tick(level: ServerLevel) {
        val cfg = EurekaConfig.SERVER
        if (!cfg.fireAtWillEnabled) return
        val now = level.gameTime
        val stations = lazy { CrewLedger.get(level.server).stationedBerths().associateBy { it.station } }

        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != level.dimensionId) continue
            val control = ship.getAttachment(EurekaShipControl::class.java) ?: continue
            if (!control.fireAtWill) continue
            serve(level, ship, control, now, stations)
        }
    }

    /**
     * One ship's guns, one tick: hold the target, then walk the batteries until one of them speaks.
     *
     * The batteries are walked in LABEL order (L1 port bow to stern, then starboard, then the chasers) --
     * the same order a hand-called broadside rolls in, so an auto-firing ship sounds like a ship rather
     * than like a machine. Only one gun fires per stagger; each gun's own four-second reload is the real
     * ceiling, exactly as it is for a player.
     */
    private fun serve(
        level: ServerLevel,
        ship: LoadedServerShip,
        control: EurekaShipControl,
        now: Long,
        stations: Lazy<Map<Long?, CrewLedger.Berth>>
    ) {
        val cfg = EurekaConfig.SERVER

        // A ship below her freefall line is not commanding anything. Struck rather than merely silenced:
        // the captain who repairs her should have to say the word again.
        if (ShipIntegrity.freefall(control)) {
            control.fireAtWill = false
            control.fireAtWillTarget = 0L
            ShipCrew.tell(
                level, ship,
                "${ShipCrew.name(ship)} is going down -- the guns fall silent.",
                PathMessages.Kind.WARN, PathMessages.Topic.GUNNERY_FIRE_AT_WILL
            )
            return
        }

        if (now >= control.fireAtWillNextScanAt) {
            control.fireAtWillNextScanAt = now + SCAN_INTERVAL
            control.fireAtWillTarget = pickTarget(level, ship)
        }
        if (control.fireAtWillTarget == 0L) return
        if (now < control.fireAtWillNextShotAt) return

        val target = level.shipObjectWorld.loadedShips.getById(control.fireAtWillTarget)
        if (target == null) {
            control.fireAtWillTarget = 0L
            return
        }
        val fallback = AutoGunnery.shipCentre(target) ?: return

        // One crewman per cannon, the same rule the hand-called broadside is built on: a gun speaks only
        // while a stationed gunner holds it. Pick the crew off a deck and that deck goes quiet.
        val labeled = GunLabels.labeled(level, ship)
        val guns: List<CannonBlockEntity> =
            if (labeled.isEmpty()) ShipGuns.aboard(level, ship) else labeled.map { it.gun }

        for (gun in guns) {
            if (gun.readyAt > now) continue
            val berth = stations.value[gun.blockPos.asLong()] ?: continue
            val aim = AutoGunnery.aimFor(level, gun, target) ?: fallback
            // A LOCKED berth keeps its own settings, here as everywhere bulk: her crew solve on the
            // measure their captain left in the breech and refuse the shot rather than re-charging.
            val refusal = AutoGunnery.fireAt(
                level, gun, aim, cfg.fireAtWillJitterBlocks,
                cfg.fireAtWillBearingToleranceDegrees, consume = true,
                only = if (berth.locked) gun.powderCharge else null,
                cooldownTicks = (cfg.cannonFireAtWillFireRateSeconds * 20.0).toLong()
            )
            if (refusal == null) {
                control.fireAtWillNextShotAt = now + cfg.fireAtWillStaggerTicks.toLong().coerceAtLeast(1L)
                return
            }
        }
        // Nothing could bear this tick. The hull is still turning, or the guns are dry -- either way the
        // answer changes at ship speed, not at tick speed, so sit out a moment rather than walking every
        // breech again next tick.
        control.fireAtWillNextShotAt = now + IDLE_RETRY_TICKS
    }

    /**
     * The nearest raider worth shooting: a loaded pirate hull in this dimension, not already beaten, whose
     * nearest planking is inside engage range.
     *
     * Range is measured hull to hull ([AutoGunnery.hullDistanceSq]) rather than between centres, for the
     * reason the pirates' own gate learned it: two big ships fighting alongside can have sixty blocks
     * between their middles while their rails are ten apart.
     */
    private fun pickTarget(level: ServerLevel, ship: LoadedServerShip): Long {
        val range = EurekaConfig.SERVER.fireAtWillEngageRange
        val rangeSq = range * range
        val centre = AutoGunnery.shipCentre(ship) ?: return 0L

        var best = 0L
        var bestSq = Double.MAX_VALUE
        for (candidate in level.shipObjectWorld.loadedShips) {
            if (candidate.id == ship.id) continue
            if (candidate.chunkClaimDimension != level.dimensionId) continue
            val theirs = candidate.getAttachment(EurekaShipControl::class.java) ?: continue
            if (theirs.pirateHelms <= 0) continue
            // Already beaten. She is a prize now; more iron only costs powder and breaks what the
            // boarding party would rather find standing.
            if (ShipIntegrity.freefall(theirs)) continue
            val distSq = AutoGunnery.hullDistanceSq(candidate, centre) ?: continue
            if (distSq > rangeSq || distSq >= bestSq) continue
            bestSq = distSq
            best = candidate.id
        }
        return best
    }

    /** Twice a second, like the pirates' own scan: targets change at ship speed, not tick speed. */
    private const val SCAN_INTERVAL = 10L

    /** How long to sit out after a walk of the batteries found nobody able to bear. */
    private const val IDLE_RETRY_TICKS = 10L
}
