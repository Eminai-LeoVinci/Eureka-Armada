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
        // A blank label means "tags only" -- the answer to a checkbox click, which re-states what the boxes
        // now hold without re-sending a number that has not changed. Overwriting the number with the blank
        // would erase it from the screen the instant a captain ticked anything.
        if (label.isNotEmpty() || this.containerId != containerId) {
            this.containerId = containerId
            this.label = label
        }
        this.tags = HoldTags.fromMask(tagMask)
    }

    /** The tags of [containerId] as a set, empty when the open screen is not a numbered hold. */
    fun tagSetFor(containerId: Int): Set<HoldTag> =
        if (this.containerId == containerId) tags else emptySet()

    /**
     * Tick a box locally and tell the server.
     *
     * Drawn optimistically because the round trip is visible on a click otherwise, and the server answers
     * with the real union a moment later -- which corrects this if the two ever disagree.
     */
    fun toggle(containerId: Int, tag: HoldTag) {
        if (this.containerId != containerId) return
        tags = if (tag in tags) tags - tag else tags + tag
        sender(containerId, tag.ordinal)
    }

    /** Installed by the networking layer; a no-op until then. */
    @JvmField
    var sender: (Int, Int) -> Unit = { _, _ -> }

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
