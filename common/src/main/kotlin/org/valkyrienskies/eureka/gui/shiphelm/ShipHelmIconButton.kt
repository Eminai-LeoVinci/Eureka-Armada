package org.valkyrienskies.eureka.gui.shiphelm

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

/**
 * A small, fill-drawn button for the ship-helm menu -- no texture-atlas region, mirroring [ShipHelmCheckbox]'s
 * draw technique. Used for the compact "Rename" control in the top-right of the helm panel. Its label is drawn
 * at a reduced scale so a short word fits in a small footprint. Like the other helm widgets it overrides
 * renderWidget (1.21.1 custom widgets draw in renderWidget).
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
        guiGraphics.fill(x, y, x + width, y + height, BORDER)
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, if (isHovered) BG_HOVER else BG)

        // Centred label at a reduced scale; pose is a 3D PoseStack in 1.21.1 so scale(s,s,1f) and divide coords by scale.
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.scale(LABEL_SCALE, LABEL_SCALE, 1f)
        val labelW = font.width(message) * LABEL_SCALE
        val tx = (x + (width - labelW) / 2f) / LABEL_SCALE
        val ty = (y + (height - font.lineHeight * LABEL_SCALE) / 2f) / LABEL_SCALE
        guiGraphics.drawString(font, message, Math.round(tx), Math.round(ty), TEXT, false)
        pose.popPose()
    }

    companion object {
        private const val LABEL_SCALE = 0.65f
        private const val BORDER = 0xFF404040.toInt()
        private const val BG = 0xFFC6C6C6.toInt()
        private const val BG_HOVER = 0xFFE0E0E0.toInt()
        private const val TEXT = 0xFF404040.toInt()
    }
}
