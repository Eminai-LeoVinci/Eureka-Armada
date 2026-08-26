package org.valkyrienskies.eureka.gui.shiphelm

import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.DataSlot
import net.minecraft.world.item.ItemStack
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaScreens
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.command.AssemblerPreferences
import kotlin.math.roundToInt

class ShipHelmScreenMenu(syncId: Int, playerInv: Inventory, private val blockEntity: ShipHelmBlockEntity?) :
    AbstractContainerMenu(EurekaScreens.SHIP_HELM.get(), syncId) {

    constructor(syncId: Int, playerInv: Inventory) : this(syncId, playerInv, null)

    // The player who opened this helm. Needed server-side to read/write that player's Eureka Assembler
    // preferences (per-player, not per-ship). On the client this is the local player and is only used as a
    // harmless fallback -- the assembler checkbox states come from the synced DataSlot, not from here.
    private val player: Player = playerInv.player

    // TODO this isn't synced...
    val aligning = blockEntity?.aligning ?: false

    // Server->client sync of the ship's keep-active flag so the "Keep Active?" checkbox shows the real state.
    // On the server get() reads the live ship setting; on the client (blockEntity == null) it returns the
    // value pushed by set() via the vanilla data-slot sync. broadcastChanges() (per-tick) keeps it current.
    // Client-side mirrors of the server-authoritative stats, populated by the DataSlot set() calls below.
    private var syncedKeepActive = false
    private var syncedBlockLow = 0   // low 16 bits of the assembled block count
    private var syncedBlockHigh = 0  // remaining high bits (a DataSlot transmits only a 16-bit short)
    private var syncedTopSpeed = 0
    private var syncedWaterHold = false
    private var syncedVanilla = false
    private var syncedEnginePower = -1 // -1 = ship has no engines (helm shows "--")
    private var syncedCruiseFlags = 0  // bit0 cruising, bit1 speedArmed, bit2 turnArmed, bit3 verticalArmed
    // Cruise readouts, each in signed thousandths split low/high (a DataSlot transmits only a 16-bit short):
    // speed m/s (+forward / -reverse), turn deg/s, vertical m/s (+up / -down).
    private var syncedCruiseSpeedLow = 0
    private var syncedCruiseSpeedHigh = 0
    private var syncedCruiseTurnLow = 0
    private var syncedCruiseTurnHigh = 0
    private var syncedCruiseVerticalLow = 0
    private var syncedCruiseVerticalHigh = 0
    private var syncedAssembler = 0    // bit0 enabled, bit1 floater, bit2 balloon
    private var syncedFloaterBonus = 0 // transient "Auto Floaters + N%" textbox value (per-player)
    private var syncedBalloonBonus = 0 // transient "Auto Balloons + N%" textbox value (per-player)
    private var syncedMassLow = 0      // low 16 bits of the ship mass (kg) for the read-only weight box
    private var syncedMassHigh = 0     // remaining high bits (a DataSlot transmits only a 16-bit short)
    private var syncedAssembled = false // is THIS helm's ship assembled (authoritative, not the client raycast)
    init {
        addDataSlot(object : DataSlot() {
            override fun get(): Int = if (blockEntity?.keepActive ?: syncedKeepActive) 1 else 0
            override fun set(value: Int) { syncedKeepActive = value == 1 }
        })
        // Assembled block count, split across two slots (a single DataSlot is a 16-bit short, but counts
        // can reach maxShipBlocks = 50000). The split also stays correct if the sync width is a full int.
        addDataSlot(object : DataSlot() {
            override fun get(): Int = (blockEntity?.assembledBlockCount ?: 0) and 0xFFFF
            override fun set(value: Int) { syncedBlockLow = value }
        })
        addDataSlot(object : DataSlot() {
            override fun get(): Int = (blockEntity?.assembledBlockCount ?: 0) ushr 16
            override fun set(value: Int) { syncedBlockHigh = value }
        })
        // Estimated top speed in m/s (already small -- fits one slot).
        addDataSlot(object : DataSlot() {
            override fun get(): Int = blockEntity?.estimatedTopSpeed ?: 0
            override fun set(value: Int) { syncedTopSpeed = value }
        })
        // Water altitude-hold (global server flag) so the checkbox reflects the real value.
        addDataSlot(object : DataSlot() {
            override fun get(): Int = if (blockEntity?.waterAltitudeHold == true) 1 else 0
            override fun set(value: Int) { syncedWaterHold = value == 1 }
        })
        // Vanilla controls (per-ship) so the checkbox reflects the controlled ship's mode.
        addDataSlot(object : DataSlot() {
            override fun get(): Int = if (blockEntity?.vanillaControls == true) 1 else 0
            override fun set(value: Int) { syncedVanilla = value == 1 }
        })
        // Engine power (fuel-tank %) 0..100, or -1 when the ship has no engines.
        addDataSlot(object : DataSlot() {
            override fun get(): Int = blockEntity?.let { if (it.engineCount > 0) it.enginePowerPercent else -1 } ?: -1
            override fun set(value: Int) { syncedEnginePower = value }
        })
        // Cruise state flags (bit-packed) so the master + 3 arm checkboxes reflect the live per-ship cruise.
        addDataSlot(object : DataSlot() {
            override fun get(): Int {
                val be = blockEntity ?: return 0
                var f = 0
                if (be.cruising) f = f or 1
                if (be.cruiseSpeedArmed) f = f or 2
                if (be.cruiseTurnArmed) f = f or 4
                if (be.cruiseVerticalArmed) f = f or 8
                return f
            }
            override fun set(value: Int) { syncedCruiseFlags = value }
        })
        // Current latched cruise values in THOUSANDTHS of a unit (signed m/s or deg/s * 1000) for the textbox
        // readouts, so the client can show three decimals. At that scale a value no longer fits the 16-bit
        // short a DataSlot transmits -- a 45 m/s ship alone is 45000 against a ceiling of 32767 -- so each
        // one is split low/high the same way the block count and ship mass above are. Splitting all three
        // rather than only speed keeps a raised baseImpulseDescendRate or turn cap from silently wrapping
        // its readout negative.
        addDataSlot(object : DataSlot() {
            override fun get(): Int = (blockEntity?.cruiseSpeedThousandths ?: 0) and 0xFFFF
            override fun set(value: Int) { syncedCruiseSpeedLow = value }
        })
        addDataSlot(object : DataSlot() {
            override fun get(): Int = (blockEntity?.cruiseSpeedThousandths ?: 0) shr 16
            override fun set(value: Int) { syncedCruiseSpeedHigh = value }
        })
        addDataSlot(object : DataSlot() {
            override fun get(): Int = (blockEntity?.cruiseTurnThousandths ?: 0) and 0xFFFF
            override fun set(value: Int) { syncedCruiseTurnLow = value }
        })
        addDataSlot(object : DataSlot() {
            override fun get(): Int = (blockEntity?.cruiseTurnThousandths ?: 0) shr 16
            override fun set(value: Int) { syncedCruiseTurnHigh = value }
        })
        addDataSlot(object : DataSlot() {
            override fun get(): Int = (blockEntity?.cruiseVerticalThousandths ?: 0) and 0xFFFF
            override fun set(value: Int) { syncedCruiseVerticalLow = value }
        })
        addDataSlot(object : DataSlot() {
            override fun get(): Int = (blockEntity?.cruiseVerticalThousandths ?: 0) shr 16
            override fun set(value: Int) { syncedCruiseVerticalHigh = value }
        })
        // Whether THIS helm's ship is assembled, server-authoritative: the client's blockEntity is null so it
        // can't tell, and the cruise/mode/action buttons gate on this instead of the fragile client raycast.
        addDataSlot(object : DataSlot() {
            override fun get(): Int = if (blockEntity?.assembled == true) 1 else 0
            override fun set(value: Int) { syncedAssembled = value == 1 }
        })
        // Eureka Assembler preferences (per-player, bit-packed): master + floater + balloon.
        addDataSlot(object : DataSlot() {
            override fun get(): Int {
                if (blockEntity == null) return 0
                val p = AssemblerPreferences.get(player.uuid)
                var f = 0
                if (p.enabled) f = f or 1
                if (p.floater) f = f or 2
                if (p.balloon) f = f or 4
                return f
            }
            override fun set(value: Int) { syncedAssembler = value }
        })
        // The two manual bonus percentages (per-player, 0..MAX_BONUS -- fit one slot each). They reset to 0
        // server-side after a successful assemble, and this sync pushes that 0 back so the textboxes clear.
        addDataSlot(object : DataSlot() {
            override fun get(): Int = if (blockEntity == null) 0 else AssemblerPreferences.get(player.uuid).floaterBonusPercent
            override fun set(value: Int) { syncedFloaterBonus = value }
        })
        addDataSlot(object : DataSlot() {
            override fun get(): Int = if (blockEntity == null) 0 else AssemblerPreferences.get(player.uuid).balloonBonusPercent
            override fun set(value: Int) { syncedBalloonBonus = value }
        })
        // Ship mass (kg), split across two slots like the block count (a DataSlot is a 16-bit short but a heavy
        // ship's mass easily exceeds that). 0 when this helm has no ship yet.
        addDataSlot(object : DataSlot() {
            override fun get(): Int = (blockEntity?.shipMass ?: 0) and 0xFFFF
            override fun set(value: Int) { syncedMassLow = value }
        })
        addDataSlot(object : DataSlot() {
            override fun get(): Int = (blockEntity?.shipMass ?: 0) ushr 16
            override fun set(value: Int) { syncedMassHigh = value }
        })
    }
    val keepActive: Boolean get() = blockEntity?.keepActive ?: syncedKeepActive
    // Server-authoritative, synced above: is this helm's ship assembled (the client's blockEntity is null).
    val assembled: Boolean get() = blockEntity?.assembled ?: syncedAssembled
    val blockCount: Int get() = blockEntity?.assembledBlockCount
        ?: ((syncedBlockHigh shl 16) or (syncedBlockLow and 0xFFFF))
    val topSpeed: Int get() = blockEntity?.estimatedTopSpeed ?: syncedTopSpeed
    val waterAltitudeHold: Boolean get() = blockEntity?.waterAltitudeHold ?: syncedWaterHold
    val vanillaControls: Boolean get() = blockEntity?.vanillaControls ?: syncedVanilla

    // Engine power: -1 (client mirror) or the real value; hasEngines tells the screen to show "--" vs "N%".
    val enginePower: Int get() = blockEntity?.let { if (it.engineCount > 0) it.enginePowerPercent else -1 } ?: syncedEnginePower
    val hasEngines: Boolean get() = enginePower >= 0

    // Cruise (read from the live block entity server-side, else the synced flags/values on the client).
    val cruising: Boolean get() = blockEntity?.cruising ?: (syncedCruiseFlags and 1 != 0)
    val cruiseSpeedArmed: Boolean get() = blockEntity?.cruiseSpeedArmed ?: (syncedCruiseFlags and 2 != 0)
    val cruiseTurnArmed: Boolean get() = blockEntity?.cruiseTurnArmed ?: (syncedCruiseFlags and 4 != 0)
    val cruiseVerticalArmed: Boolean get() = blockEntity?.cruiseVerticalArmed ?: (syncedCruiseFlags and 8 != 0)
    // Thousandths of a unit (see the DataSlots above); the screen divides by 1000 for its three-decimal
    // display. Masking the low half discards the sign the short arrived with, so the high half restores it.
    val cruiseSpeed: Int get() = blockEntity?.cruiseSpeedThousandths
        ?: ((syncedCruiseSpeedHigh shl 16) or (syncedCruiseSpeedLow and 0xFFFF))
    val cruiseTurn: Int get() = blockEntity?.cruiseTurnThousandths
        ?: ((syncedCruiseTurnHigh shl 16) or (syncedCruiseTurnLow and 0xFFFF))
    val cruiseVertical: Int get() = blockEntity?.cruiseVerticalThousandths
        ?: ((syncedCruiseVerticalHigh shl 16) or (syncedCruiseVerticalLow and 0xFFFF))

    // Eureka Assembler prefs (per-player).
    val assemblerEnabled: Boolean get() = syncedAssembler and 1 != 0
    val assemblerFloater: Boolean get() = syncedAssembler and 2 != 0
    val assemblerBalloon: Boolean get() = syncedAssembler and 4 != 0
    // Manual per-assembly bonus percentages (server-side read live off the player's prefs, else the synced mirror).
    val assemblerFloaterBonus: Int get() = blockEntity?.let { AssemblerPreferences.get(player.uuid).floaterBonusPercent } ?: syncedFloaterBonus
    val assemblerBalloonBonus: Int get() = blockEntity?.let { AssemblerPreferences.get(player.uuid).balloonBonusPercent } ?: syncedBalloonBonus
    // Read-only ship mass (kg) for the "Ship's Weight" box; reassembled from the two synced halves on the client.
    val shipMass: Int get() = blockEntity?.shipMass ?: ((syncedMassHigh shl 16) or (syncedMassLow and 0xFFFF))

    override fun stillValid(player: Player): Boolean = true

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        if (blockEntity == null) return false
        val server = !player.level().isClientSide

        // Manual assembler bonus entry ("Auto Floaters/Balloons + N%"). Encoded into a wide button id ABOVE the
        // cruise band, so it must be decoded first (the cruise check below is an open `id >= CRUISE_VALUE_BASE`).
        // Per-player like the assembler toggles -- no ship needed, set before assembling.
        if (id >= ASSEMBLER_BONUS_BASE) {
            if (server) {
                val rel = id - ASSEMBLER_BONUS_BASE
                val which = rel / ASSEMBLER_BONUS_STRIDE
                val pct = rel % ASSEMBLER_BONUS_STRIDE
                when (which) {
                    0 -> AssemblerPreferences.setFloaterBonus(player.uuid, pct)
                    1 -> AssemblerPreferences.setBalloonBonus(player.uuid, pct)
                }
            }
            return true
        }

        // Manual cruise value entry -- the textboxes encode {axis, value} into a single (wide) button id.
        if (id >= CRUISE_VALUE_BASE) {
            if (server) {
                val rel = id - CRUISE_VALUE_BASE
                val axis = rel / CRUISE_AXIS_STRIDE
                val value = ((rel % CRUISE_AXIS_STRIDE) - CRUISE_VALUE_OFFSET) / 1000.0
                if (axis in 0..2) blockEntity.setCruiseValue(axis, value)
            }
            return true
        }

        // Assembler toggles are per-player and need no ship -- handle them before the ship-gated actions.
        when (id) {
            12 -> { if (server) AssemblerPreferences.setEnabled(player.uuid, !AssemblerPreferences.get(player.uuid).enabled); return true }
            13 -> { if (server) AssemblerPreferences.setFloater(player.uuid, !AssemblerPreferences.get(player.uuid).floater); return true }
            14 -> { if (server) AssemblerPreferences.setBalloon(player.uuid, !AssemblerPreferences.get(player.uuid).balloon); return true }
        }

        if (id == 0 && !assembled && server) {
            blockEntity.assemble(player)
            return true
        }

        if (id == 1 && assembled && server) {
            blockEntity.align()
            return true
        }

        if (id == 3 && assembled && server && EurekaConfig.SERVER.allowDisassembly) {
            blockEntity.disassemble()
            return true
        }

        // "Keep Active?" checkbox -> toggle the ship's keep-active flag (same as /vs set-keep-active).
        if (id == 4 && server) {
            blockEntity.setKeepActive(!blockEntity.keepActive)
            return true
        }

        // "Water Altitude Lock" checkbox -> flip the GLOBAL enableWaterAltitudeHold server config.
        if (id == 5 && server) {
            blockEntity.toggleWaterAltitudeHold()
            return true
        }

        // "Vanilla Controls" / "Advanced Controls" radio -> select THIS ship's per-ship control mode.
        if (id == 6 && server) { blockEntity.setVanillaControls(true); return true }
        if (id == 7 && server) { blockEntity.setVanillaControls(false); return true }

        // "Cruise Control" master -> toggle cruise; 9/10/11 arm the speed/turn/vertical sets.
        if (id == 8 && server) { blockEntity.setCruise(!blockEntity.cruising); return true }
        if (id == 9 && server) { blockEntity.setCruiseAxis(0, !blockEntity.cruiseSpeedArmed); return true }
        if (id == 10 && server) { blockEntity.setCruiseAxis(1, !blockEntity.cruiseTurnArmed); return true }
        if (id == 11 && server) { blockEntity.setCruiseAxis(2, !blockEntity.cruiseVerticalArmed); return true }

        return super.clickMenuButton(player, id)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        // Do nothing
        return ItemStack.EMPTY
    }

    companion object {
        val factory: (syncId: Int, playerInv: Inventory) -> ShipHelmScreenMenu = ::ShipHelmScreenMenu

        // Manual cruise value entry rides the vanilla container button-click channel (buttonId is a full VarInt
        // on the wire), so no new packet is needed. The client encodes {axis, value} as one large id; the server
        // decodes and clamps. axis: 0 = speed m/s, 1 = turn deg/s, 2 = vertical m/s. Value is fixed-point x1000
        // so a typed third decimal survives the trip, biased by CRUISE_VALUE_OFFSET to stay positive within its
        // axis band. That band has to be ten times wider than it was for the same +/- range, so the range is
        // +/-100 units -- still far beyond anything a ship can physically do, and the server clamps regardless
        // -- which keeps the whole cruise band clear of ASSEMBLER_BONUS_BASE below.
        const val CRUISE_VALUE_BASE = 1_000_000
        const val CRUISE_AXIS_STRIDE = 250_000
        const val CRUISE_VALUE_OFFSET = 100_000

        fun encodeCruiseValue(axis: Int, value: Double): Int {
            // Rounded, not truncated: 0.29 * 1000 is 289.99999999999994 in binary floating point, and
            // truncating that would quietly enter 0.289 instead.
            val fixed = (value * 1000.0).roundToInt().coerceIn(-CRUISE_VALUE_OFFSET, CRUISE_VALUE_OFFSET)
            return CRUISE_VALUE_BASE + axis * CRUISE_AXIS_STRIDE + (fixed + CRUISE_VALUE_OFFSET)
        }

        // Assembler bonus % entry, on the same vanilla button-click channel but in a band ABOVE the cruise ids
        // (decoded first in clickMenuButton). which: 0 = floater, 1 = balloon. Percent is a small non-negative int.
        const val ASSEMBLER_BONUS_BASE = 2_000_000
        const val ASSEMBLER_BONUS_STRIDE = 1_000

        fun encodeAssemblerBonus(which: Int, percent: Int): Int =
            ASSEMBLER_BONUS_BASE + which * ASSEMBLER_BONUS_STRIDE + percent.coerceIn(0, ASSEMBLER_BONUS_STRIDE - 1)
    }
}
