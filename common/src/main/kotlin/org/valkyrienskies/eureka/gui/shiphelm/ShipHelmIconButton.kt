package org.valkyrienskies.eureka.gui.shiphelm

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

/**
 * A small, fill-drawn button for the ship-helm menu -- no texture-atlas region, mirroring [ShipHelmCheckbox]'s
 * draw technique. Used for the compact "Rename" control in the top-right of the helm panel. Its label is drawn
 * at a reduced scale so a short word fits in a small footprint. Like the other helm widgets it overrides
 * renderContents (1.21.11 made AbstractButton.renderWidget final).
 */
class ShipHelmIconButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    text: Component,
    private val font: Font,
    onPress: OnPress
) : Button(x, y, width, height, text, onPress, DEFAULT_NARRATION) {

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        // Greyed when inactive, like every other widget in this menu. It drew its live colours regardless
        // until the wheel gained modes where an icon button is DISABLED rather than absent -- Summon at a
        // wheel held in the hand -- and a button that looks pressable and is not reads as a broken one.
        guiGraphics.fill(x, y, x + width, y + height, if (active) BORDER else DIM_BORDER)
        guiGraphics.fill(
            x + 1, y + 1, x + width - 1, y + height - 1,
            if (!active) DIM_BG else if (isHovered) BG_HOVER else BG
        )

        // Centred label at a reduced scale; pose is a 2D Matrix3x2fStack in 1.21.11 so divide coords by scale.
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.scale(LABEL_SCALE, LABEL_SCALE, 1f)
        val labelW = font.width(message) * LABEL_SCALE
        val tx = (x + (width - labelW) / 2f) / LABEL_SCALE
        val ty = (y + (height - font.lineHeight * LABEL_SCALE) / 2f) / LABEL_SCALE
        guiGraphics.drawString(font, message, Math.round(tx), Math.round(ty), if (active) TEXT else DIM_TEXT, false)
        pose.popPose()
    }

    companion object {
        private const val LABEL_SCALE = 0.65f
        private const val BORDER = 0xFF404040.toInt()
        private const val BG = 0xFFC6C6C6.toInt()
        private const val BG_HOVER = 0xFFE0E0E0.toInt()
        private const val TEXT = 0xFF404040.toInt()
        private const val DIM_BORDER = 0xFF5A5A5A.toInt()
        private const val DIM_BG = 0xFF8B8B8B.toInt()
        private const val DIM_TEXT = 0xFF6E6E6E.toInt()
    }
}
