package org.valkyrienskies.eureka.ship

import kotlin.math.abs
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaBindings
import org.valkyrienskies.eureka.armada.ArmadaShipControl
import org.valkyrienskies.eureka.crew.CrewMuster
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.pirate.PirateStore
import org.valkyrienskies.eureka.util.ShipAssembler
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils
import org.valkyrienskies.mod.util.logger

/**
 * A ship that has lost its last wheel is a dying ship -- every ship, the pirates' and the players' alike.
 *
 * The life it has left: ragdoll where it is (an airship falls out of the sky -- that part has always been
 * true). Touch water and the buoyancy drains to nothing over a configured window, floaters and balloons
 * and honest wood notwithstanding, and she goes down slow under fluid drag. Come to rest -- the seabed, a
 * hillside an airship dropped onto, wherever the world catches her -- and after a settle grace the hull is
 * taken apart where it lies, blocks left as salvage. At ANY point in this, a helm placed aboard (yes,
 * underwater, for whoever is crazy enough) puts the very next physics tick back in the main control body:
 * gyro back, ramp reset, a working ship again wherever she happens to be.
 *
 * This object owns the game-thread half: the water probes physTick cannot cheaply make (fed to the
 * attachment as [EurekaShipControl.founderInWater]), the settle watch, and the helm-less teardown. The
 * physics half -- the ramp, the neutralized buoyant factor, the sink force -- lives in the freefall branch
 * of [EurekaShipControl.physTick].
 *
 * Deliberately NOT watched: armada children (they never steer themselves; the parent's wheel is theirs)
 * and static hulls (an anchor holding a helm-less hull, a conquest freeze -- held is not dying).
 */
object ShipFoundering {

    private val logger by logger()

    private class Watch(var lastY: Double, var stableTicks: Long)

    private val watches = HashMap<Long, Watch>()

    /** shipId -> when its helm-broken grace lapses. Runtime-only: a restart regenerates a full grace. */
    private val graces = HashMap<Long, Long>()

    /**
     * shipId -> when a WRECK that has touched down comes apart. Set once, on first contact, and never
     * reset: a hull balanced on a box a fraction of her own size is always creeping slightly, and a clock
     * that restarted every time she shifted would be a clock that never rang.
     */
    private val grounded = HashMap<Long, Long>()

    private var tickCount = 0L

    /** Advance every dying ship in [level]. Called once per server world tick. */
    fun tick(level: ServerLevel) {
        tickCount++
        if (tickCount % 20 != 0L) return

        val dimension = level.dimensionId
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dimension) continue
            val control = ship.getAttachment(EurekaShipControl::class.java) ?: continue

            // Maintained before anything else looks at this hull, because it decides WHICH of the two
            // deaths she is dying -- and a ship shot down keeps her wheel, so the test below would
            // otherwise wave her straight past.
            val wrecked = updateWreck(level, ship, control)

            if (control.helms >= 1 && !wrecked) {
                if (watches.remove(ship.id) != null) control.founderInWater = false
                graces.remove(ship.id)
                continue
            }
            if (ArmadaShipControl.get(ship)?.isChild == true) continue
            if (ship.isStatic) {
                watches.remove(ship.id)
                continue
            }

            // A conquest window is the pirate manager's clock, not ours: keep the probes flowing (the hold
            // needs them to choose float over hover) and stand aside.
            if (PirateStore.get(level).frozenShips.containsKey(ship.id)) {
                control.founderInWater = probeWater(level, ship)
                watches.remove(ship.id)
                continue
            }

            // The helm-broken grace, the players' version of the conquest window: the ship rides on as she
            // was for the configured time before the dying starts. Zero means forever -- a hull that never
            // founders and never breaks up. Disabled means none: the instant ragdoll of old.
            val cfg = EurekaConfig.SERVER
            // Not for a wreck. The grace answers a wheel being taken from a sound ship; a hull shot to
            // pieces has already had her window, and it was the whole fight that got her here.
            if (!wrecked && cfg.helmlessGraceEnabled) {
                val now = level.gameTime
                val deadline = graces.getOrPut(ship.id) {
                    if (cfg.helmlessGraceSeconds <= 0.0) Long.MAX_VALUE
                    else now + (cfg.helmlessGraceSeconds * 20.0).toLong()
                }
                if (now < deadline) {
                    control.founderHold = true
                    control.founderInWater = probeWater(level, ship)
                    watches.remove(ship.id)
                    continue
                }
            }
            control.founderHold = false

            control.founderInWater = probeWater(level, ship)

            val y = ship.transform.positionInWorld.y()

            if (wrecked) {
                if (tickWreck(level, ship, control, y)) {
                    watches.remove(ship.id)
                    grounded.remove(ship.id)
                    logger.info("[wreck] ship {} broke up where she lay", ship.id)
                }
                continue
            }

            val watch = watches.getOrPut(ship.id) { Watch(y, 0L) }
            if (abs(y - watch.lastY) > SETTLE_EPSILON) {
                watch.lastY = y
                watch.stableTicks = 0L
                continue
            }
            watch.stableTicks += 20L

            // A hull lying perfectly still at the SURFACE mid-ramp is about to move -- resting only counts
            // once the water has taken all the buoyancy it is owed.
            if (control.founderInWater && control.founderRamp < 1.0) continue
            if (watch.stableTicks < settleTicks()) continue
            // Not out from under anyone -- a diver stripping the wreck, a captain fumbling for a helm.
            if (ShipCrew.aboard(level, ship).isNotEmpty()) continue

            if (scuttle(level, ship)) {
                watches.remove(ship.id)
                logger.info("[foundering] ship {} came to rest and broke up", ship.id)
            }
        }

        // Ships deleted elsewhere (bottled, scuttled in another dimension) leave records behind; sweep.
        if (tickCount % 200 == 0L) {
            val world = level.shipObjectWorld
            watches.keys.removeAll { world.allShips.getById(it) == null }
            graces.keys.removeAll { world.allShips.getById(it) == null }
            grounded.keys.removeAll { world.allShips.getById(it) == null }
        }
    }

    /**
     * The helm-less teardown: the essential core of ShipHelmBlockEntity.disassemble with everything the
     * wheel owned stripped away -- no menus to close, no name to remember, no alignment dance (the unfill
     * plan snaps rotation regardless, and a hull that came to rest is as aligned as it will ever be). Crew
     * are stood down by the box sweep with no name to call them by; a pirate's crew are already dead.
     */
    fun scuttle(level: ServerLevel, ship: LoadedServerShip, wreck: Boolean = false): Boolean {
        ArmadaBindings.releaseFromArmada(level, ship)
        val aabb = ship.shipAABB ?: return false
        val anchor = BlockPos(
            (aabb.minX() + aabb.maxX()) / 2,
            (aabb.minY() + aabb.maxY()) / 2,
            (aabb.minZ() + aabb.maxZ()) / 2
        )
        val inWorld = ship.shipToWorld.transformPosition(
            Vector3d(anchor.x + 0.5, anchor.y + 0.5, anchor.z + 0.5)
        )
        val shipId = ship.id
        val holdAABB = EntityShipCollisionUtils.worldAABBForShip(ship)

        // A wreck goes UNDER the ground she came to rest on, and onto her side. She fell on her own hull
        // like any other ship -- the physics engine will always rest her on it -- so the burial is done
        // here, in the one instant her blocks are being written anyway. Nothing is displaced twice and
        // nothing can go wrong in flight.
        var fraction = 0.0
        var maxSink = 0
        var roll = 0
        if (wreck) {
            fraction = EurekaConfig.SERVER.wreckBurialFraction
            // Snapped to a quarter turn. Anything else rotates her blocks off the world lattice, and two
            // shipyard blocks landing on one world block means one of them silently wins.
            roll = ((Math.round(EurekaConfig.SERVER.wreckRollDegrees / 90.0).toInt() % 4) + 4) % 4 * 90
            // Never under the floor of the world. unfillShip REFUSES a hull that would land outside the
            // build range rather than clamping it, so a wreck on bedrock that asked to sink would simply
            // lie there forever, retrying once a second.
            maxSink = (holdAABB.minY - (level.minY + 1)).toInt().coerceAtLeast(0)
        }

        EntityShipCollisionUtils.markWorldFreeze(level, holdAABB, 2_000_000_000L)
        if (!ShipAssembler.unfillShip(
                level, ship, anchor,
                BlockPos.containing(inWorld.x, inWorld.y, inWorld.z),
                wreck = wreck, burialFraction = fraction, maxSink = maxSink, rollDegrees = roll
            )
        ) {
            return false
        }
        if (wreck) {
            logger.info(
                "[wreck] ship {} laid down: rolled {} degrees, buried {}% (cap {} blocks)",
                shipId, roll, (fraction * 100).toInt(), maxSink
            )
        }
        EntityShipCollisionUtils.markWorldFreeze(level, holdAABB, 2_000_000_000L)
        // No crew ids: a foundering has no wheel in hand and no captain behind it, so the hull sweep is the
        // only source -- which is exactly the case that sweep exists for.
        CrewMuster.standDownShip(level, shipId, holdAABB, crewIds = emptyList())
        return true
    }

    /**
     * The wreck latch, and the collider swap that follows from it.
     *
     * ## There are TWO ways to be shot down, and damage is only one of them
     * The obvious one is integrity past the freefall line. The other is losing the WHEEL to an enemy: a
     * pirate whose helm is destroyed and whom nobody claims is a wreck at any integrity at all -- she can
     * be at 95% and still be going down, because what killed her was the boarding, not the cannon. That
     * case is latched where it happens, in [org.valkyrienskies.eureka.pirate.PirateShips], and arrives
     * here already set.
     *
     * The first cut of this tested integrity alone, which meant a raider taken exactly as intended -- crew
     * killed, white wheel broken by hand -- founded the old way, on top of the seabed, because a sound hull
     * reads sound however she died.
     *
     * ## The one death that is NOT a wreck
     * A captain unmaking her OWN ship. Mine the wheel out of a hull you built and she comes apart the way
     * she always did: full collision, settles on the ground, blocks left where they land. That is a
     * disassembly with extra steps, not a sinking, and neither way in above can fire on it.
     *
     * ## And one way out, for both
     * A hull stops being a wreck when she stops DYING -- a wheel aboard, and sound enough to answer it.
     * One rule serves both entrances: a captain who mends a shot-down ship back over the line, and a
     * boarder who slaps a helm on a conquered prize, are the same recovery.
     *
     * A PIRATE still carrying her own wheel is exempt from the damage entrance. She goes ungoverned far
     * earlier than a player's ship on purpose -- she is a prize, meant to be shot until a boarding party
     * can reach her -- so taking her shape away mid-chase, or breaking her up around the boarders, would
     * bury the fight she exists for. Breaking that wheel is what hands her to the other entrance.
     */
    private fun updateWreck(level: ServerLevel, ship: LoadedServerShip, control: EurekaShipControl): Boolean {
        val cfg = EurekaConfig.SERVER
        if (!cfg.wreckBurialEnabled || ArmadaShipControl.get(ship)?.isChild == true) {
            if (control.wrecked) {
                control.wrecked = false
                grounded.remove(ship.id)
            }
            return false
        }

        val shotDown = control.pirateHelms < 1 && ShipIntegrity.freefall(control)
        if (shotDown && !control.wrecked) {
            control.wrecked = true
            logger.info(
                "[wreck] ship {} shot down at {}% integrity",
                ship.id, ShipIntegrity.integrityPercent(control)
            )
        }

        if (control.wrecked && control.helms >= 1 && !shotDown) {
            control.wrecked = false
            grounded.remove(ship.id)
            logger.info("[wreck] ship {} answers a wheel again; she is a ship, not a wreck", ship.id)
        }

        return control.wrecked
    }

    /**
     * A wreck's own settle watch. Returns true when she has come apart.
     *
     * Looser than the helm-less watch and on a CLOCK rather than a condition, because a wreck rests on a
     * box a fraction of her size and never truly stops moving. Once she has touched down the clock is set
     * and never reset: a hull still grinding her way downhill comes apart on schedule rather than sliding
     * out of the feature entirely.
     */
    private fun tickWreck(
        level: ServerLevel,
        ship: LoadedServerShip,
        control: EurekaShipControl,
        y: Double
    ): Boolean {
        val cfg = EurekaConfig.SERVER

        val deadline = grounded[ship.id]
        if (deadline == null) {
            // Still coming down. The first pass has nothing to measure against and only lays the mark.
            val watch = watches[ship.id]
            if (watch == null) {
                watches[ship.id] = Watch(y, 0L)
                return false
            }
            val moved = abs(y - watch.lastY)
            watch.lastY = y
            if (moved > cfg.wreckLandedEpsilon) return false
            // A hull lying still at the SURFACE mid-ramp has not landed; she is about to go under.
            if (control.founderInWater && control.founderRamp < 1.0) return false

            grounded[ship.id] = level.gameTime + (cfg.wreckGroundTimerSeconds * 20.0).toLong()
            logger.info(
                "[wreck] ship {} touched down at y={}; she comes apart in {}s",
                ship.id, y.toInt(), cfg.wreckGroundTimerSeconds
            )
            return false
        }

        if (level.gameTime < deadline) return false
        // Not out from under anyone -- and [ShipCrew.aboard] is the wrong question for a wreck. It resolves
        // through lastShipStoodOn, which a SWIMMER never stamps, and a hull on the seabed is stripped by
        // swimming through her. For a ship lying underwater, being near her IS being aboard her.
        if (anyoneNear(level, ship, cfg.wreckPlayerInfluenceMargin)) return false

        return scuttle(level, ship, wreck = true)
    }

    /** Whether any player is inside [ship]'s hull box, generously inflated. Spectators do not count. */
    private fun anyoneNear(level: ServerLevel, ship: LoadedServerShip, margin: Double): Boolean {
        val box = EntityShipCollisionUtils.worldAABBForShip(ship)
        val m = margin.coerceAtLeast(0.0)
        return level.players().any { player ->
            !player.isSpectator &&
                player.x >= box.minX - m && player.x <= box.maxX + m &&
                player.y >= box.minY - m && player.y <= box.maxY + m &&
                player.z >= box.minZ - m && player.z <= box.maxZ + m
        }
    }

    /**
     * Whether the hull is touching water: three probes down its own vertical -- centre, half-keel, keel.
     * Coarse on purpose; this feeds a ramp measured in tens of seconds, not a collision test.
     */
    private fun probeWater(level: ServerLevel, ship: LoadedServerShip): Boolean {
        val pos = ship.transform.positionInWorld
        val aabb = ship.shipAABB
        val halfHeight = if (aabb != null) (aabb.maxY() + 1 - aabb.minY()) * 0.5 else 2.0
        for (dy in doubleArrayOf(0.0, -halfHeight * 0.5, -halfHeight + 0.5)) {
            val at = BlockPos.containing(pos.x(), pos.y() + dy, pos.z())
            if (!level.isLoaded(at)) continue
            if (level.getFluidState(at).`is`(FluidTags.WATER)) return true
        }
        return false
    }

    /** Forget every runtime record. SERVER_STOPPED teardown. */
    fun reset() {
        watches.clear()
        graces.clear()
        grounded.clear()
        ShipWreck.reset()
        tickCount = 0L
    }

    private fun settleTicks(): Long = (EurekaConfig.SERVER.helmlessSettleSeconds * 20.0).toLong()

    /** A hull moving less than this per second is resting on something. */
    private const val SETTLE_EPSILON = 0.15
}
