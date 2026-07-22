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

        val fill = when {
            !active -> DIM_BG
            isPressed -> BG_PRESSED
            isHovered -> BG_HOVER
            else -> BG
        }
        val border = if (active) BORDER else DIM_BORDER
        val textColor = if (active) TEXT else DIM_TEXT

        guiGraphics.fill(x, y, x + width, y + height, border)
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, fill)

        val formattedCharSequence: FormattedCharSequence = message.visualOrderText
        guiGraphics.drawString(
            font,
            formattedCharSequence,
            ((x + width / 2) - font.width(formattedCharSequence) / 2),
            (y + (height - 8) / 2),
            textColor,
            false
        )
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
    }
}
