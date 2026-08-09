package org.valkyrienskies.eureka.fabric.client.crew

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.BlockPos
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import org.valkyrienskies.eureka.crew.CrewManifest
import org.valkyrienskies.eureka.fabric.PathNetworkingFabric
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmButton
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmIconButton
import java.util.UUID

/**
 * The ship's crew, berth by berth.
 *
 * Opened by SHIFT+C aimed at a wheel within mounting distance. Every berth a captain owns gets a row whether or
 * not anyone is in it, and every berth they do NOT own gets a greyed one, because the shape of the thing is
 * half the information: a manifest with four live rows and twenty-eight dim ones says "hearts buy berths"
 * without a word of explanation.
 *
 * ## A snapshot, deliberately
 * The screen holds what the server sent when it opened and does not poll. Nothing that changes a manifest can
 * happen while one is open -- the crew key does not fire under a screen, and trading or levelling a villager
 * means right-clicking them -- so reopening IS the refresh. The one exception is a rename, which the player
 * makes from in here, and the server answers that with a fresh snapshot of its own accord.
 *
 * ## Rows are drawn, not built
 * The list is drawn and hit-tested by hand rather than made of widgets. Thirty-two rows of buttons would be
 * rebuilt on every scroll tick and would each need positioning against a clip rectangle they may be outside of;
 * one rectangle test in [mouseClicked] is less code and cannot drift out of step with what was painted. Real
 * widgets appear only on the card, where there is text to type and focus to manage.
 */
@Environment(EnvType.CLIENT)
class CrewManifestScreen private constructor(private var snapshot: CrewManifest.Snapshot) : Screen(TITLE) {

    private var left = 0
    private var top = 0
    private var scroll = 0

    /** Whose card is open, if any. Held by UUID so it survives a snapshot arriving underneath it. */
    private var openCard: UUID? = null
    private var detail: CrewManifest.Detail? = null

    /** Kept across widget rebuilds so a refresh mid-type does not swallow what has been typed. */
    private var nameValue = ""
    private var nameBox: EditBox? = null

    /** The crew's own name, edited in the header. Same keep-across-rebuild reasoning as [nameValue]. */
    private var crewNameValue = ""
    private var crewNameBox: EditBox? = null
    private var renamingCrew = false

    override fun init() {
        left = (width - PANEL_W) / 2
        top = (height - PANEL_H) / 2
        clampScroll()

        // The crew's name is edited in place: clicking the heading swaps it for a box over the same pixels,
        // which is the only spot in this panel wide enough for a name and the one a player would aim at.
        // Built only while actually renaming, for the same reason the card's widgets are -- see below.
        if (openCard == null && renamingCrew) {
            crewNameBox = addRenderableWidget(
                EditBox(font, left + 6, top + 3, PANEL_W - 12 - BERTHS_GUTTER, NAME_BOX_H, RENAME_TEXT)
            ).also {
                it.setMaxLength(CrewManifest.MAX_NAME_LENGTH)
                it.value = crewNameValue
                it.setResponder { typed -> crewNameValue = typed }
                it.isFocused = true
                this.focused = it
            }
            return
        }

        // Widgets exist only while a card is open. Building them unconditionally and hiding them would leave
        // an invisible EditBox eating clicks and keystrokes over the list.
        if (openCard == null) return

        val boxW = CARD_W - 2 * CARD_PAD - RENAME_BTN_W - 4
        nameBox = addRenderableWidget(
            EditBox(font, cardX() + CARD_PAD, cardY() + CARD_PAD, boxW, NAME_BOX_H, RENAME_TEXT)
        ).also {
            it.setMaxLength(CrewManifest.MAX_NAME_LENGTH)
            it.value = nameValue
            it.setResponder { typed -> nameValue = typed }
        }

        addRenderableWidget(
            ShipHelmIconButton(
                cardX() + CARD_W - CARD_PAD - RENAME_BTN_W, cardY() + CARD_PAD,
                RENAME_BTN_W, NAME_BOX_H, RENAME_TEXT, font
            ) { commitRename() }
        )

        addRenderableWidget(
            ShipHelmButton(
                cardX() + CARD_W - CARD_PAD - BACK_BTN_W, cardY() + CARD_H - CARD_PAD - BACK_BTN_H,
                BACK_BTN_W, BACK_BTN_H, BACK_TEXT, font
            ) { closeCard() }
        )
    }

    override fun isPauseScreen(): Boolean = false

    // region opening and closing a card

    private fun openCard(row: CrewManifest.Row) {
        openCard = row.villager
        detail = null
        // The row already carries the name, so the field is right the instant the card opens rather than one
        // round trip later. The detail packet only ever adds to what is on screen; it never corrects it.
        nameValue = row.name
        PathNetworkingFabric.sendCrewAsk(snapshot.helm, row.villager)
        rebuildWidgets()
    }

    private fun closeCard() {
        openCard = null
        detail = null
        nameBox = null
        rebuildWidgets()
    }

    /** Start editing the crew's name, seeded with what it is now. */
    private fun beginCrewRename() {
        crewNameValue = snapshot.ship
        renamingCrew = true
        rebuildWidgets()
    }

    /**
     * Send the crew's new name, or abandon the edit.
     *
     * Goes through the same `helm_name` path the anvil and the block share, so there is one server-side place
     * that decides what a wheel is called -- including the refusals for clearing a crewed name and for
     * colliding with a crew you already have.
     */
    private fun commitCrewRename(send: Boolean) {
        if (send) {
            val typed = crewNameValue.trim()
            if (typed.isNotEmpty() && typed != snapshot.ship) {
                PathNetworkingFabric.sendHelmName(BlockPos.of(snapshot.helm), typed)
            }
        }
        renamingCrew = false
        crewNameBox = null
        rebuildWidgets()
    }

    private fun commitRename() {
        val villager = openCard ?: return
        PathNetworkingFabric.sendCrewRename(snapshot.helm, villager, nameValue)
        // Ask again straight after: the rename is answered with a fresh SNAPSHOT, which updates the list behind
        // the card but not the card itself. Both travel the same connection, so the order is the order.
        PathNetworkingFabric.sendCrewAsk(snapshot.helm, villager)
    }

    private fun refresh(next: CrewManifest.Snapshot) {
        snapshot = next
        // A card whose crew member has left the manifest -- paid off elsewhere, walked ashore -- has nothing
        // left to be about.
        if (openCard != null && rowOf(openCard!!) == null) {
            closeCard()
        } else {
            clampScroll()
        }
    }

    private fun applyDetail(next: CrewManifest.Detail) {
        if (next.villager != openCard) return
        detail = next
        nameValue = next.name
        nameBox?.value = next.name
    }

    // endregion

    // region input

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(mouseButtonEvent, doubled)) return true
        if (openCard != null) return false

        val mx = mouseButtonEvent.x.toInt()
        val my = mouseButtonEvent.y.toInt()

        // Clicking anywhere off the box while renaming commits it, the way a name field is expected to behave.
        if (renamingCrew) {
            commitCrewRename(send = true)
            return true
        }

        // The heading is the crew's name, and clicking it edits it. Bounded to the left of the berth counter
        // so aiming at the count never starts a rename.
        if (mx >= left && mx <= left + PANEL_W - BERTHS_GUTTER && my >= top + 2 && my < top + LIST_TOP - 4) {
            beginCrewRename()
            return true
        }

        if (mx < left || mx > left + PANEL_W) return false
        if (my < top + LIST_TOP || my >= top + LIST_BOTTOM) return false

        val berth = scroll + (my - (top + LIST_TOP)) / ROW_H
        val row = snapshot.rows.firstOrNull { it.slot == berth } ?: return false
        openCard(row)
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (openCard != null) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        scroll -= scrollY.toInt()
        clampScroll()
        return true
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        // Enter commits a rename rather than closing the screen, which is what a focused text field implies.
        if (keyEvent.key() == GLFW.GLFW_KEY_ENTER || keyEvent.key() == GLFW.GLFW_KEY_KP_ENTER) {
            if (nameBox?.isFocused == true) {
                commitRename()
                return true
            }
            if (renamingCrew) {
                commitCrewRename(send = true)
                return true
            }
        }
        // Escape steps back out of whatever is innermost: a rename, then a card, then the manifest.
        if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (renamingCrew) {
                commitCrewRename(send = false)
                return true
            }
            if (openCard != null) {
                closeCard()
                return true
            }
        }
        return super.keyPressed(keyEvent)
    }

    private fun clampScroll() {
        val overflow = snapshot.maxBerths - VISIBLE_ROWS
        scroll = scroll.coerceIn(0, if (overflow < 0) 0 else overflow)
    }

    private fun rowOf(villager: UUID): CrewManifest.Row? = snapshot.rows.firstOrNull { it.villager == villager }

    // endregion

    // region drawing

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        // NOT renderBackground: 1.21.11 calls it for us ahead of this, and calling it again draws the dim twice.
        panel(guiGraphics, left, top, PANEL_W, PANEL_H)
        drawHeader(guiGraphics)
        drawList(guiGraphics, mouseX, mouseY)
        if (openCard != null) drawCard(guiGraphics, mouseX, mouseY)
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
    }

    private fun drawHeader(guiGraphics: GuiGraphics) {
        // While renaming, the box occupies these pixels -- drawing the heading underneath would show through.
        if (!renamingCrew) {
            val heading = if (snapshot.ship.isEmpty()) TITLE else Component.literal(snapshot.ship)
            guiGraphics.drawString(font, heading, left + 8, top + 6, TEXT, false)
        }

        val berths = Component.translatable(
            "gui.vs_eureka.crew_berths", snapshot.rows.size, snapshot.berths
        )
        guiGraphics.drawString(font, berths, left + PANEL_W - 8 - font.width(berths), top + 6, ACCENT, false)

        guiGraphics.fill(left + 4, top + LIST_TOP - 3, left + PANEL_W - 4, top + LIST_TOP - 2, ACCENT)
    }

    private fun drawList(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + LIST_BOTTOM)
        for (i in 0 until VISIBLE_ROWS) {
            val berth = scroll + i
            if (berth >= snapshot.maxBerths) break
            drawRow(guiGraphics, berth, top + LIST_TOP + i * ROW_H, mouseX, mouseY)
        }
        guiGraphics.disableScissor()
        drawScrollbar(guiGraphics)
    }

    private fun drawRow(guiGraphics: GuiGraphics, berth: Int, y: Int, mouseX: Int, mouseY: Int) {
        val locked = berth >= snapshot.berths
        val row = if (locked) null else snapshot.rows.firstOrNull { it.slot == berth }
        val hovered = openCard == null && row != null &&
            mouseX >= left && mouseX <= left + PANEL_W && mouseY >= y && mouseY < y + ROW_H

        guiGraphics.fill(
            left + 4, y + 1, left + PANEL_W - 4, y + ROW_H - 1,
            when {
                locked -> ROW_LOCKED
                hovered -> ROW_HOVER
                else -> ROW_BG
            }
        )

        // The berth number, always, even on a locked row: it is what "Crewman7" refers to.
        val number = Component.literal((berth + 1).toString())
        small(guiGraphics, number, left + 18 - (font.width(number) * SMALL).toInt(), y + 8, if (locked) DIM else ACCENT)

        if (row == null) {
            val label = if (locked) LOCKED_TEXT else EMPTY_TEXT
            guiGraphics.drawString(font, label, left + 44, y + 7, DIM, false)
            if (locked) small(guiGraphics, LOCKED_HINT, left + 44 + font.width(label) + 6, y + 8, DIM)
            return
        }

        drawHead(guiGraphics, left + 20, y + 1, row)
        guiGraphics.drawString(font, Component.literal(row.name), left + 44, y + 3, TEXT, false)
        small(
            guiGraphics,
            Component.translatable(
                "gui.vs_eureka.crew_rank", professionName(row.profession), rankName(row.level)
            ),
            left + 44, y + 14, SUBTLE
        )

        // A card opens from anywhere on the row; the glyph is the affordance, not the only target.
        val bx = left + PANEL_W - 26
        guiGraphics.fill(bx, y + 4, bx + 14, y + 18, if (hovered) ACCENT else ROW_LOCKED)
        small(guiGraphics, INFO_GLYPH, bx + 5, y + 9, if (hovered) 0xFFFFFFFF.toInt() else TEXT)
    }

    private fun drawScrollbar(guiGraphics: GuiGraphics) {
        if (snapshot.maxBerths <= VISIBLE_ROWS) return
        val trackTop = top + LIST_TOP
        val trackH = LIST_BOTTOM - LIST_TOP
        val x = left + PANEL_W - 7
        guiGraphics.fill(x, trackTop, x + 3, trackTop + trackH, ROW_LOCKED)

        val thumbH = (trackH * VISIBLE_ROWS / snapshot.maxBerths).coerceAtLeast(12)
        val travel = trackH - thumbH
        val thumbY = trackTop + travel * scroll / (snapshot.maxBerths - VISIBLE_ROWS)
        guiGraphics.fill(x, thumbY, x + 3, thumbY + thumbH, ACCENT)
    }

    /**
     * A crew member's head: the actual villager, framed on the shoulders up.
     *
     * The first two attempts cut the head out of the skin by hand, and the second was correct -- correct for
     * VANILLA. Fresh Animations, which this pack runs and which half of Minecraft runs, ships a `villager.png`
     * whose head front is **blank skin**: its faces are a custom animated model that keeps eyes and mouth
     * somewhere else on the sheet entirely. So the icon rendered a face with no features, and no choice of UV
     * rectangle could have fixed it, because a resource pack is free to redefine the layout the rectangle
     * refers to.
     *
     * Rendering the entity sidesteps the whole question: whatever the game draws over that villager's head --
     * vanilla, Fresh Animations, a modded profession, a baby, a biome variant we have never heard of -- is what
     * appears here, and it stays right when the pack changes. The mouse position is pinned to the middle of the
     * box so the crew face front instead of eight of them tracking the cursor at once.
     *
     * [drawHeadFromSkin] stays as the fallback for a row whose villager the client has not got loaded, which in
     * practice means a snapshot that has gone stale under the player.
     */
    private fun drawHead(guiGraphics: GuiGraphics, x: Int, y: Int, row: CrewManifest.Row) {
        val villager = minecraft?.level?.getEntity(row.entityId) as? LivingEntity
        if (villager == null) {
            drawHeadFromSkin(guiGraphics, x, y, row.villagerType, row.profession)
            return
        }
        val centreX = (x + ICON_SIZE / 2).toFloat()
        val centreY = (y + ICON_SIZE / 2).toFloat()
        InventoryScreen.renderEntityInInventoryFollowsMouse(
            guiGraphics, x, y, x + ICON_SIZE, y + ICON_SIZE,
            HEAD_SCALE, HEAD_RAISE, centreX, centreY, villager
        )
    }

    /**
     * The head cut out of the villager's skin, composited the way the game composites it on the mob.
     *
     * Only reached when the villager itself is not available. Both `type/<biome>.png` and `profession/<job>.png`
     * are OVERLAYS -- transparent everywhere the skin below shows through -- so the base skin goes down first,
     * then the biome outfit, then the job, each with its hat box after it.
     *
     * UVs come from `VillagerModel`'s head cube, `texOffs(0,0)` and `texOffs(32,0)` over an 8x10x8 box, whose
     * front faces land one depth in. True of vanilla; see [drawHead] for why that is not a guarantee.
     */
    private fun drawHeadFromSkin(
        guiGraphics: GuiGraphics, x: Int, y: Int, typeId: String, professionId: String
    ) {
        val pose = guiGraphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(HEAD_ZOOM, HEAD_ZOOM)

        face(guiGraphics, BASE_SKIN, false)
        Identifier.tryParse(typeId)?.let {
            face(guiGraphics, it.withPath { p -> "textures/entity/villager/type/$p.png" }, true)
        }
        if (professionId != CrewManifest.NO_PROFESSION) {
            Identifier.tryParse(professionId)?.let {
                face(guiGraphics, it.withPath { p -> "textures/entity/villager/profession/$p.png" }, true)
            }
        }

        pose.popMatrix()
    }

    /** One villager skin layer's head front face, and its hat box on top when the layer has one. */
    private fun face(guiGraphics: GuiGraphics, texture: Identifier, withHat: Boolean) {
        guiGraphics.blit(
            RenderPipelines.GUI_TEXTURED, texture, 0, 0, HEAD_U, HEAD_V, HEAD_W, HEAD_H, SKIN_TEX, SKIN_TEX
        )
        if (withHat) {
            guiGraphics.blit(
                RenderPipelines.GUI_TEXTURED, texture, 0, 0, HAT_U, HEAD_V, HEAD_W, HEAD_H, SKIN_TEX, SKIN_TEX
            )
        }
    }

    // endregion

    // region the card

    private fun cardX() = left + (PANEL_W - CARD_W) / 2
    private fun cardY() = top + (PANEL_H - CARD_H) / 2

    private fun drawCard(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Dim the manifest rather than leave it, so it is plainly behind the card and not competing with it.
        guiGraphics.fill(left, top, left + PANEL_W, top + PANEL_H, CARD_SCRIM)
        panel(guiGraphics, cardX(), cardY(), CARD_W, CARD_H)

        val x = cardX() + CARD_PAD
        var y = cardY() + CARD_PAD + NAME_BOX_H + 6

        val card = detail
        if (card == null) {
            guiGraphics.drawString(font, LOADING_TEXT, x, y, DIM, false)
            return
        }

        guiGraphics.drawString(
            font,
            Component.translatable(
                "gui.vs_eureka.crew_rank", professionName(card.profession), rankName(card.level)
            ),
            x, y, TEXT, false
        )
        y += 11
        small(guiGraphics, Component.translatable("gui.vs_eureka.crew_xp", card.xp), x, y, SUBTLE)
        y += 12

        guiGraphics.fill(x, y, cardX() + CARD_W - CARD_PAD, y + 1, SEPARATOR)
        y += 4
        guiGraphics.drawString(font, SELLS_TEXT, x, y, TEXT, false)
        y += 12

        if (card.offers.isEmpty()) {
            small(guiGraphics, NO_TRADES_TEXT, x, y + 1, DIM)
            y += 12
        } else {
            for (offer in card.offers) {
                drawOffer(guiGraphics, x, y, offer, mouseX, mouseY)
                y += OFFER_H
            }
        }

        // Where duties will land. Deliberately plain and inert -- a greyed row promises a place, not a feature.
        val footY = cardY() + CARD_H - CARD_PAD - BACK_BTN_H - 24
        guiGraphics.fill(x, footY - 4, cardX() + CARD_W - CARD_PAD, footY - 3, SEPARATOR)
        small(guiGraphics, ASSIGNMENT_TEXT, x, footY, DIM)
        small(guiGraphics, STATION_TEXT, x, footY + 10, DIM)
    }

    private fun drawOffer(
        guiGraphics: GuiGraphics, x: Int, y: Int, offer: CrewManifest.Offer, mouseX: Int, mouseY: Int
    ) {
        var cursor = x
        cursor = drawStack(guiGraphics, cursor, y, offer.costA, mouseX, mouseY)
        if (!offer.costB.isEmpty) cursor = drawStack(guiGraphics, cursor, y, offer.costB, mouseX, mouseY)

        small(guiGraphics, ARROW_TEXT, cursor + 1, y + 5, SUBTLE)
        cursor += 12

        val resultX = cursor
        cursor = drawStack(guiGraphics, cursor, y, offer.result, mouseX, mouseY)
        small(
            guiGraphics, offer.result.hoverName, cursor + 2, y + 5,
            if (offer.outOfStock) DIM else TEXT
        )

        if (offer.outOfStock) small(guiGraphics, OUT_OF_STOCK_TEXT, resultX, y + 13, DIM)
    }

    /**
     * Draws one stack with its count, gives it the ordinary item tooltip on hover, and returns the x to carry
     * on from.
     *
     * The tooltip is the whole reason the wire carries stacks rather than item ids: it is vanilla's own, built
     * from the stack's data components, so an enchanted sword lists its enchantments here exactly as it does in
     * the trade screen -- and it will go on doing so for potions, trims and anything else a component ever
     * describes, without this screen learning about any of them.
     */
    private fun drawStack(
        guiGraphics: GuiGraphics, x: Int, y: Int, stack: ItemStack, mouseX: Int, mouseY: Int
    ): Int {
        if (stack.isEmpty) return x
        guiGraphics.renderFakeItem(stack, x, y)
        guiGraphics.renderItemDecorations(font, stack, x, y)
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            guiGraphics.setTooltipForNextFrame(font, stack, mouseX, mouseY)
        }
        return x + 18
    }

    // endregion

    // region helpers

    private fun panel(guiGraphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        guiGraphics.fill(x, y, x + w, y + h, PANEL_BORDER)
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL_BG)
    }

    /** Small text, drawn through the 2D GUI matrix; screen coords divide by the scale. */
    private fun small(guiGraphics: GuiGraphics, text: Component, x: Int, y: Int, color: Int) {
        val pose = guiGraphics.pose()
        pose.pushMatrix()
        pose.scale(SMALL, SMALL)
        guiGraphics.drawString(font, text, Math.round(x / SMALL), Math.round(y / SMALL), color, false)
        pose.popMatrix()
    }

    /**
     * What to call a profession.
     *
     * `none` is spelled out as "Unemployed" rather than passed through: vanilla's own component for it reads
     * "Villager", which in a list of villagers says nothing at all.
     */
    private fun professionName(id: String): Component {
        if (id == CrewManifest.NO_PROFESSION) return UNEMPLOYED_TEXT
        val key = Identifier.tryParse(id) ?: return UNEMPLOYED_TEXT
        return BuiltInRegistries.VILLAGER_PROFESSION.getOptional(key)
            .map { it.name() }
            .orElse(Component.literal(key.path))
    }

    private fun rankName(level: Int): Component =
        Component.translatable("gui.vs_eureka.crew_level.${level.coerceIn(1, 5)}")

    // endregion

    companion object {

        /** Open a manifest, or fold a fresh one into the manifest already on screen for that same wheel. */
        fun open(snapshot: CrewManifest.Snapshot) {
            val mc = Minecraft.getInstance()
            val current = mc.screen
            if (current is CrewManifestScreen && current.snapshot.helm == snapshot.helm) {
                current.refresh(snapshot)
            } else {
                mc.setScreen(CrewManifestScreen(snapshot))
            }
        }

        fun acceptDetail(detail: CrewManifest.Detail) {
            (Minecraft.getInstance().screen as? CrewManifestScreen)?.applyDetail(detail)
        }

        private val TITLE: Component = Component.translatable("gui.vs_eureka.crew_manifest")
        private val LOCKED_TEXT: Component = Component.translatable("gui.vs_eureka.crew_berth_locked")
        private val LOCKED_HINT: Component = Component.translatable("gui.vs_eureka.crew_berth_hint")
        private val EMPTY_TEXT: Component = Component.translatable("gui.vs_eureka.crew_berth_empty")
        private val RENAME_TEXT: Component = Component.translatable("gui.vs_eureka.crew_rename")
        private val BACK_TEXT: Component = Component.translatable("gui.vs_eureka.crew_back")
        private val SELLS_TEXT: Component = Component.translatable("gui.vs_eureka.crew_sells")
        private val NO_TRADES_TEXT: Component = Component.translatable("gui.vs_eureka.crew_no_trades")
        private val LOADING_TEXT: Component = Component.translatable("gui.vs_eureka.crew_loading")
        private val OUT_OF_STOCK_TEXT: Component = Component.translatable("gui.vs_eureka.crew_out_of_stock")
        private val ASSIGNMENT_TEXT: Component = Component.translatable("gui.vs_eureka.crew_assignment")
        private val STATION_TEXT: Component = Component.translatable("gui.vs_eureka.crew_station")
        private val UNEMPLOYED_TEXT: Component = Component.translatable("entity.vs_eureka.villager.unemployed")
        private val INFO_GLYPH: Component = Component.literal("i")
        private val ARROW_TEXT: Component = Component.literal("→")

        // Panel. Wide enough for a name, a profession-and-rank line and the card's trade rows without wrapping;
        // eight rows tall, which keeps the whole thing on screen at GUI scale 3 on a 1080p display.
        private const val PANEL_W = 300
        private const val PANEL_H = 214
        private const val LIST_TOP = 22
        private const val ROW_H = 22
        private const val VISIBLE_ROWS = 8
        private const val LIST_BOTTOM = LIST_TOP + ROW_H * VISIBLE_ROWS

        private const val CARD_W = 260
        private const val CARD_H = 178
        private const val CARD_PAD = 8
        private const val NAME_BOX_H = 14

        /**
         * Pixels reserved at the right of the header for the berth counter.
         *
         * Both the name box and the click target that opens it stop short of this, so "3/8 berths" stays
         * readable while renaming and can never be mistaken for part of the name.
         */
        private const val BERTHS_GUTTER = 76
        private const val RENAME_BTN_W = 44
        private const val BACK_BTN_W = 44
        private const val BACK_BTN_H = 14
        private const val OFFER_H = 20

        /** The square the head is drawn in, one pixel inside the row. */
        private const val ICON_SIZE = 20

        /**
         * Roughly pixels per block. The vanilla inventory frames a whole 1.8-block player at 30; the same
         * number over a 20-pixel window shows about two thirds of a block, which is a villager's head with a
         * little room around it.
         */
        private const val HEAD_SCALE = 30

        /**
         * How far up the villager to look, in blocks above the point the vanilla helper would centre on.
         * That point is the middle of the mob; a villager stands 1.95 tall and wears their face around 1.75,
         * so this is most of the difference.
         */
        private const val HEAD_RAISE = 0.78f

        // Fallback icon: the 8x10 front face of the villager head cube, drawn at 2x. BASE_SKIN is the only one
        // of the three layers that carries a face in VANILLA -- see drawHead for why that matters.
        private val BASE_SKIN: Identifier =
            Identifier.withDefaultNamespace("textures/entity/villager/villager.png")
        private const val HEAD_ZOOM = 2.0f
        private const val HEAD_U = 8.0f
        private const val HEAD_V = 8.0f
        private const val HAT_U = 40.0f
        private const val HEAD_W = 8
        private const val HEAD_H = 10
        private const val SKIN_TEX = 64

        private const val SMALL = 0.7f

        // Palette lifted from ShipHelmScreen so the two menus read as one mod, with the crew accent added.
        private const val PANEL_BORDER = 0xFF000000.toInt()
        private const val PANEL_BG = 0xFFC6C6C6.toInt()
        private const val SEPARATOR = 0xFF8B8B8B.toInt()
        private const val ROW_BG = 0xFFB8B8B8.toInt()
        private const val ROW_HOVER = 0xFFD8D8D8.toInt()
        private const val ROW_LOCKED = 0xFFA0A0A0.toInt()
        private const val CARD_SCRIM = 0xB0000000.toInt()
        private const val TEXT = 0xFF404040.toInt()
        private const val SUBTLE = 0xFF5A5A5A.toInt()
        private const val DIM = 0xFF7A7A7A.toInt()

        /** The same dark cyan the crew nameplates wear, so the two halves of the feature look related. */
        private const val ACCENT = 0xFF2A8FA6.toInt()
    }
}
