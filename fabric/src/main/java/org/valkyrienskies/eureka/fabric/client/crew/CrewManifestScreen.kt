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
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.crew.CrewDuty
import org.valkyrienskies.eureka.crew.CrewManifest
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

    /** The book's two faces. */
    private enum class Tab { OPERATIONS, ROSTER }

    /** Which face is showing. Operations first: fleet-scale orders are what a captain opens this FOR. */
    private var activeTab = Tab.OPERATIONS

    // region Operations state

    /** What the holds last reported, or null before the first stores payload has landed. */
    private var stores: ShipStores.Stores? = null

    /** The count boxes' values, kept as fields so widget rebuilds keep what was typed. */
    private var gunnerCount = 0
    private var fireCount = 0
    private var gunnerCountBox: EditBox? = null
    private var fireCountBox: EditBox? = null

    /** Which battery crew assignment deals to, and which one the stores orders serve. Independent knobs. */
    private var crewSide = CrewOperations.Side.BOTH
    private var storesSide = CrewOperations.Side.BOTH

    /** The round the shot restock will load. Defaults to the holds' most plentiful when stores arrive. */
    private var selectedAmmo: Pair<Cannonball, CannonCharge>? = null
    private var ammoMenuOpen = false
    private var ammoMenuScroll = 0

    private var fuelPopupOpen = false
    private var fuelPopupScroll = 0

    /** The elevation row's own knobs: which guns, and which of the five steps. Level to start. */
    private var elevSide = CrewOperations.Side.BOTH
    private var elevIndex = 2

    /** The power row's knobs, independent of the elevation row's for the same reason it is. */
    private var powerSide = CrewOperations.Side.BOTH
    private var powerLevel = 0

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

            if (activeTab == Tab.OPERATIONS) {
                initOperations()
                return
            }

            // The crew's name is edited in place: clicking the heading swaps it for a box over the same
            // pixels, which is the only spot in this panel wide enough for a name and the one a player
            // would aim at. Built only while actually renaming, same reason the card's widgets are.
            if (renamingCrew) {
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

            // Clicking the heading still starts a rename -- it is the most direct thing to aim at -- but a
            // button says the rename EXISTS, which a click target does not.
            addRenderableWidget(
                ShipHelmButton(
                    left + 6, top + PANEL_H - 16, CREW_RENAME_BTN_W, BACK_BTN_H, RENAME_CREW_TEXT, font
                ) { beginCrewRename() }
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

        addRenderableWidget(
            ShipHelmIconButton(
                cardX() + CARD_W - CARD_PAD - RENAME_BTN_W, cardY() + CARD_PAD,
                RENAME_BTN_W, NAME_BOX_H, RENAME_TEXT, font
            ) { commitRename() }
        )

        addRenderableWidget(
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

        addRenderableWidget(
            ShipHelmButton(
                cardX() + CARD_W - CARD_PAD - BACK_BTN_W, cardY() + CARD_H - CARD_PAD - BACK_BTN_H,
                BACK_BTN_W, BACK_BTN_H, BACK_TEXT, font
            ) { closeCard() }
        )
    }

    override fun isPauseScreen(): Boolean = false

    // region the tab strip and the Operations tab's widgets

    private fun addTab(index: Int, text: Component, tab: Tab) {
        addRenderableWidget(
            ShipHelmTab(
                left + TAB_MARGIN + (TAB_W + TAB_GAP) * index, top + TAB_Y, TAB_W, TAB_H, text, font,
                ACCENT, { if (activeTab == tab) ShipHelmTab.State.ACTIVE else ShipHelmTab.State.IDLE }
            ) { switchTab(tab) }
        )
    }

    private fun switchTab(next: Tab) {
        if (activeTab == next) return
        activeTab = next
        ammoMenuOpen = false
        fuelPopupOpen = false
        if (renamingCrew) commitCrewRename(send = false)
        padSel = -1
        rebuildWidgets()
        // Fresh counts every time the tab comes up: the holds change while the book is shut.
        if (next == Tab.OPERATIONS) PathNetworkingFabric.sendCrewStoresAsk(snapshot.helm)
    }

    /** The Operations tab's real widgets: just the two count boxes. Every other control is painted. */
    private fun initOperations() {
        gunnerCountBox = addRenderableWidget(
            countBox(left + OPS_BOX_X, top + OPS_ROW_G, gunnerCount) { gunnerCount = it }
        )
        fireCountBox = addRenderableWidget(
            countBox(left + OPS_BOX_X, top + OPS_ROW_F, fireCount) { fireCount = it }
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

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.mouseClicked(mouseButtonEvent, doubled)) return true

        val mx = mouseButtonEvent.x.toInt()
        val my = mouseButtonEvent.y.toInt()

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

                // The Lock answers FIRST, and answers even locked -- it is the one way back out.
                if ((card.duty != CrewDuty.NONE || card.locked) &&
                    mx >= lockButtonX() && mx < lockButtonX() + LOCK_BTN_W &&
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

        // Clicking anywhere off the box while renaming commits it, the way a name field is expected to behave.
        if (renamingCrew) {
            commitCrewRename(send = true)
            return true
        }

        // The heading is the crew's name, and clicking it edits it. Bounded to the left of the berth counter
        // and above the tab strip, so aiming at the count or a tab never starts a rename.
        if (mx >= left && mx <= left + PANEL_W - BERTHS_GUTTER && my >= top + 2 && my < top + HEADER_BOTTOM) {
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
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        }
        if (activeTab == Tab.OPERATIONS) {
            val holds = stores
            if (fuelPopupOpen && holds != null) {
                val max = (holds.fuels.size - FUEL_ROWS).coerceAtLeast(0)
                fuelPopupScroll = (fuelPopupScroll - scrollY.toInt()).coerceIn(0, max)
            } else if (ammoMenuOpen && holds != null) {
                val max = (holds.ammo.size - AMMO_MENU_ROWS).coerceAtLeast(0)
                ammoMenuScroll = (ammoMenuScroll - scrollY.toInt()).coerceIn(0, max)
            }
            // Swallowed either way: there is no list behind the Operations rows to scroll into.
            return true
        }
        scroll -= scrollY.toInt()
        clampScroll()
        return true
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
        if (openCard == null && !renamingCrew && !ammoMenuOpen && !fuelPopupOpen) {
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
            mouseScrolled(0.0, 0.0, 0.0, if (deflect < 0) 1.0 else -1.0)
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
            6 -> padCardAmmo(step, choose, back)
            5 -> padFuel(step, back, choose)
            4 -> padOpsAmmo(step, choose, back)
            3 -> padOps(step, choose, back)
            2 -> padMenu(step, choose, back)
            1 -> padCard(step, choose, back)
            else -> padRoster(step, choose)
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

    /** The Operations rows, walked top to bottom. Choose is the click; back only clears the selection. */
    private fun padOps(step: Int, choose: Boolean, back: Boolean) {
        if (back) {
            padSel = -1
            return
        }
        if (step != 0) {
            padSel = (if (padSel < 0) (if (step > 0) 0 else OPS_STOP_COUNT - 1) else padSel + step)
                .coerceIn(0, OPS_STOP_COUNT - 1)
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

    private fun padFuel(step: Int, back: Boolean, choose: Boolean) {
        if (back || choose) {
            fuelPopupOpen = false
            return
        }
        val holds = stores ?: return
        if (step != 0) {
            val max = (holds.fuels.size - FUEL_ROWS).coerceAtLeast(0)
            fuelPopupScroll = (fuelPopupScroll + step).coerceIn(0, max)
        }
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
        // Escape steps back out of whatever is innermost: a popup, then a rename, then a card, then out.
        if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (fuelPopupOpen) {
                fuelPopupOpen = false
                return true
            }
            if (ammoMenuOpen) {
                ammoMenuOpen = false
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
        if (activeTab == Tab.OPERATIONS && openCard == null) {
            drawOperations(guiGraphics, mouseX, mouseY)
        } else {
            drawList(guiGraphics, mouseX, mouseY)
        }
        if (openCard != null) drawCard(guiGraphics, mouseX, mouseY)
        // The count boxes are real widgets, which super paints LAST -- over the fuel popup, whose panel
        // covers their pixels. Hiding them while it is up is the honest fix; a popup owns the screen.
        val boxesVisible = !fuelPopupOpen
        gunnerCountBox?.visible = boxesVisible
        fireCountBox?.visible = boxesVisible
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

        // The tab baseline: the strip's ACTIVE tab opens onto this line, exactly as the helm menu's does.
        guiGraphics.fill(left + 4, top + TAB_BASELINE_Y, left + PANEL_W - 4, top + TAB_BASELINE_Y + 1, ACCENT)
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
        if (row.duty != CrewDuty.NONE) {
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

    // region the Operations tab

    /**
     * The Operations rows: painted and hand-tested like the roster, for the roster's reasons -- and one
     * more. Every control here is also a numbered PAD STOP, walked with the D-pad; a painted row asks
     * "am I stop N or under the mouse" in one expression, where a widget would need focus plumbing for
     * each of fifteen controls.
     */
    private fun drawOperations(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val holds = stores

        small(guiGraphics, OPS_CREW_TEXT, left + 8, top + OPS_CREW_LABEL_Y, DIM)

        // Gunners: [-] [count] [+], the battery to deal to, and the order itself.
        small(guiGraphics, OPS_GUNNERS_TEXT, left + 8, top + OPS_ROW_G + 4, TEXT)
        opsButton(guiGraphics, STOP_G_MINUS, MINUS_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_G_PLUS, PLUS_TEXT, mouseX, mouseY)
        drawSides(guiGraphics, STOP_G_SIDE, top + OPS_ROW_G, crewSide, OPS_SIDE_BOTH_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_G_ASSIGN, OPS_ASSIGN_TEXT, mouseX, mouseY)

        // Fire watch: a count and the order. No sides -- a fire does not care which battery you favour.
        small(guiGraphics, OPS_FIRE_TEXT, left + 8, top + OPS_ROW_F + 4, TEXT)
        opsButton(guiGraphics, STOP_F_MINUS, MINUS_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_F_PLUS, PLUS_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_F_ASSIGN, OPS_ASSIGN_TEXT, mouseX, mouseY)

        guiGraphics.fill(left + 8, top + OPS_SEP_Y, left + PANEL_W - 8, top + OPS_SEP_Y + 1, SEPARATOR)
        small(guiGraphics, OPS_STORES_TEXT, left + 8, top + OPS_STORES_LABEL_Y, DIM)

        // The stores side selector, shared by both restock orders, with the powder count alongside --
        // the readout lives where the orders that spend it are aimed.
        small(guiGraphics, OPS_SIDE_TEXT, left + 8, top + OPS_ROW_SIDE + 4, TEXT)
        drawSides(guiGraphics, STOP_S_SIDE, top + OPS_ROW_SIDE, storesSide, OPS_SIDE_BOTH_TEXT, mouseX, mouseY)
        val powder = Component.translatable(
            "gui.vs_eureka.crew_ops_powder_count", holds?.gunpowder?.toString() ?: "--"
        )
        small(
            guiGraphics, powder,
            left + PANEL_W - 8 - (font.width(powder) * SMALL).toInt(), top + OPS_ROW_SIDE + 4,
            if (holds == null) DIM else ACCENT
        )

        opsButton(guiGraphics, STOP_POWDER, OPS_RESTOCK_POWDER_TEXT, mouseX, mouseY)

        opsButton(guiGraphics, STOP_SHOT, OPS_RESTOCK_SHOT_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_AMMO_MENU, ammoButtonText(), mouseX, mouseY)

        // Elevation: the LABEL is the trigger -- press "Elevation" and the guns are laid per the
        // selectors beside it. Its first draft was a separate "Lay Guns" button on the row below, which
        // read as unrelated to the selectors it served; the label-as-button puts the order and its
        // arguments on one line, the way the Assign rows already work. Sides are independent of the
        // restock selector on purpose: "port guns up, then resupply everything" is one trip here, not two.
        opsButton(guiGraphics, STOP_LAY, OPS_ELEVATION_TEXT, mouseX, mouseY)
        drawSides(guiGraphics, STOP_ELEV_SIDE, top + OPS_ROW_ELEV, elevSide, OPS_SIDE_ALL_TEXT, mouseX, mouseY)
        drawAngles(guiGraphics, top + OPS_ROW_ELEV, mouseX, mouseY)

        // Power: the elevation row's twin, one line down -- the same order-and-arguments shape, setting
        // the battery's powder measure. Locked gunners' guns keep theirs, exactly as they do the angle.
        opsButton(guiGraphics, STOP_PWR, OPS_POWER_TEXT, mouseX, mouseY)
        drawSides(guiGraphics, STOP_PWR_SIDE, top + OPS_ROW_PWR, powerSide, OPS_SIDE_ALL_TEXT, mouseX, mouseY)
        drawPowers(guiGraphics, top + OPS_ROW_PWR, mouseX, mouseY)

        opsButton(guiGraphics, STOP_REFUEL, OPS_REFUEL_TEXT, mouseX, mouseY)
        opsButton(guiGraphics, STOP_FUEL_LIST, OPS_FUEL_LIST_TEXT, mouseX, mouseY)

        // Bottom-left corner, under everything: a summary reads last, and the rows above needed its room.
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

        // Popups last, over everything, exactly as the station dropdown is.
        if (ammoMenuOpen) drawAmmoMenu(guiGraphics, mouseX, mouseY)
        if (fuelPopupOpen) drawFuelPopup(guiGraphics)
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

    /** The five elevation steps, drawn exactly as the side segments are, one per 22.5 degrees. */
    private fun drawAngles(guiGraphics: GuiGraphics, y: Int, mouseX: Int, mouseY: Int) {
        val rect = opsStopRect(STOP_ELEV_ANGLE) ?: return
        val (gx, gy, gw, gh) = rect
        if (padContext == 3 && padSel == STOP_ELEV_ANGLE) {
            guiGraphics.fill(gx - 1, gy - 1, gx + gw + 1, gy, ACCENT)
            guiGraphics.fill(gx - 1, gy + gh, gx + gw + 1, gy + gh + 1, ACCENT)
            guiGraphics.fill(gx - 1, gy, gx, gy + gh, ACCENT)
            guiGraphics.fill(gx + gw, gy, gx + gw + 1, gy + gh, ACCENT)
        }
        for (index in 0..4) {
            val sx = gx + index * (ELEV_SEG_W + SEG_GAP)
            val active = index == elevIndex
            val hovered = mouseX >= sx && mouseX < sx + ELEV_SEG_W && mouseY >= y && mouseY < y + OPS_CTRL_H
            guiGraphics.fill(
                sx, y, sx + ELEV_SEG_W, y + OPS_CTRL_H,
                when {
                    active -> ACCENT
                    hovered -> ROW_HOVER
                    else -> ROW_LOCKED
                }
            )
            val text = ANGLE_LABELS[index]
            small(
                guiGraphics, text,
                sx + (ELEV_SEG_W - (font.width(text) * SMALL).toInt()) / 2, y + 4,
                if (active) 0xFFFFFFFF.toInt() else TEXT
            )
        }
    }

    private fun opsLit(stop: Int, x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int): Boolean =
        (padContext == 3 && padSel == stop) ||
            (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h)

    /** Every stop's rectangle, absolute. The single source both the mouse and the pad walk against. */
    private fun opsStopRect(stop: Int): IntArray? = when (stop) {
        STOP_G_MINUS -> intArrayOf(left + OPS_MINUS_X, top + OPS_ROW_G, OPS_STEP_W, OPS_CTRL_H)
        STOP_G_BOX -> intArrayOf(left + OPS_BOX_X, top + OPS_ROW_G, OPS_BOX_W, OPS_CTRL_H)
        STOP_G_PLUS -> intArrayOf(left + OPS_PLUS_X, top + OPS_ROW_G, OPS_STEP_W, OPS_CTRL_H)
        STOP_G_SIDE -> intArrayOf(left + OPS_SIDES_X, top + OPS_ROW_G, SEG_W * 3 + SEG_GAP * 2, OPS_CTRL_H)
        STOP_G_ASSIGN -> intArrayOf(left + OPS_ASSIGN_X, top + OPS_ROW_G, OPS_ASSIGN_W, OPS_CTRL_H)
        STOP_F_MINUS -> intArrayOf(left + OPS_MINUS_X, top + OPS_ROW_F, OPS_STEP_W, OPS_CTRL_H)
        STOP_F_BOX -> intArrayOf(left + OPS_BOX_X, top + OPS_ROW_F, OPS_BOX_W, OPS_CTRL_H)
        STOP_F_PLUS -> intArrayOf(left + OPS_PLUS_X, top + OPS_ROW_F, OPS_STEP_W, OPS_CTRL_H)
        STOP_F_ASSIGN -> intArrayOf(left + OPS_ASSIGN_X, top + OPS_ROW_F, OPS_ASSIGN_W, OPS_CTRL_H)
        STOP_S_SIDE -> intArrayOf(left + OPS_S_SIDES_X, top + OPS_ROW_SIDE, SEG_W * 3 + SEG_GAP * 2, OPS_CTRL_H)
        STOP_POWDER -> intArrayOf(left + 8, top + OPS_ROW_POWDER, OPS_WIDE_W, OPS_CTRL_H)
        STOP_SHOT -> intArrayOf(left + 8, top + OPS_ROW_SHOT, OPS_WIDE_W, OPS_CTRL_H)
        STOP_AMMO_MENU -> intArrayOf(left + OPS_AMMO_X, top + OPS_ROW_SHOT, OPS_AMMO_W, OPS_CTRL_H)
        STOP_ELEV_SIDE -> intArrayOf(left + OPS_ELEV_SIDES_X, top + OPS_ROW_ELEV, SEG_W * 3 + SEG_GAP * 2, OPS_CTRL_H)
        STOP_ELEV_ANGLE -> intArrayOf(left + OPS_ELEV_ANGLES_X, top + OPS_ROW_ELEV, ELEV_SEG_W * 5 + SEG_GAP * 4, OPS_CTRL_H)
        STOP_LAY -> intArrayOf(left + 8, top + OPS_ROW_ELEV, OPS_ELEV_BTN_W, OPS_CTRL_H)
        STOP_PWR -> intArrayOf(left + 8, top + OPS_ROW_PWR, OPS_ELEV_BTN_W, OPS_CTRL_H)
        STOP_PWR_SIDE -> intArrayOf(left + OPS_ELEV_SIDES_X, top + OPS_ROW_PWR, SEG_W * 3 + SEG_GAP * 2, OPS_CTRL_H)
        STOP_PWR_LEVEL -> intArrayOf(left + OPS_ELEV_ANGLES_X, top + OPS_ROW_PWR, ELEV_SEG_W * 3 + SEG_GAP * 2, OPS_CTRL_H)
        STOP_REFUEL -> intArrayOf(left + 8, top + OPS_ROW_REFUEL, OPS_WIDE_W, OPS_CTRL_H)
        STOP_FUEL_LIST -> intArrayOf(left + OPS_AMMO_X, top + OPS_ROW_REFUEL, OPS_FUEL_BTN_W, OPS_CTRL_H)
        else -> null
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
            STOP_G_ASSIGN ->
                PathNetworkingFabric.sendCrewAssignGunners(snapshot.helm, gunnerCount, crewSide)
            STOP_F_MINUS -> adjustCount(gunners = false, delta = -1)
            STOP_F_PLUS -> adjustCount(gunners = false, delta = +1)
            STOP_F_BOX -> fireCountBox?.let { this.focused = it }
            STOP_F_ASSIGN ->
                PathNetworkingFabric.sendCrewAssignFirefighters(snapshot.helm, fireCount)
            STOP_S_SIDE -> storesSide = pickSide(storesSide, STOP_S_SIDE, mouseX)
            STOP_POWDER -> PathNetworkingFabric.sendCrewRestockPowder(snapshot.helm, storesSide)
            STOP_SHOT -> selectedAmmo?.let { (ball, charge) ->
                PathNetworkingFabric.sendCrewRestockShot(snapshot.helm, storesSide, ball, charge)
            }
            STOP_AMMO_MENU -> if (stores?.ammo?.isNotEmpty() == true) {
                ammoMenuOpen = true
                ammoMenuScroll = 0
            }
            STOP_ELEV_SIDE -> elevSide = pickSide(elevSide, STOP_ELEV_SIDE, mouseX)
            STOP_ELEV_ANGLE -> elevIndex = pickAngle(mouseX)
            STOP_LAY -> PathNetworkingFabric.sendCrewSetElevation(snapshot.helm, elevSide, elevIndex)
            STOP_PWR_SIDE -> powerSide = pickSide(powerSide, STOP_PWR_SIDE, mouseX)
            STOP_PWR_LEVEL -> powerLevel = pickPower(mouseX)
            STOP_PWR -> PathNetworkingFabric.sendCrewSetPower(snapshot.helm, powerSide, powerLevel)
            STOP_REFUEL -> PathNetworkingFabric.sendCrewRefuel(snapshot.helm)
            STOP_FUEL_LIST -> if (stores != null) {
                fuelPopupOpen = true
                fuelPopupScroll = 0
            }
        }
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

    private fun pickAngle(mouseX: Int?): Int {
        if (mouseX == null) return (elevIndex + 1) % 5
        val rect = opsStopRect(STOP_ELEV_ANGLE) ?: return elevIndex
        return ((mouseX - rect[0]) / (ELEV_SEG_W + SEG_GAP)).coerceIn(0, 4)
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
        if (ammoMenuOpen) {
            if (holds != null) {
                val x = left + OPS_AMMO_X
                val menuY = top + OPS_ROW_SHOT + OPS_CTRL_H
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

        for (stop in 0 until OPS_STOP_COUNT) {
            val rect = opsStopRect(stop) ?: continue
            if (mx >= rect[0] && mx < rect[0] + rect[2] && my >= rect[1] && my < rect[1] + rect[3]) {
                opsActivate(stop, mx)
                return true
            }
        }
        return false
    }

    private fun drawAmmoMenu(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val holds = stores ?: return
        val x = left + OPS_AMMO_X
        val y = top + OPS_ROW_SHOT + OPS_CTRL_H
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
        panel(guiGraphics, x, y, FUEL_POPUP_W, FUEL_POPUP_H)
        guiGraphics.drawString(font, OPS_FUEL_TITLE_TEXT, x + 6, y + 5, TEXT, false)
        guiGraphics.fill(x + 4, y + 16, x + FUEL_POPUP_W - 4, y + 17, SEPARATOR)

        if (holds.fuels.isEmpty()) {
            small(guiGraphics, OPS_FUEL_NONE_TEXT, x + 6, y + 22, DIM)
            return
        }

        for (row in 0 until FUEL_ROWS) {
            val index = fuelPopupScroll + row
            val fuel = holds.fuels.getOrNull(index) ?: break
            val rowY = y + 20 + row * AMMO_MENU_ROW_H
            small(
                guiGraphics,
                Component.literal("${fuelName(fuel.itemId)} x ${fuel.count} -- ${fuel.burnTicks / 20}s"),
                x + 6, rowY, TEXT
            )
        }
        if (fuelPopupScroll > 0) small(guiGraphics, MORE_ABOVE, x + FUEL_POPUP_W - 10, y + 20, DIM)
        if (fuelPopupScroll + FUEL_ROWS < holds.fuels.size) {
            small(guiGraphics, MORE_BELOW, x + FUEL_POPUP_W - 10, y + FUEL_POPUP_H - 10, DIM)
        }
    }

    private fun fuelName(itemId: String): String =
        BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId)).map { ItemStack(it).hoverName.string }
            .orElse(itemId)

    /** A fresh count of the holds. Also picks a default round -- the most plentiful -- if none is chosen. */
    private fun acceptStoresNow(next: ShipStores.Stores) {
        stores = next
        val chosen = selectedAmmo
        val stillThere = chosen != null && next.ammo.any { it.ball == chosen.first && it.charge == chosen.second }
        if (!stillThere) {
            selectedAmmo = next.ammo.maxByOrNull { it.count }?.let { it.ball to it.charge }
        }
        val maxFuel = (next.fuels.size - FUEL_ROWS).coerceAtLeast(0)
        fuelPopupScroll = fuelPopupScroll.coerceIn(0, maxFuel)
        val maxAmmo = (next.ammo.size - AMMO_MENU_ROWS).coerceAtLeast(0)
        ammoMenuScroll = ammoMenuScroll.coerceIn(0, maxAmmo)
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

        drawDuties(guiGraphics, card, mouseX, mouseY)
        drawLockButton(guiGraphics, card, mouseX, mouseY)
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
        val degrees = (index.coerceIn(0, 4) - 2) * 22.5
        return if (degrees > 0) "+%.1f°".format(degrees) else "%.1f°".format(degrees)
    }

    /** Lock or Unlock, centred between Dismiss and Back -- the one control a locked card still answers. */
    private fun drawLockButton(guiGraphics: GuiGraphics, card: CrewManifest.Detail, mouseX: Int, mouseY: Int) {
        // No duty means nothing to freeze -- unless a lock is already on, in which case the way OUT must
        // exist whatever the paperwork says.
        if (card.duty == CrewDuty.NONE && !card.locked) return
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
        if (card.locked) return listOf(CARD_STOP_LOCK)
        val stops = mutableListOf(CARD_STOP_DUTY)
        if (card.duty == CrewDuty.GUNNER && card.gunOptions.isNotEmpty()) stops.add(CARD_STOP_STATION)
        if (gunPanelShown(card)) {
            stops.add(CARD_STOP_CHARGE)
            stops.add(CARD_STOP_ELEVATION)
            stops.add(CARD_STOP_AMMO)
        }
        if (card.duty != CrewDuty.NONE) stops.add(CARD_STOP_LOCK)
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
        val next = (card.elevationIndex.coerceIn(0, 4) + 1) % 5
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

        /** A stores tally arriving. Ignored unless the manifest on screen is about that same wheel. */
        fun acceptStores(helm: Long, stores: ShipStores.Stores) {
            val screen = Minecraft.getInstance().screen as? CrewManifestScreen ?: return
            if (screen.snapshot.helm != helm) return
            screen.acceptStoresNow(stores)
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
        private val OPS_CREW_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_crew")
        private val OPS_STORES_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_stores")
        private val OPS_GUNNERS_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_gunners")
        private val OPS_FIRE_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_fire_watch")
        private val OPS_ASSIGN_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_assign")
        private val OPS_SIDE_TEXT: Component = Component.translatable("gui.vs_eureka.crew_ops_side")
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

        /** The five elevation steps' labels, index-aligned with the ELEVATION property. */
        private val ANGLE_LABELS = listOf(
            Component.literal("-45"), Component.literal("-22"), Component.literal("0"),
            Component.literal("+22"), Component.literal("+45")
        )

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
        private const val TAB_W = (PANEL_W - 2 * TAB_MARGIN - TAB_GAP) / 2

        // region Operations geometry (panel-relative rows; every control 14px tall)

        private const val OPS_CTRL_H = 14
        private const val OPS_CREW_LABEL_Y = 42
        private const val OPS_ROW_G = 52
        private const val OPS_ROW_F = 72
        private const val OPS_SEP_Y = 94
        private const val OPS_STORES_LABEL_Y = 100
        private const val OPS_ROW_SIDE = 110
        private const val OPS_ROW_POWDER = 128
        private const val OPS_ROW_SHOT = 146
        private const val OPS_ROW_ELEV = 164
        private const val OPS_ROW_PWR = 182
        private const val OPS_ROW_REFUEL = 200
        private const val OPS_HOLDS_Y = 219

        private const val OPS_MINUS_X = 64
        private const val OPS_BOX_X = 80
        private const val OPS_BOX_W = 30
        private const val OPS_PLUS_X = 114
        private const val OPS_STEP_W = 12
        private const val OPS_SIDES_X = 136
        private const val OPS_S_SIDES_X = 64
        private const val OPS_ASSIGN_X = 244
        private const val OPS_ASSIGN_W = 48
        private const val OPS_WIDE_W = 100
        private const val OPS_AMMO_X = 116
        private const val OPS_AMMO_W = 176
        private const val OPS_FUEL_BTN_W = 50

        private const val SEG_W = 32
        private const val SEG_GAP = 2

        /** The five elevation steps' segments: narrower, since "-45" is as wide as a label gets. */
        private const val ELEV_SEG_W = 22
        private const val OPS_ELEV_SIDES_X = 64
        private const val OPS_ELEV_ANGLES_X = 172

        /** The Elevation label-button: the row's trigger, sized to its own word. */
        private const val OPS_ELEV_BTN_W = 52

        private const val AMMO_MENU_ROWS = 6
        private const val AMMO_MENU_ROW_H = 12

        private const val FUEL_POPUP_X = 40
        private const val FUEL_POPUP_Y = 56
        private const val FUEL_POPUP_W = 220
        private const val FUEL_POPUP_H = 110
        private const val FUEL_ROWS = 7

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

        /** The pad's walk order over the Operations rows; indexes into [opsStopRect]. */
        private const val STOP_G_MINUS = 0
        private const val STOP_G_BOX = 1
        private const val STOP_G_PLUS = 2
        private const val STOP_G_SIDE = 3
        private const val STOP_G_ASSIGN = 4
        private const val STOP_F_MINUS = 5
        private const val STOP_F_BOX = 6
        private const val STOP_F_PLUS = 7
        private const val STOP_F_ASSIGN = 8
        private const val STOP_S_SIDE = 9
        private const val STOP_POWDER = 10
        private const val STOP_SHOT = 11
        private const val STOP_AMMO_MENU = 12
        private const val STOP_ELEV_SIDE = 13
        private const val STOP_ELEV_ANGLE = 14
        private const val STOP_LAY = 15
        private const val STOP_PWR = 16
        private const val STOP_PWR_SIDE = 17
        private const val STOP_PWR_LEVEL = 18
        private const val STOP_REFUEL = 19
        private const val STOP_FUEL_LIST = 20
        private const val OPS_STOP_COUNT = 21

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
