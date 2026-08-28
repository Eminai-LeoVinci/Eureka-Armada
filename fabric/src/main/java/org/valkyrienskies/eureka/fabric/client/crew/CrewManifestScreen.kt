package org.valkyrienskies.eureka.fabric.client.crew

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Renderable
import net.minecraft.core.BlockPos
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.crew.CrewDuty
import org.valkyrienskies.eureka.crew.CrewManifest
import org.valkyrienskies.eureka.crew.CrewRoll
import org.valkyrienskies.eureka.crew.CrewOperations
import org.valkyrienskies.eureka.crew.ShipStores
import org.valkyrienskies.eureka.fabric.PathNetworkingFabric
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmButton
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmIconButton
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmTab
import org.valkyrienskies.eureka.item.Cannonball
import org.valkyrienskies.eureka.item.CannonCharge
import org.valkyrienskies.mod.client.ShipGamepad
import java.util.UUID
import kotlin.math.abs

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

    /** Whether the Station dropdown is unfolded over the card. */
    private var stationMenuOpen = false

    /** First visible entry of the dropdown when it holds more guns than fit. */
    private var stationMenuScroll = 0

    /**
     * The station label the SERVER last confirmed for the open card. What the dropdown picks is provisional
     * -- held only in [detail] -- until the card is left (Back, or closing the screen), at which point
     * [commitPendingStation] sends the difference. That is the lock-in gesture: choose freely, and the order
     * goes out when you step away from the card.
     */
    private var stationBaseline: String? = null

    /** Kept across widget rebuilds so a refresh mid-type does not swallow what has been typed. */
    private var nameValue = ""
    private var nameBox: EditBox? = null

    /**
     * The card's real widgets, held so a dropdown can hide them.
     *
     * A dropdown on the card is hand-drawn and these are not, and `super.render` paints widgets LAST --
     * so Back sat on top of the gun list, and worse, `super.mouseClicked` runs FIRST, so it also ATE the
     * clicks meant for the entries underneath it. Hiding them while a list is unfolded is what makes the
     * card's own rule -- a dropdown owns the card while it is open -- true of the widgets too.
     */
    private var cardRenameButton: ShipHelmIconButton? = null
    private var cardDismissButton: ShipHelmButton? = null
    private var cardBackButton: ShipHelmButton? = null

    /**
     * Back to the helm menu, pinned to the panel's bottom-right corner on both tabs -- the other half of
     * the helm's Crew & Operations book, so the two screens open onto each other. A real widget, which
     * means the overlay rule applies: super paints widgets last and answers their clicks first, so it
     * hides whenever a dropdown or popup is up, exactly as the card's buttons learned to.
     */
    private var helmBackButton: ShipHelmButton? = null

    /** The crew's own name, edited in the header. Same keep-across-rebuild reasoning as [nameValue]. */
    private var crewNameValue = ""
    private var crewNameBox: EditBox? = null
    private var renamingCrew = false

    /**
     * Whether the Dismiss button is on its second press.
     *
     * Paying somebody off is not catastrophic -- walk up to them and press the crew key and they are back --
     * but it is not free either: their berth is handed to whoever is next, their written copy is discarded, and
     * if they were ashore when it happened there may be no walking up to them at all. One misclick should not
     * do that, so the button asks.
     */
    private var dismissArmed = false

    /** The book's three faces. */
    private enum class Tab { OPERATIONS, ROSTER, CREWS }

    // region Crews state

    /**
     * Every crew this captain keeps, or null before the list has landed. The Crews tab's own contents.
     *
     * The same roll the helm menu's dropdown reads, asked for again when the tab comes up: it is a snapshot,
     * and a crew disbanded or renamed since the book was opened must not still be on the list.
     */
    private var crewRoll: CrewRoll.Roll? = null

    /** Which crew's articles are open, or null while the list itself is showing. */
    private var openCrew: UUID? = null

    /** Those articles, once they arrive. Read-only -- see [viewingCrew]. */
    private var crewRoster: CrewRoll.Roster? = null

    /** How far the crews list is scrolled. Its own, because it is a different list from the roster's. */
    private var crewsScroll = 0

    /**
     * Which crew's Delete is armed and reading "Really?", or null.
     *
     * One at a time, and cleared by anything else the captain does. Deleting a crew destroys every villager
     * on it and cannot be undone, so it takes two deliberate presses -- the same guard the card's Dismiss
     * uses, for a far heavier action.
     */
    private var disbandArmed: UUID? = null

    /** Whether the open crew's name is being edited, and what has been typed into it. */
    private var renamingOwnedCrew = false
    private var ownedCrewNameValue = ""
    private var ownedCrewNameBox: EditBox? = null

    // endregion

    /** Which face is showing. Operations first: fleet-scale orders are what a captain opens this FOR. */
    // Opens on Operations, except where there is no ship to operate: a wheel in the hand or on the ground
    // opens straight onto the Crews tab, the only one of the three with anything to say.
    private var activeTab = if (snapshot.readOnly) Tab.CREWS else Tab.OPERATIONS

    // region Operations state

    /** What the holds last reported, or null before the first stores payload has landed. */
    private var stores: ShipStores.Stores? = null

    /** Whether this ship is under the Fire at Will order. Server state; the stores payload carries it. */
    private var fireAtWill = false

    /** Guns per deck, keel up, off the same payload the holds ride. Empty until it lands (or no guns). */
    private var deckCounts: List<Int> = emptyList()

    /**
     * Whether this screen has seeded the widgets from the ship's own memory yet. Once per OPEN: the first
     * stores payload seeds, and every later refresh (each order pushes stores back) leaves alone whatever
     * the captain has typed since. A new screen -- another wheel, another ship, a reopen -- seeds afresh.
     */
    private var opsSeeded = false

    /** How far the Operations body is scrolled: the tab outgrew its panel when it grew categories. */
    private var opsScroll = 0

    /**
     * The count boxes' values. Kept in the COMPANION for the same reason the assign modes are: a captain who
     * closes the book after ordering twelve hands to the guns is going to order twelve again, and retyping
     * it every time is the sort of small tax that makes a panel feel like paperwork.
     */
    private var gunnerCount: Int
        get() = rememberedGunnerCount
        set(value) { rememberedGunnerCount = value }
    private var fireCount: Int
        get() = rememberedFireCount
        set(value) { rememberedFireCount = value }
    private var gunnerCountBox: EditBox? = null
    private var fireCountBox: EditBox? = null

    /**
     * The crew assigner's scope -- which side's guns to man, on which deck (0 = all). Its own pair of
     * knobs, independent of the cannon controls', because "man Deck 1 while I lay Deck 2" is one trip.
     */
    private var crewSide: CrewOperations.Side
        get() = rememberedCrewSide
        set(value) { rememberedCrewSide = value }
    private var crewLayer: Int
        get() = rememberedCrewLayer
        set(value) { rememberedCrewLayer = value }

    /**
     * The cannon-controls scope: one side + deck pair governing Set Angle, Set Power and the ammunition
     * pick. One pair rather than one per row, because the rows under it are one battery being worked.
     */
    private var ctrlSide: CrewOperations.Side
        get() = rememberedCtrlSide
        set(value) { rememberedCtrlSide = value }
    private var ctrlLayer: Int
        get() = rememberedCtrlLayer
        set(value) { rememberedCtrlLayer = value }

    /**
     * The cannonball restock's OWN scope, side and deck both.
     *
     * It used to borrow the cannon controls' deck, which quietly tied two orders a captain thinks of
     * separately: "lay Deck 3 to port" and "load Deck 2 to starboard" are one trip, and doing them in
     * either order moved the other's aim. Its own pair, like the gunners' above.
     */
    private var shotSide: CrewOperations.Side
        get() = rememberedShotSide
        set(value) { rememberedShotSide = value }
    private var shotLayer: Int
        get() = rememberedShotLayer
        set(value) { rememberedShotLayer = value }

    /**
     * What each Assign button will DO -- kept in the companion, not here, so closing the book and opening
     * it again does not quietly put a captain back on a different mode than the one they left set. They
     * reset at disconnect; see [forgetModes].
     */
    private var crewMode: CrewOperations.AssignMode
        get() = rememberedCrewMode
        set(value) { rememberedCrewMode = value }
    private var fireMode: CrewOperations.AssignMode
        get() = rememberedFireMode
        set(value) { rememberedFireMode = value }

    /** The round the shot restock will load. Defaults to the holds' most plentiful when stores arrive. */
    /**
     * The round the Operations tab is set to load, remembered for as long as the player is in a world.
     *
     * Delegates to the companion for exactly the reason the crew counts and sides do: the manifest is built
     * fresh every time the book opens, so a captain who picked their round, closed it and opened it again
     * found the choice quietly back on something else. Which round you are loading is a standing habit, not
     * a property of the ship in front of you.
     *
     * Reset by [forgetModes] when the connection drops, so it lives exactly as long as the voyage does.
     */
    private var selectedAmmo: Pair<Cannonball, CannonCharge>?
        get() = rememberedAmmo
        set(value) {
            rememberedAmmo = value
        }
    private var ammoMenuOpen = false
    private var ammoMenuScroll = 0

    private var fuelPopupOpen = false

    /**
     * The elevation and power the Set Angle / Set Power orders will send. Level (0 degrees) and 1x to
     * start, and instance state on purpose: the angle stepper reads 0 every time the book is opened.
     */
    private var elevIndex = 9
    private var powerLevel = 0

    /**
     * Which deck dropdown is unfolded, as the anchor stop it hangs from, or -1 for none.
     *
     * Named rather than inferred: this was two booleans read back as "crew, else the other one", which
     * is a shape that silently mis-files a pick the moment a THIRD dropdown exists -- and the restock
     * just grew one. The scroll stays shared; only one menu is ever open.
     */
    private var layerMenuFor = -1
    private var layerMenuScroll = 0

    /** The CARD's ammo dropdown -- the ops one's twin, listing the same holds but arming ONE gun. */
    private var cardAmmoMenuOpen = false
    private var cardAmmoMenuScroll = 0

    // endregion

    override fun init() {
        left = (width - PANEL_W) / 2
        top = (height - PANEL_H) / 2
        clampScroll()

        if (openCard == null) {
            // The card covers the whole panel, tabs included, so the strip is built only when no card is
            // open -- nothing under a card invites leaving it mid-edit, and Back keeps its meaning.
            addTab(0, TAB_OPERATIONS_TEXT, Tab.OPERATIONS)
            addTab(1, TAB_ROSTER_TEXT, Tab.ROSTER)
            addTab(2, TAB_CREWS_TEXT, Tab.CREWS)

            // Pinned to the corner, outside the scrolling body. Sends the player back to the helm menu whose
            // book brought them here (the server re-opens it; reach-guarded there).
            //
            // NOT while a crew's articles are open. That view has its own Back -- out to the list of crews --
            // and two buttons reading "Back" side by side, going to different places, is a coin toss with a
            // label on it. The one that steps out one level takes the corner; Escape still leaves outright.
            if (!(activeTab == Tab.CREWS && openCrew != null)) {
                helmBackButton = addRenderableWidget(
                    ShipHelmButton(
                        left + PANEL_W - 8 - BACK_BTN_W, top + PANEL_H - 4 - BACK_BTN_H,
                        BACK_BTN_W, BACK_BTN_H, BACK_TEXT, font
                    ) {
                        // A book opened on a wheel in the HAND has no block for the server to re-open a
                        // menu at, so Back simply closes rather than asking for a helm that isn't there.
                        if (snapshot.helm == CrewManifest.HELM_IN_HAND) onClose()
                        else PathNetworkingFabric.sendCrewOpenHelm(snapshot.helm)
                    }
                )
            }

            if (activeTab == Tab.OPERATIONS) {
                initOperations()
                return
            }

            if (activeTab == Tab.CREWS) {
                initCrews()
                return
            }

            // The crew's name is edited where it is READ: bottom-left, over the label rather than over the
            // heading at the top. The heading names the SHIP; the crew is a different thing and was being
            // renamed from a box that covered somebody else's name.
            //
            // Its width is cut so it stops short of Rename Crew, which now shares this row. Taken as a share
            // of the old full-width box and then bounded against the button, so the two can never overlap
            // however the constants around them move.
            if (renamingCrew) {
                val room = crewRenameX() - 4 - (left + 6)
                crewNameBox = addRenderableWidget(
                    EditBox(
                        font, left + 6, bottomRowY(),
                        minOf((PANEL_W - 12 - BERTHS_GUTTER) * 7 / 10, room), NAME_BOX_H, RENAME_TEXT
                    )
                ).also {
                    it.setMaxLength(CrewManifest.MAX_NAME_LENGTH)
                    it.value = crewNameValue
                    it.setResponder { typed -> crewNameValue = typed }
                    it.isFocused = true
                    this.focused = it
                }
                return
            }

            // Clicking the heading still starts a rename -- it is the most direct thing to aim at -- but a
            // button says the rename EXISTS, which a click target does not.
            addRenderableWidget(
                ShipHelmButton(
                    crewRenameX(), bottomRowY(), CREW_RENAME_BTN_W, BACK_BTN_H, RENAME_CREW_TEXT, font
                ) { beginCrewRename() }
            )
            return
        }

        // The Crews tab's card is a reading, not an editing screen: the name box, its Rename and Dismiss are
        // not built at all. Back is, because leaving has to work from everywhere.
        if (activeTab == Tab.CREWS) {
            cardBackButton = addRenderableWidget(
                ShipHelmButton(
                    cardX() + CARD_W - CARD_PAD - BACK_BTN_W, cardY() + CARD_H - CARD_PAD - BACK_BTN_H,
                    BACK_BTN_W, BACK_BTN_H, BACK_TEXT, font
                ) { closeCard() }
            )
            return
        }

        val boxW = CARD_W - 2 * CARD_PAD - RENAME_BTN_W - 4
        nameBox = addRenderableWidget(
            EditBox(font, cardX() + CARD_PAD, cardY() + CARD_PAD, boxW, NAME_BOX_H, RENAME_TEXT)
        ).also {
            it.setMaxLength(CrewManifest.MAX_NAME_LENGTH)
            it.value = nameValue
            it.setResponder { typed -> nameValue = typed }
        }

        cardRenameButton = addRenderableWidget(
            ShipHelmIconButton(
                cardX() + CARD_W - CARD_PAD - RENAME_BTN_W, cardY() + CARD_PAD,
                RENAME_BTN_W, NAME_BOX_H, RENAME_TEXT, font
            ) { commitRename() }
        )

        cardDismissButton = addRenderableWidget(
            ShipHelmButton(
                cardX() + CARD_PAD, cardY() + CARD_H - CARD_PAD - BACK_BTN_H,
                DISMISS_BTN_W, BACK_BTN_H,
                if (dismissArmed) DISMISS_CONFIRM_TEXT else DISMISS_TEXT, font
            ) { pressDismiss() }
        ).also {
            // A locked crew member cannot be paid off; the server refuses too, but a live button that only
            // ever answered with an error would read as broken rather than as protected.
            it.active = detail?.locked != true
        }

        cardBackButton = addRenderableWidget(
            ShipHelmButton(
                cardX() + CARD_W - CARD_PAD - BACK_BTN_W, cardY() + CARD_H - CARD_PAD - BACK_BTN_H,
                BACK_BTN_W, BACK_BTN_H, BACK_TEXT, font
            ) { closeCard() }
        )
    }

    override fun isPauseScreen(): Boolean = false

    // region the tab strip and the Operations tab's widgets

    /**
     * The Crews tab's widgets: none at all on the list, three while a crew's articles are open.
     *
     * Summon, Rename and Back are the whole of what this tab can DO -- everything else it shows is read-only,
     * which is why the roster it draws has no assignment controls built for it at all. Not painted out: not
     * built. A control that exists and refuses is a control a captain will keep pressing.
     */
    private fun initCrews() {
        // First look at the crews. switchTab asks whenever the tab is ENTERED, which covers every normal
        // route -- but a read-only book opens straight onto this tab and so is never switched into it.
        if (crewRoll?.helm != snapshot.helm) CrewRoll.clientAsk(snapshot.helm)
        if (openCrew == null) return

        if (renamingOwnedCrew) {
            ownedCrewNameBox = addRenderableWidget(
                EditBox(font, left + 6, top + 3, PANEL_W - 12 - BERTHS_GUTTER, NAME_BOX_H, RENAME_TEXT)
            ).also {
                it.setMaxLength(CrewManifest.MAX_NAME_LENGTH)
                it.value = ownedCrewNameValue
                it.setResponder { typed -> ownedCrewNameValue = typed }
                it.isFocused = true
                this.focused = it
            }
            return
        }

        val rowY = top + PANEL_H - 4 - BACK_BTN_H
        // Not painted out: not built. Summoning needs a deck to summon onto and renaming is a change, and
        // a book opened away from a ship is for reading. The articles themselves are already read-only
        // here -- the duty and lock controls have never been built on this tab.
        if (!snapshot.readOnly) {
            addRenderableWidget(
                ShipHelmButton(left + 6, rowY, CREW_SUMMON_BTN_W, BACK_BTN_H, CREW_SUMMON_TEXT, font) {
                    openCrew?.let { CrewRoll.clientSummon(snapshot.helm, it) }
                }
            )
            addRenderableWidget(
                ShipHelmButton(
                    left + 6 + CREW_SUMMON_BTN_W + 4, rowY, CREW_RENAME_BTN_W, BACK_BTN_H, RENAME_CREW_TEXT, font
                ) { beginOwnedCrewRename() }
            )
        }
        // Back to the LIST of crews. It takes the corner while these articles are open -- see init, where
        // the helm's own Back stands down rather than sit beside it saying the same word.
        addRenderableWidget(
            ShipHelmButton(
                left + PANEL_W - 8 - BACK_BTN_W, rowY, BACK_BTN_W, BACK_BTN_H, BACK_TEXT, font
            ) { closeCrew() }
        )
    }

    private fun addTab(index: Int, text: Component, tab: Tab) {
        // Read-only: Operations and Roster are about a SHIP -- guns to man, berths aboard a hull -- and
        // there is no ship. They stay visible and dim rather than vanishing, so the book reads as the same
        // book with two thirds of it unavailable, which is the true state of affairs.
        val unavailable = snapshot.readOnly && tab != Tab.CREWS
        addRenderableWidget(
            ShipHelmTab(
                left + TAB_MARGIN + (TAB_W + TAB_GAP) * index, top + TAB_Y, TAB_W, TAB_H, text, font,
                ACCENT,
                {
                    when {
                        unavailable -> ShipHelmTab.State.DISABLED
                        activeTab == tab -> ShipHelmTab.State.ACTIVE
                        else -> ShipHelmTab.State.IDLE
                    }
                }
            ) { switchTab(tab) }
        ).also { it.active = !unavailable }
    }

    private fun switchTab(next: Tab) {
        if (activeTab == next) return
        // The one road into the other two tabs, so the read-only refusal lives here rather than at each of
        // the mouse, key and pad routes that reach it.
        if (snapshot.readOnly && next != Tab.CREWS) return
        activeTab = next
        closeOpsMenus()
        if (renamingCrew) commitCrewRename(send = false)
        padSel = -1
        // A crew's articles do not survive leaving the tab: the list is a snapshot, and coming back to a
        // stale roster is how a captain reads a crew that has since been disbanded.
        openCrew = null
        crewRoster = null
        disbandArmed = null
        crewsScroll = 0
        scroll = 0
        if (renamingOwnedCrew) commitOwnedCrewRename(send = false)
        rebuildWidgets()
        // Fresh counts every time the tab comes up: the holds change while the book is shut.
        if (next == Tab.OPERATIONS) PathNetworkingFabric.sendCrewStoresAsk(snapshot.helm)
        // Same reasoning for the crews: one may have been disbanded, renamed or called elsewhere since.
        if (next == Tab.CREWS) CrewRoll.clientAsk(snapshot.helm)
    }

    /** Every transient Operations overlay, folded at once -- a tab switch or card must not leave one up. */
    private fun closeOpsMenus() {
        ammoMenuOpen = false
        fuelPopupOpen = false
        layerMenuFor = -1
    }

    /** Whether any Operations overlay is up. The body underneath is inert while one is. */
    private fun opsMenuOpen(): Boolean = ammoMenuOpen || fuelPopupOpen || layerMenuFor >= 0

    /**
     * The Operations tab's real widgets: just the two count boxes. Every other control is painted. Their
     * positions here are provisional -- the body scrolls, so [render] re-seats them every frame.
     */
    private fun initOperations() {
        gunnerCountBox = addRenderableWidget(
            // Clamped on the way in: a habit carried from a first-rate must not ask a sloop for forty hands.
            countBox(left + OPS_BOX_X, opsRowY(OPS_V_ROW_G), gunnerCount.coerceIn(0, snapshot.maxBerths)) {
                gunnerCount = it
            }
        )
        fireCountBox = addRenderableWidget(
            countBox(left + OPS_BOX_X, opsRowY(OPS_V_ROW_F), fireCount.coerceIn(0, snapshot.maxBerths)) {
                fireCount = it
            }
        )
        // First look at the holds: asked once, not polled -- every action answers with a fresh tally.
        if (stores == null) PathNetworkingFabric.sendCrewStoresAsk(snapshot.helm)
    }

    private fun countBox(x: Int, y: Int, value: Int, write: (Int) -> Unit): EditBox =
        EditBox(font, x, y, OPS_BOX_W, OPS_CTRL_H, COUNT_TEXT).also { box ->
            box.setMaxLength(3)
            box.setFilter { text -> text.isEmpty() || text.all { c -> c.isDigit() } }
            box.value = value.toString()
            box.setResponder { typed -> write((typed.toIntOrNull() ?: 0).coerceIn(0, snapshot.maxBerths)) }
        }

    /** The steppers' shared arithmetic; writes the field AND the box so both stay one value. */
    private fun adjustCount(gunners: Boolean, delta: Int) {
        if (gunners) {
            gunnerCount = (gunnerCount + delta).coerceIn(0, snapshot.maxBerths)
            gunnerCountBox?.value = gunnerCount.toString()
        } else {
            fireCount = (fireCount + delta).coerceIn(0, snapshot.maxBerths)
            fireCountBox?.value = fireCount.toString()
        }
    }

    // endregion

    // region opening and closing a card

    private fun openCard(row: CrewManifest.Row) {
        openCard = row.villager
        detail = null
        dismissArmed = false
        cardAmmoMenuOpen = false
        // The row already carries the name, so the field is right the instant the card opens rather than one
        // round trip later. The detail packet only ever adds to what is on screen; it never corrects it.
        nameValue = row.name
        PathNetworkingFabric.sendCrewAsk(snapshot.helm, row.villager)
        // The holds too: a gunner's ammo dropdown lists what is aboard, and this may be the first card
        // opened before the Operations tab ever asked.
        PathNetworkingFabric.sendCrewStoresAsk(snapshot.helm)
        rebuildWidgets()
    }

    private fun closeCard() {
        // Leaving the card is the lock-in: whatever the dropdown settled on goes to the server now.
        commitPendingStation()
        openCard = null
        detail = null
        nameBox = null
        cardRenameButton = null
        cardDismissButton = null
        cardBackButton = null
        dismissArmed = false
        stationMenuOpen = false
        cardAmmoMenuOpen = false
        stationBaseline = null
        rebuildWidgets()
    }

    /**
     * Send the station the dropdown settled on, if it differs from what the server last confirmed.
     *
     * One send per card visit, at lock-in -- picking three guns in a row costs nothing until the player backs
     * out. Idempotent: the baseline is advanced immediately, so the screen-close path and the Back path
     * cannot double-send.
     */
    private fun commitPendingStation() {
        val card = detail ?: return
        val baseline = stationBaseline ?: return
        if (card.duty == CrewDuty.GUNNER && card.stationLabel != baseline) {
            PathNetworkingFabric.sendCrewStation(snapshot.helm, card.villager, card.stationLabel)
        }
        stationBaseline = card.stationLabel
    }

    /** Closing the whole screen (ESC, E, another screen) locks in the same way backing out does. */
    override fun removed() {
        commitPendingStation()
        super.removed()
    }

    /**
     * First press arms, second press sends.
     *
     * The card is closed on the way out rather than waiting for the answer: the server replies with a whole
     * fresh manifest, and [refresh] closes a card whose crew member is no longer on it anyway. Doing it here
     * simply means the screen never spends a round trip showing somebody who has already been paid off.
     */
    private fun pressDismiss() {
        val villager = openCard ?: return
        if (!dismissArmed) {
            dismissArmed = true
            rebuildWidgets()
            return
        }
        PathNetworkingFabric.sendCrewDismiss(snapshot.helm, villager)
        closeCard()
    }

    /** The row Back sits on, which Rename Crew and the crew's name now share. */
    private fun bottomRowY(): Int = top + PANEL_H - 4 - BACK_BTN_H

    /** Rename Crew's left edge: immediately left of Back, with a gap so the two do not read as one control. */
    private fun crewRenameX(): Int =
        left + PANEL_W - 8 - BACK_BTN_W - CREW_RENAME_GAP - CREW_RENAME_BTN_W

    /**
     * Start editing the crew's name, seeded with what it is now.
     *
     * Seeded from [CrewManifest.Snapshot.crew], NOT from `ship`. It used to read `ship`, which meant an unnamed
     * wheel pre-filled the box with the HULL's name -- and since the commit below only sends when the value has
     * changed, pressing Save on that pre-filled text did nothing whatsoever. The crew looked named and was not.
     */
    private fun beginCrewRename() {
        crewNameValue = snapshot.crew
        renamingCrew = true
        rebuildWidgets()
    }

    /**
     * Send the crew's new name, or abandon the edit.
     *
     * Goes through the `helm_name` path, which is now purely a crew rename: a field write on the ledger, with
     * nothing to refuse. The wheel's own name is the SHIP's name and is typed in the helm menu instead.
     */
    private fun commitCrewRename(send: Boolean) {
        if (send) {
            val typed = crewNameValue.trim()
            if (typed.isNotEmpty() && typed != snapshot.crew) {
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
        val before = detail
        detail = next
        nameValue = next.name
        nameBox?.value = next.name
        // The server's word on the station is the new baseline, and any half-made dropdown choice yields to
        // it -- a fresh card arriving mid-pick means something real changed underneath the menu.
        stationBaseline = next.stationLabel
        stationMenuOpen = false
        if (next.locked) cardAmmoMenuOpen = false
        // Dismiss is a real widget, and its enabled state follows the lock -- a rebuild is how a widget
        // learns anything. Only when the lock actually moved, so typing in the name box is not disturbed.
        if (before == null || before.locked != next.locked) rebuildWidgets()
    }

    // endregion

    // region input

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true

        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        // The card's own hand-drawn controls, tested before the card swallows the click. Only live once the
        // detail has arrived -- there is no duty to cycle while the card still says "Reading the articles".
        if (openCard != null) {
            val card = detail
            if (card != null) {
                // A dropdown, while open, owns the click entirely: an entry may select, anywhere else
                // folds it away, and nothing underneath it may fire through it.
                if (cardAmmoMenuOpen) {
                    handleCardAmmoClick(card, mx, my)
                    return true
                }
                if (stationMenuOpen) {
                    handleStationMenuClick(card, mx, my)
                    return true
                }

                // Nothing on a Crews-tab card is clickable. The controls are not drawn there, and a hit test
                // that still fired would be an invisible button -- the worst of both.
                if (activeTab == Tab.CREWS) return false

                // The Lock answers FIRST, and answers even locked -- it is the one way back out.
                if (mx >= lockButtonX() && mx < lockButtonX() + LOCK_BTN_W &&
                    my >= lockButtonY() && my < lockButtonY() + BACK_BTN_H
                ) {
                    toggleLock(card)
                    return true
                }
                if (card.locked) return false

                val bx = dutyButtonX()
                val by = dutyRowY()
                if (mx >= bx && mx < bx + DUTY_BTN_W && my >= by && my < by + DUTY_BTN_H) {
                    cycleDuty()
                    return true
                }
                val sy = stationRowY()
                if (card.duty == CrewDuty.GUNNER && card.gunOptions.isNotEmpty() &&
                    mx >= bx && mx < bx + DUTY_BTN_W && my >= sy && my < sy + DUTY_BTN_H
                ) {
                    stationMenuOpen = true
                    stationMenuScroll = 0
                    return true
                }
                if (gunPanelShown(card)) {
                    for (row in 0..2) {
                        val gy = gunRowY(row)
                        val gx = if (row == 2) cardX() + CARD_W - CARD_PAD - GUN_AMMO_BTN_W else bx
                        val gw = if (row == 2) GUN_AMMO_BTN_W else DUTY_BTN_W
                        if (mx >= gx && mx < gx + gw && my >= gy && my < gy + DUTY_BTN_H) {
                            when (row) {
                                0 -> cycleCharge(card)
                                1 -> cycleElevation(card)
                                else -> openCardAmmoMenu()
                            }
                            return true
                        }
                    }
                }
            }
            return false
        }

        if (activeTab == Tab.OPERATIONS) return handleOpsClick(mx, my)
        if (activeTab == Tab.CREWS && openCrew == null) return handleCrewsClick(mx, my)
        // Clicking off the box while renaming a crew commits it, exactly as the ship's own name field does.
        if (renamingOwnedCrew) {
            commitOwnedCrewRename(send = true)
            return true
        }

        // Clicking anywhere off the box while renaming commits it, the way a name field is expected to behave.
        if (renamingCrew) {
            commitCrewRename(send = true)
            return true
        }

        // The heading is a name, and clicking it edits it -- the ship's crew on the Roster tab, the OPEN crew
        // on the Crews tab. Bounded to the left of the berth counter and above the tab strip, so aiming at
        // the count or a tab never starts a rename.
        if (mx >= left && mx <= left + PANEL_W - BERTHS_GUTTER && my >= top + 2 && my < top + HEADER_BOTTOM) {
            // Only on the Crews tab. On the Roster the heading is the SHIP's name, and clicking a ship's
            // name to rename a crew was a coincidence of where the box used to be drawn.
            if (activeTab == Tab.CREWS) {
                beginOwnedCrewRename()
                return true
            }
        }

        // The crew's name at the bottom answers to a click, the way the heading used to.
        if (activeTab == Tab.ROSTER && openCard == null &&
            mx >= left + 6 && mx < crewRenameX() - 4 &&
            my >= bottomRowY() && my < bottomRowY() + BACK_BTN_H
        ) {
            beginCrewRename()
            return true
        }

        if (mx < left || mx > left + PANEL_W) return false
        if (my < top + LIST_TOP || my >= top + LIST_BOTTOM) return false

        val berth = scroll + (my - (top + LIST_TOP)) / ROW_H
        // A crew's own articles are indexed by position, the ship's by berth number -- see drawRow.
        val crewView = viewingCrew()
        val row = if (crewView != null) crewView.rows.getOrNull(berth)
        else snapshot.rows.firstOrNull { it.slot == berth }
        openCard(row ?: return false)
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollY: Double): Boolean {
        if (openCard != null) {
            val card = detail
            if (cardAmmoMenuOpen) {
                val max = ((stores?.ammo?.size ?: 0) - AMMO_MENU_ROWS).coerceAtLeast(0)
                cardAmmoMenuScroll = (cardAmmoMenuScroll - scrollY.toInt()).coerceIn(0, max)
                return true
            }
            if (stationMenuOpen && card != null) {
                val max = (stationMenuEntries(card) - STATION_MENU_ROWS).coerceAtLeast(0)
                stationMenuScroll = (stationMenuScroll - scrollY.toInt()).coerceIn(0, max)
                return true
            }
            return super.mouseScrolled(mouseX, mouseY, scrollY)
        }
        if (activeTab == Tab.CREWS && openCrew == null) {
            val count = crewRoll?.entries?.size ?: 0
            crewsScroll = (crewsScroll - scrollY.toInt()).coerceIn(0, (count - VISIBLE_ROWS).coerceAtLeast(0))
            return true
        }
        if (activeTab == Tab.OPERATIONS) {
            val holds = stores
            if (ammoMenuOpen && holds != null) {
                val max = (holds.ammo.size - AMMO_MENU_ROWS).coerceAtLeast(0)
                ammoMenuScroll = (ammoMenuScroll - scrollY.toInt()).coerceIn(0, max)
            } else if (layerMenuFor >= 0) {
                val max = (deckCounts.size + 1 - LAYER_MENU_ROWS).coerceAtLeast(0)
                layerMenuScroll = (layerMenuScroll - scrollY.toInt()).coerceIn(0, max)
            } else if (!fuelPopupOpen) {
                // The body itself scrolls now: three categories of rows outgrew one panel.
                opsScroll -= scrollY.toInt() * OPS_SCROLL_STEP
                clampOpsScroll()
            }
            // Swallowed either way: nothing behind the Operations body should hear the wheel.
            return true
        }
        scroll -= scrollY.toInt()
        clampScroll()
        return true
    }

    private fun clampOpsScroll() {
        opsScroll = opsScroll.coerceIn(0, (OPS_CONTENT_H - OPS_BODY_H).coerceAtLeast(0))
    }

    // region the pad drives the screen

    /**
     * Controller support, read straight off the hardware (VS2's ShipGamepad, polled at the head of this
     * same tick) because a controller mod's screen handling never reaches a custom screen.
     *
     * The scheme: the RIGHT STICK is the scroll wheel, and the D-PAD is the hover -- up/down walk the
     * selection through whatever is in front of you (crew rows; the card's two buttons; the station
     * dropdown's free entries), painted through the exact same highlight the mouse hover uses. D-pad
     * RIGHT is the click on whatever is selected, and D-pad LEFT is the way back out (fold the dropdown,
     * leave the card -- which is also the lock-in gesture, exactly as Back is).
     */
    override fun tick() {
        super.tick()
        // The bumpers walk the tab strip, exactly as they do on the helm menu -- but only while the strip
        // is actually on screen: under a card or a popup they are inert, so backing out stays one gesture.
        if (openCard == null && !renamingCrew && !opsMenuOpen()) {
            if (ShipGamepad.bumperLeftPressed() || ShipGamepad.bumperRightPressed()) {
                switchTab(if (activeTab == Tab.OPERATIONS) Tab.ROSTER else Tab.OPERATIONS)
            }
        }
        padScroll()
        padNavigate()
        // The presses this screen answered must not double as deck actions the moment it closes.
        ShipGamepad.drainPresses()
    }

    /** Pad-driven selection: a berth slot on the roster, a control index on the card, an entry in the menu. */
    private var padSel = -1
    private var padContext = 0
    private var padVertHeld = 0
    private var stickHeld = 0

    private fun padScroll() {
        val deflect = ShipGamepad.rightStickY()
        if (abs(deflect) < STICK_DEADZONE) {
            stickHeld = 0
            return
        }
        if (stickHeld % STICK_EVERY == 0) {
            // Stick up (negative) scrolls up, exactly as a wheel-up does. Coordinates are unused by our
            // scroll handling, which branches on screen state instead.
            mouseScrolled(0.0, 0.0, if (deflect < 0) 1.0 else -1.0)
        }
        stickHeld++
    }

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

    private fun padNavigate() {
        // The selection is meaningful only within one view; entering or leaving the card, a dropdown or a
        // tab drops it rather than letting a row index masquerade as a button index.
        val context = when {
            fuelPopupOpen -> 5
            ammoMenuOpen -> 4
            cardAmmoMenuOpen -> 6
            layerMenuFor >= 0 -> 7
            stationMenuOpen -> 2
            openCard != null -> 1
            activeTab == Tab.OPERATIONS -> 3
            else -> 0
        }
        if (context != padContext) {
            padContext = context
            padSel = -1
        }

        val step = verticalStep()
        val choose = ShipGamepad.dpadRightPressed()
        val back = ShipGamepad.dpadLeftPressed()
        if (step == 0 && !choose && !back) return

        when (context) {
            7 -> padLayerMenu(step, choose, back)
            6 -> padCardAmmo(step, choose, back)
            5 -> padFuel(back, choose)
            4 -> padOpsAmmo(step, choose, back)
            3 -> padOps(step, choose, back)
            2 -> padMenu(step, choose, back)
            1 -> padCard(step, choose, back)
            else -> padRoster(step, choose)
        }
    }

    /** One deck dropdown, walked entry by entry: "All decks" first, then every deck keel-up. */
    private fun padLayerMenu(step: Int, choose: Boolean, back: Boolean) {
        if (back) {
            layerMenuFor = -1
            return
        }
        val entries = deckCounts.size + 1
        if (step != 0) {
            padSel = (if (padSel < 0) (if (step > 0) 0 else entries - 1) else padSel + step)
                .coerceIn(0, entries - 1)
            if (padSel < layerMenuScroll) layerMenuScroll = padSel
            if (padSel >= layerMenuScroll + LAYER_MENU_ROWS) layerMenuScroll = padSel - LAYER_MENU_ROWS + 1
        }
        if (choose && padSel >= 0) {
            setLayer(layerMenuFor, padSel)
            layerMenuFor = -1
        }
    }

    private fun padCardAmmo(step: Int, choose: Boolean, back: Boolean) {
        if (back) {
            cardAmmoMenuOpen = false
            return
        }
        val card = detail ?: return
        val holds = stores ?: return
        if (holds.ammo.isEmpty()) return
        if (step != 0) {
            padSel = (if (padSel < 0) (if (step > 0) 0 else holds.ammo.size - 1) else padSel + step)
                .coerceIn(0, holds.ammo.size - 1)
            if (padSel < cardAmmoMenuScroll) cardAmmoMenuScroll = padSel
            if (padSel >= cardAmmoMenuScroll + AMMO_MENU_ROWS) cardAmmoMenuScroll = padSel - AMMO_MENU_ROWS + 1
        }
        if (choose && padSel >= 0) {
            holds.ammo.getOrNull(padSel)?.let { pick ->
                PathNetworkingFabric.sendCrewGunAmmo(snapshot.helm, card.villager, pick.ball, pick.charge)
                cardAmmoMenuOpen = false
            }
        }
    }

    /**
     * The Operations rows, walked top to bottom. Choose is the click; back only clears the selection.
     * The selection drags the body with it, the way the roster's does -- a stop the pad rests on is
     * always in view, however far the body was scrolled.
     */
    private fun padOps(step: Int, choose: Boolean, back: Boolean) {
        if (back) {
            padSel = -1
            return
        }
        if (step != 0) {
            padSel = (if (padSel < 0) (if (step > 0) 0 else OPS_STOP_COUNT - 1) else padSel + step)
                .coerceIn(0, OPS_STOP_COUNT - 1)
            val v = opsVirtualRect(padSel)
            if (v[1] < opsScroll + 2) opsScroll = (v[1] - 4).coerceAtLeast(0)
            if (v[1] + v[3] > opsScroll + OPS_BODY_H - 2) opsScroll = v[1] + v[3] - OPS_BODY_H + 4
            clampOpsScroll()
        }
        if (choose) {
            if (padSel < 0) {
                padSel = 0
            } else {
                opsActivate(padSel, null)
            }
        }
    }

    private fun padOpsAmmo(step: Int, choose: Boolean, back: Boolean) {
        if (back) {
            ammoMenuOpen = false
            return
        }
        val holds = stores ?: return
        if (holds.ammo.isEmpty()) return
        if (step != 0) {
            padSel = (if (padSel < 0) (if (step > 0) 0 else holds.ammo.size - 1) else padSel + step)
                .coerceIn(0, holds.ammo.size - 1)
            if (padSel < ammoMenuScroll) ammoMenuScroll = padSel
            if (padSel >= ammoMenuScroll + AMMO_MENU_ROWS) ammoMenuScroll = padSel - AMMO_MENU_ROWS + 1
        }
        if (choose && padSel >= 0) {
            holds.ammo.getOrNull(padSel)?.let { pick ->
                selectedAmmo = pick.ball to pick.charge
                ammoMenuOpen = false
            }
        }
    }

    /** The fuel popup shows the top of the plan and nothing else -- any decisive press just closes it. */
    private fun padFuel(back: Boolean, choose: Boolean) {
        if (back || choose) fuelPopupOpen = false
    }

    private fun padRoster(step: Int, choose: Boolean) {
        val manned = snapshot.rows.map { it.slot }.sorted()
        if (manned.isEmpty()) return
        if (step != 0) {
            val current = manned.indexOf(padSel)
            padSel = when {
                padSel < 0 -> if (step > 0) manned.first() else manned.last()
                current < 0 -> manned.first()
                else -> manned[(current + step).coerceIn(0, manned.size - 1)]
            }
            // The selection drags the list with it, the way keyboard focus is expected to.
            if (padSel < scroll) scroll = padSel
            if (padSel >= scroll + VISIBLE_ROWS) scroll = padSel - VISIBLE_ROWS + 1
            clampScroll()
        }
        if (choose && padSel >= 0) {
            snapshot.rows.firstOrNull { it.slot == padSel }?.let { openCard(it) }
        }
    }

    private fun padCard(step: Int, choose: Boolean, back: Boolean) {
        if (back) {
            closeCard()
            return
        }
        val card = detail ?: return
        val stops = cardStops(card)
        if (stops.isEmpty()) return
        if (step != 0) padSel = (if (padSel < 0) 0 else padSel + step).coerceIn(0, stops.size - 1)
        if (choose) {
            if (padSel < 0) {
                // The first press only takes the selection -- an unaimed "choose" must not re-assign a duty.
                padSel = 0
                return
            }
            when (stops.getOrNull(padSel)) {
                CARD_STOP_DUTY -> cycleDuty()
                CARD_STOP_STATION -> {
                    stationMenuOpen = true
                    stationMenuScroll = 0
                }
                CARD_STOP_CHARGE -> cycleCharge(card)
                CARD_STOP_ELEVATION -> cycleElevation(card)
                CARD_STOP_AMMO -> openCardAmmoMenu()
                CARD_STOP_LOCK -> toggleLock(card)
            }
        }
    }

    private fun padMenu(step: Int, choose: Boolean, back: Boolean) {
        if (back) {
            stationMenuOpen = false
            return
        }
        val card = detail ?: return
        // Only the entries a click would answer: "--" and the free guns. Manned guns stay visible to the
        // eye but are stepped over, exactly as they refuse the mouse.
        val selectable = (0 until stationMenuEntries(card)).filter { index ->
            index == 0 || card.gunOptions.getOrNull(index - 1)?.occupant?.isEmpty() == true
        }
        if (selectable.isEmpty()) return
        if (step != 0) {
            val current = selectable.indexOf(padSel)
            padSel = when {
                padSel < 0 -> if (step > 0) selectable.first() else selectable.last()
                current < 0 -> selectable.first()
                else -> selectable[(current + step).coerceIn(0, selectable.size - 1)]
            }
            if (padSel < stationMenuScroll) stationMenuScroll = padSel
            if (padSel >= stationMenuScroll + STATION_MENU_ROWS) stationMenuScroll = padSel - STATION_MENU_ROWS + 1
        }
        if (choose && padSel >= 0) {
            if (padSel == 0) {
                detail = card.copy(stationLabel = "")
                stationMenuOpen = false
            } else {
                card.gunOptions.getOrNull(padSel - 1)?.takeIf { it.occupant.isEmpty() }?.let { option ->
                    detail = card.copy(stationLabel = option.label)
                    stationMenuOpen = false
                }
            }
        }
    }

    // endregion

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Enter commits a rename rather than closing the screen, which is what a focused text field implies.
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (nameBox?.isFocused == true) {
                commitRename()
                return true
            }
            if (renamingCrew) {
                commitCrewRename(send = true)
                return true
            }
            if (renamingOwnedCrew) {
                commitOwnedCrewRename(send = true)
                return true
            }
        }
        // Escape steps back out of whatever is innermost: a popup, then a rename, then a card, then out.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (opsMenuOpen()) {
                closeOpsMenus()
                return true
            }
            if (cardAmmoMenuOpen) {
                cardAmmoMenuOpen = false
                return true
            }
            if (renamingCrew) {
                commitCrewRename(send = false)
                return true
            }
            if (renamingOwnedCrew) {
                commitOwnedCrewRename(send = false)
                return true
            }
            if (openCard != null) {
                closeCard()
                return true
            }
            // Then out of a crew's articles to the list of them, before the screen itself closes.
            if (openCrew != null) {
                closeCrew()
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    /**
     * The crew whose articles the list is currently showing, or null for this ship's own.
     *
     * The Crews tab reuses the roster's whole renderer rather than growing a second one that looks nearly
     * like it -- so the row drawing, the scrollbar and the card all read through [rowsInView] and friends,
     * and the only thing that differs between "this ship's crew" and "a crew I keep" is where the rows came
     * from. Guarded on the tab as well as on the id, so a roster left loaded cannot leak into the ship's own
     * list when the captain switches back.
     */
    private fun viewingCrew(): CrewRoll.Roster? =
        if (activeTab == Tab.CREWS && openCrew != null) crewRoster?.takeIf { it.id == openCrew } else null

    private fun rowsInView(): List<CrewManifest.Row> = viewingCrew()?.rows ?: snapshot.rows

    /**
     * How many berth slots the list draws. A crew's own articles have no EMPTY berths to show -- berths are a
     * property of the captain and the ship they are standing on, not of the crew -- so it draws exactly the
     * hands it has.
     */
    private fun berthsInView(): Int = viewingCrew()?.rows?.size ?: snapshot.berths

    private fun maxBerthsInView(): Int = viewingCrew()?.rows?.size ?: snapshot.maxBerths

    private fun clampScroll() {
        val overflow = maxBerthsInView() - VISIBLE_ROWS
        scroll = scroll.coerceIn(0, if (overflow < 0) 0 else overflow)
    }

    private fun rowOf(villager: UUID): CrewManifest.Row? = rowsInView().firstOrNull { it.villager == villager }

    // endregion

    // region drawing

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        // 1.21.1: renderBackground is OURS to call, and it must come FIRST -- it runs the menu-blur pass, so
        // anything drawn before it gets blurred into soup. (1.21.11 runs it before render() ever fires.)
        renderBackground(guiGraphics)
        panel(guiGraphics, left, top, PANEL_W, PANEL_H)
        drawHeader(guiGraphics)
        if (activeTab == Tab.OPERATIONS && openCard == null) {
            drawOperations(guiGraphics, mouseX, mouseY)
        } else if (activeTab == Tab.CREWS && openCrew == null && openCard == null) {
            drawCrews(guiGraphics, mouseX, mouseY)
        } else {
            drawList(guiGraphics, mouseX, mouseY)
        }
        // Flush the batched row draws (the villager-head blits) before the card: 1.21.1 batches textured
        // blits separately from fills, and an unflushed head batch draws OVER the card panel.
        if (openCard != null) {
            guiGraphics.flush()
            drawCard(guiGraphics, mouseX, mouseY)
        }
        // The count boxes are real widgets, which super paints LAST and OUTSIDE the body's scissor -- so
        // they are re-seated from the scrolled layout every frame, and shown only while their row is
        // fully inside the body and no overlay owns the screen. A widget half over the tab strip, or
        // painted through a popup, would be the scissor's one leak.
        positionCountBox(gunnerCountBox, OPS_V_ROW_G)
        positionCountBox(fireCountBox, OPS_V_ROW_F)
        // The card's widgets step aside for the card's own dropdowns, for both halves of the same reason:
        // super paints them over the list, and answers their clicks before the list is ever asked.
        val cardWidgetsVisible = !cardMenuOpen()
        ownedCrewNameBox?.visible = !opsMenuOpen()
        nameBox?.visible = cardWidgetsVisible
        cardRenameButton?.visible = cardWidgetsVisible
        cardDismissButton?.visible = cardWidgetsVisible
        cardBackButton?.visible = cardWidgetsVisible
        // The corner Back yields to every overlay too -- an unfolded ammo list can reach the corner, and a
        // widget painted through it would also steal its clicks.
        helmBackButton?.visible = !opsMenuOpen()
        // Widgets drawn directly: super.render would call renderBackground AGAIN and blur the panels above.
        for (child in children()) if (child is Renderable) child.render(guiGraphics, mouseX, mouseY, partialTicks)
    }

    /** Whether a list is unfolded over the card. While one is, it owns the card entirely. */
    private fun cardMenuOpen(): Boolean = stationMenuOpen || cardAmmoMenuOpen

    private fun positionCountBox(box: EditBox?, vy: Int) {
        if (box == null) return
        box.x = left + OPS_BOX_X
        box.y = opsRowY(vy)
        box.visible = activeTab == Tab.OPERATIONS && openCard == null && !opsMenuOpen() &&
            vy >= opsScroll && vy + OPS_CTRL_H <= opsScroll + OPS_BODY_H
    }

    private fun drawHeader(guiGraphics: GuiGraphics) {
        // While renaming, the box occupies these pixels -- drawing the heading underneath would show through.
        // Only the CREWS tab's rename does that now: the roster's box has moved down to the crew's own name.
        val crewView = viewingCrew()
        if (!renamingOwnedCrew) {
            val heading = when {
                // A crew's own articles are headed by the CREW, not by the hull the book was opened on --
                // the crew may have nothing to do with this ship at all.
                crewView != null -> Component.literal(crewView.name)
                activeTab == Tab.CREWS -> TAB_CREWS_TEXT
                snapshot.ship.isEmpty() -> TITLE
                else -> Component.literal(snapshot.ship)
            }
            guiGraphics.drawString(font, heading, left + 8, top + 6, TEXT, false)
        }

        // The crew's OWN name, bottom-left. The heading above names the SHIP, and the two are different
        // things -- a captain reading "Decor Battleship Blue" at the top had nowhere to see which crew was
        // aboard her. It sits where it is edited: the rename box takes exactly these pixels.
        if (activeTab == Tab.ROSTER && crewView == null && openCard == null && !renamingCrew) {
            snapshot.crew.takeIf { it.isNotEmpty() }?.let { crew ->
                guiGraphics.drawString(
                    font, Component.literal(crew), left + 6, bottomRowY() + 3, DIM, false
                )
            }
        }

        val berths = when {
            crewView != null -> Component.translatable("gui.vs_eureka.crew_hands", crewView.rows.size)
            activeTab == Tab.CREWS -> Component.translatable(
                "gui.vs_eureka.crew_hands", crewRoll?.entries?.size ?: 0
            )
            else -> Component.translatable("gui.vs_eureka.crew_berths", snapshot.rows.size, snapshot.berths)
        }
        guiGraphics.drawString(font, berths, left + PANEL_W - 8 - font.width(berths), top + 6, ACCENT, false)

        // The tab baseline: the strip's ACTIVE tab opens onto this line, exactly as the helm menu's does.
        guiGraphics.fill(left + 4, top + TAB_BASELINE_Y, left + PANEL_W - 4, top + TAB_BASELINE_Y + 1, ACCENT)
    }

    private fun drawList(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + LIST_BOTTOM)
        for (i in 0 until VISIBLE_ROWS) {
            val berth = scroll + i
            if (berth >= maxBerthsInView()) break
            drawRow(guiGraphics, berth, top + LIST_TOP + i * ROW_H, mouseX, mouseY)
        }
        guiGraphics.disableScissor()
        drawScrollbar(guiGraphics)
    }

    private fun drawRow(guiGraphics: GuiGraphics, berth: Int, y: Int, mouseX: Int, mouseY: Int) {
        // A crew's own articles are drawn by POSITION rather than by berth number: a crew that has been
        // moved between ships has whatever slots it happens to hold, and gaps drawn as empty berths would
        // read as room this crew does not own.
        val crewView = viewingCrew()
        val locked = crewView == null && berth >= snapshot.berths
        val row = when {
            crewView != null -> crewView.rows.getOrNull(berth)
            locked -> null
            else -> snapshot.rows.firstOrNull { it.slot == berth }
        }
        val hovered = openCard == null && row != null &&
            (
                (mouseX >= left && mouseX <= left + PANEL_W && mouseY >= y && mouseY < y + ROW_H) ||
                    berth == padSel
                )

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

        // The job, on the row itself, so a whole crew's assignments read at a glance instead of taking eight
        // cards to find. Drawn in the crew accent because a duty is the one thing on this row the captain
        // chose; everything else is a fact about the villager. A locked berth shows a padlock beside it --
        // drawn as rectangles rather than a font glyph, which a resource pack could re-shape or drop.
        var dutyRight = left + PANEL_W - 32
        if (row.locked) {
            val lx = left + PANEL_W - 38
            guiGraphics.fill(lx, y + 9, lx + 5, y + 13, ACCENT)          // body
            guiGraphics.fill(lx + 1, y + 6, lx + 4, y + 9, ACCENT)       // shackle, filled
            guiGraphics.fill(lx + 2, y + 7, lx + 3, y + 9, ROW_BG)       // shackle's window
            dutyRight = lx - 3
        }
        // Every duty is shown, the default included: a roster where "Crewman" is a blank reads as a row
        // with no assignment at all (user request, 2026-08-26).
        run {
            val duty = dutyName(row.duty)
            small(
                guiGraphics, duty,
                dutyRight - (font.width(duty) * SMALL).toInt(), y + 9, ACCENT
            )
        }

        // A card opens from anywhere on the row; the glyph is the affordance, not the only target.
        val bx = left + PANEL_W - 26
        guiGraphics.fill(bx, y + 4, bx + 14, y + 18, if (hovered) ACCENT else ROW_LOCKED)
        small(guiGraphics, INFO_GLYPH, bx + 5, y + 9, if (hovered) 0xFFFFFFFF.toInt() else TEXT)
    }

    // region the Crews tab

    /**
     * The captain's crews, one per row, with the fare to call them and a Delete that asks twice.
     *
     * A list of things that exist somewhere else, which is what makes it different from the ship's roster: a
     * crew on this list may be standing on this deck, on a hull across the world, or nowhere at all with its
     * hands written down in the articles. All the row can honestly say is who they are, how many, and what
     * moving them would cost.
     */
    private fun drawCrews(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val roll = crewRoll
        if (roll == null) {
            guiGraphics.drawString(font, LOADING_TEXT, left + 10, top + LIST_TOP + 6, DIM, false)
            return
        }
        if (roll.entries.isEmpty()) {
            guiGraphics.drawString(font, CREWS_NONE_TEXT, left + 10, top + LIST_TOP + 6, DIM, false)
            return
        }
        guiGraphics.enableScissor(left, top + LIST_TOP, left + PANEL_W, top + LIST_BOTTOM)
        for (i in 0 until VISIBLE_ROWS) {
            val entry = roll.entries.getOrNull(crewsScroll + i) ?: break
            drawCrewRow(guiGraphics, entry, top + LIST_TOP + i * ROW_H, mouseX, mouseY)
        }
        guiGraphics.disableScissor()
        drawCrewsScrollbar(guiGraphics, roll.entries.size)
    }

    private fun drawCrewRow(
        guiGraphics: GuiGraphics,
        entry: CrewRoll.Entry,
        y: Int,
        mouseX: Int,
        mouseY: Int
    ) {
        val delX = left + PANEL_W - 8 - CREW_DEL_W
        val overDelete = mouseX >= delX && mouseX < delX + CREW_DEL_W &&
            mouseY >= y + CREW_DEL_INSET && mouseY < y + CREW_DEL_INSET + CREW_DEL_H
        val hovered = !overDelete &&
            mouseX >= left && mouseX <= left + PANEL_W && mouseY >= y && mouseY < y + ROW_H

        guiGraphics.fill(left + 4, y + 1, left + PANEL_W - 4, y + ROW_H - 1, if (hovered) ROW_HOVER else ROW_BG)
        guiGraphics.drawString(font, entry.name, left + 10, y + 4, TEXT, false)

        // What it would cost, said plainly. The crew already aboard reads "aboard" instead of "0", because a
        // price of nothing and no price at all are different things to a captain deciding where to spend.
        val note = if (entry.aboard) {
            Component.translatable("gui.vs_eureka.crew_fare_free")
        } else {
            Component.translatable("gui.vs_eureka.crew_fare", entry.fare)
        }
        small(guiGraphics, Component.translatable("gui.vs_eureka.crew_hands", entry.heads), left + 10, y + 15, DIM)
        small(
            guiGraphics, note,
            delX - 6 - (font.width(note) * SMALL).toInt(), y + 9,
            if (entry.aboard) ACCENT else DIM
        )

        // No Delete on a read-only book. Not drawn at all rather than drawn dead: this list is a reading,
        // and an unpressable button on every row would be the loudest thing on it.
        if (snapshot.readOnly) return

        val armed = disbandArmed == entry.id
        val label = if (armed) CREW_DELETE_CONFIRM_TEXT else CREW_DELETE_TEXT
        guiGraphics.fill(
            delX, y + CREW_DEL_INSET, delX + CREW_DEL_W, y + CREW_DEL_INSET + CREW_DEL_H,
            when {
                armed -> DANGER
                overDelete -> ACCENT
                else -> ROW_LOCKED
            }
        )
        small(
            guiGraphics, label,
            delX + (CREW_DEL_W - (font.width(label) * SMALL).toInt()) / 2, y + CREW_DEL_INSET + 4,
            if (armed || overDelete) 0xFFFFFFFF.toInt() else TEXT
        )
    }

    private fun drawCrewsScrollbar(guiGraphics: GuiGraphics, count: Int) {
        if (count <= VISIBLE_ROWS) return
        val trackTop = top + LIST_TOP
        val trackH = LIST_BOTTOM - LIST_TOP
        val x = left + PANEL_W - 7
        guiGraphics.fill(x, trackTop, x + 3, trackTop + trackH, ROW_LOCKED)
        val thumbH = (trackH * VISIBLE_ROWS / count).coerceAtLeast(12)
        val thumbY = trackTop + (trackH - thumbH) * crewsScroll / (count - VISIBLE_ROWS)
        guiGraphics.fill(x, thumbY, x + 3, thumbY + thumbH, ACCENT)
    }

    /** A click on the crews list: the Delete box, or the row itself to read that crew's articles. */
    private fun handleCrewsClick(mx: Int, my: Int): Boolean {
        val roll = crewRoll ?: return false
        if (mx < left || mx > left + PANEL_W) return false
        if (my < top + LIST_TOP || my >= top + LIST_BOTTOM) return false

        val index = crewsScroll + (my - (top + LIST_TOP)) / ROW_H
        val entry = roll.entries.getOrNull(index) ?: return false
        val rowY = top + LIST_TOP + (index - crewsScroll) * ROW_H
        val delX = left + PANEL_W - 8 - CREW_DEL_W
        val onDelete = mx >= delX && mx < delX + CREW_DEL_W &&
            my >= rowY + CREW_DEL_INSET && my < rowY + CREW_DEL_INSET + CREW_DEL_H

        if (onDelete) {
            // Disbanding is a change, and a book opened away from a ship is for reading only. The box is
            // not drawn in that mode either -- see drawCrewRow -- so this is the belt to that braces.
            if (snapshot.readOnly) return true
            // Two presses, and the arming is per crew: aiming at one Delete must never fire another's.
            if (disbandArmed == entry.id) {
                disbandArmed = null
                CrewRoll.clientDisband(snapshot.helm, entry.id)
            } else {
                disbandArmed = entry.id
            }
            return true
        }

        disbandArmed = null
        openCrew = entry.id
        crewRoster = null
        scroll = 0
        CrewRoll.clientRosterAsk(snapshot.helm, entry.id)
        rebuildWidgets()
        return true
    }

    /** Back out of one crew's articles to the list of them. */
    private fun closeCrew() {
        openCrew = null
        crewRoster = null
        scroll = 0
        if (renamingOwnedCrew) commitOwnedCrewRename(send = false)
        rebuildWidgets()
    }

    private fun beginOwnedCrewRename() {
        ownedCrewNameValue = crewRoster?.name ?: return
        renamingOwnedCrew = true
        rebuildWidgets()
    }

    private fun commitOwnedCrewRename(send: Boolean) {
        val crew = openCrew
        if (send && crew != null) {
            val typed = ownedCrewNameValue.trim()
            if (typed.isNotEmpty() && typed != crewRoster?.name) {
                CrewRoll.clientRenameCrew(snapshot.helm, crew, typed)
            }
        }
        renamingOwnedCrew = false
        ownedCrewNameBox = null
        rebuildWidgets()
    }

    // endregion

    private fun drawScrollbar(guiGraphics: GuiGraphics) {
        if (maxBerthsInView() <= VISIBLE_ROWS) return
        val trackTop = top + LIST_TOP
        val trackH = LIST_BOTTOM - LIST_TOP
        val x = left + PANEL_W - 7
        guiGraphics.fill(x, trackTop, x + 3, trackTop + trackH, ROW_LOCKED)

        val rows = maxBerthsInView()
        val thumbH = (trackH * VISIBLE_ROWS / rows).coerceAtLeast(12)
        val travel = trackH - thumbH
        val thumbY = trackTop + travel * scroll / (rows - VISIBLE_ROWS)
        guiGraphics.fill(x, thumbY, x + 3, thumbY + thumbH, ACCENT)
    }

    // region the Operations tab

    /**
     * The Operations rows: painted and hand-tested like the roster, for the roster's reasons -- and one
     * more. Every control here is also a numbered PAD STOP, walked with the D-pad; a painted row asks
     * "am I stop N or under the mouse" in one expression, where a widget would need focus plumbing for
     * each of fifteen controls.
     */
    private fun drawOperations(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val holds = stores

        // The body clips to a viewport and scrolls: three categories of rows outgrew one panel when the
        // guns learned decks. Rows live at virtual offsets and meet the screen through [opsRowY] and
        // [opsStopRect]; the tabs above and the holds line below hold still.
        guiGraphics.enableScissor(left + 1, top + OPS_BODY_TOP, left + PANEL_W - 1, top + OPS_BODY_BOTTOM)

        small(guiGraphics, OPS_CREW_TEXT, left + 8, opsRowY(OPS_V_CREW_LABEL), DIM)

        // Gunners: [-] [count] [+] and the order; the scope it deals to sits on the row below it.
        small(guiGraphics, OPS_GUNNERS_TEXT, left + 8, opsRowY(OPS_V_ROW_G) + 4, TEXT)
        opsButton(guiGraphics, STOP_G_MINUS, MINUS_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_G_PLUS, PLUS_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_G_MODE, modeText(crewMode), mouseX, mouseY)
        opsButton(guiGraphics, STOP_G_ASSIGN, OPS_ASSIGN_TEXT, mouseX, mouseY)
        drawSides(guiGraphics, STOP_G_SIDE, opsRowY(OPS_V_ROW_GSCOPE), crewSide, OPS_SIDE_ALL_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_G_LAYER, layerButtonText(crewLayer), mouseX, mouseY)

        // Fire watch: a count and the order. No scope -- a fire does not care which battery you favour.
        small(guiGraphics, OPS_FIRE_TEXT, left + 8, opsRowY(OPS_V_ROW_F) + 4, TEXT)
        opsButton(guiGraphics, STOP_F_MINUS, MINUS_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_F_PLUS, PLUS_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_F_MODE, modeText(fireMode), mouseX, mouseY)
        opsButton(guiGraphics, STOP_F_ASSIGN, OPS_ASSIGN_TEXT, mouseX, mouseY)

        guiGraphics.fill(left + 8, opsRowY(OPS_V_SEP1), left + PANEL_W - 8, opsRowY(OPS_V_SEP1) + 1, SEPARATOR)
        small(guiGraphics, OPS_CTRL_TEXT, left + 8, opsRowY(OPS_V_CTRL_LABEL), DIM)

        // The controls' scope: ONE side + deck pair that Set Angle, Set Power, the ammunition pick and
        // the cannonball restock below all read. "Deck 1's port guns" is said once, not four times.
        drawSides(guiGraphics, STOP_C_SIDE, opsRowY(OPS_V_ROW_CSCOPE), ctrlSide, OPS_SIDE_ALL_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_C_LAYER, layerButtonText(ctrlLayer), mouseX, mouseY)

        // Set Angle and Set Power: the LABEL is the trigger -- press it and the scoped battery is laid
        // or set in one order. Locked gunners' guns keep their own settings, as everywhere bulk.
        opsButton(guiGraphics, STOP_LAY, OPS_ELEVATION_TEXT, mouseX, mouseY)
        drawAngles(guiGraphics, opsRowY(OPS_V_ROW_ELEV), mouseX, mouseY)
        opsButton(guiGraphics, STOP_PWR, OPS_POWER_TEXT, mouseX, mouseY)
        drawPowers(guiGraphics, opsRowY(OPS_V_ROW_PWR), mouseX, mouseY)

        // The round the battery is being worked with -- a selection, not an order: the restock below
        // spends it, and one day a bulk re-arm may too.
        small(guiGraphics, OPS_AMMO_LABEL_TEXT, left + 8, opsRowY(OPS_V_ROW_AMMO) + 4, TEXT)
        opsButton(guiGraphics, STOP_AMMO_MENU, ammoButtonText(), mouseX, mouseY)

        // The standing order: the gun crews lay their own guns at the nearest raider, and keep doing it
        // until it is lifted or the ship is shot below her line. Shift+G still works while it stands.
        small(guiGraphics, OPS_FIRE_AT_WILL_TEXT, left + 8, opsRowY(OPS_V_ROW_FIRE_AT_WILL) + 4, TEXT)
        opsButton(
            guiGraphics, STOP_FIRE_AT_WILL,
            if (fireAtWill) OPS_FIRE_AT_WILL_ON_TEXT else OPS_FIRE_AT_WILL_OFF_TEXT, mouseX, mouseY
        )

        guiGraphics.fill(left + 8, opsRowY(OPS_V_SEP2), left + PANEL_W - 8, opsRowY(OPS_V_SEP2) + 1, SEPARATOR)
        small(guiGraphics, OPS_RESTOCK_TEXT, left + 8, opsRowY(OPS_V_RESTOCK_LABEL), DIM)

        // Cannonballs first -- its own scope on the row below it, spending the controls' round; then the
        // engines; then powder, which takes no aim at all: every gun aboard, split evenly.
        opsButton(guiGraphics, STOP_SHOT, OPS_RESTOCK_SHOT_TEXT, mouseX, mouseY)
        drawSides(
            guiGraphics, STOP_SHOT_SIDE, opsRowY(OPS_V_ROW_SHOT_SCOPE), shotSide, OPS_SIDE_ALL_TEXT, mouseX, mouseY
        )
        opsButton(guiGraphics, STOP_SHOT_LAYER, layerButtonText(shotLayer), mouseX, mouseY)
        opsButton(guiGraphics, STOP_REFUEL, OPS_REFUEL_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_FUEL_LIST, OPS_FUEL_LIST_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_POWDER, OPS_RESTOCK_POWDER_TEXT, mouseX, mouseY)

        guiGraphics.disableScissor()
        drawOpsScrollbar(guiGraphics)

        // Bottom-left corner, pinned OUTSIDE the scrolled body: a summary reads last, and reads always.
        val line = when {
            holds == null -> OPS_READING_TEXT
            else -> Component.translatable(
                "gui.vs_eureka.crew_ops_holds_line",
                holds.gunpowder,
                holds.ammo.size, if (holds.ammo.size == 1) OPS_TYPE_TEXT else OPS_TYPES_TEXT,
                holds.fuels.size, if (holds.fuels.size == 1) OPS_TYPE_TEXT else OPS_TYPES_TEXT
            )
        }
        small(guiGraphics, line, left + 8, top + OPS_HOLDS_Y, DIM)

        // Popups last, over everything and outside the scissor, exactly as the station dropdown is.
        if (ammoMenuOpen) drawAmmoMenu(guiGraphics, mouseX, mouseY)
        if (layerMenuFor >= 0) drawLayerMenu(guiGraphics, layerMenuFor, layerOf(layerMenuFor), mouseX, mouseY)
        if (fuelPopupOpen) drawFuelPopup(guiGraphics)
    }

    /** Where a virtual body row sits on screen this frame. */
    private fun opsRowY(vy: Int): Int = top + OPS_BODY_TOP + vy - opsScroll

    /** What an Assign row's mode toggle reads: the thing that will happen when Assign is pressed. */
    private fun modeText(mode: CrewOperations.AssignMode): Component = when (mode) {
        CrewOperations.AssignMode.KEEP -> OPS_MODE_KEEP_TEXT
        CrewOperations.AssignMode.REASSIGN -> OPS_MODE_REASSIGN_TEXT
        CrewOperations.AssignMode.RELEASE -> OPS_MODE_RELEASE_TEXT
    }

    /** The toggle steps through the three in order, both under the mouse and under the pad. */
    private fun nextMode(mode: CrewOperations.AssignMode): CrewOperations.AssignMode =
        CrewOperations.AssignMode.entries[(mode.ordinal + 1) % CrewOperations.AssignMode.entries.size]

    /** What a deck dropdown's button reads: the scope it is set to, with the unfold marker. */
    private fun layerButtonText(layer: Int): Component =
        if (layer == 0) Component.literal("${OPS_LAYER_ALL_TEXT.string} ▾")
        else Component.literal("${Component.translatable("gui.vs_eureka.crew_ops_deck", layer).string} ▾")

    /**
     * A deck dropdown: "All decks", then every deck keel-up with its gun count. Every entry answers a
     * click -- unlike the station list there is nothing here to be "taken" -- and a click outside folds
     * it, the station dropdown's rule.
     */
    private fun drawLayerMenu(guiGraphics: GuiGraphics, anchorStop: Int, current: Int, mouseX: Int, mouseY: Int) {
        val anchor = opsStopRect(anchorStop) ?: return
        val x = anchor[0]
        val y = anchor[1] + anchor[3]
        val entries = deckCounts.size + 1
        val visible = minOf(entries, LAYER_MENU_ROWS)
        layerMenuScroll = layerMenuScroll.coerceIn(0, (entries - LAYER_MENU_ROWS).coerceAtLeast(0))
        panel(guiGraphics, x, y, OPS_LAYER_BTN_W, visible * LAYER_MENU_ROW_H + 2)

        for (row in 0 until visible) {
            val index = layerMenuScroll + row
            if (index >= entries) break
            val rowY = y + 1 + row * LAYER_MENU_ROW_H
            val hovered = (padContext == 7 && padSel == index) ||
                (mouseX >= x && mouseX < x + OPS_LAYER_BTN_W && mouseY >= rowY && mouseY < rowY + LAYER_MENU_ROW_H)
            if (hovered) guiGraphics.fill(x + 1, rowY, x + OPS_LAYER_BTN_W - 1, rowY + LAYER_MENU_ROW_H, ACCENT)
            val text = if (index == 0) OPS_LAYER_ALL_TEXT else {
                val guns = deckCounts[index - 1]
                if (guns == 1) Component.translatable("gui.vs_eureka.crew_ops_deck_gun", index)
                else Component.translatable("gui.vs_eureka.crew_ops_deck_guns", index, guns)
            }
            small(
                guiGraphics, text, x + 4, rowY + 3,
                when {
                    hovered -> 0xFFFFFFFF.toInt()
                    index == current -> ACCENT
                    else -> TEXT
                }
            )
        }
        if (layerMenuScroll > 0) small(guiGraphics, MORE_ABOVE, x + OPS_LAYER_BTN_W - 10, y + 3, DIM)
        if (layerMenuScroll + visible < entries) {
            small(guiGraphics, MORE_BELOW, x + OPS_LAYER_BTN_W - 10, y + visible * LAYER_MENU_ROW_H - 8, DIM)
        }
    }

    private fun drawOpsScrollbar(guiGraphics: GuiGraphics) {
        val span = OPS_CONTENT_H - OPS_BODY_H
        if (span <= 0) return
        val trackTop = top + OPS_BODY_TOP
        val x = left + PANEL_W - 7
        guiGraphics.fill(x, trackTop, x + 3, trackTop + OPS_BODY_H, ROW_LOCKED)
        val thumbH = (OPS_BODY_H * OPS_BODY_H / OPS_CONTENT_H).coerceAtLeast(12)
        val thumbY = trackTop + (OPS_BODY_H - thumbH) * opsScroll / span
        guiGraphics.fill(x, thumbY, x + 3, thumbY + thumbH, ACCENT)
    }

    /** What the shot dropdown's button reads: the chosen round and how many the holds hold of it. */
    private fun ammoButtonText(): Component {
        val holds = stores ?: return OPS_READING_TEXT
        if (holds.ammo.isEmpty()) return OPS_AMMO_NONE_TEXT
        val chosen = selectedAmmo ?: return OPS_AMMO_NONE_TEXT
        val count = holds.ammo.firstOrNull { it.ball == chosen.first && it.charge == chosen.second }?.count ?: 0
        return Component.literal("${ammoName(chosen.first, chosen.second)} x $count ▾")
    }

    private fun ammoName(ball: Cannonball, charge: CannonCharge): String =
        ItemStack(EurekaItems.cannonball(ball, charge)).hoverName.string

    /** One painted Operations button, lit by mouse or by being the pad's stop. */
    private fun opsButton(guiGraphics: GuiGraphics, stop: Int, text: Component, mouseX: Int, mouseY: Int) {
        val rect = opsStopRect(stop) ?: return
        val (x, y, w, h) = rect
        val lit = opsLit(stop, x, y, w, h, mouseX, mouseY)
        guiGraphics.fill(x, y, x + w, y + h, if (lit) ACCENT else ROW_LOCKED)
        small(
            guiGraphics, text,
            x + (w - (font.width(text) * SMALL).toInt()) / 2, y + 4,
            if (lit) 0xFFFFFFFF.toInt() else TEXT
        )
    }

    /**
     * A three-way battery selector. The selected segment is solid accent; the group grows a thin accent
     * frame while it is the pad's stop, since "lit" has to mean something different from "selected" here.
     */
    private fun drawSides(
        guiGraphics: GuiGraphics,
        stop: Int,
        y: Int,
        selected: CrewOperations.Side,
        bothLabel: Component,
        mouseX: Int,
        mouseY: Int
    ) {
        val rect = opsStopRect(stop) ?: return
        val (gx, gy, gw, gh) = rect
        if (padContext == 3 && padSel == stop) {
            guiGraphics.fill(gx - 1, gy - 1, gx + gw + 1, gy, ACCENT)
            guiGraphics.fill(gx - 1, gy + gh, gx + gw + 1, gy + gh + 1, ACCENT)
            guiGraphics.fill(gx - 1, gy, gx, gy + gh, ACCENT)
            guiGraphics.fill(gx + gw, gy, gx + gw + 1, gy + gh, ACCENT)
        }
        for ((index, side) in SIDE_ORDER.withIndex()) {
            val sx = gx + index * (SEG_W + SEG_GAP)
            val active = side == selected
            val hovered = mouseX >= sx && mouseX < sx + SEG_W && mouseY >= y && mouseY < y + OPS_CTRL_H
            guiGraphics.fill(
                sx, y, sx + SEG_W, y + OPS_CTRL_H,
                when {
                    active -> ACCENT
                    hovered -> ROW_HOVER
                    else -> ROW_LOCKED
                }
            )
            val text = if (side == CrewOperations.Side.BOTH) bothLabel else sideText(side)
            small(
                guiGraphics, text,
                sx + (SEG_W - (font.width(text) * SMALL).toInt()) / 2, y + 4,
                if (active) 0xFFFFFFFF.toInt() else TEXT
            )
        }
    }

    private fun sideText(side: CrewOperations.Side): Component = when (side) {
        CrewOperations.Side.PORT -> OPS_SIDE_PORT_TEXT
        CrewOperations.Side.BOTH -> OPS_SIDE_BOTH_TEXT
        CrewOperations.Side.STARBOARD -> OPS_SIDE_STBD_TEXT
    }

    /** The three powder measures, drawn as the angle steps are: one segment per 1x/2x/3x. */
    private fun drawPowers(guiGraphics: GuiGraphics, y: Int, mouseX: Int, mouseY: Int) {
        val rect = opsStopRect(STOP_PWR_LEVEL) ?: return
        val (gx, gy, gw, gh) = rect
        if (padContext == 3 && padSel == STOP_PWR_LEVEL) {
            guiGraphics.fill(gx - 1, gy - 1, gx + gw + 1, gy, ACCENT)
            guiGraphics.fill(gx - 1, gy + gh, gx + gw + 1, gy + gh + 1, ACCENT)
            guiGraphics.fill(gx - 1, gy, gx, gy + gh, ACCENT)
            guiGraphics.fill(gx + gw, gy, gx + gw + 1, gy + gh, ACCENT)
        }
        for (index in 0..2) {
            val sx = gx + index * (ELEV_SEG_W + SEG_GAP)
            val active = index == powerLevel
            val hovered = mouseX >= sx && mouseX < sx + ELEV_SEG_W && mouseY >= y && mouseY < y + OPS_CTRL_H
            guiGraphics.fill(
                sx, y, sx + ELEV_SEG_W, y + OPS_CTRL_H,
                when {
                    active -> ACCENT
                    hovered -> ROW_HOVER
                    else -> ROW_LOCKED
                }
            )
            val text = POWER_LABELS[index]
            small(
                guiGraphics, text,
                sx + (ELEV_SEG_W - (font.width(text) * SMALL).toInt()) / 2, y + 4,
                if (active) 0xFFFFFFFF.toInt() else TEXT
            )
        }
    }

    /**
     * The angle stepper: [<] [value] [>]. Nineteen 5-degree steps outgrew a row of segments, so a pair
     * of arrows walks the angle 5 degrees at a time and the box between them reads the value Set Angle
     * will send. The arrows pin at the ends -- +45 never wraps to -45 under repeated clicks -- and an
     * arrow that cannot step further is drawn dim. The value box is a reading, not a button.
     */
    private fun drawAngles(guiGraphics: GuiGraphics, y: Int, mouseX: Int, mouseY: Int) {
        val rect = opsStopRect(STOP_ELEV_ANGLE) ?: return
        val (gx, gy, gw, gh) = rect
        if (padContext == 3 && padSel == STOP_ELEV_ANGLE) {
            guiGraphics.fill(gx - 1, gy - 1, gx + gw + 1, gy, ACCENT)
            guiGraphics.fill(gx - 1, gy + gh, gx + gw + 1, gy + gh + 1, ACCENT)
            guiGraphics.fill(gx - 1, gy, gx, gy + gh, ACCENT)
            guiGraphics.fill(gx + gw, gy, gx + gw + 1, gy + gh, ACCENT)
        }

        val rightX = gx + gw - ANGLE_ARROW_W
        val leftLive = elevIndex > 0
        val rightLive = elevIndex < 18
        val leftHover = mouseX >= gx && mouseX < gx + ANGLE_ARROW_W && mouseY >= y && mouseY < y + OPS_CTRL_H
        val rightHover = mouseX >= rightX && mouseX < rightX + ANGLE_ARROW_W && mouseY >= y && mouseY < y + OPS_CTRL_H
        guiGraphics.fill(
            gx, y, gx + ANGLE_ARROW_W, y + OPS_CTRL_H,
            if (leftHover && leftLive) ROW_HOVER else ROW_LOCKED
        )
        small(
            guiGraphics, ANGLE_DOWN_TEXT,
            gx + (ANGLE_ARROW_W - (font.width(ANGLE_DOWN_TEXT) * SMALL).toInt()) / 2, y + 4,
            if (leftLive) TEXT else DIM
        )
        guiGraphics.fill(
            rightX, y, rightX + ANGLE_ARROW_W, y + OPS_CTRL_H,
            if (rightHover && rightLive) ROW_HOVER else ROW_LOCKED
        )
        small(
            guiGraphics, ANGLE_UP_TEXT,
            rightX + (ANGLE_ARROW_W - (font.width(ANGLE_UP_TEXT) * SMALL).toInt()) / 2, y + 4,
            if (rightLive) TEXT else DIM
        )

        val valueX = gx + ANGLE_ARROW_W + SEG_GAP
        val value = Component.literal(degreesLabel(elevIndex))
        guiGraphics.fill(valueX, y, valueX + ANGLE_VALUE_W, y + OPS_CTRL_H, ROW_LOCKED)
        small(
            guiGraphics, value,
            valueX + (ANGLE_VALUE_W - (font.width(value) * SMALL).toInt()) / 2, y + 4,
            0xFFFFFFFF.toInt()
        )
    }

    private fun opsLit(stop: Int, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int): Boolean =
        (padContext == 3 && padSel == stop) ||
            (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h)

    /**
     * Every stop's rectangle in BODY coordinates: absolute x, VIRTUAL y, width, height. Always answers,
     * scrolled out of view or not -- the pad's scroll-follow needs the true position of what it cannot see.
     */
    private fun opsVirtualRect(stop: Int): IntArray = when (stop) {
        STOP_G_MINUS -> intArrayOf(left + OPS_MINUS_X, OPS_V_ROW_G, OPS_STEP_W, OPS_CTRL_H)
        STOP_G_BOX -> intArrayOf(left + OPS_BOX_X, OPS_V_ROW_G, OPS_BOX_W, OPS_CTRL_H)
        STOP_G_PLUS -> intArrayOf(left + OPS_PLUS_X, OPS_V_ROW_G, OPS_STEP_W, OPS_CTRL_H)
        STOP_G_MODE -> intArrayOf(left + OPS_MODE_X, OPS_V_ROW_G, OPS_MODE_W, OPS_CTRL_H)
        STOP_G_ASSIGN -> intArrayOf(left + OPS_ASSIGN_X, OPS_V_ROW_G, OPS_ASSIGN_W, OPS_CTRL_H)
        STOP_G_SIDE -> intArrayOf(left + OPS_SCOPE_SIDES_X, OPS_V_ROW_GSCOPE, SEG_W * 3 + SEG_GAP * 2, OPS_CTRL_H)
        STOP_G_LAYER -> intArrayOf(left + OPS_LAYER_X, OPS_V_ROW_GSCOPE, OPS_LAYER_BTN_W, OPS_CTRL_H)
        STOP_F_MINUS -> intArrayOf(left + OPS_MINUS_X, OPS_V_ROW_F, OPS_STEP_W, OPS_CTRL_H)
        STOP_F_BOX -> intArrayOf(left + OPS_BOX_X, OPS_V_ROW_F, OPS_BOX_W, OPS_CTRL_H)
        STOP_F_PLUS -> intArrayOf(left + OPS_PLUS_X, OPS_V_ROW_F, OPS_STEP_W, OPS_CTRL_H)
        STOP_F_MODE -> intArrayOf(left + OPS_MODE_X, OPS_V_ROW_F, OPS_MODE_W, OPS_CTRL_H)
        STOP_F_ASSIGN -> intArrayOf(left + OPS_ASSIGN_X, OPS_V_ROW_F, OPS_ASSIGN_W, OPS_CTRL_H)
        STOP_C_SIDE -> intArrayOf(left + OPS_SCOPE_SIDES_X, OPS_V_ROW_CSCOPE, SEG_W * 3 + SEG_GAP * 2, OPS_CTRL_H)
        STOP_C_LAYER -> intArrayOf(left + OPS_LAYER_X, OPS_V_ROW_CSCOPE, OPS_LAYER_BTN_W, OPS_CTRL_H)
        STOP_LAY -> intArrayOf(left + 8, OPS_V_ROW_ELEV, OPS_ELEV_BTN_W, OPS_CTRL_H)
        STOP_ELEV_ANGLE -> intArrayOf(left + OPS_SEGS_X, OPS_V_ROW_ELEV, ANGLE_ROW_W, OPS_CTRL_H)
        STOP_PWR -> intArrayOf(left + 8, OPS_V_ROW_PWR, OPS_ELEV_BTN_W, OPS_CTRL_H)
        STOP_PWR_LEVEL -> intArrayOf(left + OPS_SEGS_X, OPS_V_ROW_PWR, ELEV_SEG_W * 3 + SEG_GAP * 2, OPS_CTRL_H)
        STOP_AMMO_MENU -> intArrayOf(left + OPS_AMMO_X, OPS_V_ROW_AMMO, OPS_AMMO_W, OPS_CTRL_H)
        STOP_FIRE_AT_WILL -> intArrayOf(left + OPS_AMMO_X, OPS_V_ROW_FIRE_AT_WILL, OPS_WIDE_W, OPS_CTRL_H)
        STOP_SHOT -> intArrayOf(left + 8, OPS_V_ROW_SHOT, OPS_WIDE_W, OPS_CTRL_H)
        STOP_SHOT_SIDE ->
            intArrayOf(left + OPS_SCOPE_SIDES_X, OPS_V_ROW_SHOT_SCOPE, SEG_W * 3 + SEG_GAP * 2, OPS_CTRL_H)
        STOP_SHOT_LAYER -> intArrayOf(left + OPS_LAYER_X, OPS_V_ROW_SHOT_SCOPE, OPS_LAYER_BTN_W, OPS_CTRL_H)
        STOP_REFUEL -> intArrayOf(left + 8, OPS_V_ROW_REFUEL, OPS_WIDE_W, OPS_CTRL_H)
        STOP_FUEL_LIST -> intArrayOf(left + OPS_AMMO_X, OPS_V_ROW_REFUEL, OPS_FUEL_BTN_W, OPS_CTRL_H)
        STOP_POWDER -> intArrayOf(left + 8, OPS_V_ROW_POWDER, OPS_WIDE_W, OPS_CTRL_H)
        else -> intArrayOf(left + 8, 0, 0, 0)
    }

    /**
     * Every stop's ON-SCREEN rectangle this frame, or null while it is scrolled out of the body -- the
     * single source both the mouse and the pad walk against, so nothing invisible can be hovered, lit,
     * or clicked.
     */
    private fun opsStopRect(stop: Int): IntArray? {
        val v = opsVirtualRect(stop)
        if (v[2] == 0) return null
        if (v[1] + v[3] <= opsScroll || v[1] >= opsScroll + OPS_BODY_H) return null
        return intArrayOf(v[0], top + OPS_BODY_TOP + v[1] - opsScroll, v[2], v[3])
    }

    /**
     * Press one Operations control. [mouseX] carries which SEGMENT of a selector was clicked; null is
     * the pad, which cycles instead -- the same control, two grips.
     */
    private fun opsActivate(stop: Int, mouseX: Int?) {
        when (stop) {
            STOP_G_MINUS -> adjustCount(gunners = true, delta = -1)
            STOP_G_PLUS -> adjustCount(gunners = true, delta = +1)
            STOP_G_BOX -> gunnerCountBox?.let { this.focused = it }
            STOP_G_SIDE -> crewSide = pickSide(crewSide, STOP_G_SIDE, mouseX)
            STOP_G_LAYER -> openLayerMenu(STOP_G_LAYER)
            STOP_G_MODE -> crewMode = nextMode(crewMode)
            STOP_G_ASSIGN ->
                PathNetworkingFabric.sendCrewAssignGunners(snapshot.helm, gunnerCount, crewSide, crewLayer, crewMode)
            STOP_F_MINUS -> adjustCount(gunners = false, delta = -1)
            STOP_F_PLUS -> adjustCount(gunners = false, delta = +1)
            STOP_F_BOX -> fireCountBox?.let { this.focused = it }
            STOP_F_MODE -> fireMode = nextMode(fireMode)
            STOP_F_ASSIGN ->
                PathNetworkingFabric.sendCrewAssignFirefighters(snapshot.helm, fireCount, fireMode)
            STOP_C_SIDE -> ctrlSide = pickSide(ctrlSide, STOP_C_SIDE, mouseX)
            STOP_C_LAYER -> openLayerMenu(STOP_C_LAYER)
            STOP_LAY -> PathNetworkingFabric.sendCrewSetElevation(snapshot.helm, ctrlSide, elevIndex, ctrlLayer)
            STOP_ELEV_ANGLE -> elevIndex = pickAngle(mouseX)
            STOP_PWR -> PathNetworkingFabric.sendCrewSetPower(snapshot.helm, ctrlSide, powerLevel, ctrlLayer)
            STOP_PWR_LEVEL -> powerLevel = pickPower(mouseX)
            STOP_AMMO_MENU -> if (stores?.ammo?.isNotEmpty() == true) {
                ammoMenuOpen = true
                ammoMenuScroll = 0
                // The menu hangs below its row, and with the body scrolled high the row sits deep in
                // the viewport -- the list ran past the panel's bottom edge and its last entries were
                // cut off. Opening rides the body to its floor instead: the ammo row at its highest,
                // and the six menu rows end exactly at the body's bottom edge.
                opsScroll = (OPS_CONTENT_H - OPS_BODY_H).coerceAtLeast(0)
            }
            // Flipped locally so the button answers the press at once; the stores push that the order
            // triggers is what the state really comes from, and corrects this if the gate refused.
            STOP_FIRE_AT_WILL -> {
                fireAtWill = !fireAtWill
                PathNetworkingFabric.sendCrewFireAtWill(snapshot.helm, fireAtWill)
            }
            STOP_SHOT -> selectedAmmo?.let { (ball, charge) ->
                PathNetworkingFabric.sendCrewRestockShot(snapshot.helm, shotSide, ball, charge, shotLayer)
            }
            STOP_SHOT_SIDE -> shotSide = pickSide(shotSide, STOP_SHOT_SIDE, mouseX)
            STOP_SHOT_LAYER -> openLayerMenu(STOP_SHOT_LAYER)
            STOP_POWDER -> PathNetworkingFabric.sendCrewRestockPowder(snapshot.helm)
            STOP_REFUEL -> PathNetworkingFabric.sendCrewRefuel(snapshot.helm)
            STOP_FUEL_LIST -> if (stores != null) fuelPopupOpen = true
        }
    }

    /** Unfold one deck dropdown. With no guns censused there is nothing to list, so nothing opens. */
    private fun openLayerMenu(stop: Int) {
        if (deckCounts.isEmpty()) return
        layerMenuScroll = 0
        layerMenuFor = stop
    }

    private fun pickSide(current: CrewOperations.Side, stop: Int, mouseX: Int?): CrewOperations.Side {
        if (mouseX == null) {
            // The pad cycles the selector in reading order.
            return SIDE_ORDER[(SIDE_ORDER.indexOf(current) + 1) % SIDE_ORDER.size]
        }
        val rect = opsStopRect(stop) ?: return current
        val index = ((mouseX - rect[0]) / (SEG_W + SEG_GAP)).coerceIn(0, SIDE_ORDER.size - 1)
        return SIDE_ORDER[index]
    }

    /**
     * The stepper's answer to a press: the mouse steps one 5-degree index off whichever arrow it hit and
     * pins at the ends; a click on the value box between them changes nothing. The pad has no second
     * arrow to reach, so it walks upward and wraps -- the one path that may cross +45 back to -45,
     * because without it the pad could never come back down.
     */
    private fun pickAngle(mouseX: Int?): Int {
        if (mouseX == null) return (elevIndex + 1) % 19
        val rect = opsStopRect(STOP_ELEV_ANGLE) ?: return elevIndex
        val local = mouseX - rect[0]
        return when {
            local < ANGLE_ARROW_W -> (elevIndex - 1).coerceAtLeast(0)
            local >= rect[2] - ANGLE_ARROW_W -> (elevIndex + 1).coerceAtMost(18)
            else -> elevIndex
        }
    }

    private fun pickPower(mouseX: Int?): Int {
        if (mouseX == null) return (powerLevel + 1) % 3
        val rect = opsStopRect(STOP_PWR_LEVEL) ?: return powerLevel
        return ((mouseX - rect[0]) / (ELEV_SEG_W + SEG_GAP)).coerceIn(0, 2)
    }

    private fun handleOpsClick(mx: Int, my: Int): Boolean {
        val holds = stores

        // Popups own the click outright while open, the station dropdown's rule.
        if (fuelPopupOpen) {
            fuelPopupOpen = false
            return true
        }
        if (layerMenuFor >= 0) {
            handleLayerMenuClick(mx, my)
            return true
        }
        if (ammoMenuOpen) {
            val anchor = opsStopRect(STOP_AMMO_MENU)
            if (holds != null && anchor != null) {
                val x = anchor[0]
                val menuY = anchor[1] + anchor[3]
                if (mx >= x && mx < x + OPS_AMMO_W && my >= menuY) {
                    val index = ammoMenuScroll + (my - menuY) / AMMO_MENU_ROW_H
                    holds.ammo.getOrNull(index)?.let { pick ->
                        if ((my - menuY) / AMMO_MENU_ROW_H < AMMO_MENU_ROWS) {
                            selectedAmmo = pick.ball to pick.charge
                        }
                    }
                }
            }
            ammoMenuOpen = false
            return true
        }

        // Only the body answers body clicks: a row half-scrolled off the top must not catch a click
        // meant for the strip above it, nor one below for the holds line.
        if (my < top + OPS_BODY_TOP || my >= top + OPS_BODY_BOTTOM) return false

        for (stop in 0 until OPS_STOP_COUNT) {
            val rect = opsStopRect(stop) ?: continue
            if (mx >= rect[0] && mx < rect[0] + rect[2] && my >= rect[1] && my < rect[1] + rect[3]) {
                opsActivate(stop, mx)
                return true
            }
        }
        return false
    }

    /** A click while a deck dropdown is up: an entry picks the scope, anywhere else just folds it. */
    private fun handleLayerMenuClick(mx: Int, my: Int) {
        val anchor = opsStopRect(layerMenuFor)
        if (anchor != null) {
            val x = anchor[0]
            val menuY = anchor[1] + anchor[3]
            val entries = deckCounts.size + 1
            val visible = minOf(entries, LAYER_MENU_ROWS)
            if (mx >= x && mx < x + OPS_LAYER_BTN_W && my >= menuY + 1 && my < menuY + 1 + visible * LAYER_MENU_ROW_H) {
                val index = layerMenuScroll + (my - (menuY + 1)) / LAYER_MENU_ROW_H
                if (index in 0 until entries) setLayer(layerMenuFor, index)
            }
        }
        layerMenuFor = -1
    }

    /** File a picked deck against whichever scope its dropdown belongs to. */
    private fun setLayer(stop: Int, index: Int) {
        when (stop) {
            STOP_G_LAYER -> crewLayer = index
            STOP_C_LAYER -> ctrlLayer = index
            STOP_SHOT_LAYER -> shotLayer = index
        }
    }

    /** What that scope's dropdown should read while it is folded. */
    private fun layerOf(stop: Int): Int = when (stop) {
        STOP_G_LAYER -> crewLayer
        STOP_SHOT_LAYER -> shotLayer
        else -> ctrlLayer
    }

    private fun drawAmmoMenu(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val holds = stores ?: return
        val anchor = opsStopRect(STOP_AMMO_MENU) ?: return
        val x = anchor[0]
        val y = anchor[1] + anchor[3]
        val visible = minOf(holds.ammo.size, AMMO_MENU_ROWS)
        if (visible == 0) return
        panel(guiGraphics, x, y, OPS_AMMO_W, visible * AMMO_MENU_ROW_H + 2)

        for (row in 0 until visible) {
            val index = ammoMenuScroll + row
            val entry = holds.ammo.getOrNull(index) ?: break
            val rowY = y + 1 + row * AMMO_MENU_ROW_H
            val hovered = (padContext == 4 && padSel == index) ||
                (mouseX >= x && mouseX < x + OPS_AMMO_W && mouseY >= rowY && mouseY < rowY + AMMO_MENU_ROW_H)
            if (hovered) guiGraphics.fill(x + 1, rowY, x + OPS_AMMO_W - 1, rowY + AMMO_MENU_ROW_H, ACCENT)
            small(
                guiGraphics,
                Component.literal("${ammoName(entry.ball, entry.charge)} x ${entry.count}"),
                x + 4, rowY + 3,
                if (hovered) 0xFFFFFFFF.toInt() else TEXT
            )
        }
        if (ammoMenuScroll > 0) small(guiGraphics, MORE_ABOVE, x + OPS_AMMO_W - 10, y + 3, DIM)
        if (ammoMenuScroll + visible < holds.ammo.size) {
            small(guiGraphics, MORE_BELOW, x + OPS_AMMO_W - 10, y + visible * AMMO_MENU_ROW_H - 8, DIM)
        }
    }

    private fun drawFuelPopup(guiGraphics: GuiGraphics) {
        val holds = stores ?: return
        val x = left + FUEL_POPUP_X
        val y = top + FUEL_POPUP_Y
        // The top three fuels and no more: the refuel spends best-burn-first, so the head of the list IS
        // the plan, and a captain who wants the full inventory has the holds themselves. The panel
        // shrinks to what it shows -- one fuel gets one line, not two blank ones.
        val shown = holds.fuels.take(FUEL_ROWS)
        val height = FUEL_POPUP_HEADER + maxOf(shown.size, 1) * AMMO_MENU_ROW_H + 6
        panel(guiGraphics, x, y, FUEL_POPUP_W, height)
        guiGraphics.drawString(font, OPS_FUEL_TITLE_TEXT, x + 6, y + 5, TEXT, false)
        guiGraphics.fill(x + 4, y + 16, x + FUEL_POPUP_W - 4, y + 17, SEPARATOR)

        if (shown.isEmpty()) {
            small(guiGraphics, OPS_FUEL_NONE_TEXT, x + 6, y + FUEL_POPUP_HEADER + 2, DIM)
            return
        }
        for ((row, fuel) in shown.withIndex()) {
            small(
                guiGraphics,
                Component.literal("${fuelName(fuel.itemId)} x ${fuel.count} -- ${fuel.burnTicks / 20}s"),
                x + 6, y + FUEL_POPUP_HEADER + row * AMMO_MENU_ROW_H, TEXT
            )
        }
        if (holds.fuels.size > FUEL_ROWS) {
            small(guiGraphics, MORE_BELOW, x + FUEL_POPUP_W - 10, y + height - 10, DIM)
        }
    }

    private fun fuelName(itemId: String): String =
        BuiltInRegistries.ITEM.getOptional(ResourceLocation(itemId)).map { ItemStack(it).hoverName.string }
            .orElse(itemId)

    /**
     * A fresh count of the holds. Also picks a default round if none is chosen, or if the chosen one is no
     * longer aboard.
     *
     * The default is the TOP OF THE LIST rather than the most plentiful pile. A remembered choice now
     * survives every re-open (see [selectedAmmo]), so this only runs on a cold start or after the holds have
     * genuinely lost that round -- and in both cases a predictable first entry beats one that moves as the
     * magazine drains.
     */
    private fun acceptStoresNow(
        next: ShipStores.Stores,
        decks: List<Int>,
        firing: Boolean,
        memory: CrewOperations.OpsMemory?
    ) {
        stores = next
        deckCounts = decks
        fireAtWill = firing
        // The SHIP's memory of the last orders seeds the book, once per open and before the ammo default
        // below runs -- so the round the last restock loaded is already "chosen" by the time it is
        // checked. Every wheel projects one book, so this is what makes a terminal opened after a relog
        // -- or by another captain -- read the way the last order left it. A hull that has never been
        // ordered sends no memory, and the captain's own client-side habits stand.
        if (!opsSeeded) {
            opsSeeded = true
            if (memory != null) {
                gunnerCount = memory.gunnerCount.coerceIn(0, snapshot.maxBerths)
                fireCount = memory.fireCount.coerceIn(0, snapshot.maxBerths)
                gunnerCountBox?.value = gunnerCount.toString()
                fireCountBox?.value = fireCount.toString()
                CrewOperations.Side.entries.getOrNull(memory.crewSide)?.let { crewSide = it }
                CrewOperations.Side.entries.getOrNull(memory.ctrlSide)?.let { ctrlSide = it }
                CrewOperations.Side.entries.getOrNull(memory.shotSide)?.let { shotSide = it }
                // Clamped here as well as by the stale-deck guard below, because that guard only catches
                // a deck that is too HIGH -- a negative from a mangled payload would sail straight past it.
                crewLayer = memory.crewLayer.coerceIn(0, decks.size)
                ctrlLayer = memory.ctrlLayer.coerceIn(0, decks.size)
                shotLayer = memory.shotLayer.coerceIn(0, decks.size)
                CrewOperations.AssignMode.entries.getOrNull(memory.crewMode)?.let { crewMode = it }
                CrewOperations.AssignMode.entries.getOrNull(memory.fireMode)?.let { fireMode = it }
                val ball = Cannonball.entries.getOrNull(memory.ammoBall)
                val charge = CannonCharge.entries.getOrNull(memory.ammoCharge)
                if (ball != null && charge != null && next.ammo.any { it.ball == ball && it.charge == charge }) {
                    selectedAmmo = ball to charge
                }
            }
        }
        val chosen = selectedAmmo
        val stillThere = chosen != null && next.ammo.any { it.ball == chosen.first && it.charge == chosen.second }
        if (!stillThere) {
            selectedAmmo = next.ammo.firstOrNull()?.let { it.ball to it.charge }
        }
        // A deck that stopped existing -- guns torn out, the ship rebuilt under the open book -- falls
        // back to All rather than pointing an order at nothing.
        if (crewLayer > decks.size) crewLayer = 0
        if (ctrlLayer > decks.size) ctrlLayer = 0
        if (shotLayer > decks.size) shotLayer = 0
        val maxAmmo = (next.ammo.size - AMMO_MENU_ROWS).coerceAtLeast(0)
        ammoMenuScroll = ammoMenuScroll.coerceIn(0, maxAmmo)
        layerMenuScroll = layerMenuScroll.coerceIn(0, (decks.size + 1 - LAYER_MENU_ROWS).coerceAtLeast(0))
    }

    // endregion

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
        // The flat skin head while the info card is open: the live-entity render carries its own depth
        // (translate z ~1000) and punches straight through the card overlay, which is drawn at panel
        // depth -- eight heads floating over the character sheet. The flat head has ordinary depth and
        // sits under the card like every other row element.
        if (villager == null || detail != null) {
            drawHeadFromSkin(guiGraphics, x, y, row.villagerType, row.profession)
            return
        }
        // 1.20.1 anchors by the FEET line and draws the WHOLE entity unclipped -- the 1.21.1 form took a
        // rect and clipped to it. Without the clip every crewman standing on a loaded ship paraded his
        // torso down the roster. So: scissor to the icon box (absolute coords -- this is a plain Screen,
        // and 1.20.1 enableScissor ignores the pose), and drop the feet far enough below the box that the
        // HEAD is what fills it: a villager is ~1.95 blocks = ~59 px at scale 30, head centre ~51 px above
        // the feet, box centre at +10 -- so the feet sit ICON_SIZE + 41 below the top edge.
        // The two floats are pre-subtracted OFFSETS (anchor minus mouse), not mouse coordinates -- the
        // method does atan(param / 40) on them raw (bytecode-checked; vanilla's own caller passes
        // differences). Any absolute coordinate here saturates the angle and cranes the head into a
        // corner. Zero offset, zero angle: dead ahead, matching the 1.21.x tabs.
        guiGraphics.enableScissor(x, y, x + ICON_SIZE, y + ICON_SIZE)
        InventoryScreen.renderEntityInInventoryFollowsMouse(
            guiGraphics, x + ICON_SIZE / 2, y + ICON_SIZE + HEAD_FEET_DROP,
            HEAD_SCALE, 0f, 0f, villager
        )
        guiGraphics.disableScissor()
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
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 0f)
        pose.scale(HEAD_ZOOM, HEAD_ZOOM, 1f)

        face(guiGraphics, BASE_SKIN, false)
        ResourceLocation.tryParse(typeId)?.let {
            face(guiGraphics, it.withPath { p -> "textures/entity/villager/type/$p.png" }, true)
        }
        if (professionId != CrewManifest.NO_PROFESSION) {
            ResourceLocation.tryParse(professionId)?.let {
                face(guiGraphics, it.withPath { p -> "textures/entity/villager/profession/$p.png" }, true)
            }
        }

        pose.popPose()
    }

    /** One villager skin layer's head front face, and its hat box on top when the layer has one. */
    private fun face(guiGraphics: GuiGraphics, texture: ResourceLocation, withHat: Boolean) {
        guiGraphics.blit(
            texture, 0, 0, HEAD_U.toFloat(), HEAD_V.toFloat(), HEAD_W, HEAD_H, SKIN_TEX, SKIN_TEX
        )
        if (withHat) {
            guiGraphics.blit(
                texture, 0, 0, HAT_U.toFloat(), HEAD_V.toFloat(), HEAD_W, HEAD_H, SKIN_TEX, SKIN_TEX
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

        // A stationed gunner's card spends the trades block on the GUN instead: charge, elevation and the
        // round chambered, adjustable in place. The trades still exist -- right-click the villager -- but
        // this card is about the gun they serve, and there is exactly one rectangle to say it in.
        if (gunPanelShown(card)) {
            drawGunPanel(guiGraphics, card, mouseX, mouseY)
        } else {
            guiGraphics.drawString(font, SELLS_TEXT, x, y, TEXT, false)
            y += 12

            // What somebody is selling is the one thing the articles cannot know about them: trades change
            // every time a player buys, and the written copy is only as fresh as the last time they were in
            // hand. So an absent crew member's card says where they are instead of showing a stale or empty
            // list, which would read as "this one has no trades".
            if (!card.aboard) {
                small(guiGraphics, ASHORE_TEXT, x, y + 1, DIM)
                y += 12
            } else if (card.offers.isEmpty()) {
                small(guiGraphics, NO_TRADES_TEXT, x, y + 1, DIM)
                y += 12
            } else {
                // Bounded by where the duty rows start, rather than by the offer count. A master crewman
                // carries ten trades and the card has never had room for them, but it used to overrun into
                // an inert label; now it would overrun into the assignment button and bury a control.
                val room = ((dutyRowY() - 6) - y) / OFFER_H
                val shown = card.offers.take(room.coerceAtLeast(0))
                for (offer in shown) {
                    drawOffer(guiGraphics, x, y, offer, mouseX, mouseY)
                    y += OFFER_H
                }
                val hidden = card.offers.size - shown.size
                if (hidden > 0) {
                    small(guiGraphics, Component.translatable("gui.vs_eureka.crew_more_trades", hidden), x, y + 1, DIM)
                }
            }
        }

        // Read-only on the Crews tab: the duty control, the station dropdown and the gun's own settings are
        // orders given to somebody serving on THIS ship, and the crew being read may be nowhere near it. Not
        // drawn at all rather than drawn dead -- see initCrews.
        if (activeTab == Tab.CREWS) {
            small(guiGraphics, CREW_READONLY_TEXT, cardX() + CARD_PAD, dutyRowY() + 4, DIM)
        } else {
            drawDuties(guiGraphics, card, mouseX, mouseY)
        }
        // The Lock goes with the rest of the controls on the Crews tab: it is the captain's "do not touch"
        // on a berth serving THIS ship, and a read-only card has nothing to protect.
        if (activeTab != Tab.CREWS) drawLockButton(guiGraphics, card, mouseX, mouseY)
        if (stationMenuOpen) drawStationMenu(guiGraphics, card, mouseX, mouseY)
        if (cardAmmoMenuOpen) drawCardAmmoMenu(guiGraphics, mouseX, mouseY)
    }

    /** Whether this card shows the gun panel: a gunner with a cannon that actually resolved. */
    private fun gunPanelShown(card: CrewManifest.Detail): Boolean =
        card.duty == CrewDuty.GUNNER && card.chargeOrdinal >= 0

    /** The gun rows' geometry, shared by drawing and hit tests. Index 0 charge, 1 elevation, 2 ammo. */
    private fun gunRowY(index: Int): Int = cardY() + 66 + index * 18

    private fun drawGunPanel(guiGraphics: GuiGraphics, card: CrewManifest.Detail, mouseX: Int, mouseY: Int) {
        val x = cardX() + CARD_PAD
        val header = if (card.stationLabel.isEmpty()) CARD_GUN_TEXT
        else Component.translatable("gui.vs_eureka.crew_card_gun", card.stationLabel)
        small(guiGraphics, header, x, cardY() + 55, ACCENT)

        cardGunRow(guiGraphics, card, 0, CARD_CHARGE_TEXT, Component.literal("${card.chargeOrdinal + 1}x"), CARD_STOP_CHARGE, mouseX, mouseY)
        cardGunRow(
            guiGraphics, card, 1, CARD_ELEVATION_TEXT,
            Component.literal(degreesLabel(card.elevationIndex)), CARD_STOP_ELEVATION, mouseX, mouseY
        )
        val ammoText = when {
            card.ammoBall < 0 -> CARD_AMMO_EMPTY_TEXT
            else -> {
                val ball = Cannonball.entries.getOrNull(card.ammoBall)
                val charge = CannonCharge.entries.getOrNull(card.ammoCharge)
                if (ball == null || charge == null) CARD_AMMO_EMPTY_TEXT
                else Component.literal("${ammoName(ball, charge)} x ${card.ammoCount} ▾")
            }
        }
        cardGunRow(guiGraphics, card, 2, CARD_AMMO_TEXT, ammoText, CARD_STOP_AMMO, mouseX, mouseY)
    }

    private fun cardGunRow(
        guiGraphics: GuiGraphics,
        card: CrewManifest.Detail,
        index: Int,
        label: Component,
        value: Component,
        stop: Int,
        mouseX: Int,
        mouseY: Int
    ) {
        val x = cardX() + CARD_PAD
        val y = gunRowY(index)
        small(guiGraphics, label, x, y + 4, DIM)

        // The ammo row is wider than the footer buttons: a round's name plus a count needs the room.
        val bx = if (stop == CARD_STOP_AMMO) cardX() + CARD_W - CARD_PAD - GUN_AMMO_BTN_W else dutyButtonX()
        val bw = if (stop == CARD_STOP_AMMO) GUN_AMMO_BTN_W else DUTY_BTN_W
        val lit = !card.locked && (
            (mouseX >= bx && mouseX < bx + bw && mouseY >= y && mouseY < y + DUTY_BTN_H) ||
                padStopSelected(stop)
            )
        guiGraphics.fill(bx, y, bx + bw, y + DUTY_BTN_H, if (lit) ACCENT else ROW_LOCKED)
        small(
            guiGraphics, value,
            bx + (bw - (font.width(value) * SMALL).toInt()) / 2, y + 4,
            when {
                lit -> 0xFFFFFFFF.toInt()
                card.locked -> DIM
                else -> TEXT
            }
        )
    }

    private fun degreesLabel(index: Int): String {
        val degrees = (index.coerceIn(0, 18) - 9) * 5
        return if (degrees > 0) "+$degrees°" else "$degrees°"
    }

    /** Lock or Unlock, centred between Dismiss and Back -- the one control a locked card still answers. */
    private fun drawLockButton(guiGraphics: GuiGraphics, card: CrewManifest.Detail, mouseX: Int, mouseY: Int) {
        // Every berth can be locked, Crewmen included. The button used to hide on a berth with no duty, on
        // the reasoning that there was nothing to freeze -- but a Crewman IS something now, and locking one
        // is how a captain keeps the two hands minding the repair room out of a re-deal of the gun crew.
        // (The server never had this rule: requestLock asks no questions about duty, and every bulk order
        // already steps around a locked berth whatever it is doing. Only the button was missing, which is
        // why the workaround was to make them gunners with no gun and lock that.)
        val x = lockButtonX()
        val y = lockButtonY()
        val lit = (mouseX >= x && mouseX < x + LOCK_BTN_W && mouseY >= y && mouseY < y + BACK_BTN_H) ||
            padStopSelected(CARD_STOP_LOCK)
        guiGraphics.fill(x, y, x + LOCK_BTN_W, y + BACK_BTN_H, if (lit) ACCENT else ROW_LOCKED)
        val text = if (card.locked) CARD_UNLOCK_TEXT else CARD_LOCK_TEXT
        small(
            guiGraphics, text,
            x + (LOCK_BTN_W - (font.width(text) * SMALL).toInt()) / 2, y + 4,
            if (lit) 0xFFFFFFFF.toInt() else TEXT
        )
    }

    private fun lockButtonX(): Int = cardX() + (CARD_W - LOCK_BTN_W) / 2
    private fun lockButtonY(): Int = cardY() + CARD_H - CARD_PAD - BACK_BTN_H

    /** Whether the pad's card selection is resting on [stop], through the per-card stop list. */
    private fun padStopSelected(stop: Int): Boolean {
        if (padContext != 1) return false
        val card = detail ?: return false
        return cardStops(card).getOrNull(padSel) == stop
    }

    /**
     * The card's pad stops, built per card state: a locked card offers ONLY the way out of being locked,
     * and the gun rows exist only when there is a gun. This list replaces the old hardcoded "duty then
     * maybe station" walk.
     */
    private fun cardStops(card: CrewManifest.Detail): List<Int> {
        // A read-only card has no stops, so a controller walking it finds nothing to press either.
        if (activeTab == Tab.CREWS) return emptyList()
        if (card.locked) return listOf(CARD_STOP_LOCK)
        val stops = mutableListOf(CARD_STOP_DUTY)
        if (card.duty == CrewDuty.GUNNER && card.gunOptions.isNotEmpty()) stops.add(CARD_STOP_STATION)
        if (gunPanelShown(card)) {
            stops.add(CARD_STOP_CHARGE)
            stops.add(CARD_STOP_ELEVATION)
            stops.add(CARD_STOP_AMMO)
        }
        stops.add(CARD_STOP_LOCK)
        return stops
    }

    private fun cycleCharge(card: CrewManifest.Detail) {
        if (card.locked) return
        val next = (card.chargeOrdinal + 1) % 3
        detail = card.copy(chargeOrdinal = next)
        PathNetworkingFabric.sendCrewGunCharge(snapshot.helm, card.villager, next)
    }

    private fun cycleElevation(card: CrewManifest.Detail) {
        if (card.locked) return
        val next = (card.elevationIndex.coerceIn(0, 18) + 1) % 19
        detail = card.copy(elevationIndex = next)
        PathNetworkingFabric.sendCrewGunElevation(snapshot.helm, card.villager, next)
    }

    private fun toggleLock(card: CrewManifest.Detail) {
        val next = !card.locked
        detail = card.copy(locked = next)
        if (next) {
            stationMenuOpen = false
            cardAmmoMenuOpen = false
        }
        padSel = -1
        PathNetworkingFabric.sendCrewLock(snapshot.helm, card.villager, next)
    }

    private fun openCardAmmoMenu() {
        if (stores?.ammo?.isNotEmpty() == true) {
            cardAmmoMenuOpen = true
            cardAmmoMenuScroll = 0
        }
    }

    /**
     * The card's ammo list: the ops dropdown's twin, opened under the ammo row, arming this ONE gun.
     * Unlike the station dropdown a choice here commits IMMEDIATELY -- it moves items, and cargo moving
     * on Back would be the least expected moment for it.
     */
    private fun drawCardAmmoMenu(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val holds = stores ?: return
        val x = cardX() + CARD_W - CARD_PAD - GUN_AMMO_BTN_W
        val y = gunRowY(2) + DUTY_BTN_H
        val visible = minOf(holds.ammo.size, AMMO_MENU_ROWS)
        if (visible == 0) return
        panel(guiGraphics, x, y, GUN_AMMO_BTN_W, visible * AMMO_MENU_ROW_H + 2)

        for (row in 0 until visible) {
            val index = cardAmmoMenuScroll + row
            val entry = holds.ammo.getOrNull(index) ?: break
            val rowY = y + 1 + row * AMMO_MENU_ROW_H
            val hovered = (padContext == 6 && padSel == index) ||
                (mouseX >= x && mouseX < x + GUN_AMMO_BTN_W && mouseY >= rowY && mouseY < rowY + AMMO_MENU_ROW_H)
            if (hovered) guiGraphics.fill(x + 1, rowY, x + GUN_AMMO_BTN_W - 1, rowY + AMMO_MENU_ROW_H, ACCENT)
            small(
                guiGraphics,
                Component.literal("${ammoName(entry.ball, entry.charge)} x ${entry.count}"),
                x + 4, rowY + 3,
                if (hovered) 0xFFFFFFFF.toInt() else TEXT
            )
        }
        if (cardAmmoMenuScroll > 0) small(guiGraphics, MORE_ABOVE, x + GUN_AMMO_BTN_W - 10, y + 3, DIM)
        if (cardAmmoMenuScroll + visible < holds.ammo.size) {
            small(guiGraphics, MORE_BELOW, x + GUN_AMMO_BTN_W - 10, y + visible * AMMO_MENU_ROW_H - 8, DIM)
        }
    }

    private fun handleCardAmmoClick(card: CrewManifest.Detail, mx: Int, my: Int) {
        val holds = stores
        if (holds != null) {
            val x = cardX() + CARD_W - CARD_PAD - GUN_AMMO_BTN_W
            val menuY = gunRowY(2) + DUTY_BTN_H
            if (mx >= x && mx < x + GUN_AMMO_BTN_W && my >= menuY) {
                val row = (my - menuY) / AMMO_MENU_ROW_H
                if (row < AMMO_MENU_ROWS) {
                    holds.ammo.getOrNull(cardAmmoMenuScroll + row)?.let { pick ->
                        PathNetworkingFabric.sendCrewGunAmmo(snapshot.helm, card.villager, pick.ball, pick.charge)
                    }
                }
            }
        }
        cardAmmoMenuOpen = false
    }

    /**
     * The two duty lines: what this crew member has been told to do, and what that amounts to on this ship.
     *
     * The assignment control is PAINTED rather than built as a button, for the same reason the berth rows are:
     * its label is its state, so a widget would have to be torn down and rebuilt on every click and on every
     * detail packet that arrived underneath one. A rectangle and a hit test in [mouseClicked] cannot fall out
     * of step with what was drawn.
     */
    private fun drawDuties(guiGraphics: GuiGraphics, card: CrewManifest.Detail, mouseX: Int, mouseY: Int) {
        val x = cardX() + CARD_PAD
        val footY = dutyRowY()

        guiGraphics.fill(x, footY - 6, cardX() + CARD_W - CARD_PAD, footY - 5, SEPARATOR)
        small(guiGraphics, ASSIGNMENT_TEXT, x, footY + 4, DIM)

        val bx = dutyButtonX()
        val hovered = !card.locked && (
            (mouseX >= bx && mouseX < bx + DUTY_BTN_W && mouseY >= footY && mouseY < footY + DUTY_BTN_H) ||
                (!stationMenuOpen && padStopSelected(CARD_STOP_DUTY))
            )
        guiGraphics.fill(bx, footY, bx + DUTY_BTN_W, footY + DUTY_BTN_H, if (hovered) ACCENT else ROW_LOCKED)
        val label = dutyName(card.duty)
        guiGraphics.drawString(
            font, label,
            bx + (DUTY_BTN_W - font.width(label)) / 2, footY + 3,
            when {
                hovered -> 0xFFFFFFFF.toInt()
                card.locked -> DIM
                else -> TEXT
            },
            false
        )

        if (card.duty == CrewDuty.GUNNER && card.gunOptions.isNotEmpty()) {
            // A gunner's Station line is a CONTROL: the button unfolds the gun list, and whatever it settles
            // on is locked in when the card is left. Same visual shape as the duty button above it.
            val sy = stationRowY()
            small(guiGraphics, STATION_TEXT, x, sy + 4, DIM)
            val stationHovered = !card.locked && (
                (mouseX >= bx && mouseX < bx + DUTY_BTN_W && mouseY >= sy && mouseY < sy + DUTY_BTN_H) ||
                    (!stationMenuOpen && padStopSelected(CARD_STOP_STATION))
                )
            guiGraphics.fill(bx, sy, bx + DUTY_BTN_W, sy + DUTY_BTN_H, if (stationHovered) ACCENT else ROW_LOCKED)
            val stationName = if (card.stationLabel.isEmpty()) UNSTATIONED_TEXT else Component.literal(card.stationLabel)
            guiGraphics.drawString(
                font, stationName,
                bx + (DUTY_BTN_W - font.width(stationName)) / 2, sy + 3,
                when {
                    stationHovered -> 0xFFFFFFFF.toInt()
                    card.locked -> DIM
                    else -> TEXT
                },
                false
            )
        } else {
            small(guiGraphics, STATION_TEXT, x, footY + DUTY_BTN_H + 6, DIM)
            val station = stationLine(card)
            small(
                guiGraphics, station,
                cardX() + CARD_W - CARD_PAD - (font.width(station) * SMALL).toInt(), footY + DUTY_BTN_H + 6,
                SUBTLE
            )
        }
    }

    /**
     * What this crew member's duty amounts to aboard THIS ship, when there is no station to offer them --
     * the fire watch's tally, an off-duty note, or a gun deck with no guns.
     */
    private fun stationLine(card: CrewManifest.Detail): Component = when (card.duty) {
        CrewDuty.GUNNER ->
            if (card.guns == 0) NO_GUNS_TEXT
            else Component.translatable(
                "gui.vs_eureka.crew_station_guns", minOf(card.gunners, card.guns), card.guns
            )
        CrewDuty.FIREFIGHTER -> Component.translatable("gui.vs_eureka.crew_station_watch", card.fireParty)
        CrewDuty.NONE -> OFF_DUTY_TEXT
    }

    private fun dutyName(duty: CrewDuty): Component = Component.translatable(duty.translationKey)

    /** Top of the assignment row. Everything in the card's footer is measured from here. */
    private fun dutyRowY(): Int = cardY() + CARD_H - CARD_PAD - BACK_BTN_H - 44

    /** Top of the station button, sitting under the duty button with the same breathing room as above it. */
    private fun stationRowY(): Int = dutyRowY() + DUTY_BTN_H + 6

    private fun dutyButtonX(): Int = cardX() + CARD_W - CARD_PAD - DUTY_BTN_W

    /**
     * Step this crew member's duty on one, and tell the server where it landed.
     *
     * The card is updated straight away rather than waiting for the answer. A duty is one field and the server
     * replies with a fresh card anyway, so the optimistic hop only removes a round trip's worth of lag from a
     * button whose whole job is to say what it is now.
     */
    private fun cycleDuty() {
        val card = detail ?: return
        val next = card.duty.next
        detail = card.copy(duty = next)
        PathNetworkingFabric.sendCrewDuty(snapshot.helm, card.villager, next)
    }

    // region station dropdown

    private fun stationMenuX(): Int = cardX() + CARD_W - CARD_PAD - STATION_MENU_W
    private fun stationMenuY(): Int = stationRowY() + DUTY_BTN_H
    private fun stationMenuEntries(card: CrewManifest.Detail): Int = card.gunOptions.size + 1
    private fun stationMenuVisible(card: CrewManifest.Detail): Int =
        minOf(stationMenuEntries(card), STATION_MENU_ROWS)

    /**
     * The gun list, unfolded under the Station button: "—" to stand down, then every gun in reading order.
     * Manned guns are listed rather than hidden -- a captain deciding where to put somebody wants to see the
     * whole battery and who holds what -- but only free ones (and "—") answer a click. Scrolls when the
     * battery outgrows it. The choice made here is provisional until the card is left; see [stationBaseline].
     */
    private fun drawStationMenu(guiGraphics: GuiGraphics, card: CrewManifest.Detail, mouseX: Int, mouseY: Int) {
        val x = stationMenuX()
        val y = stationMenuY()
        val visible = stationMenuVisible(card)
        val max = (stationMenuEntries(card) - STATION_MENU_ROWS).coerceAtLeast(0)
        stationMenuScroll = stationMenuScroll.coerceIn(0, max)

        panel(guiGraphics, x - 1, y - 1, STATION_MENU_W + 2, visible * STATION_MENU_ROW_H + 2)

        for (row in 0 until visible) {
            val index = stationMenuScroll + row
            val rowY = y + row * STATION_MENU_ROW_H
            val option = if (index == 0) null else card.gunOptions.getOrNull(index - 1) ?: continue
            val label = option?.label ?: ""
            val occupant = option?.occupant ?: ""
            val selectable = option == null || occupant.isEmpty()
            val current = if (option == null) card.stationLabel.isEmpty() else label == card.stationLabel

            val hovered = selectable &&
                (
                    (mouseX >= x && mouseX < x + STATION_MENU_W && mouseY >= rowY && mouseY < rowY + STATION_MENU_ROW_H) ||
                        index == padSel
                    )
            if (hovered) guiGraphics.fill(x, rowY, x + STATION_MENU_W, rowY + STATION_MENU_ROW_H, ACCENT)

            val nameText = if (option == null) UNSTATIONED_TEXT else Component.literal(label)
            val nameColor = when {
                hovered -> 0xFFFFFFFF.toInt()
                current -> ACCENT
                selectable -> TEXT
                else -> DIM
            }
            small(guiGraphics, nameText, x + 4, rowY + 3, nameColor)
            if (occupant.isNotEmpty()) {
                small(
                    guiGraphics, Component.literal(occupant),
                    x + STATION_MENU_W - 4 - (font.width(occupant) * SMALL).toInt(), rowY + 3, DIM
                )
            }
        }
        // More entries above or below: say so, in the quietest way that still says it.
        if (stationMenuScroll > 0) small(guiGraphics, MORE_ABOVE, x + STATION_MENU_W - 10, y + 1, SUBTLE)
        if (stationMenuScroll < max) {
            small(guiGraphics, MORE_BELOW, x + STATION_MENU_W - 10, y + visible * STATION_MENU_ROW_H - 7, SUBTLE)
        }
    }

    private fun handleStationMenuClick(card: CrewManifest.Detail, mx: Int, my: Int) {
        val x = stationMenuX()
        val y = stationMenuY()
        val visible = stationMenuVisible(card)
        if (mx < x || mx >= x + STATION_MENU_W || my < y || my >= y + visible * STATION_MENU_ROW_H) {
            stationMenuOpen = false
            return
        }
        val index = stationMenuScroll + (my - y) / STATION_MENU_ROW_H
        if (index == 0) {
            detail = card.copy(stationLabel = "")
            stationMenuOpen = false
            return
        }
        val option = card.gunOptions.getOrNull(index - 1) ?: return
        if (option.occupant.isNotEmpty()) return // manned by somebody else; the menu stays open
        detail = card.copy(stationLabel = option.label)
        stationMenuOpen = false
    }

    // endregion

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
            guiGraphics.renderTooltip(font, stack, mouseX, mouseY)
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
        pose.pushPose()
        pose.scale(SMALL, SMALL, 1f)
        guiGraphics.drawString(font, text, Math.round(x / SMALL), Math.round(y / SMALL), color, false)
        pose.popPose()
    }

    /**
     * What to call a profession.
     *
     * `none` is spelled out as "Unemployed" rather than passed through: vanilla's own component for it reads
     * "Villager", which in a list of villagers says nothing at all.
     */
    private fun professionName(id: String): Component {
        if (id == CrewManifest.NO_PROFESSION) return UNEMPLOYED_TEXT
        val key = ResourceLocation.tryParse(id) ?: return UNEMPLOYED_TEXT
        return BuiltInRegistries.VILLAGER_PROFESSION.getOptional(key)
            .map { Component.literal(it.name) as Component }
            .orElse(Component.literal(key.path))
    }

    private fun rankName(level: Int): Component =
        Component.translatable("gui.vs_eureka.crew_level.${level.coerceIn(1, 5)}")

    // endregion

    companion object {

        /**
         * What each Assign row is set to DO, remembered for as long as the player is in a world.
         *
         * Kept here rather than on the screen because a manifest is built fresh every time it opens, and a
         * captain who set Reassign, closed the book and opened it again would find the toggle quietly back
         * on something else. Not written to disk either: [forgetModes] puts both back to the safe one when
         * the connection drops, so the setting lives exactly as long as the voyage does.
         */
        private var rememberedCrewMode = CrewOperations.AssignMode.KEEP
        private var rememberedFireMode = CrewOperations.AssignMode.KEEP

        // The rest of what an Operations order is made of: how many hands, and which side. Same reasoning
        // as the modes above -- these are a captain's standing habit, not a property of the ship in front
        // of them, so they outlive the screen and reset with the session.
        private var rememberedGunnerCount = 0
        private var rememberedFireCount = 0
        private var rememberedCrewSide = CrewOperations.Side.BOTH
        private var rememberedCtrlSide = CrewOperations.Side.BOTH
        private var rememberedShotSide = CrewOperations.Side.BOTH

        // The decks those three scopes point at, 0 = all. Beside the sides because they are half of the
        // same knob: a side remembered without its deck is the half-answer that made re-opening the book
        // feel like it had forgotten something.
        private var rememberedCrewLayer = 0
        private var rememberedCtrlLayer = 0
        private var rememberedShotLayer = 0

        /** The round chosen in the ammunition dropdown. Null means "not chosen yet"; see [selectedAmmo]. */
        private var rememberedAmmo: Pair<Cannonball, CannonCharge>? = null

        /** Every remembered order back to its default. Called when the client disconnects. */
        fun forgetModes() {
            rememberedCrewMode = CrewOperations.AssignMode.KEEP
            rememberedFireMode = CrewOperations.AssignMode.KEEP
            rememberedGunnerCount = 0
            rememberedFireCount = 0
            rememberedCrewSide = CrewOperations.Side.BOTH
            rememberedCtrlSide = CrewOperations.Side.BOTH
            rememberedShotSide = CrewOperations.Side.BOTH
            rememberedCrewLayer = 0
            rememberedCtrlLayer = 0
            rememberedShotLayer = 0
            rememberedAmmo = null
        }

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

        /** The captain's crews arriving, for the Crews tab. Ignored unless it is about this wheel. */
        fun acceptCrewRoll(roll: CrewRoll.Roll) {
            val screen = Minecraft.getInstance().screen as? CrewManifestScreen ?: return
            if (screen.snapshot.helm != roll.helm) return
            screen.crewRoll = roll
            // A crew that has just been deleted cannot go on being open behind the list.
            if (roll.entries.none { it.id == screen.openCrew }) {
                screen.openCrew = null
                screen.crewRoster = null
            }
            screen.disbandArmed = null
            screen.rebuildWidgets()
        }

        /** One crew's articles arriving. Ignored unless it is the crew the tab is showing. */
        fun acceptCrewRoster(roster: CrewRoll.Roster) {
            val screen = Minecraft.getInstance().screen as? CrewManifestScreen ?: return
            if (screen.openCrew != roster.id) return
            screen.crewRoster = roster
            screen.scroll = 0
            screen.rebuildWidgets()
        }

        /** A stores tally arriving, decks census riding along. Ignored unless it is about this wheel. */
        fun acceptStores(
            helm: Long,
            stores: ShipStores.Stores,
            decks: List<Int>,
            firing: Boolean,
            memory: CrewOperations.OpsMemory?
        ) {
            val screen = Minecraft.getInstance().screen as? CrewManifestScreen ?: return
            if (screen.snapshot.helm != helm) return
            screen.acceptStoresNow(stores, decks, firing, memory)
        }

        private val TITLE: Component = Component.translatable("gui.vs_eureka.crew_manifest")
        private val LOCKED_TEXT: Component = Component.translatable("gui.vs_eureka.crew_berth_locked")
        private val LOCKED_HINT: Component = Component.translatable("gui.vs_eureka.crew_berth_hint")
        private val EMPTY_TEXT: Component = Component.translatable("gui.vs_eureka.crew_berth_empty")
        private val RENAME_TEXT: Component = Component.translatable("gui.vs_eureka.crew_rename")
        private val BACK_TEXT: Component = Component.translatable("gui.vs_eureka.crew_back")
        private val RENAME_CREW_TEXT: Component = Component.translatable("gui.vs_eureka.crew_rename_crew")
        private val DISMISS_TEXT: Component = Component.translatable("gui.vs_eureka.crew_dismiss")
        private val DISMISS_CONFIRM_TEXT: Component =
            Component.translatable("gui.vs_eureka.crew_dismiss_confirm")
        private val ASHORE_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ashore")
        private val SELLS_TEXT: Component = Component.translatable("gui.vs_eureka.crew_sells")
        private val NO_TRADES_TEXT: Component = Component.translatable("gui.vs_eureka.crew_no_trades")
        private val LOADING_TEXT: Component = Component.translatable("gui.vs_eureka.crew_loading")
        private val OUT_OF_STOCK_TEXT: Component = Component.translatable("gui.vs_eureka.crew_out_of_stock")
        private val ASSIGNMENT_TEXT: Component = Component.translatable("gui.vs_eureka.crew_assignment")
        private val STATION_TEXT: Component = Component.translatable("gui.vs_eureka.crew_station")
        private val NO_GUNS_TEXT: Component = Component.translatable("gui.vs_eureka.crew_station_no_guns")
        private val OFF_DUTY_TEXT: Component = Component.translatable("gui.vs_eureka.crew_station_none")
        private val UNSTATIONED_TEXT: Component = Component.translatable("gui.vs_eureka.crew_unstationed")
        private val MORE_ABOVE: Component = Component.literal("▲")
        private val MORE_BELOW: Component = Component.literal("▼")
        private val TAB_OPERATIONS_TEXT: Component = Component.translatable("gui.vs_eureka.crew_tab_operations")
        private val TAB_ROSTER_TEXT: Component = Component.translatable("gui.vs_eureka.crew_tab_roster")
        private val TAB_CREWS_TEXT: Component = Component.translatable("gui.vs_eureka.crew_tab_crews")
        private val CREWS_NONE_TEXT: Component = Component.translatable("gui.vs_eureka.crews_none")
        private val CREW_DELETE_TEXT: Component = Component.translatable("gui.vs_eureka.crew_delete")
        private val CREW_DELETE_CONFIRM_TEXT: Component = Component.translatable("gui.vs_eureka.crew_dismiss_confirm")
        private val CREW_SUMMON_TEXT: Component = Component.translatable("gui.vs_eureka.crew_summon")
        private val CREW_READONLY_TEXT: Component = Component.translatable("gui.vs_eureka.crew_readonly")
        private val OPS_CREW_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_crew")
        private val OPS_CTRL_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_cannon_controls")
        private val OPS_RESTOCK_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_restock")
        private val OPS_GUNNERS_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_gunners")
        private val OPS_FIRE_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_fire_watch")
        private val OPS_ASSIGN_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_assign")
        private val OPS_MODE_KEEP_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_mode_keep")
        private val OPS_MODE_REASSIGN_TEXT: Component =
            Component.translatable("gui.vs_eureka.crew_ops_mode_reassign")
        private val OPS_MODE_RELEASE_TEXT: Component =
            Component.translatable("gui.vs_eureka.crew_ops_mode_release")
        private val OPS_AMMO_LABEL_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_ammo")
        private val OPS_FIRE_AT_WILL_TEXT: Component =
            Component.translatable("gui.vs_eureka.crew_ops_fire_at_will")
        private val OPS_FIRE_AT_WILL_ON_TEXT: Component =
            Component.translatable("gui.vs_eureka.crew_ops_fire_at_will_on")
        private val OPS_FIRE_AT_WILL_OFF_TEXT: Component =
            Component.translatable("gui.vs_eureka.crew_ops_fire_at_will_off")
        private val OPS_LAYER_ALL_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_layer_all")
        private val OPS_SIDE_PORT_TEXT: Component = Component.translatable("gui.vs_eureka.crew_side_port")
        private val OPS_SIDE_BOTH_TEXT: Component = Component.translatable("gui.vs_eureka.crew_side_both")
        private val OPS_SIDE_STBD_TEXT: Component = Component.translatable("gui.vs_eureka.crew_side_starboard")
        private val OPS_RESTOCK_POWDER_TEXT: Component =
            Component.translatable("gui.vs_eureka.crew_ops_restock_powder")
        private val OPS_RESTOCK_SHOT_TEXT: Component =
            Component.translatable("gui.vs_eureka.crew_ops_restock_shot")
        private val OPS_REFUEL_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_refuel")
        private val OPS_FUEL_LIST_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_fuel_list")
        private val OPS_FUEL_TITLE_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_fuel_title")
        private val OPS_FUEL_NONE_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_fuel_none")
        private val OPS_AMMO_NONE_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_ammo_none")
        private val OPS_READING_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_reading")
        private val COUNT_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_count")
        private val OPS_ELEVATION_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_elevation")
        private val OPS_SIDE_ALL_TEXT: Component = Component.translatable("gui.vs_eureka.crew_side_all")
        private val CARD_GUN_TEXT: Component = Component.translatable("gui.vs_eureka.crew_card_gun_unnamed")
        private val CARD_CHARGE_TEXT: Component = Component.translatable("gui.vs_eureka.crew_card_charge")
        private val CARD_ELEVATION_TEXT: Component = Component.translatable("gui.vs_eureka.crew_card_elevation")
        private val CARD_AMMO_TEXT: Component = Component.translatable("gui.vs_eureka.crew_card_ammo")
        private val CARD_AMMO_EMPTY_TEXT: Component = Component.translatable("gui.vs_eureka.crew_card_ammo_empty")
        private val CARD_LOCK_TEXT: Component = Component.translatable("gui.vs_eureka.crew_card_lock")
        private val CARD_UNLOCK_TEXT: Component = Component.translatable("gui.vs_eureka.crew_card_unlock")

        /** The angle stepper's arrows: left lays down toward -45, right up toward +45. */
        private val ANGLE_DOWN_TEXT: Component = Component.literal("<")
        private val ANGLE_UP_TEXT: Component = Component.literal(">")

        /** The three powder measures' labels, ordinal-aligned with PowderCharge. */
        private val POWER_LABELS = listOf(
            Component.literal("1x"), Component.literal("2x"), Component.literal("3x")
        )

        private val OPS_POWER_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_power")
        private val OPS_TYPE_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_type")
        private val OPS_TYPES_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_types")
        private val MINUS_TEXT: Component = Component.literal("-")
        private val PLUS_TEXT: Component = Component.literal("+")

        /** The selectors' reading order, which is also the pad's cycle order. */
        private val SIDE_ORDER = listOf(
            CrewOperations.Side.PORT, CrewOperations.Side.BOTH, CrewOperations.Side.STARBOARD
        )
        private val UNEMPLOYED_TEXT: Component = Component.translatable("entity.vs_eureka.villager.unemployed")
        private val INFO_GLYPH: Component = Component.literal("i")
        private val ARROW_TEXT: Component = Component.literal("→")

        // Panel. Wide enough for a name, a profession-and-rank line and the card's trade rows without wrapping;
        // eight rows tall, which keeps the whole thing on screen at GUI scale 3 on a 1080p display. Grown 16px
        // for the tab strip rather than surrendering a roster row -- the roster's scroll and pad behaviour is
        // settled, and 230 still clears the 360-logical-pixel budget with half again to spare.
        private const val PANEL_W = 300
        private const val PANEL_H = 230
        private const val LIST_TOP = 38
        private const val ROW_H = 22
        private const val VISIBLE_ROWS = 8
        private const val LIST_BOTTOM = LIST_TOP + ROW_H * VISIBLE_ROWS

        /** Where the header's click-to-rename target ends: above the tab strip, not above the old rule. */
        private const val HEADER_BOTTOM = 16

        // The tab strip, in the helm menu's proportions: two tabs sharing the panel width.
        private const val TAB_Y = 18
        private const val TAB_H = 14
        private const val TAB_BASELINE_Y = TAB_Y + TAB_H
        private const val TAB_MARGIN = 8
        private const val TAB_GAP = 4
        private const val TAB_W = (PANEL_W - 2 * TAB_MARGIN - 2 * TAB_GAP) / 3

        // region Operations geometry (virtual body rows; every control 14px tall)
        //
        // The body SCROLLS: rows live at virtual Y offsets inside a scissored viewport from OPS_BODY_TOP
        // to OPS_BODY_BOTTOM, and meet the screen only through opsRowY/opsStopRect -- which is what lets
        // one hit-test source keep the mouse, the pad and the paint honest about what is actually in
        // view. The holds line below the body is pinned and never moves.

        private const val OPS_CTRL_H = 14
        private const val OPS_BODY_TOP = 38
        private const val OPS_BODY_BOTTOM = 212
        private const val OPS_BODY_H = OPS_BODY_BOTTOM - OPS_BODY_TOP

        /** One wheel notch's worth of body travel: a whole row pitch, so rows land aligned. */
        private const val OPS_SCROLL_STEP = 18

        private const val OPS_V_CREW_LABEL = 4
        private const val OPS_V_ROW_G = 14
        private const val OPS_V_ROW_GSCOPE = 32
        private const val OPS_V_ROW_F = 50
        private const val OPS_V_SEP1 = 68
        private const val OPS_V_CTRL_LABEL = 74
        private const val OPS_V_ROW_CSCOPE = 84
        private const val OPS_V_ROW_ELEV = 102
        private const val OPS_V_ROW_PWR = 120
        private const val OPS_V_ROW_AMMO = 138
        private const val OPS_V_ROW_FIRE_AT_WILL = 156
        private const val OPS_V_SEP2 = 174
        private const val OPS_V_RESTOCK_LABEL = 180
        private const val OPS_V_ROW_SHOT = 190
        private const val OPS_V_ROW_SHOT_SCOPE = 208
        private const val OPS_V_ROW_REFUEL = 226
        private const val OPS_V_ROW_POWDER = 244
        private const val OPS_CONTENT_H = OPS_V_ROW_POWDER + OPS_CTRL_H + 4

        private const val OPS_HOLDS_Y = 219

        private const val OPS_MINUS_X = 64
        private const val OPS_BOX_X = 80
        private const val OPS_BOX_W = 30
        private const val OPS_PLUS_X = 114
        private const val OPS_STEP_W = 12
        private const val OPS_ASSIGN_X = 244
        private const val OPS_ASSIGN_W = 48

        /** The mode toggle, in the gap the count stepper and Assign left between them. */
        private const val OPS_MODE_X = 136
        private const val OPS_MODE_W = 100
        private const val OPS_WIDE_W = 100
        private const val OPS_AMMO_X = 116
        private const val OPS_AMMO_W = 176
        private const val OPS_FUEL_BTN_W = 50

        /** A scope row: the three side segments, then the deck dropdown's button beside them. */
        private const val OPS_SCOPE_SIDES_X = 64
        private const val OPS_LAYER_X = 172
        private const val OPS_LAYER_BTN_W = 88

        /** The cannonball restock's own side segments, right of its button. */
        private const val OPS_SHOT_SIDES_X = 116

        private const val SEG_W = 32
        private const val SEG_GAP = 2

        /** The power segments' width (and the unit the angle stepper's row width is derived from). */
        private const val ELEV_SEG_W = 22

        /**
         * The angle stepper: [<] [value] [>], filling exactly the row the five 22.5-degree segments
         * used to, so nothing else in the panel moves. Nineteen 5-degree steps live behind the arrows.
         */
        private const val ANGLE_ROW_W = ELEV_SEG_W * 5 + SEG_GAP * 4
        private const val ANGLE_ARROW_W = 16
        private const val ANGLE_VALUE_W = ANGLE_ROW_W - 2 * (ANGLE_ARROW_W + SEG_GAP)

        /** Where the angle/power segments start: right of their label-buttons, no selector between now. */
        private const val OPS_SEGS_X = 72

        /** The Set Angle / Set Power label-buttons: the rows' triggers, sized to their own words. */
        private const val OPS_ELEV_BTN_W = 52

        private const val AMMO_MENU_ROWS = 6
        private const val AMMO_MENU_ROW_H = 12
        private const val LAYER_MENU_ROWS = 6
        private const val LAYER_MENU_ROW_H = 12

        private const val FUEL_POPUP_X = 40
        private const val FUEL_POPUP_Y = 56
        private const val FUEL_POPUP_W = 220
        private const val FUEL_POPUP_HEADER = 20

        /** The refuel plan's preview depth: the top three fuels, or the one there is. */
        private const val FUEL_ROWS = 3

        /** The card's pad stops -- IDs, not positions; [cardStops] builds the per-card walk order. */
        private const val CARD_STOP_DUTY = 0
        private const val CARD_STOP_STATION = 1
        private const val CARD_STOP_CHARGE = 2
        private const val CARD_STOP_ELEVATION = 3
        private const val CARD_STOP_AMMO = 4
        private const val CARD_STOP_LOCK = 5

        /** The Lock/Unlock button, sized for the longer word so it does not resize when pressed. */
        private const val LOCK_BTN_W = 56

        /** The card's ammo control and its dropdown: a round's name plus a count needs the room. */
        private const val GUN_AMMO_BTN_W = 150

        /** The pad's walk order over the Operations rows -- reading order; indexes into [opsVirtualRect]. */
        private const val STOP_G_MINUS = 0
        private const val STOP_G_BOX = 1
        private const val STOP_G_PLUS = 2
        private const val STOP_G_MODE = 3
        private const val STOP_G_ASSIGN = 4
        private const val STOP_G_SIDE = 5
        private const val STOP_G_LAYER = 6
        private const val STOP_F_MINUS = 7
        private const val STOP_F_BOX = 8
        private const val STOP_F_PLUS = 9
        private const val STOP_F_MODE = 10
        private const val STOP_F_ASSIGN = 11
        private const val STOP_C_SIDE = 12
        private const val STOP_C_LAYER = 13
        private const val STOP_LAY = 14
        private const val STOP_ELEV_ANGLE = 15
        private const val STOP_PWR = 16
        private const val STOP_PWR_LEVEL = 17
        private const val STOP_AMMO_MENU = 18
        private const val STOP_FIRE_AT_WILL = 19
        private const val STOP_SHOT = 20
        private const val STOP_SHOT_SIDE = 21
        // INSERTED, not appended: padOps walks these in numeric order, so the restock's deck has to sit
        // beside its side to be reached next rather than after the powder at the far end of the tab.
        private const val STOP_SHOT_LAYER = 22
        private const val STOP_REFUEL = 23
        private const val STOP_FUEL_LIST = 24
        private const val STOP_POWDER = 25
        private const val OPS_STOP_COUNT = 26

        // endregion

        private const val CARD_W = 260

        /** Grown from 178 to make room for the two duty lines under the trades, which used to be inert labels. */
        private const val CARD_H = 192
        private const val CARD_PAD = 8
        private const val NAME_BOX_H = 14

        /** Wide enough for "Firefighter", so the control does not resize as it is cycled through. */
        private const val DUTY_BTN_W = 76
        private const val DUTY_BTN_H = 14

        /** The station dropdown: room for a label on the left and an occupant's name on the right. */
        private const val STATION_MENU_W = 110
        private const val STATION_MENU_ROW_H = 12
        private const val STATION_MENU_ROWS = 6

        /** Ticks a held D-pad direction waits before repeating, and the gap between repeats after that. */
        private const val PAD_REPEAT_DELAY = 6
        private const val PAD_REPEAT_EVERY = 3

        /** Right-stick scroll: deflection under this is rest, and one notch scrolls every few ticks held. */
        private const val STICK_DEADZONE = 0.4f
        private const val STICK_EVERY = 3

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

        /** Wide enough for "Really?" as well as "Dismiss", so the button does not resize under the cursor. */
        private const val DISMISS_BTN_W = 58

        private const val CREW_RENAME_BTN_W = 78

        /** Between Rename Crew and Back. Enough that they are plainly two buttons, not a split one. */
        private const val CREW_RENAME_GAP = 6
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

        /** 1.20.1: how far below the icon box the feet anchor sits so the head fills the box. */
        private const val HEAD_FEET_DROP = 41

        // Fallback icon: the 8x10 front face of the villager head cube, drawn at 2x. BASE_SKIN is the only one
        // of the three layers that carries a face in VANILLA -- see drawHead for why that matters.
        private val BASE_SKIN: ResourceLocation =
            ResourceLocation("minecraft", "textures/entity/villager/villager.png")
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

        /** An armed Delete. Red because the next press destroys villagers and there is no way back. */
        private const val DANGER = 0xFFA03030.toInt()
        private const val CREW_DEL_W = 42
        private const val CREW_DEL_H = 14
        private const val CREW_DEL_INSET = 4
        /**
         * Matched to Rename Crew beside it. At 60 the label had to marquee, which on a button that is only
         * ever read once -- to find out what it does -- is motion for nothing.
         */
        private const val CREW_SUMMON_BTN_W = CREW_RENAME_BTN_W
    }
}
