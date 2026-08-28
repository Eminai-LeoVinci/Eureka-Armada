package org.valkyrienskies.eureka.gui.shiphelm

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

/**
 * One tab of the helm menu's ship-category strip -- Boats & Ships, Airships, Submarine.
 *
 * Drawn in [renderContents] like the other helm widgets, because 1.21.11 made AbstractButton.renderWidget
 * final. Each tab carries its own accent colour so the panel reads as a different instrument at a glance
 * even before you get to the labels.
 *
 * Four looks, and the distinction between the first two is the point of the whole strip:
 *
 *  - [State.ACTIVE] -- this is what the ship IS. Solid accent fill, and the bottom border is left off so the
 *    tab reads as joined to the panel below it.
 *  - [State.PEEK] -- you are looking at this category's panel on a ship that is something else. Hollow, with
 *    an accent outline, and still open at the bottom. Nothing here changes how the ship handles.
 *  - [State.IDLE] -- a category you could look at but aren't. Sunk below the baseline, plain panel colours.
 *  - [State.DISABLED] -- Submarine, until the submarine control law lands. Dim, and [active] is false so it
 *    cannot be selected.
 *
 * Clicking never reaches the server: a ship's category is derived from its floaters and balloons, not chosen,
 * so a tab is a view control and nothing more.
 */
class ShipHelmTab(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    text: Component,
    private val font: Font,
    private val accent: Int,
    private val state: () -> State,
    onPress: OnPress
) : Button(x, y, width, height, text, onPress, DEFAULT_NARRATION) {

    enum class State { ACTIVE, PEEK, IDLE, DISABLED }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val s = state()

        // An idle tab sits a couple of pixels lower than the selected one, so the strip reads as depth
        // rather than as three buttons that happen to be coloured differently.
        val top = if (s == State.ACTIVE || s == State.PEEK) y else y + SUNK
        val bottom = y + height

        val border = when (s) {
            State.ACTIVE, State.PEEK -> accent
            State.IDLE -> IDLE_BORDER
            State.DISABLED -> DIM_BORDER
        }
        val fill = when {
            s == State.ACTIVE -> accent
            s == State.PEEK -> if (isHovered) IDLE_BG_HOVER else PANEL_BG
            s == State.IDLE -> if (isHovered) IDLE_BG_HOVER else IDLE_BG
            else -> DIM_BG
        }
        val textColor = when (s) {
            State.ACTIVE -> ACTIVE_TEXT
            State.PEEK -> accent
            State.IDLE -> IDLE_TEXT
            State.DISABLED -> DIM_TEXT
        }

        // Border, then the fill inset by one pixel on three sides. The BOTTOM edge of a selected tab is left
        // open (fill drawn all the way down to `bottom`) so it runs into the panel; an unselected one is
        // closed, which is what puts it visually behind.
        guiGraphics.fill(x, top, x + width, bottom, border)
        val open = s == State.ACTIVE || s == State.PEEK
        guiGraphics.fill(x + 1, top + 1, x + width - 1, if (open) bottom else bottom - 1, fill)

        // Label centred, at the checkbox label scale so the strip sits in the same type family as the rows
        // below it. The pose is a 2D Matrix3x2fStack in 1.21.11, so divide screen coords by the scale.
        val scale = ShipHelmCheckbox.LABEL_SCALE
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.scale(scale, scale, 1f)
        val tx = (x + (width - font.width(message) * scale) / 2f) / scale
        val ty = (top + (bottom - top - font.lineHeight * scale) / 2f) / scale
        guiGraphics.drawString(font, message, Math.round(tx), Math.round(ty), textColor, false)
        pose.popPose()
    }

    companion object {
        /** How far an unselected tab sits below the selected one. */
        const val SUNK = 3

        // Accents. Chosen to stay apart from one another at a glance, and to stay legible against the panel's
        // 0xFFC6C6C6 grey both as a fill (with white text on it) and as a hairline outline.
        const val ACCENT_BOAT = 0xFF1F5C8B.toInt()      // deep navy -- open water
        const val ACCENT_AIRSHIP = 0xFF9A7328.toInt()   // brass -- envelope and rigging
        const val ACCENT_SUBMARINE = 0xFF2A6B5A.toInt() // abyss green

        private const val PANEL_BG = 0xFFC6C6C6.toInt()
        private const val ACTIVE_TEXT = 0xFFF0F0F0.toInt()
        private const val IDLE_BORDER = 0xFF8B8B8B.toInt()
        private const val IDLE_BG = 0xFFB0B0B0.toInt()
        private const val IDLE_BG_HOVER = 0xFFDCDCDC.toInt()
        private const val IDLE_TEXT = 0xFF505050.toInt()
        private const val DIM_BORDER = 0xFF9A9A9A.toInt()
        private const val DIM_BG = 0xFFAFAFAF.toInt()
        private const val DIM_TEXT = 0xFF8A8A8A.toInt()
    }
}
