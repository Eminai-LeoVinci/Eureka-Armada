package org.valkyrienskies.eureka.gui.shiphelm

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

/**
 * A small checkbox for the ship-helm menu. Mirrors [ShipHelmButton]'s renderWidget override (1.21.1 custom
 * widgets draw in renderWidget). The box always reflects [state], so it stays in sync with the server-synced
 * keep-active flag / the client display-speed config; clicking fires the [Button] OnPress (which sends the
 * menu-button packet or toggles the client config).
 */
class ShipHelmCheckbox(
    x: Int,
    y: Int,
    width: Int,
    text: Component,
    private val font: Font,
    private val state: () -> Boolean,
    onPress: OnPress
) : Button(x, y, width, BOX, text, onPress, DEFAULT_NARRATION) {

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        // When inactive (e.g. a HUD sub-toggle while the master is off) the checkbox renders greyed and its
        // OnPress is already blocked by Button.active -- but the box still shows its current state() so the
        // user can see what it's set to.
        val on = active
        val border = if (on) BORDER else DIM_BORDER
        val fill = if (on) (if (isHovered) BG_HOVER else BG) else DIM_BG
        val mark = if (on) MARK else DIM_MARK
        val textColor = if (on) TEXT else DIM_TEXT

        // Box: border, light fill (brighter on hover), filled centre when checked.
        guiGraphics.fill(x, y, x + BOX, y + BOX, border)
        guiGraphics.fill(x + 1, y + 1, x + BOX - 1, y + BOX - 1, fill)
        if (state()) guiGraphics.fill(x + 3, y + 3, x + BOX - 3, y + BOX - 3, mark)

        // Label to the right of the box, drawn at a reduced scale for a smaller, subtler readout. The pose is
        // a 3D PoseStack in 1.21.1, so push/scale(s,s,1f) and divide screen coords by the scale to keep position.
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.scale(LABEL_SCALE, LABEL_SCALE, 1f)
        val tx = (x + BOX + GAP) / LABEL_SCALE
        val ty = (y + (BOX - font.lineHeight * LABEL_SCALE) / 2f) / LABEL_SCALE
        guiGraphics.drawString(font, message, Math.round(tx), Math.round(ty), textColor, false)
        pose.popPose()
    }

    companion object {
        const val BOX = 9 // compact so six rows (master + 3 subs + 2 toggles) fit above the buttons
        const val GAP = 4 // pixels between the box and the label
        const val LABEL_SCALE = 0.85f // labels render slightly smaller than the default font
        private const val BORDER = 0xFF404040.toInt()
        private const val BG = 0xFFC6C6C6.toInt()
        private const val BG_HOVER = 0xFFE0E0E0.toInt()
        private const val MARK = 0xFF404040.toInt()
        private const val TEXT = 0xFF404040.toInt()
        // Greyed-out palette for inactive checkboxes.
        private const val DIM_BORDER = 0xFF6A6A6A.toInt()
        private const val DIM_BG = 0xFF9A9A9A.toInt()
        private const val DIM_MARK = 0xFF6A6A6A.toInt()
        private const val DIM_TEXT = 0xFF808080.toInt()
    }
}
