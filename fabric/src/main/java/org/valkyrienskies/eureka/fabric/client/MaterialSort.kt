package org.valkyrienskies.eureka.fabric.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.network.chat.Component

/**
 * How a list of materials is ordered, and the one control that says so.
 *
 * A bill of materials arrives in the order the ship was WALKED -- which is an honest order and a useless one.
 * A captain reading a page is answering one of two questions: "have I got the birch planks" (find a name) or
 * "what is this mostly made of" (find the big numbers). Neither is served by the order the blocks happened to
 * be visited in.
 *
 * ## One setting, every list
 * Kept here rather than on a screen, so the blueprint page, the shipwright's plans and a dismantled ship's
 * claim list all read the same way at once. A captain who sorts by quantity to price a repair and then opens
 * the page for the same hull is asking the same question of both; being answered differently by each is the
 * sort of thing that makes a panel feel arbitrary.
 *
 * Client-side and session-lived, exactly like the crew book's remembered orders. Nothing about it belongs on
 * disk: it is a way of looking, not a property of the ship.
 */
@Environment(EnvType.CLIENT)
object MaterialSort {

    enum class Mode { NAME, MOST, FEWEST }

    var mode: Mode = Mode.NAME
        private set

    /** Step to the next order. Three states is few enough for a cycling button to be read at a glance. */
    fun cycle() {
        mode = when (mode) {
            Mode.NAME -> Mode.MOST
            Mode.MOST -> Mode.FEWEST
            Mode.FEWEST -> Mode.NAME
        }
    }

    /**
     * What the button says. Deliberately terse -- it lives in the corner above a divider, and the arrow is
     * doing the work: A-Z reads as alphabetical anywhere, and 9-1 against 1-9 reads as direction.
     */
    val label: Component
        get() = when (mode) {
            Mode.NAME -> NAME_TEXT
            Mode.MOST -> MOST_TEXT
            Mode.FEWEST -> FEWEST_TEXT
        }

    /**
     * Order [rows] by the current mode.
     *
     * Ties inside a quantity sort fall back to the NAME, so a page with forty rows of "16" does not reshuffle
     * itself every time it is drawn -- a stable order is most of what makes a list feel like a document
     * rather than a readout.
     */
    fun <T> apply(rows: List<T>, name: (T) -> String, count: (T) -> Int): List<T> = when (mode) {
        Mode.NAME -> rows.sortedBy { name(it).lowercase() }
        Mode.MOST -> rows.sortedWith(compareByDescending<T> { count(it) }.thenBy { name(it).lowercase() })
        Mode.FEWEST -> rows.sortedWith(compareBy<T> { count(it) }.thenBy { name(it).lowercase() })
    }

    private val NAME_TEXT: Component = Component.translatable("gui.vs_eureka.sort_name")
    private val MOST_TEXT: Component = Component.translatable("gui.vs_eureka.sort_most")
    private val FEWEST_TEXT: Component = Component.translatable("gui.vs_eureka.sort_fewest")
}
