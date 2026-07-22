package org.valkyrienskies.eureka.gui.shiphelm

import net.minecraft.ChatFormatting
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
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Overhauled ship-helm menu. Fully code-drawn (no baked texture): a flat grey panel with dark borders, matching
 * the fill-drawn checkboxes/buttons. Layout, top-down / left-to-right:
 *  - title strip: "Name: <ship>" + a Rename control.
 *  - Advanced Controls / Vanilla Controls radio (Advanced default; Vanilla = the pre-overhaul control feel).
 *  - three columns: Cruise Control (master + Speed/Turn/Vertical arm checkboxes, each with a manual value box),
 *    Display HUD (master + Speed/Altitude/Heading), Eureka Assembler (master + Auto Floaters/Balloons).
 *  - a divider, then miscellaneous toggles (Keep Active, Water Lock).
 *  - bottom row: three info boxes (Top Speed / Blocks / Dimensions) bottom-left, the Assemble/Align/Disassemble
 *    buttons centre, and three boxes bottom-right (Engine Power %, then two reserved-blank).
 */
class ShipHelmScreen(handler: ShipHelmScreenMenu, playerInventory: Inventory, text: Component) :
    AbstractContainerScreen<ShipHelmScreenMenu>(handler, playerInventory, text) {

    private lateinit var assembleButton: ShipHelmButton
    private lateinit var alignButton: ShipHelmButton
    private lateinit var disassembleButton: ShipHelmButton

    private lateinit var advancedCheckbox: ShipHelmCheckbox
    private lateinit var vanillaCheckbox: ShipHelmCheckbox

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
    private lateinit var assemblerFloaterBox: EditBox   // "Auto Floaters + N%" manual bonus
    private lateinit var assemblerBalloonBox: EditBox   // "Auto Balloons + N%" manual bonus
    private lateinit var shipWeightBox: EditBox         // read-only ship mass readout (not editable)
    private var weightBoxX = 0                          // absolute X, computed in init from the label width
    private var weightBoxW = 0

    private lateinit var keepActiveCheckbox: ShipHelmCheckbox
    private lateinit var waterLockCheckbox: ShipHelmCheckbox

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

        // region Advanced / Vanilla radio
        advancedCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + MODE_ADV_X, y + MODE_Y, checkboxWidth(ADVANCED_TEXT),
                ADVANCED_TEXT, font, { !menu.vanillaControls }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 7) }
        )
        vanillaCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + MODE_VAN_X, y + MODE_Y, checkboxWidth(VANILLA_TEXT),
                VANILLA_TEXT, font, { menu.vanillaControls }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 6) }
        )
        // endregion

        // region Column 1: Cruise Control
        cruiseMasterCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL1_X, y + HEADER_Y, checkboxWidth(CRUISE_TEXT),
                CRUISE_TEXT, font, { menu.cruising }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 8) }
        )
        cruiseSpeedCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL1_SUB_X, y + cruiseRowY(0), checkboxWidth(CRUISE_SPEED_TEXT),
                CRUISE_SPEED_TEXT, font, { menu.cruiseSpeedArmed }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 9) }
        )
        cruiseTurnCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL1_SUB_X, y + cruiseRowY(1), checkboxWidth(CRUISE_TURN_TEXT),
                CRUISE_TURN_TEXT, font, { menu.cruiseTurnArmed }
            ) { minecraft?.gameMode?.handleInventoryButtonClick(menu.containerId, 10) }
        )
        cruiseVerticalCheckbox = addRenderableWidget(
            ShipHelmCheckbox(
                x + COL1_SUB_X, y + cruiseRowY(2), checkboxWidth(CRUISE_VERTICAL_TEXT),
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

        // region Column 3: Eureka Assembler (per-player, server-friendly)
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
        // Per-assembly "+ N%" bonus boxes, right of each sub-toggle (committed on Enter, reset to 0% after assemble).
        assemblerFloaterBox = addPctBox(x + PCT_BOX_X, y + cruiseRowY(0))
        assemblerBalloonBox = addPctBox(x + PCT_BOX_X, y + cruiseRowY(1))
        // Read-only "Ship's Weight" readout, styled like the % boxes (black bg, grey border, white text) but wider
        // for heavy ships. Placed after its label, spanning to the panel's right edge (>= 30-50% longer than a % box).
        weightBoxX = x + COL3_SUB_X + Math.round(font.width(SHIP_WEIGHT_TEXT) * ShipHelmCheckbox.LABEL_SCALE) + 5
        weightBoxW = (x + PANEL_WIDTH - PANEL_RIGHT_MARGIN) - weightBoxX
        shipWeightBox = addRenderableWidget(
            EditBox(font, weightBoxX, y + cruiseRowY(2), weightBoxW, PCT_BOX_H, Component.empty())
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

    private fun toggleRename() {
        val s = ship ?: return
        if (!renaming) {
            renameBox.value = (pendingNames[s.id] ?: s.slug)?.replace('-', ' ') ?: ""
            renameBox.visible = true
            this.focused = renameBox
            renameBox.isFocused = true
            renaming = true
            renameButton.message = SAVE_TEXT
        } else {
            val slugName = renameBox.value.trim().replace("\"", "").replace(' ', '-')
            val shown = pendingNames[s.id] ?: s.slug
            if (slugName.isNotEmpty() && slugName != shown) {
                minecraft?.player?.connection?.sendCommand("vs rename @v[id=${s.id}] \"$slugName\"")
                pendingNames[s.id] = slugName
            }
            cancelRename()
        }
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

    private fun updateButtons() {
        val newPos = (Minecraft.getInstance().hitResult as? BlockHitResult)?.blockPos
        if (newPos != null) pos = newPos
        val newShip = pos?.let { Minecraft.getInstance().level?.getShipManagingPos(it) }
        if (newShip != null) ship = newShip

        ship?.let { sh -> if (pendingNames[sh.id] == sh.slug) pendingNames.remove(sh.id) }

        val isLookingAtShip = ship != null
        // Whether THIS helm's ship is assembled, from the server-synced DataSlot -- NOT the crosshair raycast.
        // The raycast reads null while the menu is open at close range (the frozen camera points off-ship),
        // which used to grey out cruise / keep-active / the mode radio even on a fully assembled ship.
        val assembled = menu.assembled
        val advanced = !menu.vanillaControls

        assembleButton.active = !assembled
        disassembleButton.active = EurekaConfig.SERVER.allowDisassembly && assembled
        alignButton.active = disassembleButton.active

        // Advanced / Vanilla radio is per-ship -> only meaningful once assembled.
        advancedCheckbox.active = assembled
        vanillaCheckbox.active = assembled

        keepActiveCheckbox.active = assembled
        waterLockCheckbox.active = true

        // Cruise controls need a ship AND advanced mode (vanilla cruise is the single C-toggle, no per-axis sets).
        val cruiseUsable = assembled && advanced
        cruiseMasterCheckbox.active = cruiseUsable
        cruiseSpeedCheckbox.active = cruiseUsable
        cruiseTurnCheckbox.active = cruiseUsable
        cruiseVerticalCheckbox.active = cruiseUsable
        updateCruiseBox(cruiseSpeedBox, cruiseUsable, menu.cruiseSpeed)
        updateCruiseBox(cruiseTurnBox, cruiseUsable, menu.cruiseTurn)
        updateCruiseBox(cruiseVerticalBox, cruiseUsable, menu.cruiseVertical)

        // HUD sub-toggles grey out while the master is off.
        val hudOn = EurekaConfig.CLIENT.displayHud
        displaySpeedCheckbox.active = hudOn
        displayAltitudeCheckbox.active = hudOn
        displayHeadingCheckbox.active = hudOn

        // Assembler subs grey out while the section master is off; the section is per-player (no ship needed).
        val asmOn = menu.assemblerEnabled
        assemblerFloaterCheckbox.active = asmOn
        assemblerBalloonCheckbox.active = asmOn
        // The "+ N%" boxes are editable only when their sub is on; otherwise greyed (still show the synced value).
        updatePctBox(assemblerFloaterBox, asmOn && menu.assemblerFloater, menu.assemblerFloaterBonus)
        updatePctBox(assemblerBalloonBox, asmOn && menu.assemblerBalloon, menu.assemblerBalloonBonus)
        // Read-only weight readout (0 when this helm has no ship yet); thousands-separated like the Blocks box.
        val weightStr = String.format("%,d", menu.shipMass)
        if (shipWeightBox.value != weightStr) shipWeightBox.value = weightStr

        renameButton.visible = isLookingAtShip
        renameButton.active = isLookingAtShip
        if (!isLookingAtShip && renaming) cancelRename()
    }

    // Show/enable a cruise box and, while it isn't being edited, keep it displaying the live synced value.
    // The synced value is in thousandths (see ShipHelmScreenMenu) so it renders with three decimals for fine
    // tuning; speed tracks the ship's live velocity (HUD-synced), turn/vertical show their locked setpoints.
    private fun updateCruiseBox(box: EditBox, usable: Boolean, syncedThousandths: Int) {
        box.visible = usable
        box.setEditable(usable)
        // Locale.ROOT so the decimal separator is always '.', matching the box's NUMERIC filter (a locale that
        // formats with ',' would be rejected by the filter and truncate the value).
        if (usable && !box.isFocused) box.value = String.format(java.util.Locale.ROOT, "%.3f", syncedThousandths / 1000.0)
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

        // Panel: border + inner fill.
        guiGraphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER)
        guiGraphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_BG)

        // Separators.
        hLine(guiGraphics, x, y + DIVIDER1_Y)
        hLine(guiGraphics, x, y + DIVIDER2_Y)

        // Bottom info boxes (left) + engine/reserved boxes (right).
        frame(guiGraphics, x + INFO_X, y + boxRowY(INFO_Y0, 0), INFO_W, BOX_H)
        frame(guiGraphics, x + INFO_X, y + boxRowY(INFO_Y0, 1), INFO_W, BOX_H)
        frame(guiGraphics, x + INFO_X, y + boxRowY(INFO_Y0, 2), INFO_W, BOX_H)
        frame(guiGraphics, x + ENG_X, y + boxRowY(INFO_Y0, 0), ENG_W, BOX_H)
        frame(guiGraphics, x + ENG_X, y + boxRowY(INFO_Y0, 1), ENG_W, BOX_H)
        frame(guiGraphics, x + ENG_X, y + boxRowY(INFO_Y0, 2), ENG_W, BOX_H)
    }

    private fun hLine(g: GuiGraphics, x: Int, absY: Int) =
        g.fill(x + 4, absY, x + imageWidth - 4, absY + 1, SEPARATOR)

    private fun frame(g: GuiGraphics, bx: Int, by: Int, w: Int, h: Int) {
        g.fill(bx, by, bx + w, by + h, BOX_BORDER)
        g.fill(bx + 1, by + 1, bx + w - 1, by + h - 1, BOX_BG)
    }

    override fun renderLabels(guiGraphics: GuiGraphics, i: Int, j: Int) {
        if (this.menu.aligning) {
            alignButton.message = ALIGNING_TEXT
            alignButton.active = false
        } else {
            alignButton.message = ALIGN_TEXT
        }

        // Assembler column extras -- drawn before the ship null-check because the assembler section is per-player
        // and shown with or without a ship: a "+" between each sub-toggle and its % box, and the weight label.
        drawScaledLabel(guiGraphics, PLUS_TEXT, PLUS_X, cruiseRowY(0), PCT_BOX_H, INFO_TEXT)
        drawScaledLabel(guiGraphics, PLUS_TEXT, PLUS_X, cruiseRowY(1), PCT_BOX_H, INFO_TEXT)
        drawScaledLabel(guiGraphics, SHIP_WEIGHT_TEXT, COL3_SUB_X, cruiseRowY(2), PCT_BOX_H, INFO_TEXT)

        val s = ship ?: return

        if (!renaming) {
            (pendingNames[s.id] ?: s.slug)?.let {
                guiGraphics.drawString(font, "Name: " + it.replace('-', ' '), titleLabelX, titleLabelY, INFO_TEXT, false)
            }
        }

        // Bottom-left info boxes.
        val topLine = "Top Speed: ${menu.topSpeed}m/s~"
        val blockLine = "Blocks: " + String.format("%,d", menu.blockCount)
        val dimLine = dimensionsText(s)
        drawBoxText(guiGraphics, topLine, INFO_X, boxRelY(INFO_Y0, 0), INFO_W)
        drawBoxText(guiGraphics, blockLine, INFO_X, boxRelY(INFO_Y0, 1), INFO_W)
        drawBoxText(guiGraphics, dimLine, INFO_X, boxRelY(INFO_Y0, 2), INFO_W)

        // Bottom-right: engine power (fuel-tank %), then two reserved-blank boxes.
        val powerLine = if (menu.hasEngines) "Engine Power: ${menu.enginePower}%" else "Engine Power: --"
        drawBoxText(guiGraphics, powerLine, ENG_X, boxRelY(INFO_Y0, 0), ENG_W)
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
        private val pendingNames = HashMap<Long, String>()
        private val NUMERIC = Regex("-?\\d*\\.?\\d*")
        // Whole non-negative percent, up to 3 digits, with an optional trailing '%' (so the displayed "5%" re-parses).
        private val PERCENT = Regex("\\d{0,3}%?")

        // Panel. Wide (320) so the three column headers and the long "Auto Assemble Floaters/Balloons" sub-labels
        // fit three-across without clipping. init() centres it, so it stays on-screen at normal GUI scales.
        private const val PANEL_WIDTH = 320
        private const val PANEL_HEIGHT = 210
        private const val PANEL_BORDER = 0xFF000000.toInt()
        private const val PANEL_BG = 0xFFC6C6C6.toInt()
        private const val SEPARATOR = 0xFF8B8B8B.toInt()

        // Checkbox label helper (full font width for the clickable area).
        private fun checkboxWidth(text: Component) =
            ShipHelmCheckbox.BOX + ShipHelmCheckbox.GAP + Minecraft.getInstance().font.width(text)

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
        private const val DIVIDER2_Y = 98

        // Advanced / Vanilla radio row.
        private const val MODE_Y = 24
        private const val MODE_ADV_X = 24
        private const val MODE_VAN_X = 175

        // Three columns.
        private const val HEADER_Y = 40
        private const val ROW0_Y = 54
        private const val ROW_DY = 14
        private fun cruiseRowY(row: Int) = ROW0_Y + ROW_DY * row

        private const val COL1_X = 12            // Cruise Control master
        private const val COL1_SUB_X = 16        // Speed/Turn/Vertical checkboxes
        private const val COL1_BOX_X = 72        // manual value boxes
        private const val COL2_X = 120           // Display HUD master
        private const val COL2_SUB_X = 124
        private const val COL3_X = 190           // Eureka Assembler master
        private const val COL3_SUB_X = 194

        // Wide enough for a full three-decimal value: "-15.140" is 40px in this font and a bordered EditBox
        // spends 8 of its width on the frame and left padding. The column is boxed in on both sides -- the
        // "Vertical:" label ends at COL1_SUB_X + BOX + GAP + 41 = 70, and the HUD sub-checkboxes start at
        // COL2_SUB_X = 124 -- so 72..120 is very nearly the whole space there is between them.
        private const val CRUISE_BOX_W = 48
        private const val CRUISE_BOX_H = 12

        // Assembler "+ N%" bonus boxes (right-aligned to the panel's right edge) and the read-only weight box.
        private const val PANEL_RIGHT_MARGIN = 6
        private const val PCT_BOX_W = 30
        private const val PCT_BOX_H = 12
        private const val PCT_BOX_X = PANEL_WIDTH - PANEL_RIGHT_MARGIN - PCT_BOX_W // 284
        private const val PLUS_X = PCT_BOX_X - 8 // the "+" sits just left of each % box
        private const val WEIGHT_TEXT_COLOR = 0xFFE0E0E0.toInt() // near-white, matching the % EditBoxes' text

        // Miscellaneous stack.
        private const val MISC_X = 14
        private const val MISC_Y = 104
        private const val MISC_DY = 13

        // Bottom row: info boxes (left), buttons (centre), engine boxes (right).
        private const val BOX_H = 15
        private const val INFO_Y0 = 150
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
        private const val BOX_BORDER = 0xFF404040.toInt()
        private const val BOX_BG = 0xFFB0B0B0.toInt()

        private val KEEP_ACTIVE_TEXT = Component.translatable("gui.vs_eureka.keep_active")
        private val WATER_LOCK_TEXT = Component.translatable("gui.vs_eureka.water_lock")
        // Underlined so the Advanced / Vanilla mode selectors read as the section headers they are.
        private val ADVANCED_TEXT = Component.translatable("gui.vs_eureka.advanced_controls")
            .withStyle(ChatFormatting.UNDERLINE)
        private val VANILLA_TEXT = Component.translatable("gui.vs_eureka.vanilla_controls")
            .withStyle(ChatFormatting.UNDERLINE)

        private val CRUISE_TEXT = Component.translatable("gui.vs_eureka.cruise_control")
        private val CRUISE_SPEED_TEXT = Component.translatable("gui.vs_eureka.cruise_speed")
        private val CRUISE_TURN_TEXT = Component.translatable("gui.vs_eureka.cruise_turn")
        private val CRUISE_VERTICAL_TEXT = Component.translatable("gui.vs_eureka.cruise_vertical")

        private val DISPLAY_HUD_TEXT = Component.translatable("gui.vs_eureka.display_hud")
        private val SPEED_TEXT = Component.translatable("gui.vs_eureka.display_speed")
        private val ALTITUDE_TEXT = Component.translatable("gui.vs_eureka.display_altitude")
        private val HEADING_TEXT = Component.translatable("gui.vs_eureka.display_heading")

        private val ASSEMBLER_TEXT = Component.translatable("gui.vs_eureka.assembler")
        private val ASSEMBLER_FLOATER_TEXT = Component.translatable("gui.vs_eureka.assembler_floater")
        private val ASSEMBLER_BALLOON_TEXT = Component.translatable("gui.vs_eureka.assembler_balloon")
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
