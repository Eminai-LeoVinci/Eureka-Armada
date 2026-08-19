package org.valkyrienskies.eureka.ship

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.shipwright.ShipRepair
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * What is left of a ship, and what that costs her.
 *
 * ## Integrity is a block count, not an assessment
 * A ship's integrity is her current block count as a fraction of the count captured at assembly --
 * [EurekaShipControl.currentBlocks] over [EurekaShipControl.assembledBlocks]. Nothing here walks the hull:
 * the count is maintained live by [onSetBlock], one increment per block placed and one decrement per block
 * lost, however it was lost -- cannon fire, flames, or a captain's own pickaxe. That is what lets the
 * repercussions answer every physics tick without ever paying for a survey, and it is why they respond the
 * instant a broadside lands rather than the next time somebody opens a menu.
 *
 * The shipwright's plans-match percentage is deliberately NOT this number. That one needs filed plans and a
 * template walk, exists only for ships somebody blueprinted, and refreshes only when asked. Every assembled
 * ship has a block count.
 *
 * ## The three repercussions
 * All thresholds are INTEGRITY percentages -- 100 is pristine -- and all of them read config live, so a
 * `/reload` lands on the next physics tick:
 *
 *  - **Speed** ([speedMultiplier]): full speed at or above `damageSpeedLossStart`, ramping linearly down to
 *    `1 - damageSpeedLossMaxPercent` at `damageSpeedLossFull`, flat below. A mauled ship limps; it never
 *    quite stops.
 *  - **Settling** ([sinkRate]): none at or above `damageSinkStart`, ramping to
 *    `damageSinkMaxMetersPerSecond` at `damageSinkFull`. Airships bleed altitude; boats bleed buoyancy, but only while
 *    the keel is actually in water. Ascend still applies its whole force against this, which is the design:
 *    a captain with power to spare can hold a wounded ship up, at the price of their attention, until
 *    repairs are made.
 *  - **Ungoverned** ([freefall]): below `damageFreefallBelow` the control law stands down entirely and the
 *    hull ragdolls exactly as if every helm aboard were broken. Repair her back over the line and the gyro
 *    rights her and the wheel answers again.
 *
 * ## Ships from before this existed
 * An attachment that has never had its count established ([EurekaShipControl.blocksCounted] false) reads
 * 100% -- "unknown" must never mean "everything is gone", or a world of old ships would fall out of the sky
 * on first load. The helm's next game tick then takes a one-time [census]: a real count of the hull as it
 * stands, so a ship damaged before the update owns its damage rather than being presumed healthy. Every
 * assembly stamps the count exactly and nothing pays for the walk twice.
 */
object ShipIntegrity {

    /**
     * This ship's integrity, 0..100. A ship whose count has never been established reads 100 -- see the
     * class note.
     *
     * A pure read, deliberately: the physics thread calls this every tick, and the writes -- the census, the
     * per-block deltas -- all happen on the game thread, where they cannot race each other.
     */
    fun integrityPercent(control: EurekaShipControl): Int {
        if (!control.blocksCounted) return 100
        val baseline = control.assembledBlocks
        val current = control.currentBlocks
        if (baseline <= 0 || current <= 0) return 100
        return (100L * current / baseline).toInt().coerceIn(0, 100)
    }

    /**
     * Establish the live count of a ship that predates it, by actually counting.
     *
     * The first version of this "seeded" an old ship at its baseline -- assumed healthy -- and that
     * assumption was wrong precisely for the ships this feature is about: a hull shot half to pieces before
     * the update loaded at 100% and then only ever counted its NEW wounds, reading 81% while the shipwright,
     * which counts fresh every time, could see 41% of it standing. So the one-time bridge counts too --
     * [ShipRepair.bounds] is the same walk the shipwright takes, and its answer is the same truth.
     *
     * Called from the helm's own game-thread tick (the same thread the per-block deltas arrive on), once
     * ever per ship: assembly stamps [EurekaShipControl.blocksCounted] alongside its exact count, so the
     * only hulls that ever pay for this walk are the ones carried over from before the count existed. A
     * null bounds (shipyard chunks not readable yet) just tries again next tick.
     */
    fun census(level: ServerLevel, ship: LoadedServerShip, control: EurekaShipControl) {
        if (control.blocksCounted || control.assembledBlocks <= 0) return
        val hull = ShipRepair.bounds(level, ship) ?: return
        control.currentBlocks = hull.blocks
        control.blocksCounted = true
    }

    /** What is left of the ship's speed, 0.5..1.0 at the default config. */
    fun speedMultiplier(integrity: Int): Double {
        val cfg = EurekaConfig.SERVER
        if (!cfg.shipDamageRepercussions) return 1.0
        val start = cfg.damageSpeedLossStart.coerceIn(1, 100)
        val full = cfg.damageSpeedLossFull.coerceIn(0, start - 1)
        val maxLoss = cfg.damageSpeedLossMaxPercent.coerceIn(0, 99) / 100.0
        if (integrity >= start) return 1.0
        if (integrity <= full) return 1.0 - maxLoss
        return 1.0 - maxLoss * (start - integrity).toDouble() / (start - full).toDouble()
    }

    /**
     * How fast the ship settles, in m/s downward. Zero for a sound hull.
     *
     * The same shape as [speedMultiplier] -- nothing at `damageSinkStart`, ramping linearly to the full rate
     * at `damageSinkFull`, flat below. It used to be a fixed 1 m/s per 10 integrity, which tied the rate at
     * which a ship fell to how far apart two config numbers happened to be; a captain setting the start and
     * the cap should get exactly those, with the ramp fitted between them.
     */
    fun sinkRate(integrity: Int): Double {
        val cfg = EurekaConfig.SERVER
        if (!cfg.shipDamageRepercussions) return 0.0
        val start = cfg.damageSinkStart.coerceIn(1, 100)
        val full = cfg.damageSinkFull.coerceIn(0, start - 1)
        val maxRate = cfg.damageSinkMaxMetersPerSecond.coerceAtLeast(0.0)
        if (integrity >= start) return 0.0
        if (integrity <= full) return maxRate
        return maxRate * (start - integrity).toDouble() / (start - full).toDouble()
    }

    /** Whether the hull is too far gone to answer its helm at all. */
    fun freefall(integrity: Int): Boolean {
        val cfg = EurekaConfig.SERVER
        if (!cfg.shipDamageRepercussions) return false
        return integrity < cfg.damageFreefallBelow.coerceIn(0, 100)
    }

    /**
     * One block changed somewhere in the world; if it was aboard a ship, keep her count honest.
     *
     * Called from the same chunk-write choke point VS2 uses to keep mass honest (its `MixinLevelChunk`), so
     * everything that changes a block goes through here -- a cannonball's punch, fire burning through a deck,
     * a shipwright's repair, a player's own hands. Only air<->solid transitions move the count; a door
     * opening or a stair being swapped for another is the same block still standing.
     *
     * Deltas only apply to a SEEDED count. Before the first seed the count reads "unknown" and adjusting
     * unknown by one would invent a number; [integrityPercent] seeds it on first read instead, and the next
     * assembly re-captures both numbers exactly. Any drift the deltas ever accumulate dies the same way.
     */
    fun onSetBlock(level: Level, pos: BlockPos, prev: BlockState, new: BlockState) {
        if (level !is ServerLevel) return
        val wasAir = prev.isAir
        val isAir = new.isAir
        if (wasAir == isAir) return

        val ship = level.getLoadedShipManagingPos(pos) ?: return
        val control = ship.getAttachment(EurekaShipControl::class.java) ?: return
        // Deltas only move a count that has actually been established -- adjusting "unknown" by one would
        // invent a number. The census or the next assembly establishes it; until then integrity reads 100.
        if (!control.blocksCounted || control.assembledBlocks <= 0) return

        control.currentBlocks += if (isAir) -1 else 1
    }
}
