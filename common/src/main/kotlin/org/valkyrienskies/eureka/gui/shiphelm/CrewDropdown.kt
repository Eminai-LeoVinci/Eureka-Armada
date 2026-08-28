package org.valkyrienskies.eureka.gui.shiphelm

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component

/**
 * A code-drawn dropdown: one closed row that opens into a scrolling list.
 *
 * ## Why this is not a Button
 * An open dropdown has to be drawn OVER the widgets beneath it and has to eat their clicks. A `Button` can do
 * neither: `AbstractContainerScreen` paints `renderBg`, then the widgets, then `renderLabels`, and it offers
 * every click to the widget list before the screen sees it. So this is a plain object the screen owns and
 * drives -- [renderClosed] from `renderLabels` so it lands over the widgets, [renderOpen] after it, and
 * [clicked] from `mouseClicked` BEFORE `super`, so an open list takes the click that would otherwise land on
 * whatever it is covering.
 *
 * The same shape the crew manifest's station menu grew into, lifted out of that screen's private methods so a
 * second screen did not have to grow a third copy of it.
 *
 * ## The open list is wider than the closed row
 * Because names do not fit. The closed row marquees what overflows -- the reader gets the whole name in time
 * -- but a list is read at a glance, so it takes the width it needs and hangs it off the row's centre.
 */
class CrewDropdown(
    private val font: Font,
    /** Panel-relative geometry of the CLOSED row. Absolute pixels are worked out per frame from the origin. */
    private val relX: Int,
    private val relY: Int,
    private val width: Int,
    private val height: Int
) {

    /** One row. [trailing] is drawn right-aligned and dimmer -- the fare, or "aboard". */
    class Row(val key: Any, val label: String, val trailing: String, val enabled: Boolean = true)

    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            if (scroll > maxScroll()) scroll = maxScroll()
        }

    /** The key of the picked row, or null for "nothing picked". */
    var selected: Any? = null

    /** The empty-list message, shown in place of the label when there is nothing to pick. */
    var placeholder: Component = Component.empty()

    var open = false
        private set

    var enabled = true

    private var scroll = 0

    /** Called with the picked key. A row that is already selected still reports, so re-picking is not silent. */
    var onPick: (Any) -> Unit = {}

    fun close() {
        open = false
    }

    private fun visibleRows() = minOf(rows.size, MAX_VISIBLE)

    private fun maxScroll() = (rows.size - MAX_VISIBLE).coerceAtLeast(0)

    private fun openWidth() = maxOf(width, OPEN_W)

    private fun openX(originX: Int) = originX + relX - (openWidth() - width) / 2

    private fun openY(originY: Int) = originY + relY + height

    private fun openHeight() = visibleRows() * ROW_H

    /** Whether an open list covers this point, and would therefore eat a click there. */
    fun coversOpen(originX: Int, originY: Int, mouseX: Double, mouseY: Double): Boolean {
        if (!open) return false
        val bx = openX(originX)
        val by = openY(originY)
        return mouseX >= bx && mouseX < bx + openWidth() && mouseY >= by && mouseY < by + openHeight()
    }

    /** The closed row: border, fill, the selected name, and a caret. Draw from `renderLabels`. */
    // scissorOriginX/Y: 1.21.1 enableScissor takes ABSOLUTE screen coordinates and ignores the pose --
    // a caller drawing under a translated pose (renderLabels) passes origin 0 for the DRAWS but must
    // still hand the real panel corner here or the marquee clip lands at the screen corner instead.
    fun renderClosed(
        g: GuiGraphics, originX: Int, originY: Int, mouseX: Int, mouseY: Int,
        scissorOriginX: Int = originX, scissorOriginY: Int = originY
    ) {
        val bx = originX + relX
        val by = originY + relY
        val hovered = enabled && inside(mouseX, mouseY, bx, by, width, height)

        val fill = when {
            !enabled -> DIM_BG
            open || hovered -> BG_HOVER
            else -> BG
        }
        g.fill(bx, by, bx + width, by + height, if (enabled) BORDER else DIM_BORDER)
        g.fill(bx + 1, by + 1, bx + width - 1, by + height - 1, fill)

        val picked = rows.firstOrNull { it.key == selected }
        val label = picked?.label ?: placeholder.string
        val color = when {
            !enabled -> DIM_TEXT
            picked == null -> DIM_TEXT
            else -> TEXT
        }
        // The caret's strip is reserved before the label is measured, so a long name is clipped by the text
        // and never by the arrow -- an arrow with half a letter through it reads as a rendering fault.
        val inner = width - 2 * PAD - CARET_W
        val baseline = by + (height - font.lineHeight) / 2 + 1
        val labelWidth = font.width(label)
        if (labelWidth <= inner) {
            g.drawString(font, label, bx + PAD, baseline, color, false)
        } else {
            val sx = scissorOriginX + relX
            val sy = scissorOriginY + relY
            g.enableScissor(sx + PAD, sy + 1, sx + PAD + inner, sy + height - 1)
            g.drawString(font, label, bx + PAD - Marquee.offset(labelWidth - inner), baseline, color, false)
            g.disableScissor()
        }
        g.drawString(font, if (open) "▴" else "▾", bx + width - PAD - CARET_W + 1, baseline, color, false)
    }

    /** The open list. Draw AFTER everything else, so it covers the panel it hangs over. */
    fun renderOpen(g: GuiGraphics, originX: Int, originY: Int, mouseX: Int, mouseY: Int) {
        if (!open || rows.isEmpty()) return
        val bx = openX(originX)
        val by = openY(originY)
        val w = openWidth()
        val h = openHeight()

        g.fill(bx, by, bx + w, by + h, BORDER)
        g.fill(bx + 1, by + 1, bx + w - 1, by + h - 1, LIST_BG)

        for (i in 0 until visibleRows()) {
            val row = rows.getOrNull(scroll + i) ?: break
            val ry = by + i * ROW_H
            val hovered = row.enabled && inside(mouseX, mouseY, bx, ry, w, ROW_H)
            if (hovered) g.fill(bx + 1, ry, bx + w - 1, ry + ROW_H, ROW_HOVER)

            val color = when {
                !row.enabled -> DIM_TEXT
                row.key == selected -> ACCENT
                else -> TEXT
            }
            val trailingWidth = if (row.trailing.isEmpty()) 0 else font.width(row.trailing) + PAD
            val inner = w - 2 * PAD - trailingWidth
            val baseline = ry + (ROW_H - font.lineHeight) / 2 + 1
            g.enableScissor(bx + PAD, ry, bx + PAD + inner, ry + ROW_H)
            g.drawString(font, row.label, bx + PAD, baseline, color, false)
            g.disableScissor()
            if (row.trailing.isNotEmpty()) {
                g.drawString(
                    font, row.trailing, bx + w - PAD - font.width(row.trailing), baseline, TRAILING_TEXT, false
                )
            }
        }

        // Overflow markers rather than a scrollbar: six rows is the whole list for almost every captain, and
        // a bar drawn on a list that never scrolls is furniture.
        if (scroll > 0) g.drawString(font, "▲", bx + w - PAD - 4, by + 1, TRAILING_TEXT, false)
        if (scroll < maxScroll()) g.drawString(font, "▼", bx + w - PAD - 4, by + h - 8, TRAILING_TEXT, false)
    }

    /**
     * Offer a click. Returns whether it was taken.
     *
     * An open list takes every click inside itself -- including one on a row that cannot be picked, which
     * leaves the list open rather than folding it, because folding on a refused click looks like the pick
     * was accepted. A click anywhere else folds it and is NOT taken, so the thing actually clicked still
     * gets it: closing a menu should not cost the next action.
     */
    fun clicked(originX: Int, originY: Int, mouseX: Double, mouseY: Double): Boolean {
        if (!enabled) return false
        val bx = originX + relX
        val by = originY + relY

        if (inside(mouseX, mouseY, bx, by, width, height)) {
            open = !open
            if (open) scroll = 0
            return true
        }
        if (!open) return false

        if (!coversOpen(originX, originY, mouseX, mouseY)) {
            open = false
            return false
        }

        val index = scroll + ((mouseY - openY(originY)).toInt() / ROW_H)
        val row = rows.getOrNull(index) ?: return true
        if (!row.enabled) return true
        selected = row.key
        open = false
        onPick(row.key)
        return true
    }

    /** Offer a scroll. Returns whether it was taken. */
    fun scrolled(originX: Int, originY: Int, mouseX: Double, mouseY: Double, delta: Double): Boolean {
        if (!open || !coversOpen(originX, originY, mouseX, mouseY)) return false
        scroll = (scroll - delta.toInt()).coerceIn(0, maxScroll())
        return true
    }

    private fun inside(mx: Int, my: Int, bx: Int, by: Int, w: Int, h: Int) =
        mx >= bx && mx < bx + w && my >= by && my < by + h

    private fun inside(mx: Double, my: Double, bx: Int, by: Int, w: Int, h: Int) =
        mx >= bx && mx < bx + w && my >= by && my < by + h

    companion object {
        /** Six is what fits under the book without reaching the bottom band; past that the list scrolls. */
        private const val MAX_VISIBLE = 6
        private const val ROW_H = 12
        private const val OPEN_W = 110
        private const val PAD = 3
        private const val CARET_W = 6

        private const val BORDER = 0xFF404040.toInt()
        private const val BG = 0xFFC6C6C6.toInt()
        private const val BG_HOVER = 0xFFE0E0E0.toInt()
        private const val LIST_BG = 0xFFD8D8D8.toInt()
        private const val ROW_HOVER = 0xFFB0C4DE.toInt()
        private const val TEXT = 0xFF404040.toInt()
        private const val TRAILING_TEXT = 0xFF6A6A6A.toInt()
        private const val ACCENT = 0xFF2A6099.toInt()
        private const val DIM_BORDER = 0xFF6A6A6A.toInt()
        private const val DIM_BG = 0xFF9A9A9A.toInt()
        private const val DIM_TEXT = 0xFF808080.toInt()
    }
}
