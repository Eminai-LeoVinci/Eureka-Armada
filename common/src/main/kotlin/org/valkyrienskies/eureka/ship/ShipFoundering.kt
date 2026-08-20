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

    private var tickCount = 0L

    /** Advance every dying ship in [level]. Called once per server world tick. */
    fun tick(level: ServerLevel) {
        tickCount++
        if (tickCount % 20 != 0L) return

        val dimension = level.dimensionId
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dimension) continue
            val control = ship.getAttachment(EurekaShipControl::class.java) ?: continue

            if (control.helms >= 1) {
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
            if (cfg.helmlessGraceEnabled) {
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
        }
    }

    /**
     * The helm-less teardown: the essential core of ShipHelmBlockEntity.disassemble with everything the
     * wheel owned stripped away -- no menus to close, no name to remember, no alignment dance (the unfill
     * plan snaps rotation regardless, and a hull that came to rest is as aligned as it will ever be). Crew
     * are stood down by the box sweep with no name to call them by; a pirate's crew are already dead.
     */
    fun scuttle(level: ServerLevel, ship: LoadedServerShip): Boolean {
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
        EntityShipCollisionUtils.markWorldFreeze(level, holdAABB, 2_000_000_000L)
        if (!ShipAssembler.unfillShip(
                level, ship, anchor,
                BlockPos.containing(inWorld.x, inWorld.y, inWorld.z)
            )
        ) {
            return false
        }
        EntityShipCollisionUtils.markWorldFreeze(level, holdAABB, 2_000_000_000L)
        CrewMuster.standDownShip(level, shipId, holdAABB, crewName = null, variant = null)
        return true
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
        tickCount = 0L
    }

    private fun settleTicks(): Long = (EurekaConfig.SERVER.helmlessSettleSeconds * 20.0).toLong()

    /** A hull moving less than this per second is resting on something. */
    private const val SETTLE_EPSILON = 0.15
}
