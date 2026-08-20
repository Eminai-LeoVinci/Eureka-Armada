package org.valkyrienskies.eureka.pirate

import java.util.UUID
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.block.HelmMark
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.crew.CrewStations
import org.valkyrienskies.eureka.path.PathMessages

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

    /** Wall-clock is deliberate here: this paces a HUD message for a human, not game logic. */
    private const val BREAK_DENY_WINDOW_MS = 5_000L
    private val lastBreakDeny = HashMap<UUID, Long>()
}
