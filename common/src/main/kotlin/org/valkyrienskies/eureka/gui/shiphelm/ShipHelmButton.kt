package org.valkyrienskies.eureka.gui.shiphelm

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence

/**
 * A flat, code-drawn button for the ship-helm menu (border + fill + centred label), mirroring
 * [ShipHelmIconButton]/[ShipHelmCheckbox]'s draw technique. The helm overhaul dropped the baked
 * panel/button texture (ship_helm.png) in favour of an all-code-drawn panel, so this no longer blits sprite
 * regions -- it fills its own frame with hover / pressed / inactive states. Width/height are passed in so the
 * three action buttons can be resized to make room for the new layout. Like the other helm widgets it
 * overrides renderContents (1.21.11 made AbstractButton.renderWidget final).
 */
class ShipHelmButton(
    x: Int, y: Int, width: Int, height: Int, text: Component, private val font: Font, onPress: OnPress
) : Button(x, y, width, height, text, onPress, DEFAULT_NARRATION) {

    var isPressed = false

    init {
        active = true
    }

    override fun renderContents(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (!isHovered) isPressed = false

        // Focus lights the button exactly as hover does: it is how a controller's D-pad selection shows
        // itself on screens that walk the buttons by focus rather than by moving a pointer.
        val fill = when {
            !active -> DIM_BG
            isPressed -> BG_PRESSED
            isHovered || isFocused -> BG_HOVER
            else -> BG
        }
        val border = if (active) BORDER else DIM_BORDER
        val textColor = if (active) TEXT else DIM_TEXT

        guiGraphics.fill(x, y, x + width, y + height, border)
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill)

        val label: FormattedCharSequence = message.visualOrderText
        val labelWidth = font.width(label)
        val inner = width - 2 * LABEL_PAD
        val baseline = y + (height - 8) / 2

        if (labelWidth <= inner) {
            guiGraphics.drawString(font, label, (x + width / 2) - labelWidth / 2, baseline, textColor, false)
            return
        }

        // A label with more name than button: clipped to the frame and walked slowly from end to end, so
        // the whole of it can be read without any of it being drawn outside the button. A ship's plans are
        // named by their captain, and captains do not name ships to fit a dropdown -- the old draw simply
        // centred whatever it was given and let long names spill across the panel.
        guiGraphics.enableScissor(x + LABEL_PAD, y + 1, x + width - LABEL_PAD, y + height - 1)
        guiGraphics.drawString(font, label, x + LABEL_PAD - Marquee.offset(labelWidth - inner), baseline, textColor, false)
        guiGraphics.disableScissor()
    }

    override fun onClick(mouseButtonEvent: MouseButtonEvent, bl: Boolean) {
        isPressed = true
        super.onClick(mouseButtonEvent, bl)
    }

    override fun onRelease(mouseButtonEvent: MouseButtonEvent) {
        isPressed = false
    }

    companion object {
        private const val BORDER = 0xFF404040.toInt()
        private const val BG = 0xFFC6C6C6.toInt()
        private const val BG_HOVER = 0xFFE0E0E0.toInt()
        private const val BG_PRESSED = 0xFFA8A8A8.toInt()
        private const val TEXT = 0xFF404040.toInt()
        private const val DIM_BORDER = 0xFF6A6A6A.toInt()
        private const val DIM_BG = 0xFF9A9A9A.toInt()
        private const val DIM_TEXT = 0xFF808080.toInt()

        /** Breathing room either side of a label, and the strip an over-long one is clipped to. */
        private const val LABEL_PAD = 3
    }
}
