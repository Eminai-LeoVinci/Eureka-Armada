package org.valkyrienskies.eureka.gui.shiphelm

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.phys.BlockHitResult
import org.lwjgl.glfw.GLFW
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaConfigLoader
import org.valkyrienskies.eureka.blockentity.FIT_PERCENT_NONE
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.crew.HelmNames
import org.valkyrienskies.eureka.ship.ControlProfile
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.roundToInt

/**
 * Overhauled ship-helm menu. Fully code-drawn (no baked texture): a flat grey panel with dark borders, matching
 * the fill-drawn checkboxes/buttons. Layout, top-down / left-to-right:
 *  - title strip: "Name: <ship>" + a Rename control.
 *  - the SHIP-CATEGORY tab strip: Boats & Ships / Airships / Submarine.
 *  - three columns: Cruise Control (master + three arm checkboxes, each with a manual value box, labelled for
 *    the category on show), Display HUD (master + Speed/Altitude/Heading), Eureka Auto-Shipwright (master +
 *    Fit Floaters/Balloons with their +N% boxes, Replace All, and the ship's weight).
 *  - a divider, then miscellaneous toggles: Keep Active / Water Lock left, Armada Parent / Armada Child right.
 *  - bottom row: three info boxes (Top Speed / Blocks / Dimensions) bottom-left, the Assemble/Align/Disassemble
 *    buttons centre, and three boxes bottom-right (Engine Power %, then two readouts chosen by category).
 *
 * The tab strip replaced the old Advanced / Vanilla radio, and it is a fundamentally different kind of control.
 * The radio was a SETTING: it wrote a flag on the ship. A tab is not -- a ship's category is derived from what
 * it is built out of (floaters, balloons, or both plus where the waterline is), so the strip FOLLOWS the ship
 * and snaps to whatever it currently is. Clicking another tab only changes what this screen draws; it never
 * reaches the server and never changes how the ship handles. The Submarine tab is dim and unselectable until
 * the submarine control law exists.
 *
 * Each category carries its own accent colour, which runs through the tab, the dividers and the info-box
 * borders, so a glance at the panel says what kind of ship you are standing on.
 */
class ShipHelmScreen(handler: ShipHelmScreenMenu, playerInventory: Inventory, text: Component) :
    AbstractContainerScreen<ShipHelmScreenMenu>(handler, playerInventory, text) {

    private lateinit var assembleButton: ShipHelmButton
    private lateinit var alignButton: ShipHelmButton
    private lateinit var disassembleButton: ShipHelmButton

    private lateinit var boatTab: ShipHelmTab
    private lateinit var airshipTab: ShipHelmTab
    private lateinit var submarineTab: ShipHelmTab

    // Which category's panel is on show. Follows the ship (see updateButtons); clicking a tab peeks at
    // another one until the ship's own category changes, which snaps the view back.
    private var viewedTab = ControlProfile.BOAT
    private var lastActiveTab: ControlProfile? = null

    private lateinit var cruiseMasterCheckbox: ShipHelmCheckbox
    private lateinit var cruiseSpeedCheckbox: ShipHelmCheckbox
    private lateinit var cruiseTurnCheckbox: ShipHelmCheckbox
    private lateinit var cruiseVerticalCheckbox: ShipHelmCheckbox
    private lateinit var cruiseSpeedBox: EditBox
    private lateinit var cruiseTurnBox: EditBox
    private lateinit var cruiseVerticalBox: EditBox

    private lateinit var displayHudCheckbox: ShipHelmCheckbox
    private lateinit var displaySpeedCheckbox: ShipHelmCheckbox
    private lateinit var displayAltitudeCheckbox: ShipHelmCheckbox
    private lateinit var displayHeadingCheckbox: ShipHelmCheckbox

    private lateinit var assemblerMasterCheckbox: ShipHelmCheckbox
    private lateinit var assemblerFloaterCheckbox: ShipHelmCheckbox
    private lateinit var assemblerBalloonCheckbox: ShipHelmCheckbox
    private lateinit var assemblerReplaceAllCheckbox: ShipHelmCheckbox
    private lateinit var assemblerFloaterBox: EditBox   // "Fit Floaters + N%" manual bonus
    private lateinit var assemblerBalloonBox: EditBox   // "Fit Balloons + N%" manual bonus
    private lateinit var shipWeightBox: EditBox         // read-only ship mass readout (not editable)
    private var weightBoxX = 0                          // absolute X, computed in init from the label width
    private var weightBoxW = 0

    private lateinit var keepActiveCheckbox: ShipHelmCheckbox
    private lateinit var waterLockCheckbox: ShipHelmCheckbox
    private lateinit var keepNameCheckbox: ShipHelmCheckbox
    private lateinit var armadaParentCheckbox: ShipHelmCheckbox
    private lateinit var armadaChildCheckbox: ShipHelmCheckbox

    private lateinit var renameButton: ShipHelmIconButton
    private lateinit var renameBox: EditBox
    private var renaming = false

    private var pos = (Minecraft.getInstance().hitResult as? BlockHitResult)?.blockPos
    private var ship: Ship? = pos?.let { Minecraft.getInstance().level?.getShipManagingPos(it) }

    init {
        titleLabelX = 8
        titleLabelY = 7
        imageWidth = PANEL_WIDTH
        imageHeight = PANEL_HEIGHT
    }

    override fun init() {
        super.init()
        val x = (width - imageWidth) / 2
        val y = (height - imageHeight) / 2

        // region Ship-category tab strip
        // No button ids: selecting a tab is a view change on this client and nothing else.
        boatTab = addRenderableWidget(
            ShipHelmTab(
                x + tabX(0), y + TAB_Y, TAB_W, TAB_H, TAB_BOAT_TEXT, font,
                ShipHelmTab.ACCENT_BOAT, { tabState(ControlProfile.BOAT) }
            ) { viewedTab = ControlProfile.BOAT }
        )
        airshipTab = addRenderableWidget(
            ShipHelmTab(
                x + tabX(1), y + TAB_Y, TAB_W, TAB_H, TAB_AIRSHIP_TEXT, font,
                ShipHelmTab.ACCENT_AIRSHIP, { tabState(ControlProfile.AIRSHIP) }
            ) { viewedTab = ControlProfile.AIRSHIP }
        )
        submarineTab = addRenderableWidget(
            ShipHelmTab(
                x + tabX(2), y + TAB_Y, TAB_W, TAB_H, TAB_SUBMARINE_TEXT, font,
                ShipHelmTab.ACCENT_SUBMARINE, { tabState(ControlProfile.SUBMARINE) }
            ) { /* not selectable yet -- see updateButtons */ }
        )
        submarineTab.active = false
        // endregion

        // region Column 1: Cruise Control
        cruiseMasterCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL1_X, y + HEADER_Y, checkboxWidth(CRUISE_TEXT),
                CRUISE_TEXT, font, { menu.cruising }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 8) }
        )
        // The three arm checkboxes are RE-LABELLED per category (Speed/Rudder/Trim on a boat,
        // Airspeed/Yaw/Climb on an airship -- see applyAxisLabels). The axes underneath are the same three;
        // only the words change, so the panel talks about the ship you are actually on. Their hitboxes are
        // sized from the widest label any category uses, so a re-label never clips or leaves dead pixels.
        cruiseSpeedCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL1_SUB_X, y + cruiseRowY(0), widestAxisWidth(0),
                CRUISE_SPEED_TEXT, font, { menu.cruiseSpeedArmed }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 9) }
        )
        cruiseTurnCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL1_SUB_X, y + cruiseRowY(1), widestAxisWidth(1),
                CRUISE_TURN_TEXT, font, { menu.cruiseTurnArmed }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 10) }
        )
        cruiseVerticalCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL1_SUB_X, y + cruiseRowY(2), widestAxisWidth(2),
                CRUISE_VERTICAL_TEXT, font, { menu.cruiseVerticalArmed }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 11) }
        )
        cruiseSpeedBox = addCruiseBox(x + COL1_BOX_X, y + cruiseRowY(0))
        cruiseTurnBox = addCruiseBox(x + COL1_BOX_X, y + cruiseRowY(1))
        cruiseVerticalBox = addCruiseBox(x + COL1_BOX_X, y + cruiseRowY(2))
        // endregion

        // region Column 2: Display HUD (client config, persisted immediately)
        displayHudCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL2_X, y + HEADER_Y, checkboxWidth(DISPLAY_HUD_TEXT),
                DISPLAY_HUD_TEXT, font, { EurekaConfig.CLIENT.displayHud }
            ) { EurekaConfig.CLIENT.displayHud = !EurekaConfig.CLIENT.displayHud; EurekaConfigLoader.save() }
        )
        displaySpeedCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL2_SUB_X, y + cruiseRowY(0), checkboxWidth(SPEED_TEXT),
                SPEED_TEXT, font, { EurekaConfig.CLIENT.displaySpeed }
            ) { EurekaConfig.CLIENT.displaySpeed = !EurekaConfig.CLIENT.displaySpeed; EurekaConfigLoader.save() }
        )
        displayAltitudeCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL2_SUB_X, y + cruiseRowY(1), checkboxWidth(ALTITUDE_TEXT),
                ALTITUDE_TEXT, font, { EurekaConfig.CLIENT.displayAltitude }
            ) { EurekaConfig.CLIENT.displayAltitude = !EurekaConfig.CLIENT.displayAltitude; EurekaConfigLoader.save() }
        )
        displayHeadingCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL2_SUB_X, y + cruiseRowY(2), checkboxWidth(HEADING_TEXT),
                HEADING_TEXT, font, { EurekaConfig.CLIENT.displayHeading }
            ) { EurekaConfig.CLIENT.displayHeading = !EurekaConfig.CLIENT.displayHeading; EurekaConfigLoader.save() }
        )
        // endregion

        // region Column 3: Eureka Auto-Shipwright (per-player, server-friendly)
        assemblerMasterCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL3_X, y + HEADER_Y, checkboxWidth(ASSEMBLER_TEXT),
                ASSEMBLER_TEXT, font, { menu.assemblerEnabled }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 12) }
        )
        assemblerFloaterCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL3_SUB_X, y + cruiseRowY(0), checkboxWidth(ASSEMBLER_FLOATER_TEXT),
                ASSEMBLER_FLOATER_TEXT, font, { menu.assemblerFloater }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 13) }
        )
        assemblerBalloonCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL3_SUB_X, y + cruiseRowY(1), checkboxWidth(ASSEMBLER_BALLOON_TEXT),
                ASSEMBLER_BALLOON_TEXT, font, { menu.assemblerBalloon }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 14) }
        )
        // Balloons only. There is no floater equivalent on purpose: past the waterline, more floaters just make
        // a boat ride high and roll, whereas more balloons straightforwardly climb better -- so "all of them"
        // is a sensible thing to ask for on one and not the other.
        assemblerReplaceAllCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL3_SUB2_X, y + cruiseRowY(2), checkboxWidth(ASSEMBLER_REPLACE_ALL_TEXT),
                ASSEMBLER_REPLACE_ALL_TEXT, font, { menu.assemblerReplaceAll }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 17) }
        )
        // Per-assembly "+ N%" bonus boxes, right of each sub-toggle (committed on Enter, reset to 0% after assemble).
        assemblerFloaterBox = addPctBox(x + PCT_BOX_X, y + cruiseRowY(0))
        assemblerBalloonBox = addPctBox(x + PCT_BOX_X, y + cruiseRowY(1))
        // Read-only "Ship's Weight" readout, styled like the % boxes (black bg, grey border, white text) but wider
        // for heavy ships. Placed after its label, spanning to the panel's right edge (>= 30-50% longer than a % box).
        weightBoxX = x + COL3_SUB_X + Math.round(font.width(SHIP_WEIGHT_TEXT) * ShipHelmCheckbox.LABEL_SCALE) + 5
        weightBoxW = (x + PANEL_WIDTH - PANEL_RIGHT_MARGIN) - weightBoxX
        shipWeightBox = addRenderableWidget(
            EditBox(font, weightBoxX, y + cruiseRowY(3), weightBoxW, PCT_BOX_H, Component.empty())
        ).also {
            it.setBordered(true)
            it.setEditable(false)
            it.setTextColorUneditable(WEIGHT_TEXT_COLOR)
            it.active = false // purely a display -- never focus/accept clicks
            it.value = "0"
        }
        // endregion

        // region Miscellaneous stack (below the divider)
        keepActiveCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + MISC_X, y + MISC_Y, checkboxWidth(KEEP_ACTIVE_TEXT),
                KEEP_ACTIVE_TEXT, font, { menu.keepActive }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 4) }
        )
        waterLockCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + MISC_X, y + MISC_Y + MISC_DY, checkboxWidth(WATER_LOCK_TEXT),
                WATER_LOCK_TEXT, font, { menu.waterAltitudeHold }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 5) }
        )
        // "Keep Name": the wheel re-applies the last ship name it saw to whatever it assembles next. Sits with
        // Water Lock rather than in the armada column because both are properties of this helm's own
        // behaviour, and unlike everything else here it stays live on an UNASSEMBLED wheel -- which is exactly
        // when a captain carrying one to a new hull wants to check it.
        keepNameCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + MISC_X, y + MISC_Y + 2 * MISC_DY, checkboxWidth(KEEP_NAME_TEXT),
                KEEP_NAME_TEXT, font, { menu.keepName }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 2) }
        )
        // Armada role markers, second misc column: tick Parent at the lead ship, then Child at each ship that
        // should fly in its formation. Mutually exclusive (see updateButtons).
        armadaParentCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + MISC2_X, y + MISC_Y, checkboxWidth(ARMADA_PARENT_TEXT),
                ARMADA_PARENT_TEXT, font, { menu.isArmadaParent }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 15) }
        )
        armadaChildCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + MISC2_X, y + MISC_Y + MISC_DY, checkboxWidth(ARMADA_CHILD_TEXT),
                ARMADA_CHILD_TEXT, font, { menu.isArmadaChild }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 16) }
        )
        // endregion

        // region Action buttons (centre-bottom, resized smaller)
        assembleButton = addRenderableWidget(
            ShipHelmButton(x + BTN_X, y + btnRowY(0), BTN_W, BTN_H, ASSEMBLE_TEXT, font) {
                minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 0)
            }
        )
        alignButton = addRenderableWidget(
            ShipHelmButton(x + BTN_X, y + btnRowY(1), BTN_W, BTN_H, ALIGN_TEXT, font) {
                minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 1)
            }
        )
        disassembleButton = addRenderableWidget(
            ShipHelmButton(x + BTN_X, y + btnRowY(2), BTN_W, BTN_H, DISSEMBLE_TEXT, font) {
                minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 3)
            }
        )
        // endregion

        // region Rename control
        renameBox = addRenderableWidget(
            EditBox(font, x + RENAME_BOX_X, y + RENAME_BOX_Y, RENAME_BOX_W, RENAME_BOX_H, RENAME_TEXT)
        ).also {
            it.setMaxLength(48)
            it.visible = false
        }
        renameButton = addRenderableWidget(
            ShipHelmIconButton(x + RENAME_BTN_X, y + RENAME_BTN_Y, RENAME_BTN_W, RENAME_BTN_H, RENAME_TEXT, font) {
                toggleRename()
            }
        )
        // endregion

        disassembleButton.active = EurekaConfig.SERVER.allowDisassembly
        updateButtons()
    }

    // A compact numeric edit box for a manual cruise value. Filtered to a signed decimal; committed on Enter
    // (encoded into a container button id -- the server clamps to what the ship can physically do and syncs the
    // result back). Hidden until wired visible by updateButtons.
    private fun addCruiseBox(bx: Int, by: Int): EditBox =
        addRenderableWidget(EditBox(font, bx, by, CRUISE_BOX_W, CRUISE_BOX_H, Component.empty())).also {
            it.setMaxLength(8) // "-28.000" plus headroom, now that values carry three decimals
            it.setBordered(true)
            it.setFilter { s -> s.matches(NUMERIC) }
        }

    // A compact "N%" bonus box for the assembler (whole non-negative percent, up to 3 digits + the % sign).
    // Committed on Enter into the assembler-bonus button channel; the server stores it on the player's prefs and
    // resets it to 0 once a ship is assembled. The filter tolerates the trailing % so the displayed value re-parses.
    private fun addPctBox(bx: Int, by: Int): EditBox =
        addRenderableWidget(EditBox(font, bx, by, PCT_BOX_W, PCT_BOX_H, Component.empty())).also {
            it.setMaxLength(4) // "999%"
            it.setBordered(true)
            it.setFilter { s -> s.matches(PERCENT) }
        }

    /**
     * Open the name box, or commit what is in it.
     *
     * This names the SHIP. The wheel's own name is a different thing entirely -- it names the CREW, and is
     * typed in the crew menu or on an anvil, not here.
     *
     * The typed value goes over a payload rather than `sendCommand("vs rename ...")`. That command is gated
     * behind an op permission in VS2, so the old button silently did nothing for an ordinary player on a
     * server; it only ever appeared to work because a single-player host is op. Slugging is left to the
     * server, which uses the same `HelmNames.slugOf` this screen compares against below.
     */
    private fun toggleRename() {
        val s = ship ?: return
        if (!renaming) {
            renameBox.value = shipName() ?: ""
            renameBox.visible = true
            this.focused = renameBox
            renameBox.isFocused = true
            renaming = true
            renameButton.message = SAVE_TEXT
        } else {
            val typed = renameBox.value.trim()
            val helmPos = pos
            if (helmPos != null && typed.isNotEmpty() && typed != (shipName() ?: "")) {
                HelmNames.clientShipSender(helmPos, typed)
                // Shown until vs-core's own value comes back, so the box does not flicker to the old name for
                // the round trip.
                pendingNames[s.id] = typed
            }
            cancelRename()
        }
    }

    /**
     * This ship's name as a player reads it, or null if it has none.
     *
     * `slug` is the stored form and it cannot hold a space, so the dashes are shown back as the spaces they
     * stand in for -- what the player typed is what the player sees. A pending local edit wins until vs-core's
     * version of it arrives.
     */
    private fun shipName(): String? {
        val s = ship ?: return null
        pendingNames[s.id]?.let { return it.ifEmpty { null } }
        // The WHEEL's copy first. `Ship.slug` on the client is only ever what the ship was called when it
        // loaded -- VS2 does not push later renames -- so a ship named by anything the player did not just
        // type here (Keep Name, another captain's rename) reads stale from it forever. The helm block entity
        // syncs, so its copy is the current one. Falls back to the ship for a wheel that has not sampled yet.
        val helm = pos?.let { Minecraft.getInstance().level?.getBlockEntity(it) } as? ShipHelmBlockEntity
        return (helm?.shipSlug ?: s.slug)?.replace('-', ' ')
    }

    private fun cancelRename() {
        renaming = false
        renameBox.visible = false
        renameBox.isFocused = false
        if (this.focused === renameBox) this.focused = null
        renameButton.message = RENAME_TEXT
    }

    // Parse + send one cruise value box (axis 0 = speed, 1 = turn, 2 = vertical). Blank / unparseable is ignored
    // (the box refreshes back to the live synced value). Then unfocus so updateButtons re-syncs the clamped value.
    private fun commitCruiseBox(axis: Int, box: EditBox) {
        box.value.trim().toDoubleOrNull()?.let { v ->
            minecraft?.gameMode?.handleInventoryButtonClick(
                menu.containerId, ShipHelmScreenMenu.encodeCruiseValue(axis, v)
            )
        }
        box.isFocused = false
        if (this.focused === box) this.focused = null
    }

    private fun cruiseBoxAxis(box: EditBox): Int = when (box) {
        cruiseSpeedBox -> 0
        cruiseTurnBox -> 1
        else -> 2
    }

    // Parse + send one assembler bonus box (which 0 = floater, 1 = balloon). Strips the trailing '%', ignores a
    // blank/unparseable entry (the box refreshes back to the synced value), then unfocuses so it re-syncs.
    private fun commitPctBox(which: Int, box: EditBox) {
        box.value.trim().removeSuffix("%").toIntOrNull()?.let { pct ->
            minecraft?.gameMode?.handleInventoryButtonClick(
                menu.containerId, ShipHelmScreenMenu.encodeAssemblerBonus(which, pct)
            )
        }
        box.isFocused = false
        if (this.focused === box) this.focused = null
    }

    private fun pctBoxWhich(box: EditBox): Int = if (box === assemblerFloaterBox) 0 else 1

    /**
     * The bumpers walk the category tab strip, exactly as clicking the tabs does -- a view change on this
     * client and nothing else. Read straight off the hardware (VS2's ShipGamepad), because a controller
     * mod's screen handling never reaches a custom screen. Submarines join the cycle when their tab is
     * live.
     */
    override fun containerTick() {
        super.containerTick()
        val step = when {
            org.valkyrienskies.mod.client.ShipGamepad.bumperRightPressed() -> 1
            org.valkyrienskies.mod.client.ShipGamepad.bumperLeftPressed() -> -1
            else -> return
        }
        val tabs = mutableListOf(ControlProfile.BOAT, ControlProfile.AIRSHIP)
        if (submarineTab.active) tabs.add(ControlProfile.SUBMARINE)
        val index = tabs.indexOf(viewedTab).coerceAtLeast(0)
        viewedTab = tabs[(index + step + tabs.size) % tabs.size]
    }

    private fun updateButtons() {
        val newPos = (Minecraft.getInstance().hitResult as? BlockHitResult)?.blockPos
        if (newPos != null) pos = newPos
        val newShip = pos?.let { Minecraft.getInstance().level?.getShipManagingPos(it) }
        if (newShip != null) ship = newShip

        // Drop the local echo once vs-core's own value agrees with it -- from then on the ship is the single
        // source, and a stale echo would survive a rename made from anywhere else. Compared through the same
        // slugging the server applies, so "Going Merry" and `Going-Merry` are recognised as the same answer.
        ship?.let { sh ->
            val pending = pendingNames[sh.id]
            if (pending != null && HelmNames.slugOf(Component.literal(pending)) == sh.slug) {
                pendingNames.remove(sh.id)
            }
        }

        val isLookingAtShip = ship != null
        // Whether THIS helm's ship is assembled, from the server-synced DataSlot -- NOT the crosshair raycast.
        // The raycast reads null while the menu is open at close range (the frozen camera points off-ship),
        // which used to grey out cruise / keep-active / the mode radio even on a fully assembled ship.
        val assembled = menu.assembled
        // A ship bound as an armada child is read-only from its own helm -- it's steered only by its parent, so
        // its controls grey out. The readouts (Top Speed / Blocks / Dimensions / Engine Power, and the cruise
        // value boxes) stay visible so the child can still be inspected. Only "/armada unbind" releases it.
        val childLocked = menu.isArmadaChild

        // The view follows the ship. Snapping only when the ship's OWN category changes is what makes peeking
        // work: click Airships on a boat and it stays there, but the moment the hull actually becomes an
        // airship (or you open the menu on a different ship) the panel goes back to telling the truth.
        val activeTab = menu.controlProfile
        if (lastActiveTab != activeTab) {
            lastActiveTab = activeTab
            viewedTab = activeTab
        }

        assembleButton.active = !assembled && !childLocked
        disassembleButton.active = EurekaConfig.SERVER.allowDisassembly && assembled && !childLocked
        alignButton.active = disassembleButton.active

        applyAxisLabels(viewedTab)

        keepActiveCheckbox.active = assembled && !childLocked
        // Water Lock is a boat's control: it pins the hull at the waterline, which means nothing in the air.
        // It reads and writes the Boats & Ships settings block, so a hybrid gets the hold exactly while it is
        // being a boat. Hidden rather than greyed on the other tabs -- a permanently dead checkbox invites the
        // question of what would enable it, and the answer is "nothing".
        waterLockCheckbox.visible = viewedTab == ControlProfile.BOAT
        waterLockCheckbox.active = !childLocked

        // Always live, on every tab, assembled or not: it describes what this WHEEL does next time, and the
        // moment it most needs setting is while the wheel is sitting on a hull that has not been built yet.
        keepNameCheckbox.visible = true
        keepNameCheckbox.active = true

        // Armada role markers are mutually exclusive: a child can't be marked parent, and a parent (real or
        // just marked) can't be ticked child. Both need an assembled ship, since a bond is between two ships.
        // Child is the ONE control an armada child keeps -- unticking it is how the ship gets released.
        armadaParentCheckbox.active = assembled && !childLocked
        armadaChildCheckbox.active = assembled && (childLocked || !menu.isArmadaParent)

        // Cruise needs a ship, and is locked on a child. The value boxes stay VISIBLE (read-only) for a child
        // so its speed/turn/vertical setpoints remain legible; they're editable only when the section is
        // actually usable.
        val cruiseUsable = assembled && !childLocked
        val cruiseVisible = assembled
        cruiseMasterCheckbox.active = cruiseUsable
        cruiseSpeedCheckbox.active = cruiseUsable
        cruiseTurnCheckbox.active = cruiseUsable
        cruiseVerticalCheckbox.active = cruiseUsable
        updateCruiseBox(cruiseSpeedBox, cruiseVisible, cruiseUsable, menu.cruiseSpeed)
        updateCruiseBox(cruiseTurnBox, cruiseVisible, cruiseUsable, menu.cruiseTurn)
        updateCruiseBox(cruiseVerticalBox, cruiseVisible, cruiseUsable, menu.cruiseVertical)

        // HUD sub-toggles grey out while the master is off.
        val hudOn = EurekaConfig.CLIENT.displayHud
        displaySpeedCheckbox.active = hudOn
        displayAltitudeCheckbox.active = hudOn
        displayHeadingCheckbox.active = hudOn

        // Auto-Shipwright subs grey out while the section master is off; the section is per-player (no ship
        // needed) and stays fully usable on every tab, because it is set BEFORE a ship exists -- at which
        // point the ship has no category to gate it by, and you may well be building a hybrid.
        val asmOn = menu.assemblerEnabled
        assemblerFloaterCheckbox.active = asmOn
        assemblerBalloonCheckbox.active = asmOn
        assemblerReplaceAllCheckbox.active = asmOn && menu.assemblerBalloon
        // The "+ N%" boxes are editable only when their sub is on; otherwise greyed (still show the synced
        // value). Replace All takes every convertible block there is, so a percentage on top of it means
        // nothing -- the balloon box greys out while it is ticked.
        updatePctBox(assemblerFloaterBox, asmOn && menu.assemblerFloater, menu.assemblerFloaterBonus)
        updatePctBox(
            assemblerBalloonBox,
            asmOn && menu.assemblerBalloon && !menu.assemblerReplaceAll,
            menu.assemblerBalloonBonus
        )
        // Read-only weight readout (0 when this helm has no ship yet); thousands-separated like the Blocks box.
        val weightStr = String.format("%,d", menu.shipMass)
        if (shipWeightBox.value != weightStr) shipWeightBox.value = weightStr

        // Naming needs a WHEEL, not a ship. A helm sitting on the dock can be named before it has ever built
        // anything -- which is the whole point of a name that travels with the block -- so this is gated on
        // having a block position rather than on `isLookingAtShip` the way it was when it renamed the ship.
        // Only a ship can be renamed, so an unassembled wheel has nothing to offer here. Naming the CREW is
        // always available, but it lives in the crew menu.
        renameButton.visible = isLookingAtShip
        renameButton.active = isLookingAtShip
        if (!isLookingAtShip && renaming) cancelRename()
    }

    // Show a cruise box and, while it isn't being edited, keep it displaying the live synced value. [visible]
    // and [editable] are separate so an armada child can keep the box readable (visible) while locking edits
    // (not editable). The synced value is in thousandths (see ShipHelmScreenMenu) so it renders with three
    // decimals for fine tuning; speed tracks the ship's live velocity (HUD-synced), turn/vertical show setpoints.
    private fun updateCruiseBox(box: EditBox, visible: Boolean, editable: Boolean, syncedThousandths: Int) {
        box.visible = visible
        box.setEditable(editable)
        // Locale.ROOT so the decimal separator is always '.', matching the box's NUMERIC filter (a locale that
        // formats with ',' would be rejected by the filter and truncate the value).
        if (visible && !box.isFocused) box.value = String.format(java.util.Locale.ROOT, "%.3f", syncedThousandths / 1000.0)
    }

    // Assembler "+ N%" box: always visible (matching the checkboxes), editable only when its sub is on. While not
    // being edited it shows the synced percent with a trailing '%' (the filter tolerates it so it re-parses on Enter).
    private fun updatePctBox(box: EditBox, usable: Boolean, syncedPercent: Int) {
        box.setEditable(usable)
        if (!box.isFocused) box.value = "$syncedPercent%"
    }

    override fun renderBg(guiGraphics: GuiGraphics, partialTicks: Float, mouseX: Int, mouseY: Int) {
        updateButtons()

        val x = (width - imageWidth) / 2
        val y = (height - imageHeight) / 2

        // The category on show tints the panel's chrome: the tab baseline, the two dividers and the info-box
        // borders. Enough to change the instrument's character at a glance without repainting the whole thing.
        val accent = accentOf(viewedTab)

        // Panel: border + inner fill.
        guiGraphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER)
        guiGraphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_BG)

        // Title separator stays neutral; the tab strip's baseline and the section divider take the accent.
        hLine(guiGraphics, x, y + DIVIDER1_Y, SEPARATOR)
        hLine(guiGraphics, x, y + TAB_BASELINE_Y, accent)
        hLine(guiGraphics, x, y + DIVIDER2_Y, accent)

        // Bottom info boxes (left) + engine/category boxes (right).
        for (row in 0..2) {
            frame(guiGraphics, x + INFO_X, y + boxRowY(INFO_Y0, row), INFO_W, BOX_H, accent)
            frame(guiGraphics, x + ENG_X, y + boxRowY(INFO_Y0, row), ENG_W, BOX_H, accent)
        }
    }

    private fun hLine(g: GuiGraphics, x: Int, absY: Int, color: Int) =
        g.fill(x + 4, absY, x + imageWidth - 4, absY + 1, color)

    // A framed readout box. The border carries the category accent; the fill stays neutral so the text in it
    // keeps the same contrast on every tab.
    private fun frame(g: GuiGraphics, bx: Int, by: Int, w: Int, h: Int, border: Int) {
        g.fill(bx, by, bx + w, by + h, border)
        g.fill(bx + 1, by + 1, bx + w - 1, by + h - 1, BOX_BG)
    }

    override fun renderLabels(guiGraphics: GuiGraphics, i: Int, j: Int) {
        if (this.menu.aligning) {
            alignButton.message = ALIGNING_TEXT
            alignButton.active = false
        } else {
            alignButton.message = ALIGN_TEXT
        }

        // Auto-Shipwright column extras -- drawn before the ship null-check because the section is per-player
        // and shown with or without a ship: a "+" between each sub-toggle and its % box, and the weight label.
        drawScaledLabel(guiGraphics, PLUS_TEXT, PLUS_X, cruiseRowY(0), PCT_BOX_H, INFO_TEXT)
        drawScaledLabel(guiGraphics, PLUS_TEXT, PLUS_X, cruiseRowY(1), PCT_BOX_H, INFO_TEXT)
        drawScaledLabel(guiGraphics, SHIP_WEIGHT_TEXT, COL3_SUB_X, cruiseRowY(3), PCT_BOX_H, INFO_TEXT)

        // "now +N%": how far the assembled ship's fitted counts already sit over what its mass calls for.
        // Right-aligned into the gap between each row's label and its "+", small and grey so it reads as an
        // annotation rather than another control. This is the number that tells you what to type next time.
        drawFitPercent(guiGraphics, menu.floaterFitPercent, cruiseRowY(0))
        drawFitPercent(guiGraphics, menu.balloonFitPercent, cruiseRowY(1))

        // The SHIP's name. Drawn only while there is a ship to name -- an unassembled wheel steers nothing yet,
        // and the crew's name (the wheel's own) belongs to the crew menu, not here.
        if (!renaming) {
            shipName()?.let { shown ->
                guiGraphics.drawString(font, "Name: $shown", titleLabelX, titleLabelY, INFO_TEXT, false)
            }
        }

        val s = ship ?: return

        // Bottom-left info boxes -- the same three whatever the ship is.
        val topLine = "Top Speed: ${menu.topSpeed}m/s~"
        val blockLine = "Blocks: " + String.format("%,d", menu.blockCount)
        val dimLine = dimensionsText(s)
        drawBoxText(guiGraphics, topLine, INFO_X, boxRelY(INFO_Y0, 0), INFO_W)
        drawBoxText(guiGraphics, blockLine, INFO_X, boxRelY(INFO_Y0, 1), INFO_W)
        drawBoxText(guiGraphics, dimLine, INFO_X, boxRelY(INFO_Y0, 2), INFO_W)

        // Bottom-right: engine power, then the two readouts the category on show actually cares about.
        val powerLine = if (menu.hasEngines) "Engine Power: ${menu.enginePower}%" else "Engine Power: --"
        drawBoxText(guiGraphics, powerLine, ENG_X, boxRelY(INFO_Y0, 0), ENG_W)
        val (line1, line2) = categoryReadouts(s)
        drawBoxText(guiGraphics, line1, ENG_X, boxRelY(INFO_Y0, 1), ENG_W)
        drawBoxText(guiGraphics, line2, ENG_X, boxRelY(INFO_Y0, 2), ENG_W)
    }

    /**
     * The two category-specific readouts for the bottom-right boxes.
     *
     * A boat wants to know what is under it and how far the beach is; an airship wants its height, both
     * absolute and above whatever it is over; a submarine wants the seabed below and the surface above. The
     * distances all come from the server (sampled off real blocks around the hull), except the airship's
     * absolute altitude, which the client already has from the ship's own transform.
     */
    private fun categoryReadouts(s: Ship): Pair<String, String> = when (viewedTab) {
        ControlProfile.BOAT ->
            "Depth: " + blocks(menu.seabedDistance) to
                // Shore means nothing once the hull is under; swap in the climb back to air, which is the
                // reading that matters then -- and it is how the submersion detection can be seen working
                // before there is a submarine control law to see it with.
                if (menu.isSubmerged) "Surface: " + blocks(menu.surfaceDistance)
                else "Shore: " + blocks(menu.shoreDistance)
        ControlProfile.AIRSHIP ->
            "Altitude: " + s.transform.positionInWorld.y().roundToInt() to
                "Ground: " + blocks(menu.groundDistance)
        ControlProfile.SUBMARINE ->
            "Seabed: " + blocks(menu.seabedDistance) to
                "Surface: " + blocks(menu.surfaceDistance)
    }

    /** A block distance, or "--" for the server's "not known" sentinel (out of range / unloaded chunk). */
    private fun blocks(d: Int): String = if (d < 0) "--" else d.toString()

    // The "now +N%" annotation for one Auto-Shipwright row, right-aligned just left of that row's "+".
    private fun drawFitPercent(guiGraphics: GuiGraphics, percent: Int, relY: Int) {
        val text = if (percent == FIT_PERCENT_NONE) "--" else (if (percent >= 0) "+$percent%" else "$percent%")
        val scale = FIT_SCALE
        val w = font.width(text) * scale
        val pose = guiGraphics.pose()
        pose.pushMatrix()
        pose.scale(scale, scale)
        val tx = (PLUS_X - 2 - w) / scale
        val ty = (relY + (PCT_BOX_H - font.lineHeight * scale) / 2f) / scale
        guiGraphics.drawString(font, text, Math.round(tx), Math.round(ty), FIT_TEXT, false)
        pose.popMatrix()
    }

    /**
     * Re-label the three cruise arm checkboxes for the category on show. The axes are unchanged -- forward,
     * yaw, vertical -- so this is purely a matter of the panel using the words that belong to the ship you
     * are on: a boat has a rudder and trims its draught, an airship yaws and climbs.
     */
    private fun applyAxisLabels(tab: ControlProfile) {
        val labels = axisLabels(tab)
        cruiseSpeedCheckbox.message = labels[0]
        cruiseTurnCheckbox.message = labels[1]
        cruiseVerticalCheckbox.message = labels[2]
    }

    /** How this tab reads: ACTIVE = what the ship is, PEEK = being looked at, IDLE, DISABLED. */
    private fun tabState(tab: ControlProfile): ShipHelmTab.State = when {
        tab == ControlProfile.SUBMARINE -> ShipHelmTab.State.DISABLED
        tab == menu.controlProfile && tab == viewedTab -> ShipHelmTab.State.ACTIVE
        tab == viewedTab -> ShipHelmTab.State.PEEK
        else -> ShipHelmTab.State.IDLE
    }

    private fun dimensionsText(s: Ship): String {
        val a = s.shipAABB ?: return "H:- W:- L:-"
        val h = a.maxY() - a.minY() + 1
        val xExt = a.maxX() - a.minX() + 1
        val zExt = a.maxZ() - a.minZ() + 1
        val w = minOf(xExt, zExt)
        val l = maxOf(xExt, zExt)
        return "H:$h W:$w L:$l"
    }

    // Draw a small label (panel-relative), vertically centred against a box of height [boxH], at the same reduced
    // scale the checkbox labels use so the assembler "+" / "Ship's Weight:" text matches the rows around it.
    private fun drawScaledLabel(guiGraphics: GuiGraphics, text: Component, relX: Int, relY: Int, boxH: Int, color: Int) {
        val scale = ShipHelmCheckbox.LABEL_SCALE
        val pose = guiGraphics.pose()
        pose.pushMatrix()
        pose.scale(scale, scale)
        val tx = relX / scale
        val ty = (relY + (boxH - font.lineHeight * scale) / 2f) / scale
        guiGraphics.drawString(font, text, Math.round(tx), Math.round(ty), color, false)
        pose.popMatrix()
    }

    // Draw dark text centred in a bottom box (panel-relative x), auto-scaled so it fits the box width.
    private fun drawBoxText(guiGraphics: GuiGraphics, text: String, boxX: Int, topY: Int, boxW: Int) {
        val widest = font.width(text).toFloat()
        val scale = if (widest <= 0f) INFO_MAX_SCALE else minOf(INFO_MAX_SCALE, (boxW - 3) / widest)
        val pose = guiGraphics.pose()
        pose.pushMatrix()
        pose.scale(scale, scale)
        val tx = (boxX + 2) / scale
        val ty = (topY + (BOX_H - font.lineHeight * scale) / 2f) / scale
        guiGraphics.drawString(font, text, Math.round(tx), Math.round(ty), INFO_TEXT, false)
        pose.popMatrix()
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (renaming) {
            when (keyEvent.key()) {
                GLFW.GLFW_KEY_ESCAPE -> { cancelRename(); return true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { toggleRename(); return true }
            }
            renameBox.keyPressed(keyEvent)
            return true
        }
        // While a cruise value box is focused, keep keystrokes away from the inventory-close key and commit on
        // Enter. Characters still arrive via the base charTyped routing to the focused edit box.
        val f = this.focused
        if (f is EditBox && (f === cruiseSpeedBox || f === cruiseTurnBox || f === cruiseVerticalBox)) {
            when (keyEvent.key()) {
                GLFW.GLFW_KEY_ESCAPE -> { f.isFocused = false; this.focused = null; return true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { commitCruiseBox(cruiseBoxAxis(f), f); return true }
            }
            f.keyPressed(keyEvent)
            return true
        }
        // Same handling for the assembler "+ N%" boxes: Enter commits the percent, Esc abandons the edit.
        if (f is EditBox && (f === assemblerFloaterBox || f === assemblerBalloonBox)) {
            when (keyEvent.key()) {
                GLFW.GLFW_KEY_ESCAPE -> { f.isFocused = false; this.focused = null; return true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { commitPctBox(pctBoxWhich(f), f); return true }
            }
            f.keyPressed(keyEvent)
            return true
        }
        return super.keyPressed(keyEvent)
    }

    // mojank doesn't check mouse release for their widgets for some reason
    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        isDragging = false
        if (getChildAt(mouseButtonEvent.x(), mouseButtonEvent.y())
                .filter { it.mouseReleased(mouseButtonEvent) }.isPresent
        ) {
            return true
        }
        return super.mouseReleased(mouseButtonEvent)
    }

    companion object {
        /**
         * A just-typed name, keyed on the WHEEL's packed block position, held until the server's own copy of
         * the block entity agrees with it.
         *
         * Static because the screen is rebuilt on every resize and would otherwise forget an in-flight rename.
         * Was keyed on ship id when this box renamed ships; the key moved with the name, onto the block that
         * now carries it -- which also means a wheel that has never built a ship can hold a pending name.
         */
        private val pendingNames = HashMap<Long, String>()
        private val NUMERIC = Regex("-?\\d*\\.?\\d*")
        // Whole non-negative percent, up to 3 digits, with an optional trailing '%' (so the displayed "5%" re-parses).
        private val PERCENT = Regex("\\d{0,3}%?")

        // Panel. Wide (320) so the three column headers and the long Auto-Shipwright sub-labels fit
        // three-across without clipping; 222 tall since the Auto-Shipwright column grew a fourth row for
        // Replace All. init() centres it, so it stays on-screen at normal GUI scales.
        private const val PANEL_WIDTH = 320
        private const val PANEL_HEIGHT = 222
        private const val PANEL_BORDER = 0xFF000000.toInt()
        private const val PANEL_BG = 0xFFC6C6C6.toInt()
        private const val SEPARATOR = 0xFF8B8B8B.toInt()

        // Checkbox label helper (full font width for the clickable area).
        private fun checkboxWidth(text: Component) =
            ShipHelmCheckbox.BOX + ShipHelmCheckbox.GAP + Minecraft.getInstance().font.width(text)

        private fun accentOf(tab: ControlProfile): Int = when (tab) {
            ControlProfile.BOAT -> ShipHelmTab.ACCENT_BOAT
            ControlProfile.AIRSHIP -> ShipHelmTab.ACCENT_AIRSHIP
            ControlProfile.SUBMARINE -> ShipHelmTab.ACCENT_SUBMARINE
        }

        // The cruise axes, named for each category. Same three axes throughout -- forward, yaw, vertical.
        private fun axisLabels(tab: ControlProfile): Array<Component> = when (tab) {
            ControlProfile.BOAT -> arrayOf(CRUISE_SPEED_TEXT, CRUISE_RUDDER_TEXT, CRUISE_TRIM_TEXT)
            ControlProfile.AIRSHIP -> arrayOf(CRUISE_AIRSPEED_TEXT, CRUISE_YAW_TEXT, CRUISE_CLIMB_TEXT)
            ControlProfile.SUBMARINE -> arrayOf(CRUISE_SPEED_TEXT, CRUISE_RUDDER_TEXT, CRUISE_DIVE_TEXT)
        }

        // A cruise checkbox's hitbox is fixed at construction but its label changes with the tab, so size it
        // from the widest word any category puts on that row.
        private fun widestAxisWidth(axis: Int): Int =
            ControlProfile.values().maxOf { checkboxWidth(axisLabels(it)[axis]) }

        // Title / rename.
        private const val RENAME_BTN_W = 38
        private const val RENAME_BTN_H = 10
        private const val RENAME_BTN_X = PANEL_WIDTH - RENAME_BTN_W - 6
        private const val RENAME_BTN_Y = 5
        private const val RENAME_BOX_X = 8
        private const val RENAME_BOX_Y = 3
        private const val RENAME_BOX_W = 150
        private const val RENAME_BOX_H = 12

        private const val DIVIDER1_Y = 18
        private const val DIVIDER2_Y = 110

        // Ship-category tab strip, in exactly the band the Advanced / Vanilla radio used to occupy: below the
        // title separator at 18, above the column headers at 40. TAB_BASELINE_Y is the rule the strip sits
        // on -- a selected tab is drawn with its bottom edge open so it runs through this line into the panel.
        private const val TAB_Y = 20
        private const val TAB_H = 14
        private const val TAB_BASELINE_Y = TAB_Y + TAB_H
        private const val TAB_MARGIN = 8
        private const val TAB_GAP = 4
        private const val TAB_W = (PANEL_WIDTH - 2 * TAB_MARGIN - 2 * TAB_GAP) / 3
        private fun tabX(i: Int) = TAB_MARGIN + (TAB_W + TAB_GAP) * i

        // Three columns.
        private const val HEADER_Y = 40
        private const val ROW0_Y = 54
        private const val ROW_DY = 14
        private fun cruiseRowY(row: Int) = ROW0_Y + ROW_DY * row

        private const val COL1_X = 12            // Cruise Control master
        private const val COL1_SUB_X = 16        // the three cruise axis checkboxes
        private const val COL1_BOX_X = 72        // manual value boxes
        private const val COL2_X = 120           // Display HUD master
        private const val COL2_SUB_X = 124
        private const val COL3_X = 190           // Eureka Auto-Shipwright master
        private const val COL3_SUB_X = 194
        private const val COL3_SUB2_X = 200      // Replace All, indented a step under Fit Balloons

        // Wide enough for a full three-decimal value: "-15.140" is 40px in this font and a bordered EditBox
        // spends 8 of its width on the frame and left padding. The column is boxed in on both sides -- the
        // "Vertical:" label ends at COL1_SUB_X + BOX + GAP + 41 = 70, and the HUD sub-checkboxes start at
        // COL2_SUB_X = 124 -- so 72..120 is very nearly the whole space there is between them.
        private const val CRUISE_BOX_W = 48
        private const val CRUISE_BOX_H = 12

        // Auto-Shipwright "+ N%" bonus boxes (right-aligned to the panel's right edge) and the read-only weight box.
        private const val PANEL_RIGHT_MARGIN = 6
        private const val PCT_BOX_W = 30
        private const val PCT_BOX_H = 12
        private const val PCT_BOX_X = PANEL_WIDTH - PANEL_RIGHT_MARGIN - PCT_BOX_W // 284
        private const val PLUS_X = PCT_BOX_X - 8 // the "+" sits just left of each % box
        private const val WEIGHT_TEXT_COLOR = 0xFFE0E0E0.toInt() // near-white, matching the % EditBoxes' text
        // The "now +N%" annotation: smaller and lighter than the row it belongs to, so it reads as a note
        // about the ship rather than another number to set.
        private const val FIT_SCALE = 0.6f
        private const val FIT_TEXT = 0xFF5A5A5A.toInt()

        // Miscellaneous stack. Two columns: Keep Active / Water Lock on the left, the armada role markers on
        // the right. MISC2_X clears the widest left label ("Keep Active?" is ~78px wide including its box).
        private const val MISC_X = 14
        private const val MISC_Y = 116
        private const val MISC_DY = 13
        private const val MISC2_X = 120

        // Bottom row: info boxes (left), buttons (centre), engine boxes (right).
        private const val BOX_H = 15
        private const val INFO_Y0 = 162
        private const val BOX_ROW_DY = 17
        private fun boxRelY(y0: Int, row: Int) = y0 + BOX_ROW_DY * row
        private fun boxRowY(y0: Int, row: Int) = boxRelY(y0, row) // same offset; separated for readability at call sites

        private const val INFO_X = 10
        private const val INFO_W = 92
        private const val ENG_X = 218
        private const val ENG_W = 92

        private const val BTN_X = 126
        private const val BTN_W = 68
        private const val BTN_H = 16
        private const val BTN_ROW_DY = 18
        private fun btnRowY(row: Int) = INFO_Y0 + BTN_ROW_DY * row

        private const val INFO_MAX_SCALE = 0.8f
        private const val INFO_TEXT = 0xFF383838.toInt()
        private const val BOX_BG = 0xFFB0B0B0.toInt()

        private val KEEP_ACTIVE_TEXT = Component.translatable("gui.vs_eureka.keep_active")
        private val KEEP_NAME_TEXT = Component.translatable("gui.vs_eureka.keep_name")
        private val WATER_LOCK_TEXT = Component.translatable("gui.vs_eureka.water_lock")
        private val ARMADA_PARENT_TEXT = Component.translatable("gui.vs_eureka.armada_parent")
        private val ARMADA_CHILD_TEXT = Component.translatable("gui.vs_eureka.armada_child")

        private val TAB_BOAT_TEXT = Component.translatable("gui.vs_eureka.tab_boat")
        private val TAB_AIRSHIP_TEXT = Component.translatable("gui.vs_eureka.tab_airship")
        private val TAB_SUBMARINE_TEXT = Component.translatable("gui.vs_eureka.tab_submarine")

        private val CRUISE_TEXT = Component.translatable("gui.vs_eureka.cruise_control")
        // The cruise axes wear different names on each tab -- see axisLabels. CRUISE_VERTICAL_TEXT is the
        // neutral fallback the checkboxes are constructed with before the first applyAxisLabels.
        private val CRUISE_SPEED_TEXT = Component.translatable("gui.vs_eureka.cruise_speed")
        private val CRUISE_TURN_TEXT = Component.translatable("gui.vs_eureka.cruise_turn")
        private val CRUISE_VERTICAL_TEXT = Component.translatable("gui.vs_eureka.cruise_vertical")
        private val CRUISE_RUDDER_TEXT = Component.translatable("gui.vs_eureka.cruise_rudder")
        private val CRUISE_TRIM_TEXT = Component.translatable("gui.vs_eureka.cruise_trim")
        private val CRUISE_AIRSPEED_TEXT = Component.translatable("gui.vs_eureka.cruise_airspeed")
        private val CRUISE_YAW_TEXT = Component.translatable("gui.vs_eureka.cruise_yaw")
        private val CRUISE_CLIMB_TEXT = Component.translatable("gui.vs_eureka.cruise_climb")
        private val CRUISE_DIVE_TEXT = Component.translatable("gui.vs_eureka.cruise_dive")

        private val DISPLAY_HUD_TEXT = Component.translatable("gui.vs_eureka.display_hud")
        private val SPEED_TEXT = Component.translatable("gui.vs_eureka.display_speed")
        private val ALTITUDE_TEXT = Component.translatable("gui.vs_eureka.display_altitude")
        private val HEADING_TEXT = Component.translatable("gui.vs_eureka.display_heading")

        private val ASSEMBLER_TEXT = Component.translatable("gui.vs_eureka.assembler")
        private val ASSEMBLER_FLOATER_TEXT = Component.translatable("gui.vs_eureka.assembler_floater")
        private val ASSEMBLER_BALLOON_TEXT = Component.translatable("gui.vs_eureka.assembler_balloon")
        private val ASSEMBLER_REPLACE_ALL_TEXT = Component.translatable("gui.vs_eureka.assembler_replace_all")
        private val SHIP_WEIGHT_TEXT = Component.translatable("gui.vs_eureka.ship_weight")
        private val PLUS_TEXT = Component.literal("+")

        private val RENAME_TEXT = Component.translatable("gui.vs_eureka.rename")
        private val SAVE_TEXT = Component.translatable("gui.vs_eureka.rename_save")

        private val ASSEMBLE_TEXT = Component.translatable("gui.vs_eureka.assemble")
        private val DISSEMBLE_TEXT = Component.translatable("gui.vs_eureka.disassemble")
        private val ALIGN_TEXT = Component.translatable("gui.vs_eureka.align")
        private val ALIGNING_TEXT = Component.translatable("gui.vs_eureka.aligning")
    }
}
