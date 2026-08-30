package org.valkyrienskies.eureka.pirate

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.block.HelmMark
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.crew.CrewStations
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * THE one gate on a pirate ship's wheel.
 *
 * A helm has fourteen distinct doors -- the menu, the seat, both arms of the Heart gesture, bottle marking,
 * a bottle marked BEFORE the ship turned pirate, blueprint drafting, Sneak+C, every crew-book payload, the
 * rename payloads, the villager job-site claim, and the pickaxe -- and the design rule from the roadmap is
 * that pirates are safe BY CONSTRUCTION: every one of those doors asks the same two questions here, so a
 * fifteenth door added later has one obvious call to make rather than a checklist to rediscover. If each
 * feature checked separately, a Ship Bottle would be a free ship: walk up, click, throw, and the vessel is
 * yours without a fight.
 *
 * Two predicates because access and destruction part ways at TAKEN: a white-hub wheel (crew dead, ship not
 * yet conquered) still refuses its menu -- there is nothing sensible a player can do at a dead pirate's
 * wheel but break it -- yet it must break normally, because breaking it IS the conquest.
 */
object PirateHelm {

    const val MESSAGE = "This is a pirate ship, you cannot access the Helm. Destroy it to conquer the vessel!"

    /** The guns' own refusal. Worded for the gun rather than the wheel, because that is what was clicked. */
    const val GUNS_MESSAGE = "These guns answer to the pirate helm. Destroy it to claim them!"

    /** And the engines'. Same rule, same wheel, different fitting under the crosshair. */
    const val ENGINES_MESSAGE = "This engine answers to the pirate helm. Destroy it to claim her!"

    /** The break refusal, counting the defenders. -1 = a wheel with no crew records (a test helm). */
    fun defended(remaining: Int): String = when {
        remaining < 0 -> "The crew defends the helm -- defeat the pillagers to conquer the vessel!"
        remaining == 1 -> "The crew defends the helm -- 1 pillager still stands."
        else -> "The crew defends the helm -- $remaining pillagers still stand."
    }

    /** Every interaction door asks this: is this wheel a pirate's (or a dead pirate's, pre-conquest)? */
    @JvmStatic
    fun gated(state: BlockState): Boolean =
        state.block is ShipHelmBlock && state.getValue(EurekaProperties.MARK) != HelmMark.NORMAL

    /**
     * Every destruction path asks this: mining, explosions, cannon fire, deck fires. PIRATE only --
     * a TAKEN wheel breaks and drops like any other, by design.
     */
    @JvmStatic
    fun inviolable(state: BlockState): Boolean =
        state.block is ShipHelmBlock && state.getValue(EurekaProperties.MARK) == HelmMark.PIRATE

    /** The refusal every access door speaks. Safe to hand a null or client-side player. */
    fun deny(player: Player?) {
        val server = player as? ServerPlayer ?: return
        PathMessages.send(server, MESSAGE, PathMessages.Kind.ERROR)
    }

    /** The same refusal, spoken by a gun. */
    fun denyGuns(player: Player?) {
        val server = player as? ServerPlayer ?: return
        PathMessages.send(server, GUNS_MESSAGE, PathMessages.Kind.ERROR)
    }

    /** The same refusal, spoken by an engine. */
    fun denyEngines(player: Player?) {
        val server = player as? ServerPlayer ?: return
        PathMessages.send(server, ENGINES_MESSAGE, PathMessages.Kind.ERROR)
    }

    /**
     * The refusal the pickaxe hears, rate-limited to roughly the message's own display life. The server
     * consults [ShipHelmBlock.getDestroyProgress] once per break ATTEMPT (on START_DESTROY_BLOCK), not per
     * tick, so this is a one-shot [PathMessages.Kind.ERROR] rather than a PROMPT refresh -- and because a
     * player worrying at an unbreakable block restarts the attempt over and over, the window below is what
     * keeps the HUD from stacking forty copies of the same line.
     */
    fun denyBreak(player: Player?, remaining: Int) {
        val server = player as? ServerPlayer ?: return
        val now = System.currentTimeMillis()
        val last = lastBreakDeny[server.uuid]
        if (last != null && now - last < BREAK_DENY_WINDOW_MS) return
        lastBreakDeny[server.uuid] = now
        PathMessages.send(server, defended(remaining), PathMessages.Kind.ERROR)
    }

    /**
     * Whether [ship] still answers to a pirate wheel, black or white. While it does, no other helm may be
     * placed aboard: a fresh wheel beside the pirate's would hand over the ship's controls -- and later its
     * conquest -- without the fight the pirate wheel exists to force. Breaking theirs is the only door.
     */
    fun shipHasPirateWheel(level: ServerLevel, ship: LoadedServerShip): Boolean =
        CrewStations.helmsAboard(level, ship)?.any { gated(it.blockState) } == true

    /**
     * Whether a block broken at [pos] should hand the breaker anything at all.
     *
     * A raider's hull is breakable and always was -- you can chop a hole in her side with an axe and climb
     * in, which is how a player without a ship of their own boards one. What she is not is a quarry. While
     * ANY pirate wheel still stands aboard, black hub or white, every block broken on her gives nothing:
     * not her planking, not her balloons, not her engines, and not her guns. Her chests are untouched by
     * this, because looting a chest is opening it, not breaking it -- a raid you can carry out and run from
     * is the point, and it is a different act from dismantling the ship for parts.
     *
     * Break the wheel and the rule lifts in the same instant, because the wheel IS the fight. What was a
     * fortress becomes a prize, and everything in her -- guns included -- can be claimed by hand.
     *
     * Deliberately asked of the SHIP rather than of the block: a pirate ship is pirate everywhere, and a
     * rule that had to be stamped on each block would be a rule with holes in it wherever a raider's
     * carpenter had put something the stamp did not know about.
     */
    fun dropsSuppressedAt(level: LevelAccessor, pos: BlockPos): Boolean = aboardPirateShip(level, pos)

    /**
     * Whether whatever stands at [pos] is aboard a hull that still answers to a pirate wheel.
     *
     * The ship-level question every block-level rule is built on, named for the question rather than for
     * the first thing that asked it. [dropsSuppressedAt] is this asked about BREAKING; a cannon's magazine
     * asks it about OPENING. Both want the same answer for the same reason -- she is a fortress until her
     * wheel goes -- and both lift in the same instant, which is what makes conquest the one door.
     *
     * Her chests remain the deliberate exception, and the exception is what defines the rule: looting a
     * chest is a raid you carry out and run from, where emptying her magazines is dismantling her arsenal
     * without ever having fought for it.
     */
    fun aboardPirateShip(level: LevelAccessor, pos: BlockPos): Boolean {
        val server = level as? ServerLevel ?: return false
        val ship = server.getLoadedShipManagingPos(pos) as? LoadedServerShip ?: return false
        return shipHasPirateWheel(server, ship)
    }

    /** Wall-clock is deliberate here: this paces a HUD message for a human, not game logic. */
    private const val BREAK_DENY_WINDOW_MS = 5_000L
    private val lastBreakDeny = HashMap<UUID, Long>()
}
