package org.valkyrienskies.eureka.crew

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.npc.villager.Villager

/**
 * What a crew member is called.
 *
 * A crew member's name is their real vanilla custom name, not a label we keep on the side. That is worth the
 * small amount of care below, because it is what makes the name show up everywhere a name should: on the
 * nameplate, in the trade screen's title bar, in the death message when they go over the side. Nothing else
 * has to be taught about crew names, because to the rest of the game they are simply named villagers.
 *
 * Signing on gives an unnamed villager a default -- "Crewman4" for berth 3 -- and paying them off takes it
 * back, but ONLY if it is still that untouched default. A name a player typed is theirs and survives being
 * dismissed; a number we handed out does not, so a paid-off villager stops looking like crew and the number is
 * genuinely free for the next hire.
 */
object CrewNames {

    /** The default name for a crew member holding [slot]. Berths are zero-based; the name people read is not. */
    fun defaultFor(slot: Int): String = "Crewman${slot + 1}"

    /** Give [villager] their berth number as a name, unless they already answer to something. */
    fun applyDefault(villager: Villager, slot: Int) {
        if (slot == CrewRoster.NO_SLOT || villager.customName != null) return
        villager.setCustomName(Component.literal(defaultFor(slot)))
    }

    /** Take back a default name on pay-off. A name the player chose is left alone. */
    fun clearDefault(villager: Villager, slot: Int) {
        if (slot == CrewRoster.NO_SLOT) return
        if (villager.customName?.string != defaultFor(slot)) return
        villager.setCustomName(null)
    }

    /** What [villager] is called right now -- their own name if they have one, their profession if not. */
    fun displayName(villager: Villager): String = (villager.customName ?: villager.name).string
}
