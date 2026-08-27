package org.valkyrienskies.eureka.fabric.client.blueprint

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Renderable
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW
import org.valkyrienskies.eureka.fabric.PathNetworkingFabric
import org.valkyrienskies.eureka.fabric.client.MaterialSort
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmButton
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.eureka.shipwright.MaterialFamilies
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

    /**
     * Rebuilt once per open rather than per frame -- the census never changes under us.
     *
     * An ANY row keeps its FAMILY rather than a stack, because it has no one face to show: it is drawn as a
     * different member every few seconds. The list is worked out here, once, because
     * [MaterialFamilies.replacementsFor] walks the whole item registry.
     */
    private val unsorted: List<Row> = page.census.map { row ->
        Row(
            item = row.item,
            count = row.count,
            faces = if (row.any) MaterialFamilies.replacementsFor(row.item) else emptyList()
        )
    }

    /**
     * The census in the captain's chosen order. Re-read per frame rather than cached, because the order can
     * change under the button while the page is open and a cached list would need invalidating by hand --
     * sixty sorts a second of a sixty-row list is nothing beside the bug that forgetting to do so would be.
     */
    private val rows: List<Row>
        get() = MaterialSort.apply(unsorted, { ItemStack(it.item).hoverName.string }, { it.count })

    private class Row(val item: Item, val count: Int, val faces: List<Item>) {
        val any: Boolean get() = faces.isNotEmpty()

        /**
         * What to draw right now. The phase is taken off the WALL CLOCK, so every open Any row -- here and
         * on the shipwright's own panel -- turns over on the same beat rather than flickering out of step.
         */
        fun face(): ItemStack {
            if (faces.isEmpty()) return ItemStack(item)
            val step = (System.currentTimeMillis() / ANY_CYCLE_MS).toInt()
            return ItemStack(faces[Math.floorMod(step, faces.size)])
        }
    }

    /**
     * What the header says right now.
     *
     * Held apart from [page] because the page is a snapshot of the ITEM, and a rename travels to the server
     * and comes back on the next inventory sync. Waiting for that would leave the old name on screen for a
     * beat after the captain typed the new one, which reads as the rename having missed.
     */
    private var displayName: String = page.shipName
    private var renaming = false
    private var nameValue: String = ""
    private var nameBox: EditBox? = null

    override fun init() {
        left = (width - PANEL_W) / 2
        top = (height - PANEL_H) / 2
        clampScroll()

        // Edited where it is read: the box takes the header's own pixels. Short of the dimensions on the
        // right, which stay legible while typing and are the thing that tells two similar hulls apart.
        if (renaming) {
            nameBox = addRenderableWidget(
                EditBox(font, left + 6, top + 3, PANEL_W - 12 - NAME_GUTTER, NAME_BOX_H, RENAME_TEXT)
            ).also {
                it.setMaxLength(MAX_NAME_LENGTH)
                it.value = nameValue
                it.setResponder { typed -> nameValue = typed }
                it.isFocused = true
                this.focused = it
            }
        } else {
            addRenderableWidget(
                ShipHelmButton(left + 8, top + BTN_Y, RENAME_W, BTN_H, RENAME_TEXT, font) {
                    nameValue = displayName
                    renaming = true
                    rebuild()
                }
            )
        }

        addRenderableWidget(
            ShipHelmButton(left + PANEL_W - 8 - EXIT_W, top + BTN_Y, EXIT_W, BTN_H, EXIT_TEXT, font) {
                onClose()
            }
        )

        // Above the divider, in the corner the header leaves empty. It belongs to the LIST rather than to
        // the page, which is why it sits on the line between them rather than down with Rename and Exit.
        addRenderableWidget(
            ShipHelmButton(
                left + PANEL_W - 8 - SORT_W, top + LIST_TOP - 5 - SORT_H, SORT_W, SORT_H,
                MaterialSort.label, font, SORT_TEXT_SCALE
            ) {
                MaterialSort.cycle()
                rebuild()
            }
        )
    }

    private fun rebuild() {
        clearWidgets()
        init()
    }

    /**
     * Send the new name, or abandon it.
     *
     * Blank clears the custom name rather than setting an empty one, which puts the page back to the name it
     * was drawn under -- so a captain who renames one by mistake has a way back that does not involve
     * guessing what it used to be called.
     */
    private fun commitRename(send: Boolean) {
        if (send) {
            val typed = nameValue.trim()
            if (typed != displayName) {
                PathNetworkingFabric.sendBlueprintName(typed)
                displayName = typed.ifEmpty { page.shipName }
            }
        }
        renaming = false
        nameBox = null
        rebuild()
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (renaming) {
            when (keyCode) {
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    commitRename(send = true)
                    return true
                }
                GLFW.GLFW_KEY_ESCAPE -> {
                    commitRename(send = false)
                    return true
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun isPauseScreen(): Boolean = false

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        // 1.21.1: renderBackground is OURS to call, and it must come FIRST -- it runs the menu-blur pass, so
        // anything drawn before it gets blurred into soup. (1.21.11 runs it before render() ever fires.)
        renderBackground(guiGraphics, mouseX, mouseY, partialTicks)
        panel(guiGraphics, left, top, PANEL_W, PANEL_H)
        drawHeader(guiGraphics)
        drawList(guiGraphics, mouseX, mouseY)
        // Widgets drawn directly: super.render would call renderBackground AGAIN and blur the panels above.
        for (child in children()) if (child is Renderable) child.render(guiGraphics, mouseX, mouseY, partialTicks)
    }

    private fun drawHeader(guiGraphics: GuiGraphics) {
        // While renaming, the box occupies these pixels -- the heading underneath would show through it.
        if (!renaming) {
            guiGraphics.drawString(font, Component.literal(displayName), left + 8, top + 6, ACCENT, false)
        }

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

        val face = row.face()
        guiGraphics.renderItem(face, left + 8, y + 1)
        // Orange, and marked, exactly as the shipwright's own list marks a row the plans left open -- a page
        // that says nothing about it is a page a captain reads as demanding one particular slab.
        guiGraphics.drawString(
            font, if (row.any) ANY_KIND_TEXT else face.hoverName,
            left + 30, y + 5, if (row.any) ORANGE else TEXT, false
        )

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
        pose.pushPose()
        pose.scale(SMALL, SMALL, 1f)
        guiGraphics.drawString(font, text, Math.round(x / SMALL), Math.round(y / SMALL), color, false)
        pose.popPose()
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

        private const val BTN_H = 14
        private const val SORT_W = 32
        private const val SORT_H = 12

        /** All three labels, not just the long ones: a control whose type changes size as you cycle it reads
         *  as two different controls. "Hi-Lo" is what sets the size; "A-Z" simply comes along. */
        private const val SORT_TEXT_SCALE = 0.8f

        /** The row under the list. LIST_BOTTOM is exclusive, so a button starting on it clears the last row. */
        private const val BTN_Y = LIST_BOTTOM
        private const val RENAME_W = 56
        private const val EXIT_W = 44
        private const val NAME_BOX_H = 14

        /** Room kept on the header's right for the dimensions, which stay readable while the name is typed. */
        private const val NAME_GUTTER = 76

        /** The helm's own cap. A page's name and a ship's name are the same string in the end. */
        private const val MAX_NAME_LENGTH = 32

        private val RENAME_TEXT: Component = Component.translatable("gui.vs_eureka.blueprint_rename")
        private val EXIT_TEXT: Component = Component.translatable("gui.vs_eureka.blueprint_exit")

        private const val PANEL_BORDER = 0xFF000000.toInt()
        private const val PANEL_BG = 0xFFC6C6C6.toInt()
        private const val ROW_HOVER = 0xFFD8D8D8.toInt()
        private const val ROW_LOCKED = 0xFFA0A0A0.toInt()
        private const val TEXT = 0xFF404040.toInt()

        private val ANY_KIND_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_any_kind")

        /** A row the plans left open. The shipwright's own list marks these the same colour. */
        private const val ORANGE = 0xFFC97A1E.toInt()

        /** How long an ANY row shows each member of its family. Shared beat with the shipwright panel. */
        private const val ANY_CYCLE_MS = 3000L
        private const val DIM = 0xFF7A7A7A.toInt()
        private const val ACCENT = 0xFF2A8FA6.toInt()
    }
}
