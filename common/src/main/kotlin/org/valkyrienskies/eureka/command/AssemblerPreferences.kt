package org.valkyrienskies.eureka.command

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player, in-memory toggle store for the Eureka Assembler, set via
 * `/vs eureka-assembler <floater|balloon> <true|false>` and read once, at ship-assembly time, in
 * [org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity.assemble].
 *
 * The toggles are STICKY: they persist for a player until they explicitly turn them off, so a player
 * sets a mode once and it applies to every ship they assemble ("must be active before a ship is
 * assembled"). ConcurrentHashMap because the command runs on the server command thread while the
 * deferred applyControl (executeIf) may read on a later tick.
 *
 * Not persisted across a restart -- these are session preferences, not world data. A player simply
 * re-enables the mode after a relaunch, exactly like a client toggle.
 */
object AssemblerPreferences {
    // enabled = the "Eureka Assembler!" section master (helm menu). floater/balloon are the two sub auto-fills.
    // Assembly applies a sub only when enabled AND that sub is on, so the master can pause both without losing
    // which subs were picked. The /vs eureka-assembler command auto-sets enabled when it turns a sub on, so
    // command users (who never see the master) keep working exactly as before.
    // floaterBonusPercent/balloonBonusPercent are a TRANSIENT extra % (0..MAX_BONUS) the helm-menu textboxes add
    // on top of the auto-calculated floater/balloon counts for the NEXT assembly, then reset to 0 once a ship is
    // built (see clearBonuses) -- a manual nudge for a ship that feels off, per-sub, not sticky like the toggles.
    data class Prefs(
        var enabled: Boolean = false,
        var floater: Boolean = false,
        var balloon: Boolean = false,
        var floaterBonusPercent: Int = 0,
        var balloonBonusPercent: Int = 0
    )

    // Cap on the manual textbox bonus, matching the helm box's 3-digit filter (a sane ceiling so a fat-fingered
    // 9999% can't try to convert the whole hull; the shortfall message still fires if the bonus outruns blocks).
    const val MAX_BONUS = 999

    private val byPlayer = ConcurrentHashMap<UUID, Prefs>()

    fun setEnabled(id: UUID, value: Boolean) {
        byPlayer.getOrPut(id) { Prefs() }.enabled = value
    }

    fun setFloater(id: UUID, value: Boolean) {
        byPlayer.getOrPut(id) { Prefs() }.also { it.floater = value; if (value) it.enabled = true }
    }

    fun setBalloon(id: UUID, value: Boolean) {
        byPlayer.getOrPut(id) { Prefs() }.also { it.balloon = value; if (value) it.enabled = true }
    }

    fun setFloaterBonus(id: UUID, percent: Int) {
        byPlayer.getOrPut(id) { Prefs() }.floaterBonusPercent = percent.coerceIn(0, MAX_BONUS)
    }

    fun setBalloonBonus(id: UUID, percent: Int) {
        byPlayer.getOrPut(id) { Prefs() }.balloonBonusPercent = percent.coerceIn(0, MAX_BONUS)
    }

    /** Reset both textbox bonuses to 0 -- called after a successful assembly so the % doesn't silently carry over. */
    fun clearBonuses(id: UUID) {
        byPlayer[id]?.also { it.floaterBonusPercent = 0; it.balloonBonusPercent = 0 }
    }

    /** Current toggles for a player (defaults to all off); never null, never mutated by the reader. */
    fun get(id: UUID): Prefs = byPlayer[id]?.copy() ?: Prefs()
}
