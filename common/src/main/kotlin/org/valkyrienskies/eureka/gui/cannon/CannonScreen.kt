package org.valkyrienskies.eureka.gui.cannon

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * The magazine, drawn in code rather than from an atlas.
 *
 * The Shipwright's menu established that here and it earns its keep the same way: no art to author, no
 * texture to keep in step with the layout, and no repeat of the engine atlas's implicit-256 blit trap. The
 * palette is deliberately the Shipwright's so the mod's own screens look like one another.
 */
class CannonScreen(handler: CannonScreenMenu, playerInventory: Inventory, title: Component) :
    AbstractContainerScreen<CannonScreenMenu>(handler, playerInventory, title) {

    init {
        imageWidth = 176
        imageHeight = 166
        inventoryLabelY = imageHeight - 94
    }

    override fun renderBg(guiGraphics: GuiGraphics, partialTicks: Float, mouseX: Int, mouseY: Int) {
        val left = (width - imageWidth) / 2
        val top = (height - imageHeight) / 2

        guiGraphics.fill(left, top, left + imageWidth, top + imageHeight, PANEL_BORDER)
        guiGraphics.fill(left + 1, top + 1, left + imageWidth - 1, top + imageHeight - 1, PANEL_BG)

        // Sits between the title and the slot labels. It was at y=28 and drew straight through them --
        // the labels start at SLOT_Y - 12 = 23 and the font is 9 tall, so anything from 23 to 32 is
        // inside the text, not under it.
        guiGraphics.fill(left + 7, top + 19, left + imageWidth - 7, top + 20, ACCENT)

        well(guiGraphics, left + CannonScreenMenu.POWDER_X, top + CannonScreenMenu.SLOT_Y)
        well(guiGraphics, left + CannonScreenMenu.SHOT_X, top + CannonScreenMenu.SLOT_Y)

        for (row in 0 until 3) {
            for (col in 0 until 9) {
                well(guiGraphics, left + 8 + col * 18, top + 84 + row * 18)
            }
        }
        for (col in 0 until 9) {
            well(guiGraphics, left + 8 + col * 18, top + 142)
        }
    }

    override fun renderLabels(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        super.renderLabels(guiGraphics, mouseX, mouseY)

        // Which slot is which, said once, so an empty magazine is not a guessing game.
        centered(guiGraphics, POWDER_LABEL, CannonScreenMenu.POWDER_X + 8, CannonScreenMenu.SLOT_Y - 12, DIM)
        centered(guiGraphics, SHOT_LABEL, CannonScreenMenu.SHOT_X + 8, CannonScreenMenu.SLOT_Y - 12, DIM)

        val status = if (menu.loaded) LOADED else UNLOADED
        centered(guiGraphics, status, 88, CannonScreenMenu.SLOT_Y + 22, if (menu.loaded) READY else DIM)
    }

    private fun centered(guiGraphics: GuiGraphics, text: Component, centreX: Int, y: Int, color: Int) {
        guiGraphics.drawString(font, text, centreX - font.width(text) / 2, y, color, false)
    }

    /** A slot's recess: dark edge on the top and left, so the well reads as sunken rather than painted on. */
    private fun well(guiGraphics: GuiGraphics, x: Int, y: Int) {
        guiGraphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_SHADOW)
        guiGraphics.fill(x, y, x + 17, y + 17, SLOT_BG)
    }

    companion object {
        private val POWDER_LABEL: Component = Component.translatable("gui.vs_eureka.cannon_powder")
        private val SHOT_LABEL: Component = Component.translatable("gui.vs_eureka.cannon_shot")
        private val LOADED: Component =
            Component.translatable("gui.vs_eureka.cannon_loaded").withStyle(ChatFormatting.BOLD)
        private val UNLOADED: Component = Component.translatable("gui.vs_eureka.cannon_unloaded")

        private const val PANEL_BORDER = 0xFF000000.toInt()
        private const val PANEL_BG = 0xFFC6C6C6.toInt()
        private const val SLOT_SHADOW = 0xFF373737.toInt()
        private const val SLOT_BG = 0xFF8B8B8B.toInt()
        private const val ACCENT = 0xFF2A8FA6.toInt()
        private const val READY = 0xFF2E8B45.toInt()
        private const val DIM = 0xFF555555.toInt()
    }
}
