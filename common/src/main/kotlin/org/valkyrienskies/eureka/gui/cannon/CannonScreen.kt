package org.valkyrienskies.eureka.gui.cannon

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.cannon.GunLabels

/**
 * The magazine, drawn in code rather than from an atlas.
 *
 * The Shipwright's menu established that here and it earns its keep the same way: no art to author, no
 * texture to keep in step with the layout, and no repeat of the engine atlas's implicit-256 blit trap. The
 * palette is deliberately the Shipwright's so the mod's own screens look like one another.
 *
 * ## The layout is boxed in from below
 * [org.valkyrienskies.eureka.util.inventorySlots] puts the player's inventory at a fixed y=84, and that
 * helper is shared with the engine and helm menus -- so growing the panel to make room would leave a gap
 * rather than move anything. The magazine therefore has y=20..82 to work in, and a column of three powder
 * wells is most of it. The per-slot text labels went to pay for it: a slot that only accepts gunpowder,
 * drawn in a column beside a slot that only accepts shot, does not need to be captioned.
 */
class CannonScreen(handler: CannonScreenMenu, playerInventory: Inventory, title: Component) :
    AbstractContainerScreen<CannonScreenMenu>(handler, playerInventory, title) {

    private var chargeButton: Button? = null

    init {
        imageWidth = 176
        imageHeight = 166
        inventoryLabelY = imageHeight - 94
    }

    override fun init() {
        super.init()

        // The breech's powder measure. Server-side the menu owns the change (see
        // CannonScreenMenu.clickMenuButton); this only asks for it, so a second player watching the same gun
        // sees the same setting.
        chargeButton = addRenderableWidget(
            Button.builder(chargeLabel()) {
                minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, CannonScreenMenu.BUTTON_CHARGE)
            }
                .bounds(leftPos + BUTTON_X, topPos + BUTTON_Y, BUTTON_W, BUTTON_H)
                .tooltip(Tooltip.create(Component.translatable("gui.vs_eureka.cannon_charge.tip")))
                .build()
        )
    }

    /**
     * The label is rebuilt every frame rather than only when the button is pressed, because the value can
     * change without this client touching it -- another player at the same gun, or the server refusing a
     * click. The synced container data is the truth; the button just shows it.
     */
    override fun containerTick() {
        super.containerTick()
        chargeButton?.message = chargeLabel()
    }

    private fun chargeLabel(): Component =
        Component.translatable("gui.vs_eureka.cannon_charge", menu.powderCharge.powder)

    override fun renderBg(guiGraphics: GuiGraphics, partialTicks: Float, mouseX: Int, mouseY: Int) {
        val left = (width - imageWidth) / 2
        val top = (height - imageHeight) / 2

        guiGraphics.fill(left, top, left + imageWidth, top + imageHeight, PANEL_BORDER)
        guiGraphics.fill(left + 1, top + 1, left + imageWidth - 1, top + imageHeight - 1, PANEL_BG)

        // Sits between the title and the magazine. It was at y=28 and drew straight through the old slot
        // labels -- anything from 23 to 32 was inside that text rather than under it.
        guiGraphics.fill(left + 7, top + 19, left + imageWidth - 7, top + 20, ACCENT)

        for (slot in CannonBlockEntity.POWDER_A..CannonBlockEntity.POWDER_C) {
            well(guiGraphics, left + CannonScreenMenu.POWDER_X, top + CannonScreenMenu.powderSlotY(slot))
        }
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

    /**
     * Deliberately does NOT call super, which would draw the "Inventory" caption at y=72 -- straight through
     * the bottom powder well, which runs 60..78. Something had to give between the two and the well is load
     * bearing; every other inventory in the game is captioned, so an uncaptioned one is not a puzzle.
     *
     * The powder column loses its caption for the same reason -- the divider sits at y=19 and the top well
     * starts at y=24, so there is no line to put it on. Three identical wells that accept only gunpowder,
     * beside a button reading "1x", are not ambiguous. The shot slot keeps its label because it has the room.
     */
    override fun renderLabels(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        guiGraphics.drawString(font, title, titleLabelX, titleLabelY, TITLE, false)

        // Which gun this is, by its bow-relative name -- upper right, quiet. Absent for a gun on the ground
        // or on a ship whose wheel holds no articles, which is exactly when it has no name to show.
        GunLabels.decode(menu.gunLabelCode)?.let { label ->
            guiGraphics.drawString(
                font, label, imageWidth - 8 - font.width(label), titleLabelY, DIM, false
            )
        }

        centered(guiGraphics, SHOT_LABEL, CannonScreenMenu.SHOT_X + 8, CannonScreenMenu.SLOT_Y - 12, DIM)

        val status = if (menu.loaded) LOADED else UNLOADED
        centered(guiGraphics, status, BUTTON_X + BUTTON_W / 2, BUTTON_Y + BUTTON_H + 5, if (menu.loaded) READY else DIM)
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
        private val SHOT_LABEL: Component = Component.translatable("gui.vs_eureka.cannon_shot")
        private val LOADED: Component =
            Component.translatable("gui.vs_eureka.cannon_loaded").withStyle(ChatFormatting.BOLD)
        private val UNLOADED: Component = Component.translatable("gui.vs_eureka.cannon_unloaded")

        // Wide enough that the label never has to scroll. A vanilla Button insets its text 2px each side and
        // scrolls whatever is left over, so the usable width is BUTTON_W - 4; "1x Charge" measures 52 in the
        // default font, which overran a 52-wide button by exactly the 4 it lost to the insets. 60 leaves 56
        // usable and a few pixels of headroom for a wider glyph set, and there is room to grow it further --
        // the shot slot ends at x=97 and the panel's inner edge is at 168.
        private const val BUTTON_X = 104
        private const val BUTTON_Y = 36
        private const val BUTTON_W = 60
        private const val BUTTON_H = 20

        private const val PANEL_BORDER = 0xFF000000.toInt()
        private const val PANEL_BG = 0xFFC6C6C6.toInt()
        private const val SLOT_SHADOW = 0xFF373737.toInt()
        private const val SLOT_BG = 0xFF8B8B8B.toInt()
        private const val ACCENT = 0xFF2A8FA6.toInt()
        private const val READY = 0xFF2E8B45.toInt()
        private const val DIM = 0xFF555555.toInt()
        private const val TITLE = 0xFF404040.toInt()
    }
}
