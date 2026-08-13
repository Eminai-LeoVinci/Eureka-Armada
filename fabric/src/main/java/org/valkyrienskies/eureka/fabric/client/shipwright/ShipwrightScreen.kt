package org.valkyrienskies.eureka.fabric.client.shipwright

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.eureka.fabric.ShipwrightNetworkingFabric
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmButton
import org.valkyrienskies.eureka.shipwright.ShipwrightMenu

/**
 * The shipwright's book: every set of plans a captain owns, and what is still owed on each.
 *
 * Two views in one panel. The **shelf** lists ships with a progress bar apiece; clicking one opens its **card**,
 * which is the blueprint page again -- dimensions, weight, speed, and the full material list -- plus the
 * buttons that spend it. Back returns to the shelf.
 *
 * ## A snapshot, and the server's reply is the refresh
 * Same arrangement as `CrewManifestScreen`. The screen never polls: it draws what arrived, and every button
 * sends an action whose answer is a fresh shelf. So the moment materials are handed over, the bar that moved is
 * the one the server moved.
 *
 * ## Rows are drawn, not built
 * Only the four buttons on the card are real widgets. A scrolling list of them would be rebuilt on every scroll
 * tick and positioned against a clip rectangle it may fall outside of; one rectangle test in [mouseClicked] is
 * less code and cannot drift out of step with what was painted.
 */
@Environment(EnvType.CLIENT)
class ShipwrightScreen private constructor(private var shelf: ShipwrightMenu.Shelf) : Screen(TITLE) {

    private var left = 0
    private var top = 0
    private var scroll = 0

    /** Which card is open, held by ship NAME so it survives a snapshot arriving underneath it. */
    private var openCard: String? = null

    private val card: ShipwrightMenu.Row?
        get() = openCard?.let { name -> shelf.rows.firstOrNull { it.shipName == name } }

    override fun init() {
        left = (width - PANEL_W) / 2
        top = (height - PANEL_H) / 2
        clampScroll()

        val row = card ?: return

        // Build and Bottle only exist once a hull is paid for -- a button that is present but refuses is worse
        // than one that is not there, because the player has to press it to learn anything.
        var x = left + 8
        if (row.ready) {
            addRenderableWidget(
                ShipHelmButton(x, top + PANEL_H - 22, BTN_W, BTN_H, BUILD_TEXT, font) { act(ShipwrightMenu.Action.BUILD) }
            )
            x += BTN_W + 4

            // Greyed rather than hidden when there is no bottle: the option exists, and the reason it cannot be
            // taken is worth saying. The tooltip says it.
            addRenderableWidget(
                ShipHelmButton(x, top + PANEL_H - 22, BTN_W, BTN_H, BOTTLE_TEXT, font) {
                    act(ShipwrightMenu.Action.BOTTLE)
                }.also { it.active = shelf.hasFreeBottle }
            )
            x += BTN_W + 4
        } else {
            addRenderableWidget(
                ShipHelmButton(x, top + PANEL_H - 22, BTN_W, BTN_H, PAY_TEXT, font) { act(ShipwrightMenu.Action.PAY) }
            )
            x += BTN_W + 4
        }

        addRenderableWidget(
            ShipHelmButton(x, top + PANEL_H - 22, BTN_W, BTN_H, DELETE_TEXT, font) {
                act(ShipwrightMenu.Action.DELETE)
                openCard = null
            }
        )

        addRenderableWidget(
            ShipHelmButton(
                left + PANEL_W - 8 - BACK_W, top + PANEL_H - 22, BACK_W, BTN_H, BACK_TEXT, font
            ) { openCard = null; rebuild() }
        )
    }

    override fun isPauseScreen(): Boolean = false

    private fun act(action: ShipwrightMenu.Action) {
        val name = openCard ?: return
        ShipwrightNetworkingFabric.send(shelf.villager, action, name)
    }

    private fun rebuild() {
        clearWidgets()
        init()
    }

    /** A fresh shelf from the server. Keeps the open card if that ship is still on file. */
    private fun refresh(next: ShipwrightMenu.Shelf) {
        shelf = next
        if (openCard != null && next.rows.none { it.shipName == openCard }) openCard = null
        clampScroll()
        rebuild()
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        // NOT renderBackground: 1.21.11 calls it for us ahead of this, and calling it again draws the dim twice.
        panel(guiGraphics, left, top, PANEL_W, PANEL_H)

        val row = card
        if (row == null) drawShelf(guiGraphics, mouseX, mouseY) else drawCard(guiGraphics, row)

        super.render(guiGraphics, mouseX, mouseY, partialTicks)

        if (row != null && row.ready && !shelf.hasFreeBottle) {
            // Only said where it matters: hovering the greyed button.
            val bx = left + 8 + BTN_W + 4
            if (mouseX in bx..(bx + BTN_W) && mouseY in (top + PANEL_H - 22)..(top + PANEL_H - 22 + BTN_H)) {
                guiGraphics.setTooltipForNextFrame(font, NO_BOTTLE_TEXT, mouseX, mouseY)
            }
        }
    }

    // ---- The shelf ----------------------------------------------------------------------------------------

    private fun drawShelf(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        guiGraphics.drawString(font, TITLE, left + 8, top + 6, ACCENT, false)
        val count = Component.literal("${shelf.rows.size} / ${shelf.slots}")
        guiGraphics.drawString(font, count, left + PANEL_W - 8 - font.width(count), top + 6, DIM, false)
        guiGraphics.fill(left + 4, top + LIST_TOP - 3, left + PANEL_W - 4, top + LIST_TOP - 2, ACCENT)

        if (shelf.rows.isEmpty()) {
            small(guiGraphics, EMPTY_TEXT, left + 8, top + LIST_TOP + 8, DIM)
            return
        }

        guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + LIST_BOTTOM)
        for (i in 0 until VISIBLE_ROWS) {
            val index = scroll + i
            if (index >= shelf.rows.size) break
            drawShelfRow(guiGraphics, shelf.rows[index], top + LIST_TOP + i * ROW_H, mouseX, mouseY)
        }
        guiGraphics.disableScissor()
        drawScrollbar(guiGraphics, shelf.rows.size, VISIBLE_ROWS, LIST_BOTTOM)
    }

    private fun drawShelfRow(
        guiGraphics: GuiGraphics,
        row: ShipwrightMenu.Row,
        y: Int,
        mouseX: Int,
        mouseY: Int
    ) {
        val hovered = mouseX >= left + 4 && mouseX <= left + PANEL_W - 8 &&
            mouseY >= y && mouseY < y + ROW_H && mouseY >= top + LIST_TOP && mouseY < top + LIST_BOTTOM
        if (hovered) guiGraphics.fill(left + 4, y, left + PANEL_W - 8, y + ROW_H - 1, ROW_HOVER)

        guiGraphics.drawString(font, Component.literal(row.shipName), left + 10, y + 4, TEXT, false)

        // The bar is the whole reason the shelf exists: how close is each of these to being a ship.
        val barX = left + PANEL_W - 12 - BAR_W
        val barY = y + 6
        guiGraphics.fill(barX, barY, barX + BAR_W, barY + BAR_H, ROW_LOCKED)
        guiGraphics.fill(barX, barY, barX + (BAR_W * row.progress).toInt(), barY + BAR_H, if (row.ready) READY else ACCENT)

        val label = if (row.ready) READY_TEXT else Component.literal("${(row.progress * 100).toInt()}%")
        small(guiGraphics, label, barX - 6 - (font.width(label) * SMALL).toInt(), y + 7, if (row.ready) READY else DIM)
    }

    /** Shared by both views: without one on the card, a bill of nine kinds hides three with no hint. */
    private fun drawScrollbar(guiGraphics: GuiGraphics, count: Int, visible: Int, bottom: Int) {
        if (count <= visible) return
        val trackTop = top + LIST_TOP
        val trackH = bottom - LIST_TOP
        val x = left + PANEL_W - 7
        guiGraphics.fill(x, trackTop, x + 3, trackTop + trackH, ROW_LOCKED)

        val thumbH = maxOf(12, trackH * visible / count)
        val span = count - visible
        val thumbY = trackTop + if (span <= 0) 0 else (trackH - thumbH) * scroll / span
        guiGraphics.fill(x, thumbY, x + 3, thumbY + thumbH, ACCENT)
    }

    // ---- One ship's card ----------------------------------------------------------------------------------

    private fun drawCard(guiGraphics: GuiGraphics, row: ShipwrightMenu.Row) {
        guiGraphics.drawString(font, Component.literal(row.shipName), left + 8, top + 6, ACCENT, false)
        val size = Component.literal("${row.width} x ${row.height} x ${row.length}")
        guiGraphics.drawString(font, size, left + PANEL_W - 8 - font.width(size), top + 6, DIM, false)

        small(
            guiGraphics,
            Component.literal("${fmt(row.blocks)} blocks  -  ${fmt(row.items)} items  -  ${row.materials.size} kinds"),
            left + 8, top + 20, TEXT
        )
        small(
            guiGraphics,
            Component.literal("${vessel(row)}  -  ${fmtKg(row.mass)}  -  top speed ${"%.1f".format(row.topSpeed)} m/s~"),
            left + 8, top + 30, DIM
        )
        small(
            guiGraphics,
            Component.literal("${fmt(row.given)} of ${fmt(row.items)} delivered  -  ${(row.progress * 100).toInt()}%"),
            left + 8, top + 40, if (row.ready) READY else ACCENT
        )
        guiGraphics.fill(left + 4, top + LIST_TOP - 3, left + PANEL_W - 4, top + LIST_TOP - 2, ACCENT)

        val shown = cardRows(row)
        val settled = row.materials.none { it.outstanding > 0 }

        guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + CARD_BOTTOM)
        for (i in 0 until CARD_ROWS) {
            val index = scroll + i
            if (index >= shown.size) break
            drawMaterial(guiGraphics, shown[index], top + LIST_TOP + i * ROW_H, settled)
        }
        guiGraphics.disableScissor()
        drawScrollbar(guiGraphics, shown.size, CARD_ROWS, CARD_BOTTOM)
    }

    /**
     * The material rows a card shows: what is still owed, or the whole bill once nothing is.
     *
     * Outstanding-first is the useful order while a ship is being paid for -- the list answers "what do I go
     * and fetch" -- and a finished ship has no outstanding rows at all, so it falls back to showing everything
     * rather than an empty panel.
     */
    private fun cardRows(row: ShipwrightMenu.Row): List<ShipwrightMenu.Material> {
        val outstanding = row.materials.filter { it.outstanding > 0 }
        return outstanding.ifEmpty { row.materials }
    }

    private fun drawMaterial(
        guiGraphics: GuiGraphics,
        material: ShipwrightMenu.Material,
        y: Int,
        settled: Boolean
    ) {
        val stack = ItemStack(material.item)
        guiGraphics.renderItem(stack, left + 8, y + 1)
        guiGraphics.drawString(font, stack.hoverName, left + 30, y + 5, TEXT, false)

        // "given / needed" rather than a bare remainder: a player deciding what to go and fetch wants to know
        // how far in they already are, not only what is missing.
        val tally = Component.literal("${fmt(material.given)} / ${fmt(material.needed)}")
        guiGraphics.drawString(
            font, tally, left + PANEL_W - 12 - font.width(tally), y + 5,
            if (settled || material.outstanding <= 0) READY else ACCENT, false
        )
    }

    // ---- Input --------------------------------------------------------------------------------------------

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(mouseButtonEvent, doubled)) return true
        if (openCard != null) return false

        val mouseX = mouseButtonEvent.x().toInt()
        val mouseY = mouseButtonEvent.y().toInt()
        if (mouseX < left + 4 || mouseX > left + PANEL_W - 8) return false
        if (mouseY < top + LIST_TOP || mouseY >= top + LIST_BOTTOM) return false

        val index = scroll + (mouseY - (top + LIST_TOP)) / ROW_H
        val row = shelf.rows.getOrNull(index) ?: return false
        openCard = row.shipName
        scroll = 0
        rebuild()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll -= scrollY.toInt()
        clampScroll()
        return true
    }

    private fun clampScroll() {
        val open = card
        // Counted off the SAME list the card paints, not the full bill -- the card usually shows only what is
        // still owed, and clamping against the longer list would scroll past the end into blank rows.
        val rows = if (open != null) cardRows(open).size else shelf.rows.size
        val visible = if (open != null) CARD_ROWS else VISIBLE_ROWS
        scroll = scroll.coerceIn(0, maxOf(0, rows - visible))
    }

    // ---- Painting helpers ---------------------------------------------------------------------------------

    private fun panel(guiGraphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        guiGraphics.fill(x, y, x + w, y + h, PANEL_BORDER)
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL_BG)
    }

    private fun small(guiGraphics: GuiGraphics, text: Component, x: Int, y: Int, color: Int) {
        val pose = guiGraphics.pose()
        pose.pushMatrix()
        pose.scale(SMALL, SMALL)
        guiGraphics.drawString(font, text, Math.round(x / SMALL), Math.round(y / SMALL), color, false)
        pose.popMatrix()
    }

    private fun vessel(row: ShipwrightMenu.Row): String = when (row.profile) {
        "AIRSHIP" -> "Airship"
        "SUBMARINE" -> "Submarine"
        else -> "Boat"
    }

    companion object {

        /** Open a shelf, or fold a fresh one into the shelf already on screen. */
        fun open(shelf: ShipwrightMenu.Shelf) {
            val mc = Minecraft.getInstance()
            val current = mc.screen
            if (current is ShipwrightScreen) current.refresh(shelf) else mc.setScreen(ShipwrightScreen(shelf))
        }

        private fun fmt(v: Int) = String.format("%,d", v)
        private fun fmtKg(v: Double) = String.format("%,.0f kg", v)

        private val TITLE: Component = Component.translatable("gui.vs_eureka.shipwright")
        private val EMPTY_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_empty")
        private val READY_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_ready")
        private val PAY_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_pay")
        private val BUILD_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_build")
        private val BOTTLE_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_bottle")
        private val DELETE_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_delete")
        private val BACK_TEXT: Component = Component.translatable("gui.vs_eureka.crew_back")
        private val NO_BOTTLE_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_no_bottle")

        private const val PANEL_W = 250
        private const val PANEL_H = 214
        private const val LIST_TOP = 53
        private const val ROW_H = 18
        private const val VISIBLE_ROWS = 8
        private const val LIST_BOTTOM = LIST_TOP + ROW_H * VISIBLE_ROWS
        private const val CARD_ROWS = 6
        private const val CARD_BOTTOM = LIST_TOP + ROW_H * CARD_ROWS

        private const val BAR_W = 70
        private const val BAR_H = 6
        private const val BTN_W = 56
        private const val BACK_W = 40
        private const val BTN_H = 16
        private const val SMALL = 0.7f

        private const val PANEL_BORDER = 0xFF000000.toInt()
        private const val PANEL_BG = 0xFFC6C6C6.toInt()
        private const val ROW_HOVER = 0xFFD8D8D8.toInt()
        private const val ROW_LOCKED = 0xFFA0A0A0.toInt()
        private const val TEXT = 0xFF404040.toInt()
        private const val DIM = 0xFF7A7A7A.toInt()
        private const val ACCENT = 0xFF2A8FA6.toInt()
        private const val READY = 0xFF2E8B45.toInt()
    }
}
