package org.valkyrienskies.eureka.fabric.client.blueprint

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.eureka.blueprint.Blueprint
import org.valkyrienskies.eureka.util.BuoyancyMath

/**
 * A blueprint, read.
 *
 * ## The material list is the page
 * Everything else here is context. The list is what a player carries to a shipwright, and it is the one thing
 * they will come back to this screen for over and over, so it gets the space and the scrollbar and everything
 * else is squeezed into a header above it.
 *
 * ## No server, ever
 * The page arrives whole in the item's own component, so this screen opens instantly from an inventory slot
 * and never asks anything of anyone. That is also why there is no refresh: a template is immutable once
 * written, so what is on screen cannot go out of date while it is being looked at.
 *
 * ## Rows are drawn, not built
 * Same reasoning as `CrewManifestScreen`: a scrolling list of widgets gets rebuilt on every scroll tick and
 * has to be positioned against a clip rectangle it may fall outside of. Painting the rows and hit-testing one
 * rectangle is less code and cannot drift out of step with what was drawn.
 */
@Environment(EnvType.CLIENT)
class BlueprintScreen private constructor(private val page: Blueprint.Page) : Screen(TITLE) {

    private var left = 0
    private var top = 0
    private var scroll = 0

    /** Rebuilt once per open rather than per frame -- the census never changes under us. */
    private val rows: List<Row> = page.census.map { (item, count) -> Row(ItemStack(item), count) }

    private class Row(val stack: ItemStack, val count: Int)

    override fun init() {
        left = (width - PANEL_W) / 2
        top = (height - PANEL_H) / 2
        clampScroll()
    }

    override fun isPauseScreen(): Boolean = false

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        // NOT renderBackground: 1.21.11 calls it for us ahead of this, and calling it again draws the dim twice.
        panel(guiGraphics, left, top, PANEL_W, PANEL_H)
        drawHeader(guiGraphics)
        drawList(guiGraphics, mouseX, mouseY)
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
    }

    private fun drawHeader(guiGraphics: GuiGraphics) {
        guiGraphics.drawString(font, Component.literal(page.shipName), left + 8, top + 6, ACCENT, false)

        val size = Component.literal("${page.width} x ${page.height} x ${page.length}")
        guiGraphics.drawString(font, size, left + PANEL_W - 8 - font.width(size), top + 6, DIM, false)

        // Three lines rather than one long one: a player scanning for "how much iron" should not have to read
        // past the tonnage to get to the list.
        small(
            guiGraphics,
            Component.literal("${fmt(page.blocks)} blocks  -  ${fmt(page.items)} items  -  ${page.kinds} kinds"),
            left + 8, top + 20, TEXT
        )
        small(
            guiGraphics,
            Component.literal(
                "${fmtKg(page.mass)}  -  ${BuoyancyMath.recommendedFloaters(page.mass)} floaters" +
                    "  -  ${BuoyancyMath.recommendedBalloons(page.mass)} balloons"
            ),
            left + 8, top + 30, DIM
        )
        // The tilde is the helm's own, and means the same thing here: drag leaves the realized speed a little
        // under the figure the controller targets.
        small(
            guiGraphics,
            Component.literal("${vessel()}  -  top speed ${"%.1f".format(page.topSpeed)} m/s~"),
            left + 8, top + 40, DIM
        )

        guiGraphics.fill(left + 4, top + LIST_TOP - 3, left + PANEL_W - 4, top + LIST_TOP - 2, ACCENT)
    }

    private fun vessel(): String = when (page.profile) {
        "AIRSHIP" -> "Airship"
        "SUBMARINE" -> "Submarine"
        else -> "Boat"
    }

    private fun drawList(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        if (rows.isEmpty()) {
            small(guiGraphics, EMPTY_TEXT, left + 8, top + LIST_TOP + 8, DIM)
            return
        }

        guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + LIST_BOTTOM)
        for (i in 0 until VISIBLE_ROWS) {
            val index = scroll + i
            if (index >= rows.size) break
            drawRow(guiGraphics, rows[index], top + LIST_TOP + i * ROW_H, mouseX, mouseY)
        }
        guiGraphics.disableScissor()
        drawScrollbar(guiGraphics)
    }

    private fun drawRow(guiGraphics: GuiGraphics, row: Row, y: Int, mouseX: Int, mouseY: Int) {
        val hovered = mouseX >= left + 4 && mouseX <= left + PANEL_W - 8 &&
            mouseY >= y && mouseY < y + ROW_H && mouseY >= top + LIST_TOP && mouseY < top + LIST_BOTTOM
        if (hovered) guiGraphics.fill(left + 4, y, left + PANEL_W - 8, y + ROW_H - 1, ROW_HOVER)

        guiGraphics.renderItem(row.stack, left + 8, y + 1)
        guiGraphics.drawString(font, row.stack.hoverName, left + 30, y + 5, TEXT, false)

        // Right-aligned so the numbers form a column the eye can run down, which is how a shopping list is read.
        val count = Component.literal(fmt(row.count))
        guiGraphics.drawString(font, count, left + PANEL_W - 12 - font.width(count), y + 5, ACCENT, false)
    }

    private fun drawScrollbar(guiGraphics: GuiGraphics) {
        if (rows.size <= VISIBLE_ROWS) return
        val trackTop = top + LIST_TOP
        val trackH = LIST_BOTTOM - LIST_TOP
        val x = left + PANEL_W - 7
        guiGraphics.fill(x, trackTop, x + 3, trackTop + trackH, ROW_LOCKED)

        val thumbH = maxOf(12, trackH * VISIBLE_ROWS / rows.size)
        val span = rows.size - VISIBLE_ROWS
        val thumbY = trackTop + if (span <= 0) 0 else (trackH - thumbH) * scroll / span
        guiGraphics.fill(x, thumbY, x + 3, thumbY + thumbH, ACCENT)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll -= scrollY.toInt()
        clampScroll()
        return true
    }

    private fun clampScroll() {
        scroll = scroll.coerceIn(0, maxOf(0, rows.size - VISIBLE_ROWS))
    }

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

    companion object {

        fun open(page: Blueprint.Page) {
            Minecraft.getInstance().setScreen(BlueprintScreen(page))
        }

        private fun fmt(v: Int) = String.format("%,d", v)
        private fun fmtKg(v: Double) = String.format("%,.0f kg", v)

        private val TITLE: Component = Component.translatable("item.vs_eureka.blueprint")
        private val EMPTY_TEXT: Component = Component.translatable("gui.vs_eureka.blueprint_nothing")

        // Wide enough for the longest vanilla block name beside an icon and a six-figure count without
        // wrapping; nine rows deep, which keeps the panel on screen at GUI scale 3 on a 1080p display.
        private const val PANEL_W = 250
        private const val PANEL_H = 214
        private const val LIST_TOP = 53
        private const val ROW_H = 18
        private const val VISIBLE_ROWS = 8
        private const val LIST_BOTTOM = LIST_TOP + ROW_H * VISIBLE_ROWS

        private const val SMALL = 0.7f

        private const val PANEL_BORDER = 0xFF000000.toInt()
        private const val PANEL_BG = 0xFFC6C6C6.toInt()
        private const val ROW_HOVER = 0xFFD8D8D8.toInt()
        private const val ROW_LOCKED = 0xFFA0A0A0.toInt()
        private const val TEXT = 0xFF404040.toInt()
        private const val DIM = 0xFF7A7A7A.toInt()
        private const val ACCENT = 0xFF2A8FA6.toInt()
    }
}
