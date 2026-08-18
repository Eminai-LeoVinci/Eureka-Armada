package org.valkyrienskies.eureka.gui.shiphelm

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

/**
 * The Crew & Operations book: a closed volume drawn on the helm menu, bound in the Heart of the Sea's deep
 * teal -- the colour a berth already costs, and the accent every crew screen already answers in -- with the
 * page block showing white along the fore-edge and the foot. Clicking it opens the same articles the crew
 * key opens at the wheel.
 *
 * Drawn entirely with fills, like every widget on this screen and like the manifest's padlock: the helm
 * menu ships no textures, and art a resource pack can restyle would drift out from under its own hit-box.
 * Mirrors its siblings' [renderContents] override (1.21.11 made AbstractButton.renderWidget final) and
 * [ShipHelmButton]'s rule that keyboard focus lights the same as hover -- which is how a controller's
 * selection shows itself on screens that walk widgets by focus.
 */
class CrewBookButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val font: Font,
    onPress: OnPress
) : Button(x, y, width, height, TITLE, onPress, DEFAULT_NARRATION) {

    override fun renderContents(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        val lit = active && (isHovered || isFocused)
        val cover = if (!active) COVER_DIM else if (lit) COVER_LIT else COVER
        val spine = if (!active) SPINE_DIM else SPINE
        val ink = if (!active) INK_DIM else INK

        val x1 = x + width
        val y1 = y + height

        // The page block first: the leaves peeking out along the fore-edge and the foot, the way a closed
        // book sits when you look at its front board. Two hairlines suggest the stacked pages.
        guiGraphics.fill(x + PAGE_INSET, y + PAGE_INSET, x1, y1, PAGE_EDGE)
        guiGraphics.fill(x + PAGE_INSET + 1, y + PAGE_INSET + 1, x1 - 1, y1 - 1, PAGES)
        guiGraphics.fill(x1 - 2, y + PAGE_INSET + 2, x1 - 1, y1 - 3, PAGE_LINE)
        guiGraphics.fill(x + PAGE_INSET + 2, y1 - 2, x1 - 3, y1 - 1, PAGE_LINE)

        // The front board over it, offset up-left so the pages show: border, board, spine strip, and the
        // hinge groove where the board meets the spine.
        val cx1 = x1 - PAGE_REVEAL
        val cy1 = y1 - PAGE_REVEAL
        guiGraphics.fill(x, y, cx1, cy1, EDGE)
        guiGraphics.fill(x + 1, y + 1, cx1 - 1, cy1 - 1, cover)
        guiGraphics.fill(x + 1, y + 1, x + SPINE_W, cy1 - 1, spine)
        guiGraphics.fill(x + SPINE_W, y + 1, x + SPINE_W + 1, cy1 - 1, HINGE)

        // The Heart of the Sea's glint: one pale spark near the top fore corner of the board.
        guiGraphics.fill(cx1 - 7, y + 4, cx1 - 4, y + 5, GLINT)
        guiGraphics.fill(cx1 - 6, y + 3, cx1 - 5, y + 6, GLINT)

        // The title, staggered the way a cover sets it: Crew high and left of centre, the ampersand
        // mid-board and right of it, Operations across the foot. Centred on the BOARD, not the widget --
        // the spine and the page reveal are not places words go.
        val boardX = x + SPINE_W + 1
        val boardW = (cx1 - 1) - boardX
        val pose = guiGraphics.pose()
        pose.pushMatrix()
        pose.scale(TITLE_SCALE, TITLE_SCALE)
        fun coverLine(text: Component, atY: Int, shift: Int) {
            val w = font.width(text) * TITLE_SCALE
            val tx = (boardX + (boardW - w) / 2f + shift) / TITLE_SCALE
            guiGraphics.drawString(font, text, Math.round(tx), Math.round((y + atY) / TITLE_SCALE), ink, false)
        }
        coverLine(CREW_TEXT, CREW_Y, 0)
        coverLine(AMP_TEXT, AMP_Y, 0)
        coverLine(OPS_TEXT, OPS_Y, 0)
        pose.popMatrix()
    }

    companion object {
        /** Narration / fallback title; the cover itself is set in the three staggered lines below. */
        private val TITLE = Component.translatable("gui.vs_eureka.crew_book")
        private val CREW_TEXT = Component.translatable("gui.vs_eureka.crew_book_1")
        private val AMP_TEXT = Component.translatable("gui.vs_eureka.crew_book_2")
        private val OPS_TEXT = Component.translatable("gui.vs_eureka.crew_book_3")

        /** Small enough that "Operations" clears the board of a 48-wide book with a margin either side. */
        private const val TITLE_SCALE = 0.6f

        /** Cover-relative baselines for the three title lines. */
        private const val CREW_Y = 13
        private const val AMP_Y = 25
        private const val OPS_Y = 39

        /** How far the page block peeks past the front board, and how far in from the top-left it starts. */
        private const val PAGE_REVEAL = 3
        private const val PAGE_INSET = 3
        private const val SPINE_W = 5

        // The binding: the Heart of the Sea's teal, the exact accent the crew nameplates and the manifest
        // wear -- a berth is bought with one, so the book of berths is bound in it.
        private const val COVER = 0xFF2A8FA6.toInt()
        private const val COVER_LIT = 0xFF37A7BF.toInt()
        private const val COVER_DIM = 0xFF8A9296.toInt()
        private const val SPINE = 0xFF14454F.toInt()
        private const val SPINE_DIM = 0xFF6E7A7E.toInt()
        private const val HINGE = 0xFF1D6273.toInt()
        private const val EDGE = 0xFF0B2A31.toInt()
        private const val GLINT = 0xFF7FD3DF.toInt()

        // The leaves: warm white with a faint stacked-page line, dark-edged so they read against the panel.
        private const val PAGES = 0xFFF4F1E8.toInt()
        private const val PAGE_EDGE = 0xFF3A382F.toInt()
        private const val PAGE_LINE = 0xFFCFC9B8.toInt()

        // The lettering: pale foam white, dimmed with the rest when the ship is not assembled.
        private const val INK = 0xFFEAF6F8.toInt()
        private const val INK_DIM = 0xFFB9BFC1.toInt()
    }
}
