package org.valkyrienskies.eureka.fabric.client.shipwright

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.KeyEvent
import org.valkyrienskies.eureka.fabric.ShipwrightNetworkingFabric
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmCheckbox
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmButton
import org.valkyrienskies.eureka.shipwright.MaterialFamilies
import org.valkyrienskies.eureka.shipwright.ShipwrightMenu
import org.valkyrienskies.mod.client.ShipGamepad
import kotlin.math.abs

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

    /** Which hull's card is open, by slug. Same reasoning. */
    private var openVessel: String? = null

    /** Which half of the book is showing. */
    private var onYard = false

    /** Whether the plans picker is expanded over the yard card. */
    private var dropdownOpen = false

    /**
     * The material row whose popup is open, if any -- a third depth below the plans card.
     *
     * Held as the ITEM rather than an index, for the same reason [openCard] holds a name: a refresh can
     * reorder or shorten the list under an open popup, and an index would then quietly point at something
     * else. An item that has left the bill resolves to null and the popup closes itself.
     */
    private var openMaterial: Item? = null
    private var swapOpen = false

    /** The swap list scrolls on its own: a family is routinely ninety items long. */
    private var swapScroll = 0

    /** Which broken-up ship's claim list is open, by name. Same reasoning as [openCard]. */
    private var openPile: String? = null

    /** 0 = what it was built from, 1 = what it carried, 2 = what could not be counted. */
    private var claimTab = 0

    /** Whether a claim should fill the captain's empty shulker boxes first. Their choice, not the server's. */
    private var useShulkers = false

    /** The row a Dismiss has been pressed on once. Throwing a ship's worth of timber away asks twice. */
    private var dismissArmed: Item? = null

    /** The same, for the Kept tab, which is addressed by position rather than by item. */
    private var keepArmed: Int? = null

    /** Whether Dismiss All has been pressed once. Emptying a whole list asks twice, like everything else here. */
    private var dismissAllArmed = false

    /** Whether Dismantle has been pressed once. Same reason, and the more serious of the two. */
    private var dismantleArmed = false

    /** Whether the Save-as-New prompt is up, and what has been typed into it so far. */
    private var naming = false
    private var nameValue = ""
    private var nameBox: EditBox? = null

    private val card: ShipwrightMenu.Row?
        get() = openCard?.let { name -> shelf.rows.firstOrNull { it.shipName == name } }

    /** The open popup's row, re-resolved every frame so a refresh cannot leave it stale. */
    private val material: ShipwrightMenu.Material?
        get() = openMaterial?.let { item ->
            val row = card ?: return@let null
            (row.materials + row.struck).firstOrNull { it.item == item }
        }

    private val vessel: ShipwrightMenu.Vessel?
        get() = openVessel?.let { slug -> shelf.vessels.firstOrNull { it.slug == slug } }

    private val pile: ShipwrightMenu.Pile?
        get() = openPile?.let { name -> shelf.salvage.firstOrNull { it.shipName == name } }

    /** The rows on the open tab. Empty for the keepsakes, which are a count rather than a list. */
    private fun claimRows(pile: ShipwrightMenu.Pile): List<ShipwrightMenu.Material> = when (claimTab) {
        1 -> pile.cargo
        2 -> emptyList()
        else -> pile.hull
    }

    override fun init() {
        left = (width - PANEL_W) / 2
        top = (height - PANEL_H) / 2
        clampScroll()

        // The two halves of the book. Always present, so switching between the shelf and the water is one
        // click from anywhere rather than a Back first -- unless this world's shipwrights take no repair
        // work, in which case the book has no Yard page and no tabs to switch between.
        // The Yard is not only about repair: it is also where a broken-up ship waits to be carried off. A
        // world with repair turned off but dismantling on still needs the page, and a captain with salvage
        // on the books needs it even if both are turned off after the fact -- otherwise their ship is in a
        // ledger they have no door to.
        if (!shelf.repairEnabled && !shelf.dismantleEnabled && shelf.salvage.isEmpty()) {
            onYard = false
            openVessel = null
            openPile = null
        } else {
            addRenderableWidget(
                ShipHelmButton(left + 4, top + PANEL_H - 22, TAB_W, BTN_H, PLANS_TAB, font) {
                    onYard = false; openVessel = null; scroll = 0; rebuild()
                }.also { it.active = onYard }
            )
            addRenderableWidget(
                ShipHelmButton(left + 6 + TAB_W, top + PANEL_H - 22, TAB_W, BTN_H, YARD_TAB, font) {
                    onYard = true; openCard = null; scroll = 0; rebuild()
                }.also { it.active = !onYard }
            )
        }

        if (onYard) {
            if (openPile != null) initClaim() else initYard()
            return
        }

        val row = card ?: return

        // A popup owns the panel outright while it is up -- see [initMaterial].
        if (openMaterial != null) {
            initMaterial(row)
            return
        }
        if (naming) {
            initNaming(row)
            return
        }

        // A row of their own, ABOVE the tab strip. Sharing that row put Build straight through the Yard tab:
        // they overlapped, and the sliver between the card's buttons fell through to the tab underneath, so
        // aiming between Build and Bottle switched tabs.
        val y = top + PANEL_H - ACTION_ROW

        // Build and Bottle only exist once a hull is paid for -- a button that is present but refuses is worse
        // than one that is not there, because the player has to press it to learn anything.
        var x = left + 8
        if (row.ready) {
            addRenderableWidget(
                ShipHelmButton(x, y, BTN_W, BTN_H, BUILD_TEXT, font) { act(ShipwrightMenu.Action.BUILD) }
            )
            x += BTN_W + 4

            // Greyed rather than hidden when there is no bottle: the option exists, and the reason it cannot be
            // taken is worth saying. The tooltip says it.
            addRenderableWidget(
                ShipHelmButton(x, y, BTN_W, BTN_H, BOTTLE_TEXT, font) {
                    act(ShipwrightMenu.Action.BOTTLE)
                }.also { it.active = shelf.hasFreeBottle }
            )
            x += BTN_W + 4
        } else {
            addRenderableWidget(
                ShipHelmButton(x, y, BTN_W, BTN_H, PAY_TEXT, font) { act(ShipwrightMenu.Action.PAY) }
            )
            x += BTN_W + 4
        }

        addRenderableWidget(
            ShipHelmButton(x, y, BTN_W, BTN_H, DELETE_TEXT, font) {
                act(ShipwrightMenu.Action.DELETE)
                openCard = null
            }
        )

        addRenderableWidget(
            ShipHelmButton(left + PANEL_W - 8 - BACK_W, y, BACK_W, BTN_H, BACK_TEXT, font) {
                openCard = null; rebuild()
            }
        )

        // A second row, for the things that act on the PAGE rather than on the ship: take a copy of it,
        // file the changes as their own design, or put it back the way the shipwright drew it.
        val alterY = y - BTN_H - 2
        var ax = left + 8

        // Greyed rather than hidden without a blank blueprint to write on, the same courtesy Bottle gets:
        // the option exists, and the reason it cannot be taken is worth saying.
        addRenderableWidget(
            ShipHelmButton(ax, alterY, BTN_W, BTN_H, TAKE_PAGE_TEXT, font) {
                act(ShipwrightMenu.Action.TAKE_BLUEPRINT)
            }.also { it.active = shelf.hasBlankBlueprint }
        )
        ax += BTN_W + 4

        // Only on an altered page. On an unaltered one "save as new" would file a duplicate and "reset"
        // would do nothing -- two buttons whose whole answer is that there was nothing to do.
        if (row.altered) {
            addRenderableWidget(
                ShipHelmButton(ax, alterY, BTN_W, BTN_H, SAVE_AS_NEW_TEXT, font) { beginNaming(row) }
            )
            ax += BTN_W + 4
            addRenderableWidget(
                ShipHelmButton(ax, alterY, BTN_W, BTN_H, RESET_TEXT, font) {
                    act(ShipwrightMenu.Action.RESET_ALTERATION)
                }
            )
        }
    }

    /**
     * The Save-as-New prompt: a box over the card's heading, and two buttons under it.
     *
     * Built INSTEAD of the card's own widgets, for the reason [initMaterial] gives -- and typed into a screen
     * field rather than read off the box at commit time, so a shelf arriving from the server mid-word does not
     * swallow what the captain has written.
     */
    private fun initNaming(row: ShipwrightMenu.Row) {
        nameBox = addRenderableWidget(
            EditBox(font, left + 6, top + 3, PANEL_W - 12, BTN_H, NAME_TEXT)
        ).also {
            it.setMaxLength(MAX_NAME)
            it.value = nameValue
            it.setResponder { typed -> nameValue = typed }
            it.isFocused = true
            this.focused = it
        }

        val y = top + PANEL_H - ACTION_ROW
        addRenderableWidget(
            ShipHelmButton(left + 8, y, BTN_W, BTN_H, SAVE_AS_NEW_TEXT, font) { commitNaming(send = true) }
        )
        addRenderableWidget(
            ShipHelmButton(left + PANEL_W - 8 - BACK_W, y, BACK_W, BTN_H, BACK_TEXT, font) {
                commitNaming(send = false)
            }
        )
    }

    /** Start naming a variant, seeded blank: the server picks a name of its own when nothing is typed. */
    private fun beginNaming(row: ShipwrightMenu.Row) {
        nameValue = ""
        naming = true
        rebuild()
    }

    private fun commitNaming(send: Boolean) {
        val name = openCard
        naming = false
        nameBox = null
        if (send && name != null) {
            ShipwrightNetworkingFabric.send(
                shelf.villager, ShipwrightMenu.Action.SAVE_AS_NEW, name, nameValue.trim()
            )
        }
        rebuild()
    }

    /**
     * The yard's card: a dropdown of the captain's plans, then the buttons that act on the choice.
     *
     * Repair is dead until plans are chosen, and stays dead if the shipwright has refused them -- a repair is
     * an instruction to make this hull match that page, and there is nothing sensible to do without one.
     */
    private fun initYard() {
        val hull = vessel ?: return
        val y = top + PANEL_H - ACTION_ROW

        // Opens a list rather than stepping to the next entry. A cycling button is fine for three states and
        // unusable for thirty-two: picking the last set of plans would mean thirty-one clicks past the ones
        // you did not want, with a server round trip on each.
        val label = hull.plansName ?: NO_PLANS.string
        addRenderableWidget(
            ShipHelmButton(left + 8, y, DROP_W, BTN_H, Component.literal(label), font) {
                dropdownOpen = !dropdownOpen
                rebuild()
            }.also { it.active = shelf.rows.isNotEmpty() }
        )

        if (dropdownOpen) {
            // Drawn upward from the button so a long list does not run off the bottom of the panel.
            val visible = minOf(shelf.rows.size, DROP_ROWS)
            for (i in 0 until visible) {
                val plans = shelf.rows[i]
                addRenderableWidget(
                    ShipHelmButton(
                        left + 8, y - (visible - i) * (BTN_H + 1), DROP_W, BTN_H,
                        Component.literal(plans.shipName), font
                    ) {
                        dropdownOpen = false
                        ShipwrightNetworkingFabric.send(
                            shelf.villager, ShipwrightMenu.Action.SELECT, hull.slug, plans.shipName
                        )
                    }.also { it.active = plans.shipName != hull.plansName }
                )
            }
        }

        val repairable = hull.plansName != null && hull.refusal == null && !hull.sound
        // With partial repair on, Repair goes live the moment ANYTHING is in the pot -- 1% funded or 100% --
        // and the shipwright mends keel-up as far as it stretches. Off, it waits for the whole bill as before.
        val funded = if (shelf.partialRepair) hull.given > 0 else hull.paid
        addRenderableWidget(
            ShipHelmButton(left + 12 + DROP_W, y, BTN_W, BTN_H, REPAIR_TEXT, font) {
                ShipwrightNetworkingFabric.send(shelf.villager, ShipwrightMenu.Action.REPAIR, hull.slug)
            }.also { it.active = repairable && funded }
        )

        addRenderableWidget(
            ShipHelmButton(
                left + PANEL_W - 8 - BTN_W, y, BTN_W, BTN_H, PAY_TEXT, font
            ) {
                ShipwrightNetworkingFabric.send(shelf.villager, ShipwrightMenu.Action.PAY_REPAIR, hull.slug)
            }.also { it.active = repairable && !hull.paid }
        )

        // Not while the plans list is open over it: the dropdown draws upward across this row, and a button
        // that has something painted on top of it still takes the click.
        if (shelf.dismantleEnabled && !dropdownOpen) {
            addRenderableWidget(
                ShipHelmButton(
                    left + 8, y - BTN_H - 2, BTN_W + 24, BTN_H,
                    if (dismantleArmed) REALLY_TEXT else DISMANTLE_TEXT, font
                ) {
                    if (!dismantleArmed) {
                        dismantleArmed = true
                        rebuild()
                    } else {
                        dismantleArmed = false
                        ShipwrightNetworkingFabric.send(
                            shelf.villager, ShipwrightMenu.Action.DISMANTLE, hull.slug
                        )
                        openVessel = null
                        scroll = 0
                    }
                }.also { it.active = canPayFee(hull) }
            )
        }

        addRenderableWidget(
            ShipHelmButton(
                left + PANEL_W - 8 - BACK_W, top + PANEL_H - 22, BACK_W, BTN_H, BACK_TEXT, font
            ) { openVessel = null; dismantleArmed = false; scroll = 0; rebuild() }
        )
    }

    /**
     * The claim list: two tabs of counted materials, one of keepsakes, and the buttons that carry them off.
     *
     * The per-row Dismiss is NOT a widget. Thirty rebuilt buttons per scroll tick, positioned against a clip
     * rectangle they may fall outside of, is the arrangement this screen already decided against for the
     * material rows -- so a Dismiss is a rectangle in the right-hand gutter, hit-tested in [mouseClicked]
     * beside the row it belongs to.
     */
    private fun initClaim() {
        val open = pile ?: run { openPile = null; return }
        val y = top + PANEL_H - ACTION_ROW

        addRenderableWidget(
            ShipHelmButton(left + 8, y, BTN_W + 8, BTN_H, CLAIM_ALL_TEXT, font) {
                ShipwrightNetworkingFabric.send(
                    shelf.villager, ShipwrightMenu.Action.CLAIM_ALL, open.shipName, tabArgument()
                )
            }.also { it.active = if (claimTab == 2) open.keepsakes.isNotEmpty() else true }
        )

        // A checkbox rather than a setting: whether to spend your boxes on this pile is a decision per pile,
        // and it is the captain's pack that pays for it.
        addRenderableWidget(
            ShipHelmCheckbox(left + 20 + BTN_W, y + 2, 92, SHULKER_TEXT, font, { useShulkers }) {
                useShulkers = !useShulkers
                rebuild()
            }
        )

        val tabY = y - BTN_H - 2
        val labels = listOf(HULL_TAB, CARGO_TAB, KEPT_TAB)
        for (i in labels.indices) {
            addRenderableWidget(
                ShipHelmButton(left + 8 + i * (TAB_W + 4), tabY, TAB_W, BTN_H, labels[i], font) {
                    claimTab = i
                    dismissArmed = null
                    keepArmed = null
                    dismissAllArmed = false
                    scroll = 0
                    rebuild()
                }.also { it.active = claimTab != i }
            )
        }

        // Beside the tabs rather than beside Claim All, because it acts on the TAB -- and because putting
        // "throw all of this away" next to "carry all of this home" is asking for a misclick.
        val loaded = if (claimTab == 2) open.keepsakes.isNotEmpty() else claimRows(open).isNotEmpty()
        addRenderableWidget(
            ShipHelmButton(
                left + PANEL_W - 8 - DISMISS_ALL_W, tabY, DISMISS_ALL_W, BTN_H,
                if (dismissAllArmed) REALLY_TEXT else DISMISS_ALL_TEXT, font
            ) {
                if (!dismissAllArmed) {
                    dismissAllArmed = true
                    rebuild()
                } else {
                    dismissAllArmed = false
                    ShipwrightNetworkingFabric.send(
                        shelf.villager, ShipwrightMenu.Action.SALVAGE_DISMISS_ALL, open.shipName,
                        tabArgument()
                    )
                }
            }.also { it.active = loaded }
        )

        addRenderableWidget(
            ShipHelmButton(
                left + PANEL_W - 8 - BACK_W, top + PANEL_H - 22, BACK_W, BTN_H, BACK_TEXT, font
            ) { openPile = null; dismissArmed = null; keepArmed = null; dismissAllArmed = false; scroll = 0; rebuild() }
        )
    }

    /** The tab, plus the shulker flag, in the one string the wire carries for it. */
    private fun tabArgument(): String {
        val tab = when (claimTab) {
            1 -> "cargo"
            2 -> "keep"
            else -> "hull"
        }
        return if (useShulkers && claimTab != 2) "$tab+box" else tab
    }

    /** Tell the shipwright the book is shut, so he stops standing at his counter waiting on us. */
    override fun removed() {
        ShipwrightNetworkingFabric.send(shelf.villager, ShipwrightMenu.Action.CLOSED, "")
        super.removed()
    }

    override fun isPauseScreen(): Boolean = false

    /**
     * The material popup's controls: exclude, swap, and the way back.
     *
     * Built INSTEAD of the plans card's own buttons, not alongside them. super.render paints widgets
     * last and super.mouseClicked answers them first, so a Build button left behind under this popup
     * would still be visible and still be clickable through it.
     */
    private fun initMaterial(row: ShipwrightMenu.Row) {
        val material = material ?: run { openMaterial = null; return }
        val y = top + PANEL_H - ACTION_ROW
        val struck = material.needed <= 0

        // Exclude. Structure is never optional and says so by being greyed rather than by being absent --
        // a control that vanishes teaches nobody why.
        val canExclude = shelf.excludeEnabled && material.excludable
        addRenderableWidget(
            ShipHelmCheckbox(
                left + 8, top + LIST_TOP + 6, 110, EXCLUDE_TEXT, font, { struck }
            ) {
                ShipwrightNetworkingFabric.send(
                    shelf.villager, ShipwrightMenu.Action.EXCLUDE_ITEM, row.shipName,
                    BuiltInRegistries.ITEM.getKey(material.item).toString()
                )
            }.also { it.active = canExclude }
        )

        // Swap. Blocked outright for hull materials unless the shipwright has been told otherwise.
        val canSwap = shelf.swapEnabled && material.family != null &&
            (material.excludable || shelf.swapFoundational)
        val head = material.swappedFrom?.let { ItemStack(material.item).hoverName.string }
            ?: SWAP_TEXT.string
        addRenderableWidget(
            ShipHelmButton(
                left + 8, top + LIST_TOP + 24, DROP_W, BTN_H, Component.literal(head), font
            ) {
                swapOpen = !swapOpen
                swapScroll = 0
                rebuild()
            }.also { it.active = canSwap && !struck }
        )

        if (swapOpen && canSwap) initSwapList(row, material)

        addRenderableWidget(
            ShipHelmButton(
                left + PANEL_W - 8 - BACK_W, y, BACK_W, BTN_H, BACK_TEXT, font
            ) {
                openMaterial = null
                swapOpen = false
                rebuild()
            }
        )
    }

    /**
     * The replacement list, built here on the client and nowhere else.
     *
     * The server sends the row's FAMILY and stops there. Item tags are synced to every client at login and
     * the captain's own pack is client-side, so the list and the greying can both be worked out here --
     * where sending them would mean a few hundred entries riding on every row of every refresh.
     *
     * Unlike the yard's plans dropdown, which truncates at eight and shrugs, this one scrolls: a family is
     * routinely ninety items long and the ninetieth is not less real than the first.
     */
    private fun initSwapList(row: ShipwrightMenu.Row, material: ShipwrightMenu.Material) {
        val player = minecraft?.player ?: return
        val creative = player.abilities.instabuild
        val origin = material.swappedFrom ?: material.item

        val options = ArrayList<Pair<Component, String>>()
        options.add(SWAP_ANY_TEXT to "*${material.family}")
        for (candidate in MaterialFamilies.replacementsFor(origin)) {
            options.add(
                ItemStack(candidate).hoverName to BuiltInRegistries.ITEM.getKey(candidate).toString()
            )
        }
        if (material.swapped) options.add(SWAP_ORIGINAL_TEXT to "")

        swapScroll = swapScroll.coerceIn(0, maxOf(0, options.size - DROP_ROWS))
        val head = top + LIST_TOP + 24
        val visible = minOf(options.size - swapScroll, DROP_ROWS)
        for (i in 0 until visible) {
            val (label, argument) = options[swapScroll + i]
            // Greyed when the captain holds none of it -- the offer is still shown, because "you cannot
            // have this" and "this does not exist" are different things and a blank list says the wrong one.
            // Greyed when the captain holds none of it, and when it is already what the row is built from --
            // the yard dropdown marks its current entry the same way.
            val current = argument == BuiltInRegistries.ITEM.getKey(material.item).toString()
            val holds = !current && (
                creative || argument.startsWith("*") || argument.isEmpty() ||
                    heldCount(player, argument) > 0
                )
            addRenderableWidget(
                ShipHelmButton(
                    left + 8 + DROP_W + 4, head + i * (BTN_H + 1), DROP_W + 20, BTN_H, label, font
                ) {
                    swapOpen = false
                    ShipwrightNetworkingFabric.send(
                        shelf.villager, ShipwrightMenu.Action.SWAP, row.shipName,
                        BuiltInRegistries.ITEM.getKey(origin).toString(), argument
                    )
                }.also { it.active = holds }
            )
        }
    }

    /** How many of [id] the captain is carrying. Client-side: their own pack is not a server secret. */
    private fun heldCount(player: net.minecraft.world.entity.player.Player, id: String): Int {
        val item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null) ?: return 0
        var total = 0
        for (slot in 0 until player.inventory.containerSize) {
            val stack = player.inventory.getItem(slot)
            if (stack.item == item) total += stack.count
        }
        return total
    }

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
        // A hull that has sailed off, been disassembled or bottled takes its card with it.
        if (openVessel != null && next.vessels.none { it.slug == openVessel }) openVessel = null

        // A popup outlives a refresh -- swapping a material IS a refresh, and closing the popup the captain
        // is working in every time they use it would make it unusable. It closes only when the row it was
        // opened on is no longer on the page at all, and the name prompt goes with the card it names.
        if (openMaterial != null && material == null) {
            openMaterial = null
            swapOpen = false
        }
        if (naming && openCard == null) {
            naming = false
            nameBox = null
        }
        // A pile that has been carried off entirely stops existing, and its list closes with it.
        if (openPile != null && next.salvage.none { it.shipName == openPile }) {
            openPile = null
            dismissArmed = null; keepArmed = null; dismissAllArmed = false
        }
        dismantleArmed = false
        dropdownOpen = false
        clampScroll()
        rebuild()
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        // NOT renderBackground: 1.21.11 calls it for us ahead of this, and calling it again draws the dim twice.
        panel(guiGraphics, left, top, PANEL_W, PANEL_H)

        if (onYard) {
            val open = pile
            val hull = vessel
            when {
                open != null -> drawClaim(guiGraphics, open, mouseX, mouseY)
                hull != null -> drawVessel(guiGraphics, hull)
                else -> drawYard(guiGraphics, mouseX, mouseY)
            }
        } else {
            val row = card
            when {
                row == null -> drawShelf(guiGraphics, mouseX, mouseY)
                // A popup replaces the list it came from rather than floating over it: the panel has no
                // layering, and a painted overlay under a real widget loses both its pixels and its clicks.
                material != null -> drawMaterialCard(guiGraphics, row, material!!)
                naming -> drawNaming(guiGraphics, row)
                else -> drawCard(guiGraphics, row, mouseX, mouseY)
            }
        }

        super.render(guiGraphics, mouseX, mouseY, partialTicks)

        val row = if (onYard) null else card
        if (row != null && row.ready && !shelf.hasFreeBottle) {
            // Only said where it matters: hovering the greyed button.
            val bx = left + 8 + BTN_W + 4
            val by = top + PANEL_H - ACTION_ROW
            if (mouseX in bx..(bx + BTN_W) && mouseY in by..(by + BTN_H)) {
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
            drawShelfRow(guiGraphics, shelf.rows[index], top + LIST_TOP + i * ROW_H, mouseX, mouseY, index == padSel)
        }
        guiGraphics.disableScissor()
        drawScrollbar(guiGraphics, shelf.rows.size, VISIBLE_ROWS, LIST_BOTTOM)
    }

    private fun drawShelfRow(
        guiGraphics: GuiGraphics,
        row: ShipwrightMenu.Row,
        y: Int,
        mouseX: Int,
        mouseY: Int,
        selected: Boolean
    ) {
        val hovered = selected ||
            mouseX >= left + 4 && mouseX <= left + PANEL_W - 8 &&
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

    // ---- The yard: hulls the shipwright can see -----------------------------------------------------------

    private fun drawYard(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        guiGraphics.drawString(font, YARD_TITLE, left + 8, top + 6, ACCENT, false)
        val count = Component.literal("${shelf.vessels.size}")
        guiGraphics.drawString(font, count, left + PANEL_W - 8 - font.width(count), top + 6, DIM, false)
        small(guiGraphics, YARD_HINT, left + 8, top + 22, DIM)
        guiGraphics.fill(left + 4, top + LIST_TOP - 3, left + PANEL_W - 4, top + LIST_TOP - 2, ACCENT)

        if (shelf.vessels.isEmpty() && shelf.salvage.isEmpty()) {
            small(guiGraphics, NO_SHIPS, left + 8, top + LIST_TOP + 8, DIM)
            return
        }

        // Broken-up ships sit at the bottom of the same list rather than behind a third tab. They belong to
        // the yard -- a pile of a ship is a thing in front of the bench, same as a hull in the water is --
        // and a tab that is empty in almost every session is a tab nobody learns.
        val total = shelf.vessels.size + shelf.salvage.size
        guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + LIST_BOTTOM)
        for (i in 0 until VISIBLE_ROWS) {
            val index = scroll + i
            if (index >= total) break
            val y = top + LIST_TOP + i * ROW_H
            if (index < shelf.vessels.size) {
                drawYardRow(guiGraphics, shelf.vessels[index], y, mouseX, mouseY, index == padSel)
            } else {
                drawPileRow(guiGraphics, shelf.salvage[index - shelf.vessels.size], y, mouseX, mouseY, index == padSel)
            }
        }
        guiGraphics.disableScissor()
        drawScrollbar(guiGraphics, total, VISIBLE_ROWS, LIST_BOTTOM)
    }

    /** A pile in the yard list: the ship's name, struck through by its being past tense, and what is left of it. */
    private fun drawPileRow(
        guiGraphics: GuiGraphics,
        open: ShipwrightMenu.Pile,
        y: Int,
        mouseX: Int,
        mouseY: Int,
        selected: Boolean
    ) {
        val hovered = selected ||
            mouseX >= left + 4 && mouseX <= left + PANEL_W - 8 &&
            mouseY >= y && mouseY < y + ROW_H && mouseY >= top + LIST_TOP && mouseY < top + LIST_BOTTOM
        if (hovered) guiGraphics.fill(left + 4, y, left + PANEL_W - 8, y + ROW_H - 1, ROW_HOVER)

        guiGraphics.drawString(font, Component.literal(open.shipName), left + 10, y + 4, DIM, false)
        val state = Component.literal("${fmt(open.items)} ${SALVAGE_MARK.string}")
        small(guiGraphics, state, left + PANEL_W - 14 - (font.width(state) * SMALL).toInt(), y + 7, ACCENT)
    }

    /**
     * A claim list: the tab's rows, each with a Dismiss in the gutter.
     *
     * Counts are drawn plainly rather than through [drawMaterial], because "4096 / 4096" is the wrong shape
     * for a pile -- there is nothing outstanding here, only a number waiting to be carried.
     */
    private fun drawClaim(
        guiGraphics: GuiGraphics,
        open: ShipwrightMenu.Pile,
        mouseX: Int,
        mouseY: Int
    ) {
        guiGraphics.drawString(font, Component.literal(open.shipName), left + 8, top + 6, ACCENT, false)
        val state = Component.literal("${fmt(open.items)} items")
        guiGraphics.drawString(font, state, left + PANEL_W - 8 - font.width(state), top + 6, DIM, false)
        small(guiGraphics, CLAIM_HINT, left + 8, top + 22, DIM)
        small(
            guiGraphics,
            Component.literal(
                "${fmt(open.hull.sumOf { it.needed })} hull  -  " +
                    "${fmt(open.cargo.sumOf { it.needed })} cargo  -  ${open.keepsakes.size} kept"
            ),
            left + 8, top + 34, TEXT
        )
        guiGraphics.fill(left + 4, top + LIST_TOP - 3, left + PANEL_W - 4, top + LIST_TOP - 2, ACCENT)

        if (claimTab == 2) {
            if (open.keepsakes.isEmpty()) {
                small(guiGraphics, NOTHING_KEPT, left + 8, top + LIST_TOP + 8, DIM)
                return
            }
            guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + CLAIM_BOTTOM)
            for (i in 0 until CLAIM_ROWS) {
                val index = scroll + i
                if (index >= open.keepsakes.size) break
                drawKeepRow(guiGraphics, open.keepsakes[index], top + LIST_TOP + i * ROW_H, mouseX, mouseY)
            }
            guiGraphics.disableScissor()
            drawScrollbar(guiGraphics, open.keepsakes.size, CLAIM_ROWS, CLAIM_BOTTOM)
            return
        }

        val rows = claimRows(open)
        if (rows.isEmpty()) {
            small(guiGraphics, NOTHING_LEFT, left + 8, top + LIST_TOP + 8, DIM)
            return
        }

        guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + CLAIM_BOTTOM)
        for (i in 0 until CLAIM_ROWS) {
            val index = scroll + i
            if (index >= rows.size) break
            drawClaimRow(guiGraphics, rows[index], top + LIST_TOP + i * ROW_H, mouseX, mouseY)
        }
        guiGraphics.disableScissor()
        drawScrollbar(guiGraphics, rows.size, CLAIM_ROWS, CLAIM_BOTTOM)
    }

    /**
     * [name], cut to [room] pixels with an ellipsis if it will not fit.
     *
     * Cheaper than a marquee and steadier to read on a list: "Incendiary Netherite Cannonball" is thirty-one
     * characters against a gutter that holds about twenty, and left alone it drew straight through the count
     * beside it. A row you cannot read the number on is worse than a row with a shortened name.
     */
    private fun clip(name: String, room: Int): String {
        if (room <= 0 || font.width(name) <= room) return name
        val ellipsis = "..."
        val cut = font.plainSubstrByWidth(name, maxOf(0, room - font.width(ellipsis)))
        return if (cut.isEmpty()) ellipsis else cut.trimEnd() + ellipsis
    }

    /**
     * One kept stack: what it is, and the line saying why it could not be counted.
     *
     * Two lines rather than one, because "Shulker Box" on its own is exactly the information that made
     * this tab useless -- four identical boxes with four different cargoes inside them.
     */
    private fun drawKeepRow(
        guiGraphics: GuiGraphics,
        kept: ShipwrightMenu.Keepsake,
        y: Int,
        mouseX: Int,
        mouseY: Int
    ) {
        val onRow = mouseX >= left + 4 && mouseX <= left + PANEL_W - 8 &&
            mouseY >= y && mouseY < y + ROW_H && mouseY >= top + LIST_TOP && mouseY < top + CLAIM_BOTTOM
        val onDismiss = onRow && mouseX >= left + PANEL_W - 8 - DISMISS_W
        val armed = keepArmed == kept.index
        when {
            armed -> guiGraphics.fill(left + 4, y, left + PANEL_W - 8, y + ROW_H - 1, ARMED_ROW)
            onRow -> guiGraphics.fill(left + 4, y, left + PANEL_W - 8, y + ROW_H - 1, ROW_HOVER)
        }

        val stack = ItemStack(kept.item, kept.count)
        guiGraphics.renderItem(stack, left + 8, y + 1)
        guiGraphics.renderItemDecorations(font, stack, left + 8, y + 1)

        val room = (left + PANEL_W - 14 - DISMISS_W) - (left + 30)
        guiGraphics.drawString(
            font, Component.literal(clip(stack.hoverName.string, room)), left + 30, y + 1,
            if (armed) STRUCK else TEXT, false
        )
        small(guiGraphics, Component.literal(kept.label), left + 30, y + 11, DIM)

        val label = if (armed) REALLY_TEXT else DISMISS_TEXT
        small(
            guiGraphics, label,
            left + PANEL_W - 12 - (font.width(label) * SMALL).toInt(), y + 7,
            when {
                armed -> STRUCK
                onDismiss -> TEXT
                else -> DIM
            }
        )
    }

    private fun drawClaimRow(
        guiGraphics: GuiGraphics,
        material: ShipwrightMenu.Material,
        y: Int,
        mouseX: Int,
        mouseY: Int
    ) {
        val onRow = mouseX >= left + 4 && mouseX <= left + PANEL_W - 8 &&
            mouseY >= y && mouseY < y + ROW_H && mouseY >= top + LIST_TOP && mouseY < top + CLAIM_BOTTOM
        val onDismiss = onRow && mouseX >= left + PANEL_W - 8 - DISMISS_W
        val armed = dismissArmed == material.item

        // An armed row is washed red across its whole width. The word in the gutter changes too, but a
        // four-letter change in small dim text at the far edge of the panel is a thing you can click past
        // without ever seeing -- and a confirm nobody notices is a confirm that never fires.
        when {
            armed -> guiGraphics.fill(left + 4, y, left + PANEL_W - 8, y + ROW_H - 1, ARMED_ROW)
            onRow -> guiGraphics.fill(left + 4, y, left + PANEL_W - 8, y + ROW_H - 1, ROW_HOVER)
        }

        val stack = ItemStack(material.item)
        guiGraphics.renderItem(stack, left + 8, y + 1)

        // The count is placed first, so the name can be told how much room is actually left for it.
        val tally = Component.literal(fmt(material.needed))
        val tallyX = left + PANEL_W - 16 - DISMISS_W - font.width(tally)
        guiGraphics.drawString(font, tally, tallyX, y + 5, ACCENT, false)
        guiGraphics.drawString(
            font, Component.literal(clip(stack.hoverName.string, tallyX - 6 - (left + 30))),
            left + 30, y + 5, if (armed) STRUCK else TEXT, false
        )

        val label = if (armed) REALLY_TEXT else DISMISS_TEXT
        small(
            guiGraphics, label,
            left + PANEL_W - 12 - (font.width(label) * SMALL).toInt(), y + 7,
            when {
                armed -> STRUCK
                onDismiss -> TEXT
                else -> DIM
            }
        )
    }

    private fun drawYardRow(
        guiGraphics: GuiGraphics,
        hull: ShipwrightMenu.Vessel,
        y: Int,
        mouseX: Int,
        mouseY: Int,
        selected: Boolean
    ) {
        val hovered = selected ||
            mouseX >= left + 4 && mouseX <= left + PANEL_W - 8 &&
            mouseY >= y && mouseY < y + ROW_H && mouseY >= top + LIST_TOP && mouseY < top + LIST_BOTTOM
        if (hovered) guiGraphics.fill(left + 4, y, left + PANEL_W - 8, y + ROW_H - 1, ROW_HOVER)

        // Children are marked rather than indented: an armada can be twelve deep and indentation would run out
        // of panel long before the fleet ran out of ships.
        val name = if (hull.child) "  ${hull.slug}" else hull.slug
        guiGraphics.drawString(font, Component.literal(name), left + 10, y + 4, TEXT, false)

        val state = when {
            hull.plansName == null -> NO_MATCH
            hull.refusal != null -> REFUSED
            hull.sound -> SOUND
            hull.paid -> READY_TEXT
            else -> Component.literal("${(hull.progress * 100).toInt()}%")
        }
        val colour = when {
            hull.plansName == null || hull.refusal != null -> DIM
            hull.sound || hull.paid -> READY
            else -> ACCENT
        }
        small(guiGraphics, state, left + PANEL_W - 14 - (font.width(state) * SMALL).toInt(), y + 7, colour)
    }

    private fun drawVessel(guiGraphics: GuiGraphics, hull: ShipwrightMenu.Vessel) {
        guiGraphics.drawString(font, Component.literal(hull.slug), left + 8, top + 6, ACCENT, false)
        val size = Component.literal("${hull.width} x ${hull.height} x ${hull.length}")
        guiGraphics.drawString(font, size, left + PANEL_W - 8 - font.width(size), top + 6, DIM, false)

        val fuel = if (hull.fuel < 0f) NO_ENGINES.string else "${(hull.fuel * 100).toInt()}% fuel"
        small(
            guiGraphics,
            Component.literal("${fmt(hull.blocks)} blocks  -  ${fmtKg(hull.mass)}  -  $fuel"),
            left + 8, top + 20, TEXT
        )

        drawFee(guiGraphics, hull)

        val line = when {
            hull.plansName == null -> NO_MATCH.string
            hull.refusal != null -> "${(hull.match * 100).toInt()}% matches -- refused"
            hull.sound -> SOUND.string
            else -> "${(hull.match * 100).toInt()}% matches  -  ${fmt(hull.given)} of ${fmt(hull.needed)} delivered"
        }
        small(
            guiGraphics, Component.literal(line), left + 8, top + 30,
            if (hull.refusal != null || hull.plansName == null) DIM else if (hull.sound) READY else ACCENT
        )
        hull.refusal?.let { small(guiGraphics, Component.literal(it), left + 8, top + 40, DIM) }
        guiGraphics.fill(left + 4, top + LIST_TOP - 3, left + PANEL_W - 4, top + LIST_TOP - 2, ACCENT)

        val owed = hull.repairs.filter { it.outstanding > 0 }
        if (owed.isEmpty()) {
            small(guiGraphics, if (hull.sound) SOUND else REPAIR_PAID, left + 8, top + LIST_TOP + 8, READY)
            return
        }

        guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + YARD_CARD_BOTTOM)
        for (i in 0 until YARD_CARD_ROWS) {
            val index = scroll + i
            if (index >= owed.size) break
            drawMaterial(guiGraphics, owed[index], top + LIST_TOP + i * ROW_H, false)
        }
        guiGraphics.disableScissor()
        drawScrollbar(guiGraphics, owed.size, YARD_CARD_ROWS, YARD_CARD_BOTTOM)
    }

    // ---- One ship's card ----------------------------------------------------------------------------------

    /**
     * One material, and what may be done about it.
     *
     * Painted like the vessel card -- a header, a rule, then whatever the row has to say -- with the real
     * controls added as widgets in [initMaterial]. Nothing here is clickable; the checkbox and the dropdown
     * are buttons, so they answer clicks before any hit-test of ours could.
     */
    private fun drawMaterialCard(
        guiGraphics: GuiGraphics,
        row: ShipwrightMenu.Row,
        material: ShipwrightMenu.Material
    ) {
        val stack = ItemStack(material.item)
        guiGraphics.renderItem(stack, left + 8, top + 4)
        guiGraphics.drawString(
            font, clip(stack.hoverName.string, PANEL_W - 38), left + 30, top + 8, ACCENT, false
        )

        val heading = when {
            material.needed <= 0 -> EXCLUDED_TEXT.string
            material.swapped -> "${fmt(material.needed)} needed  -  swapped"
            else -> "${fmt(material.given)} of ${fmt(material.needed)} delivered"
        }
        small(guiGraphics, Component.literal(heading), left + 8, top + 22, TEXT)

        val origin = material.swappedFrom?.let { ItemStack(it).hoverName.string }
        small(
            guiGraphics,
            Component.literal(
                if (origin != null) "${ORIGINALLY_TEXT.string} $origin"
                else "${row.shipName}  -  ${material.category.lowercase()}"
            ),
            left + 8, top + 32, DIM
        )

        if (material.family == null) {
            small(guiGraphics, NO_SWAP_TEXT, left + 8, top + 42, DIM)
        } else if (!material.excludable && !shelf.swapFoundational) {
            small(guiGraphics, HULL_LOCKED_TEXT, left + 8, top + 42, DIM)
        }

        guiGraphics.fill(left + 4, top + LIST_TOP - 3, left + PANEL_W - 4, top + LIST_TOP - 2, ACCENT)
    }

    /**
     * The Save-as-New prompt, and a list of what is about to be filed.
     *
     * The box itself is a widget and paints over the heading; everything here sits below it. The changed rows
     * are listed because "save as new" is otherwise a leap of faith -- the captain is naming something they
     * cannot see, and two swaps ago is easy to forget.
     */
    private fun drawNaming(guiGraphics: GuiGraphics, row: ShipwrightMenu.Row) {
        small(guiGraphics, NAME_HINT, left + 8, top + 24, DIM)
        small(
            guiGraphics,
            Component.literal("${row.shipName}  -  ${fmt(row.blocks)} blocks  -  ${fmt(row.items)} items"),
            left + 8, top + 36, TEXT
        )
        guiGraphics.fill(left + 4, top + LIST_TOP - 3, left + PANEL_W - 4, top + LIST_TOP - 2, ACCENT)

        val changed = cardRowsAll(row).filter { it.swapped || it.needed <= 0 }
        val struckFrom = cardRowsAll(row).size - row.struck.size
        if (changed.isEmpty()) {
            small(guiGraphics, NO_CHANGES, left + 8, top + LIST_TOP + 6, DIM)
            return
        }
        guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + CARD_BOTTOM)
        for (i in 0 until CARD_ROWS) {
            val entry = changed.getOrNull(i) ?: break
            drawMaterial(
                guiGraphics, entry, top + LIST_TOP + i * ROW_H, true,
                excluded = cardRowsAll(row).indexOf(entry) >= struckFrom
            )
        }
        guiGraphics.disableScissor()
    }

    private fun drawCard(guiGraphics: GuiGraphics, row: ShipwrightMenu.Row, mouseX: Int, mouseY: Int) {
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

        val shown = cardRowsAll(row)
        val settled = row.materials.none { it.outstanding > 0 }
        val struckFrom = shown.size - row.struck.size

        guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + CARD_BOTTOM)
        for (i in 0 until CARD_ROWS) {
            val index = scroll + i
            if (index >= shown.size) break
            val rowY = top + LIST_TOP + i * ROW_H
            drawMaterial(
                guiGraphics, shown[index], rowY, settled,
                excluded = index >= struckFrom,
                hovered = mouseX >= left + 4 && mouseX < left + PANEL_W - 8 &&
                    mouseY >= rowY && mouseY < rowY + ROW_H
            )
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

    /**
     * Struck rows are listed after the outstanding ones rather than dropped.
     *
     * They are no longer part of the bill -- that is what striking one means, and why they must not count
     * toward the total or the percentage -- but a row a captain cannot see is a row they cannot put back.
     */
    private fun cardRowsAll(row: ShipwrightMenu.Row): List<ShipwrightMenu.Material> =
        cardRows(row) + row.struck

    private fun drawMaterial(
        guiGraphics: GuiGraphics,
        material: ShipwrightMenu.Material,
        y: Int,
        settled: Boolean,
        excluded: Boolean = false,
        hovered: Boolean = false
    ) {
        if (hovered) guiGraphics.fill(left + 4, y, left + PANEL_W - 8, y + ROW_H - 1, ROW_HOVER)

        val stack = ItemStack(material.item)
        guiGraphics.renderItem(stack, left + 8, y + 1)

        // Orange for a row the captain has re-materialled, dim for one they have struck off -- with a marker
        // in the gutter saying which, because colour on its own is a poor thing to make a rule out of.
        val struck = excluded || material.needed <= 0

        // "given / needed" rather than a bare remainder: a player deciding what to go and fetch wants to know
        // how far in they already are, not only what is missing. Drawn BEFORE the name, so the name can be
        // told what room is left -- item names run past thirty characters and this panel is 250 wide.
        val tally = Component.literal(
            if (struck) EXCLUDED_MARK.string else "${fmt(material.given)} / ${fmt(material.needed)}"
        )
        val tallyX = left + PANEL_W - 12 - font.width(tally)
        guiGraphics.drawString(
            font, tally, tallyX, y + 5,
            when {
                struck -> STRUCK
                settled || material.outstanding <= 0 -> READY
                else -> ACCENT
            },
            false
        )

        var nameRoom = tallyX - 6 - (left + 30)
        if (material.swapped && !struck) {
            val markX = tallyX - 4 - font.width(SWAPPED_MARK)
            guiGraphics.drawString(font, SWAPPED_MARK, markX, y + 5, ORANGE, false)
            nameRoom = markX - 4 - (left + 30)
        }
        guiGraphics.drawString(
            font, Component.literal(clip(stack.hoverName.string, nameRoom)), left + 30, y + 5,
            when {
                struck -> DIM
                material.swapped -> ORANGE
                else -> TEXT
            },
            false
        )
    }

    // ---- Input --------------------------------------------------------------------------------------------

    /**
     * Enter commits a name; Escape steps back out of whatever is innermost.
     *
     * Without this the screen had no key handling at all, so Escape closed the entire book from any depth --
     * including out of a half-typed name, which is a rude way to lose a word.
     */
    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (keyEvent.key() == GLFW.GLFW_KEY_ENTER || keyEvent.key() == GLFW.GLFW_KEY_KP_ENTER) {
            if (naming) {
                commitNaming(send = true)
                return true
            }
        }
        if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (swapOpen) {
                swapOpen = false
                rebuild()
                return true
            }
            if (naming) {
                commitNaming(send = false)
                return true
            }
            if (openMaterial != null) {
                openMaterial = null
                rebuild()
                return true
            }
            if (dropdownOpen) {
                dropdownOpen = false
                rebuild()
                return true
            }
            if (openPile != null) {
                openPile = null
                dismissArmed = null; keepArmed = null; dismissAllArmed = false
                scroll = 0
                rebuild()
                return true
            }
            if (openCard != null || openVessel != null) {
                openCard = null
                openVessel = null
                dismantleArmed = false
                scroll = 0
                rebuild()
                return true
            }
        }
        return super.keyPressed(keyEvent)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(mouseButtonEvent, doubled)) return true
        // A card open used to make the whole list inert. The plans card's rows are now the way into a
        // material's popup, so only the yard card and an already-open popup swallow clicks.
        //
        // The pile is tested FIRST, the same order render() and init() use. When these disagreed, a claim
        // list drew and built its buttons while every row click was thrown away here.
        if (onYard && openPile == null && openVessel != null) return false
        if (openMaterial != null || naming) return false

        val mouseX = mouseButtonEvent.x().toInt()
        val mouseY = mouseButtonEvent.y().toInt()
        if (mouseX < left + 4 || mouseX > left + PANEL_W - 8) return false
        if (mouseY < top + LIST_TOP || mouseY >= top + LIST_BOTTOM) return false

        val index = scroll + (mouseY - (top + LIST_TOP)) / ROW_H

        val claiming = pile
        if (onYard && claiming != null) {
            if (mouseY >= top + CLAIM_BOTTOM) return false

            // The Kept tab is addressed by position, so it has its own armed state and its own send.
            if (claimTab == 2) {
                val kept = claiming.keepsakes.getOrNull(index) ?: return false
                if (mouseX >= left + PANEL_W - 8 - DISMISS_W) {
                    if (keepArmed != kept.index) {
                        keepArmed = kept.index
                        return true
                    }
                    keepArmed = null
                    ShipwrightNetworkingFabric.send(
                        shelf.villager, ShipwrightMenu.Action.SALVAGE_DISMISS, claiming.shipName,
                        tabArgument(), kept.index.toString()
                    )
                    return true
                }
                keepArmed = null
                ShipwrightNetworkingFabric.send(
                    shelf.villager, ShipwrightMenu.Action.CLAIM_ONE, claiming.shipName,
                    tabArgument(), kept.index.toString()
                )
                return true
            }

            val row = claimRows(claiming).getOrNull(index) ?: return false

            // The right-hand gutter is Dismiss; the rest of the row is Claim. Two targets, no widgets --
            // see [initClaim] for why this list has none.
            if (mouseX >= left + PANEL_W - 8 - DISMISS_W) {
                if (dismissArmed != row.item) {
                    dismissArmed = row.item
                    return true
                }
                dismissArmed = null; keepArmed = null; dismissAllArmed = false
                ShipwrightNetworkingFabric.send(
                    shelf.villager, ShipwrightMenu.Action.SALVAGE_DISMISS, claiming.shipName,
                    tabArgument(), BuiltInRegistries.ITEM.getKey(row.item).toString()
                )
                return true
            }

            // Aiming at a row disarms a Dismiss left armed on another one: the confirm belongs to the row it
            // was pressed on, and an armed button waiting quietly two rows up is a trap.
            dismissArmed = null; keepArmed = null; dismissAllArmed = false
            ShipwrightNetworkingFabric.send(
                shelf.villager, ShipwrightMenu.Action.CLAIM_ONE, claiming.shipName,
                tabArgument(), BuiltInRegistries.ITEM.getKey(row.item).toString()
            )
            return true
        }

        val open = card
        if (!onYard && open != null) {
            // The card's list is shorter than the shelf's, so it needs its own floor.
            if (mouseY >= top + CARD_BOTTOM) return false
            val clicked = cardRowsAll(open).getOrNull(index) ?: return false
            openMaterial = clicked.item
            swapOpen = false
            swapScroll = 0
            rebuild()
            return true
        }
        if (onYard) {
            // Hulls first, then the piles that used to be hulls -- the order [drawYard] paints them in.
            val hull = shelf.vessels.getOrNull(index)
            if (hull != null) {
                openVessel = hull.slug
                // Two cards cannot both be open. Belt and braces beside the ordering fixes above: whichever
                // way the state is reached, only one of these is ever set.
                openPile = null
                dismantleArmed = false
            } else {
                val salvaged = shelf.salvage.getOrNull(index - shelf.vessels.size) ?: return false
                openPile = salvaged.shipName
                openVessel = null
                claimTab = 0
                dismissArmed = null; keepArmed = null; dismissAllArmed = false
            }
        } else {
            val row = shelf.rows.getOrNull(index) ?: return false
            openCard = row.shipName
        }
        scroll = 0
        rebuild()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        // While the swap list is up the wheel belongs to it -- a family is long enough that scrolling the
        // card underneath instead would be the wrong answer every time.
        if (swapOpen) {
            swapScroll = maxOf(0, swapScroll - scrollY.toInt())
            rebuild()
            return true
        }
        scroll -= scrollY.toInt()
        clampScroll()
        return true
    }

    /**
     * Controller support, read straight off the hardware (VS2's ShipGamepad) because a controller mod's
     * screen handling never reaches a custom screen.
     *
     * The right stick is the scroll wheel everywhere. On the shelf and in the yard the D-pad is the
     * hover: up/down walk the highlight through the rows -- painted through the same highlight the mouse
     * uses -- D-pad right opens the highlighted ship, and D-pad left steps back out of an open card the
     * way Back does. On an open card, up/down scroll the bill of materials a row at a time.
     */
    override fun tick() {
        super.tick()

        val deflect = ShipGamepad.rightStickY()
        if (abs(deflect) < STICK_DEADZONE) {
            stickHeld = 0
        } else {
            if (stickHeld % STICK_EVERY == 0) mouseScrolled(0.0, 0.0, 0.0, if (deflect < 0) 1.0 else -1.0)
            stickHeld++
        }

        // The bumpers flip between the book's two halves, exactly as the tab buttons do.
        if (ShipGamepad.bumperLeftPressed() || ShipGamepad.bumperRightPressed()) {
            if (onYard) {
                onYard = false
                openVessel = null
            } else {
                onYard = true
                openCard = null
            }
            scroll = 0
            rebuild()
        }

        // Entering or leaving a view drops the selection rather than letting a shelf index masquerade as
        // a yard one.
        // Ordering is load-bearing, and getting it wrong here once made the claim list click-inert. A pile
        // only ever opens on the Yard, so a bare "onYard" arm placed above it matches first and swallows it
        // -- the pad then believed it was on the yard LIST and its choose set openVessel underneath an open
        // claim list, which the click guard reads as "a vessel card is up" and refuses every row.
        // Most specific first, always.
        val context = when {
            // A popup and a name prompt each get their own number, so stepping into one drops a selection
            // that was pointing at the card's buttons -- the same index would otherwise name a new widget.
            naming -> 5
            openMaterial != null -> 4
            onYard && openPile != null -> 6
            onYard && openVessel != null -> 3
            onYard -> 2
            openCard != null -> 1
            else -> 0
        }
        if (context != padContext) {
            padContext = context
            padSel = -1
            focused = null
        }

        if (ShipGamepad.dpadLeftPressed()) {
            when (context) {
                6 -> {
                    openPile = null
                    dismissArmed = null; keepArmed = null; dismissAllArmed = false
                    scroll = 0
                    rebuild()
                }
                5 -> commitNaming(send = false)
                4 -> {
                    if (swapOpen) swapOpen = false else openMaterial = null
                    rebuild()
                }
                3 -> {
                    openVessel = null
                    scroll = 0
                    rebuild()
                }
                1 -> {
                    openCard = null
                    rebuild()
                }
            }
        }

        val step = verticalStep()
        val choose = ShipGamepad.dpadRightPressed()
        ShipGamepad.drainPresses()

        if (context == 1 || context == 3 || context == 4 || context == 5 || context == 6) {
            // An open card is its BUTTONS: D-pad up/down walk them (tabs included), lit through the same
            // state hover uses, and D-pad right presses the one selected. The bill of materials scrolls
            // on the right stick or the wheel.
            val buttons = children().filterIsInstance<AbstractButton>().filter { it.active && it.visible }
            if (buttons.isEmpty()) return
            if (step != 0) {
                padSel = when {
                    padSel < 0 -> if (step > 0) 0 else buttons.size - 1
                    else -> (padSel + step).coerceIn(0, buttons.size - 1)
                }
                focused = buttons[padSel]
            }
            if (choose) {
                buttons.getOrNull(padSel)?.onPress(object : InputWithModifiers {
                    override fun input(): Int = GLFW.GLFW_KEY_ENTER
                    override fun modifiers(): Int = 0
                })
            }
            return
        }

        val count = if (onYard) shelf.vessels.size + shelf.salvage.size else shelf.rows.size
        if (count == 0) return
        if (step != 0) {
            padSel = when {
                padSel < 0 -> if (step > 0) 0 else count - 1
                else -> (padSel + step).coerceIn(0, count - 1)
            }
            // The selection drags the list with it, the way keyboard focus is expected to.
            if (padSel < scroll) scroll = padSel
            if (padSel >= scroll + VISIBLE_ROWS) scroll = padSel - VISIBLE_ROWS + 1
            clampScroll()
        }
        if (choose && padSel >= 0) {
            if (onYard) {
                val hull = shelf.vessels.getOrNull(padSel)
                if (hull != null) {
                    openVessel = hull.slug
                    openPile = null
                } else {
                    shelf.salvage.getOrNull(padSel - shelf.vessels.size)?.let {
                        openPile = it.shipName
                        openVessel = null
                        claimTab = 0
                    }
                }
            } else {
                shelf.rows.getOrNull(padSel)?.let { openCard = it.shipName }
            }
            scroll = 0
            rebuild()
        }
    }

    /** Pad-driven selection: an index into whichever list is in front of you. -1 = the pad has not taken it. */
    private var padSel = -1
    private var padContext = 0
    private var padVertHeld = 0
    private var stickHeld = 0

    /** One selection step per press, repeating on a short beat while held. 0 when this tick moves nothing. */
    private fun verticalStep(): Int {
        val direction = when {
            ShipGamepad.dpadUp() -> -1
            ShipGamepad.dpadDown() -> 1
            else -> {
                padVertHeld = 0
                return 0
            }
        }
        if (ShipGamepad.dpadUpPressed() || ShipGamepad.dpadDownPressed()) padVertHeld = 0
        val fires = padVertHeld == 0 ||
            (padVertHeld >= PAD_REPEAT_DELAY && (padVertHeld - PAD_REPEAT_DELAY) % PAD_REPEAT_EVERY == 0)
        padVertHeld++
        return if (fires) direction else 0
    }

    private fun clampScroll() {
        // Counted off the SAME list each view paints, not the full bill -- a card usually shows only what is
        // still owed, and clamping against the longer list would scroll past the end into blank rows.
        val rows: Int
        val visible: Int
        if (onYard) {
            val hull = vessel
            val claiming = pile
            when {
                claiming != null -> {
                    rows = if (claimTab == 2) claiming.keepsakes.size else claimRows(claiming).size
                    visible = CLAIM_ROWS
                }
                hull != null -> {
                    rows = hull.repairs.count { it.outstanding > 0 }
                    visible = YARD_CARD_ROWS
                }
                else -> {
                    rows = shelf.vessels.size + shelf.salvage.size
                    visible = VISIBLE_ROWS
                }
            }
        } else {
            val open = card
            rows = if (open != null) cardRowsAll(open).size else shelf.rows.size
            visible = if (open != null) CARD_ROWS else VISIBLE_ROWS
        }
        scroll = scroll.coerceIn(0, maxOf(0, rows - visible))
    }

    // ---- Painting helpers ---------------------------------------------------------------------------------

    private fun panel(guiGraphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        guiGraphics.fill(x, y, x + w, y + h, PANEL_BORDER)
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL_BG)
    }

    /**
     * "Dismantle Fee: 19 <emerald>", right-aligned under the hull's dimensions.
     *
     * Laid out RIGHT TO LEFT: the last item sits against the panel edge and the label ends up wherever the
     * icons leave room. A one-item fee and a two-item fee then both read as one block against the margin,
     * and a long fee grows leftward into empty panel rather than pushing its own label off the edge.
     *
     * The count is drawn as text ahead of the icon rather than as the stack's corner decoration: a stack
     * badge is for what you are holding, and this is a price.
     */
    private fun drawFee(guiGraphics: GuiGraphics, hull: ShipwrightMenu.Vessel) {
        if (hull.fee.isEmpty()) return
        val y = top + 20
        var x = left + PANEL_W - 8

        for (line in hull.fee.asReversed()) {
            x -= ICON
            guiGraphics.renderItem(ItemStack(line.item), x, y)
            val count = Component.literal(line.needed.toString())
            x -= (font.width(count) * SMALL).toInt() + 2
            small(guiGraphics, count, x, y + 5, TEXT)
            x -= 3
        }
        x -= (font.width(DISMANTLE_FEE_TEXT) * SMALL).toInt()
        small(guiGraphics, DISMANTLE_FEE_TEXT, x, y + 5, DIM)
    }

    /**
     * Whether the captain is carrying the fee. Answered on the CLIENT so the button can grey itself rather
     * than accept a click and refuse -- the same courtesy the Bottle button pays with [Shelf.hasFreeBottle].
     * The server re-quotes and re-checks regardless; this only decides how the button looks.
     */
    private fun canPayFee(hull: ShipwrightMenu.Vessel): Boolean {
        if (hull.fee.isEmpty()) return true
        val player = Minecraft.getInstance().player ?: return true
        if (player.abilities.instabuild) return true
        for (line in hull.fee) {
            var held = 0
            for (slot in 0 until player.inventory.containerSize) {
                val stack = player.inventory.getItem(slot)
                if (!stack.isEmpty && stack.`is`(line.item)) held += stack.count
            }
            if (held < line.needed) return false
        }
        return true
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
        private val PLANS_TAB: Component = Component.translatable("gui.vs_eureka.shipwright_tab_plans")
        private val YARD_TAB: Component = Component.translatable("gui.vs_eureka.shipwright_tab_yard")
        private val YARD_TITLE: Component = Component.translatable("gui.vs_eureka.shipwright_yard")
        private val YARD_HINT: Component = Component.translatable("gui.vs_eureka.shipwright_yard_hint")
        private val NO_SHIPS: Component = Component.translatable("gui.vs_eureka.shipwright_no_ships")
        private val NO_PLANS: Component = Component.translatable("gui.vs_eureka.shipwright_no_plans")
        private val NO_MATCH: Component = Component.translatable("gui.vs_eureka.shipwright_no_match")
        private val REFUSED: Component = Component.translatable("gui.vs_eureka.shipwright_refused")
        private val SOUND: Component = Component.translatable("gui.vs_eureka.shipwright_sound")
        private val REPAIR_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_repair")
        private val REPAIR_PAID: Component = Component.translatable("gui.vs_eureka.shipwright_repair_paid")
        private val SWAPPED_MARK: Component = Component.translatable("gui.vs_eureka.shipwright_swapped_mark")
        private val EXCLUDED_MARK: Component = Component.translatable("gui.vs_eureka.shipwright_excluded_mark")
        private val EXCLUDE_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_exclude")
        private val EXCLUDED_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_excluded")
        private val SWAP_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_swap")
        private val SWAP_ANY_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_swap_any")
        private val SWAP_ORIGINAL_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_swap_original")
        private val NO_SWAP_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_no_swap")
        private val HULL_LOCKED_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_hull_locked")
        private val ORIGINALLY_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_originally")
        private val SAVE_AS_NEW_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_save_as_new")
        private val TAKE_PAGE_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_take_blueprint")
        private val NO_BLANK_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_no_blank")
        private val RESET_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_reset")
        private val NAME_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_name_prompt")
        private val DISMANTLE_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_dismantle")
        private val DISMANTLE_FEE_TEXT: Component =
            Component.translatable("gui.vs_eureka.shipwright_dismantle_fee")
        private val REALLY_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_really")
        private val DISMISS_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_dismiss")
        private val DISMISS_ALL_TEXT: Component =
            Component.translatable("gui.vs_eureka.shipwright_dismiss_all")
        private val CLAIM_ALL_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_claim_all")
        private val SHULKER_TEXT: Component = Component.translatable("gui.vs_eureka.shipwright_use_shulkers")
        private val HULL_TAB: Component = Component.translatable("gui.vs_eureka.shipwright_tab_hull")
        private val CARGO_TAB: Component = Component.translatable("gui.vs_eureka.shipwright_tab_cargo")
        private val KEPT_TAB: Component = Component.translatable("gui.vs_eureka.shipwright_tab_kept")
        private val CLAIM_HINT: Component = Component.translatable("gui.vs_eureka.shipwright_claim_hint")
        private val NOTHING_KEPT: Component = Component.translatable("gui.vs_eureka.shipwright_nothing_kept")
        private val NOTHING_LEFT: Component = Component.translatable("gui.vs_eureka.shipwright_nothing_left")
        private val SALVAGE_MARK: Component = Component.translatable("gui.vs_eureka.shipwright_salvage_mark")
        private val NAME_HINT: Component = Component.translatable("gui.vs_eureka.shipwright_name_hint")
        private val NO_CHANGES: Component = Component.translatable("gui.vs_eureka.shipwright_no_changes")
        private val NO_ENGINES: Component = Component.translatable("gui.vs_eureka.shipwright_no_engines")

        private const val PANEL_W = 250
        private const val PANEL_H = 214

        /** Ticks a held D-pad direction waits before repeating, and the gap between repeats after that. */
        private const val PAD_REPEAT_DELAY = 6
        private const val PAD_REPEAT_EVERY = 3

        /** Right-stick scroll: deflection under this is rest, and one notch scrolls every few ticks held. */
        private const val STICK_DEADZONE = 0.4f
        private const val STICK_EVERY = 3
        private const val LIST_TOP = 53
        private const val ROW_H = 18
        private const val VISIBLE_ROWS = 8
        private const val LIST_BOTTOM = LIST_TOP + ROW_H * VISIBLE_ROWS
        /** One row shallower than the shelf: the plans card gives that line to the alteration buttons. */
        private const val CARD_ROWS = 5

        /** The claim list gives a row back to its tab strip, exactly as the plans card does. */
        private const val CLAIM_ROWS = 5
        private const val CLAIM_BOTTOM = LIST_TOP + ROW_H * CLAIM_ROWS

        /** The right-hand gutter of a claim row, which is a Dismiss rather than part of the row. */
        private const val DISMISS_W = 44

        /** Wide enough for "Dismiss All" beside three tabs, in the gap they leave at the right. */
        private const val DISMISS_ALL_W = 78
        private const val CARD_BOTTOM = LIST_TOP + ROW_H * CARD_ROWS

        /** One row shallower than a plans card: the yard card gives a line back to the dropdown. */
        private const val YARD_CARD_ROWS = 5
        private const val YARD_CARD_BOTTOM = LIST_TOP + ROW_H * YARD_CARD_ROWS
        private const val DROP_W = 96
        private const val TAB_W = 48

        /** How many sets of plans the picker shows at once before it would overrun the panel. */
        private const val DROP_ROWS = 8

        /** How far up from the bottom a card's own buttons sit, clear of the tab strip below them. */
        private const val ACTION_ROW = 42

        /** Same ceiling a wheel's name gets, so a variant cannot be named something a ship could not be. */
        private const val MAX_NAME = 32

        private const val BAR_W = 70
        private const val BAR_H = 6
        private const val BTN_W = 56
        private const val BACK_W = 40
        private const val BTN_H = 16
        private const val SMALL = 0.7f

        /** A vanilla item icon is 16 square, and the fee row lays itself out against that. */
        private const val ICON = 16

        private const val PANEL_BORDER = 0xFF000000.toInt()
        private const val PANEL_BG = 0xFFC6C6C6.toInt()
        private const val ROW_HOVER = 0xFFD8D8D8.toInt()

        /** A row with a Dismiss armed on it, washed so the second press is never a surprise. */
        private const val ARMED_ROW = 0xFFE9C6C6.toInt()
        private const val ROW_LOCKED = 0xFFA0A0A0.toInt()
        private const val TEXT = 0xFF404040.toInt()
        private const val DIM = 0xFF7A7A7A.toInt()
        private const val ACCENT = 0xFF2A8FA6.toInt()

        /** A row the captain has re-materialled. Warm, so it reads as "changed" rather than "wrong". */
        private const val ORANGE = 0xFFC97A1E.toInt()

        /** A row struck off the plans. */
        private const val STRUCK = 0xFFB03A3A.toInt()
        private const val READY = 0xFF2E8B45.toInt()
    }
}
