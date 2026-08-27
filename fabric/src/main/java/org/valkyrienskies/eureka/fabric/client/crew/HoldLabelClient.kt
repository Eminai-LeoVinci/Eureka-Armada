package org.valkyrienskies.eureka.fabric.client.crew

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import org.valkyrienskies.eureka.crew.HoldTag
import org.valkyrienskies.eureka.crew.HoldTags

/**
 * The number and tags of the chest screen currently open, as the server last told us.
 *
 * One slot, not a map. Only one container screen can be open at a time, and keying by the menu's container
 * id is what stops a stale label from a chest closed a moment ago being drawn over the next one -- ids are
 * handed out per open, so a mismatch means "this is not the box that message was about".
 *
 * Nothing here is persisted or ticked: the label arrives once when the menu opens and cannot change while
 * the screen is up, because the number is a property of the ship's geometry and the tags of a box the
 * player is currently looking inside.
 */
@Environment(EnvType.CLIENT)
object HoldLabelClient {

    private var containerId = -1
    private var label: String? = null
    private var tags: Set<HoldTag> = emptySet()

    fun accept(containerId: Int, label: String, tagMask: Int) {
        this.containerId = containerId
        this.label = label
        this.tags = HoldTags.fromMask(tagMask)
    }

    /** The label for [containerId], or null when the open screen is not a numbered hold. */
    fun labelFor(containerId: Int): String? = if (this.containerId == containerId) label else null

    /**
     * What the open box is for, rendered as a short suffix -- "[Shot]", "[Powder, Fuel]" -- or empty when it
     * has learned nothing yet.
     *
     * Spelled out rather than drawn as icons: three categories is few enough that words are shorter to read
     * than a legend, and a captain who has just tipped cannonballs into a barrel wants to see the word
     * confirming the barrel noticed.
     */
    fun tagsFor(containerId: Int): String {
        if (this.containerId != containerId || tags.isEmpty()) return ""
        return tags.sortedBy { it.ordinal }.joinToString(", ", prefix = "  [", postfix = "]") { it.label }
    }
}
