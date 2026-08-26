package org.valkyrienskies.eureka.blockentity

import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Direction.Axis
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.eureka.EurekaBlockEntities
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaConfigLoader
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.block.BalloonBlock
import org.valkyrienskies.eureka.block.EngineBlock
import org.valkyrienskies.eureka.block.FloaterBlock
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.command.AssemblerPreferences
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmScreenMenu
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.util.EurekaAssembler
import org.valkyrienskies.eureka.util.ShipAssembler
import org.valkyrienskies.mod.api.SeatedControllingPlayer
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.util.settings
import org.valkyrienskies.mod.common.util.toDoubles
import org.valkyrienskies.mod.common.util.toJOMLD
import org.valkyrienskies.mod.util.logger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// Blocks that never assemble, full stop: fluids, portals, and the world's own guard blocks.
 var ASSEMBLE_BLACKLIST: TagKey<Block> =
     TagKey.create(Registries.BLOCK, ResourceLocation(EurekaMod.MOD_ID, "assemble_blacklist"))

// Blocks the WORLD is made of -- stone, dirt, sand, ice, vegetation. These are neither banned nor free:
// whether one assembles depends on whether the patch it belongs to is a player's build or the landscape,
// which ShipAssembler.TerrainPocketClassifier decides by extent (a deck ends; a beach doesn't). This is
// what keeps a ship from swallowing the hillside next to it while still letting a grass-decked raft fly.
// (Note: before this, the config blockBlacklist was never read on this version at all -- the assembly
// predicate consulted the tag alone. It is honoured now, same as on 1.21.x.)
val ASSEMBLE_TERRAIN: TagKey<Block> =
    TagKey.create(Registries.BLOCK, ResourceLocation(EurekaMod.MOD_ID, "assemble_terrain"))

// Keep-active accessor cache. VS2's ShipSettings.keepActive exists on OUR custom VS2 but NOT on the official
// VS2 2.4.10 this one Eureka jar is compiled against (one-jar strategy: the jar loads on both), so the flag is
// reached reflectively. Resolved once against the runtime ShipSettings class: found on our custom VS2 (the
// checkbox works), absent on official VS2 (the checkbox silently no-ops). See [ShipHelmBlockEntity.keepActive].
private var keepActiveGetter: java.lang.reflect.Method? = null
private var keepActiveSetter: java.lang.reflect.Method? = null
private var keepActiveResolved = false
private fun resolveKeepActive(settings: Any) {
    if (keepActiveResolved) return
    keepActiveResolved = true
    try {
        keepActiveGetter = settings.javaClass.getMethod("getKeepActive")
        keepActiveSetter = settings.javaClass.getMethod("setKeepActive", java.lang.Boolean.TYPE)
    } catch (e: ReflectiveOperationException) {
        keepActiveGetter = null
        keepActiveSetter = null
    }
}

class ShipHelmBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EurekaBlockEntities.SHIP_HELM.get(), pos, state), MenuProvider {

    @OptIn(GameTickOnly::class)
    private val ship: LoadedServerShip? get() = (level as ServerLevel).getLoadedShipManagingPos(this.blockPos)
    @OptIn(GameTickOnly::class, VsBeta::class)
    private val control: EurekaShipControl? get() = ship?.getAttachment(EurekaShipControl::class.java)
    private val seats = mutableListOf<ShipMountingEntity>()
    @OptIn(GameTickOnly::class)
    val assembled get() = ship != null
    val aligning get() = control?.aligning == true
    private var shouldDisassembleWhenPossible = false

    // === Helm-menu hooks (server-side; synced to the client via DataSlots in ShipHelmScreenMenu) ===

    // Keep-active (VS2 ShipSettings.keepActive) toggled from the helm's "Keep Active" checkbox -- same effect as
    // `/vs set-keep-active`: when set, VS2's ShipActivationManager force-ticks this ship's chunks so it keeps
    // simulating with no player nearby. The flag isn't on the official VS2 2.4.10 ShipSettings this jar compiles
    // against, so it's read/written reflectively (see [resolveKeepActive]); no-ops on official VS2. Server-side.
    val keepActive: Boolean
        @OptIn(GameTickOnly::class, VsBeta::class)
        get() {
            val settings = ship?.settings ?: return false
            resolveKeepActive(settings)
            return try {
                keepActiveGetter?.invoke(settings) as? Boolean ?: false
            } catch (e: ReflectiveOperationException) {
                false
            }
        }

    @OptIn(GameTickOnly::class, VsBeta::class)
    fun setKeepActive(value: Boolean) {
        val settings = ship?.settings ?: return
        resolveKeepActive(settings)
        try {
            keepActiveSetter?.invoke(settings, value)
        } catch (e: ReflectiveOperationException) {
            // official VS2 has no keepActive -- ignore
        }
    }

    // Whether the runtime VS2 actually exposes ShipSettings.keepActive (resolved reflectively once). Lets the
    // helm GREY OUT the "Keep Active" checkbox on official VS2 (no keep-active -> the toggle would silently
    // no-op) while leaving it live on our custom VS2. Needs a ship to resolve the settings class; returns false
    // with no ship, which is fine because the checkbox is gated on the ship being assembled anyway. Synced to
    // the client via a DataSlot in ShipHelmScreenMenu. Server-side.
    val keepActiveSupported: Boolean
        @OptIn(GameTickOnly::class, VsBeta::class)
        get() {
            val settings = ship?.settings ?: return false
            resolveKeepActive(settings)
            return keepActiveSetter != null
        }

    // Ship stats surfaced to the helm menu. Both are captured at assembly (see [assemble]) and persisted on the
    // EurekaShipControl attachment, so a ship assembled before this feature existed reads 0 until re-assembled.
    val assembledBlockCount: Int get() = control?.assembledBlocks ?: 0
    val estimatedTopSpeed: Int get() = ceil(control?.estimateTopSpeed() ?: 0.0).toInt()
    // Live ship mass (kg) for the helm menu's read-only "Ship's Weight" box; 0 when this helm has no ship yet.
    // Same source as /vs get-ship-weight (inertiaData.shipMass on the 1.20.1 vs-core pin), synced via two DataSlots.
    val shipMass: Int get() = (ship?.inertiaData?.shipMass ?: 0.0).roundToInt()

    // Water altitude-hold is a GLOBAL server setting (EurekaConfig.SERVER), not per-ship. The helm's "Water
    // Lock" checkbox flips it for everyone; the DataSlot reflects the current value back.
    val waterAltitudeHold: Boolean get() = EurekaConfig.SERVER.enableWaterAltitudeHold
    fun toggleWaterAltitudeHold() {
        EurekaConfig.SERVER.enableWaterAltitudeHold = !EurekaConfig.SERVER.enableWaterAltitudeHold
        EurekaConfigLoader.save()
    }

    // Vanilla controls is PER-SHIP (against `control`). The helm's "Vanilla Controls" checkbox flips THIS one
    // ship's mode and cancels its cruise, since `control` resolves via getLoadedShipManagingPos(blockPos).
    val vanillaControls: Boolean get() = control?.vanillaControls ?: false
    fun toggleVanillaControls() {
        control?.let {
            it.vanillaControls = !it.vanillaControls
            it.cancelCruiseForModeSwitch()
        }
    }
    // Radio-style select from the "Advanced Controls" / "Vanilla Controls" checkboxes (vs the plain toggle).
    fun setVanillaControls(value: Boolean) {
        control?.let {
            if (it.vanillaControls == value) return
            it.vanillaControls = value
            it.cancelCruiseForModeSwitch()
        }
    }

    // Engine fuel-tank readout for the helm's "Engine Power: X%" box. engineCount == 0 -> no reading (the helm
    // shows "--" instead of 0%). Both resolve off the live EurekaShipControl (aggregated in onServerTick).
    val engineCount: Int get() = control?.engines ?: 0
    val enginePowerPercent: Int get() = control?.engineFuelPercent ?: 0

    // Helm-menu cruise control bridge (server-side). The menu routes its "Cruise Control" master, the three
    // Speed/Turn/Vertical arm checkboxes, and the manual value textboxes here; state getters feed the DataSlot
    // sync so the widgets reflect the live per-ship cruise. All no-ops when this helm isn't managing a ship.
    val cruising: Boolean get() = control?.isCruising ?: false
    val cruiseSpeedArmed: Boolean get() = control?.cruiseHorizontalArmed ?: false
    val cruiseTurnArmed: Boolean get() = control?.cruiseTurnArmed ?: false
    val cruiseVerticalArmed: Boolean get() = control?.cruiseVerticalArmed ?: false
    // Thousandths of a unit (value * 1000) so the helm can show three decimals; the client divides by 1000.
    // A turn especially wants that third place -- a tenth of a degree per second is a visibly different
    // circle. Scaled this far the values outgrow the 16-bit DataSlot, so the menu splits each across two.
    val cruiseSpeedThousandths: Int get() = ((control?.cruiseSpeedMps() ?: 0.0) * 1000.0).roundToInt()
    val cruiseTurnThousandths: Int get() = ((control?.cruiseTurnDegPerSec() ?: 0.0) * 1000.0).roundToInt()
    val cruiseVerticalThousandths: Int get() = ((control?.cruiseVerticalMps() ?: 0.0) * 1000.0).roundToInt()
    // The forward a helm-menu cruise thrusts along when nobody is seated. A seated pilot's forward is
    // seat.direction.opposite (VSGamePackets), and the helm seat faces this block's HORIZONTAL_FACING, so the
    // matching seat direction is facing.opposite -- passed into the cruise bridge so a menu-activated cruise has
    // a real course (and moves on a typed speed/turn) even with no one at the wheel. Agrees with the C key.
    private val helmSeatDirection: Direction get() = blockState.getValue(HORIZONTAL_FACING).opposite

    fun setCruise(enable: Boolean) { control?.setCruiseFromMenu(enable, helmSeatDirection) }
    fun setCruiseAxis(axis: Int, armed: Boolean) { control?.setCruiseAxisArmed(axis, armed, helmSeatDirection) }
    fun setCruiseValue(axis: Int, value: Double) { control?.setCruiseValueMenu(axis, value, helmSeatDirection) }

    override fun createMenu(id: Int, playerInventory: Inventory, player: Player): AbstractContainerMenu {
        return ShipHelmScreenMenu(id, playerInventory, this)
    }

    override fun getDisplayName(): Component {
        return Component.translatable("gui.vs_eureka.ship_helm")
    }

    // Needs to get called server-side
    fun spawnSeat(blockPos: BlockPos, state: BlockState, level: ServerLevel): ShipMountingEntity {
        val newPos = blockPos.relative(state.getValue(HorizontalDirectionalBlock.FACING))
        val newState = level.getBlockState(newPos)
        var height = 0.0

        val shape = newState.getShape(level, newPos)

        if (!shape.isEmpty) {
            height = if (
                newState.block is StairBlock &&
                (!newState.hasProperty(StairBlock.HALF) || newState.getValue(StairBlock.HALF) == Half.BOTTOM)
            )
                0.5 // Valid StairBlock
            else
                shape.max(Axis.Y)
        } else {
            val stateBelow = level.getBlockState(BlockPos(newPos.x, newPos.y - 1, newPos.z))
            val shapeBelow = stateBelow.getShape(level, newPos)

            if (!shapeBelow.isEmpty) {
                // If block below expected seat is valid slab or stair, move seat down one block
                val shapeHeight = shapeBelow.max(Axis.Y)
                // if block is slab or higher
                if (shapeHeight >= 0.5 && shapeHeight < 1.0) {
                    height = shapeHeight - 1.0
                }
            }
        }

        val entity = ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE.create(level)!!.apply {

            val offset =
                if (height > 0.15)
                // when seated, place player 0.1m closer to helm
                    state.getValue(HorizontalDirectionalBlock.FACING).normal.toDoubles().scale(-0.1).add(.5, height - .5, .5)
                else
                    Vec3(.5, height + 0.1, .5)

            val seatEntityPos: Vector3dc = Vector3d(newPos.x + offset.x, newPos.y + offset.y, newPos.z + offset.z)
            moveTo(seatEntityPos.x(), seatEntityPos.y(), seatEntityPos.z())

            lookAt(
                EntityAnchorArgument.Anchor.EYES,
                state.getValue(HORIZONTAL_FACING).normal.toDoubles().add(position())
            )

            isController = true
        }

        level.addFreshEntityWithPassengers(entity)
        return entity
    }

    fun startRiding(player: Player, force: Boolean, blockPos: BlockPos, state: BlockState, level: ServerLevel): Boolean {
        for (i in seats.size - 1 downTo 0) {
            if (!seats[i].isVehicle) {
                seats[i].kill()
                seats.removeAt(i)
            } else if (!seats[i].isAlive) {
                seats.removeAt(i)
            }
        }

        val seat = spawnSeat(blockPos, blockState, level)
        val ride = player.startRiding(seat, force)

        if (ride) {
            control?.seatedPlayer = player
            seats.add(seat)
        }

        return ride
    }

    @OptIn(VsBeta::class, GameTickOnly::class)
    fun tick() {
        val curShip = ship
        val curControl = curShip?.getAttachment(EurekaShipControl::class.java)
        if (shouldDisassembleWhenPossible && curControl?.canDisassemble == true) {
            this.disassemble()
        }
        curControl?.ship = curShip

        // Feed the control real-world water contact at the keel + per-axis input-hold time (game thread =
        // safe world access + fixed 20 TPS). VS2's liquidOverlap only sees the dimension's flat sea-level
        // plane, so keelInWater is what lets the water altitude hold engage on any water body; the input
        // holds gate the physics turn-acceleration phase and all three cruise sets' hold-to-cancel.
        val sLevel = level
        if (curControl != null && curShip != null && sLevel is ServerLevel) {
            // ~36 fluid reads per helm per tick, so only pay for them when something consumes the
            // answer, and then only every 4th tick. Staggered by block position so a fleet of helms
            // doesn't sample on the same tick. The hold's own hysteresis (engage on contact, release
            // only on a full clear -- see EurekaShipControl) absorbs a verdict up to 0.2s stale.
            if (!EurekaConfig.SERVER.enableWaterAltitudeHold) {
                curControl.keelInWater = false
            } else if (sLevel.gameTime and 3L == (blockPos.hashCode() and 3).toLong()) {
                curControl.keelInWater = sampleKeelInWater(sLevel, curShip)
            }
            val seat = curShip.getAttachment(SeatedControllingPlayer::class.java)
            curControl.updateInputHolds(
                sLevel.gameTime,
                seat?.forwardImpulse ?: 0.0f,
                seat?.leftImpulse ?: 0.0f,
                seat?.upImpulse ?: 0.0f
            )
        }
    }

    // Is the ship's keel touching real-world water? Samples a coarse grid of the hull's world footprint
    // at the keel Y for water. Game-thread only (touches the world). This is what lets the water altitude
    // hold work on ANY body of water -- VS2's liquidOverlap only sees the flat dimension sea-level plane.
    @OptIn(GameTickOnly::class)
    private fun sampleKeelInWater(level: ServerLevel, ship: LoadedServerShip): Boolean {
        val aabb = ship.worldAABB
        val keelY = floor(aabb.minY()).toInt()
        val minX = floor(aabb.minX()).toInt()
        val maxX = floor(aabb.maxX()).toInt()
        val minZ = floor(aabb.minZ()).toInt()
        val maxZ = floor(aabb.maxZ()).toInt()
        // Coarse grid (~6x6 max) so a large hull doesn't cost a full-footprint scan every tick.
        val stepX = max(1, (maxX - minX) / 5)
        val stepZ = max(1, (maxZ - minZ) / 5)
        val pos = BlockPos.MutableBlockPos()
        var x = minX
        while (x <= maxX) {
            var z = minZ
            while (z <= maxZ) {
                pos.set(x, keelY, z)
                if (level.hasChunkAt(pos) && level.getFluidState(pos).`is`(FluidTags.WATER)) {
                    return true
                }
                z += stepZ
            }
            x += stepX
        }
        return false
    }

    // Needs to get called server-side
    @OptIn(GameTickOnly::class, VsBeta::class)
    fun assemble(player: Player) {
        val level = level as ServerLevel

        // Check the block state before assembling to avoid creating an empty ship
        val blockState = level.getBlockState(blockPos)
        if (blockState.block !is ShipHelmBlock) return

        // Capture the helm's forward (bow) direction NOW, while the helm is still in the world and ship space is
        // about to be frozen aligned to world axes. This is the same value VSGamePackets records via
        // seat.direction.opposite, so it seeds ShipInfluenceOrientation at ASSEMBLY -- locking the influence-border
        // faces to the helm the instant the ship exists, instead of waiting for a player to mount it. See [assemble].
        val forwardAtAssembly = helmSeatDirection

        // Collect the connected block set WITHOUT building the ship yet, so the Eureka Assembler can swap
        // hull blocks (and abort cleanly) before anything is committed to a ship.
        //
        // What assembles: everything a player could have placed. Air never; the config blockBlacklist and
        // the assemble_blacklist tag never (fluids, portals, world-guard blocks); and terrain-type blocks
        // (the assemble_terrain tag) exactly when the patch they belong to is small enough to be a build
        // rather than the landscape -- see TerrainPocketClassifier for how that inference works and where
        // it can be wrong. Verdicts are cached in the classifier for the duration of this one assembly.
        val terrain = ShipAssembler.TerrainPocketClassifier(
            level, EurekaConfig.SERVER.terrainPocketMaxBlocks
        ) { state ->
            !state.isAir && state.`is`(ASSEMBLE_TERRAIN) && !state.`is`(ASSEMBLE_BLACKLIST) &&
                !EurekaConfig.SERVER.blockBlacklist.contains(BuiltInRegistries.BLOCK.getKey(state.block).toString())
        }
        val blockPositions = ShipAssembler.collectBlockPositions(
            level,
            blockPos
        ) { pos, it ->
            when {
                it.isAir -> false
                EurekaConfig.SERVER.blockBlacklist.contains(BuiltInRegistries.BLOCK.getKey(it.block).toString()) -> false
                it.`is`(ASSEMBLE_BLACKLIST) -> false
                it.`is`(ASSEMBLE_TERRAIN) -> terrain.isBoundedPocket(pos)
                else -> true
            }
        }

        if (blockPositions == null) {
            player.displayClientMessage(Component.translatable("info.vs_eureka.too_big", EurekaConfig.SERVER.maxShipBlocks), true)
            logger.warn("Failed to assemble to large of a ship for ${player.name.string}")
            return
        }

        // Tally from the FINAL de-duplicated set (the BFS can revisit a position, so counting in the
        // predicate would over-count -- see ShipAssembler). engineCount + blockCount feed the helm-menu
        // stats; floaters/balloons feed the Eureka Assembler's "already present" counts (floaters in
        // fifteenths, matching FloaterBlock.onPlace). Floaters/balloons/anchors/helms are otherwise NOT
        // applied here: on 1.20.1 the assembly relocation fires each block's onPlace at its shipyard pos,
        // so those counters -- including the assembler's freshly-swapped floaters/balloons -- populate
        // themselves; applying them again would double.
        var engineCount = 0
        val blockCount = blockPositions.size
        var floaterFifteenths = 0
        var balloonCount = 0
        for (pos in blockPositions) {
            val state = level.getBlockState(pos)
            when (state.block) {
                is EngineBlock -> engineCount++
                is FloaterBlock -> floaterFifteenths += 15 - state.getValue(POWER)
                is BalloonBlock -> balloonCount++
            }
        }

        // Eureka Assembler: if this player has floater/balloon auto-fill on, replace hull blocks with the
        // floaters/balloons the ship needs (gated on inventory) BEFORE the ship is built. A shortfall aborts
        // the whole assembly -- no ship, no world changes -- with a chat message the player can re-read.
        val prefs = AssemblerPreferences.get(player.uuid)
        if (prefs.enabled && (prefs.floater || prefs.balloon)) {
            when (
                val outcome = EurekaAssembler.apply(
                    level, player, blockPositions, prefs.floater, prefs.balloon,
                    existingFloaters = floaterFifteenths / 15, existingBalloons = balloonCount,
                    floaterBonusPercent = prefs.floaterBonusPercent, balloonBonusPercent = prefs.balloonBonusPercent
                )
            ) {
                is EurekaAssembler.Cancelled -> {
                    // Send to chat (overlay=false), not the action bar: the shortfall detail can run several
                    // lines and the action bar neither wraps nor persists. In chat it word-wraps and stays in
                    // the scrollback (press T) for the player to re-read.
                    // Bonuses are LEFT intact on a cancel so the player can fix inventory and retry the same %.
                    player.displayClientMessage(outcome.message, false)
                    return
                }
                is EurekaAssembler.Applied -> {
                    // The freshly-placed floaters/balloons are counted by their own onPlace when the assembly
                    // relocation fires (1.20.1), so there is nothing to fold in here.
                    // Report the swap in chat (overlay=false) like the cancel path, so it survives in the
                    // scrollback. In creative nothing leaves the inventory, so this is the only feedback.
                    player.displayClientMessage(EurekaAssembler.placementSummary(outcome), false)
                    // The manual % boxes are per-assembly: reset them now that a ship was built (syncs 0% back).
                    AssemblerPreferences.clearBonuses(player.uuid)
                }
            }
        }

        val builtShip = ShipAssembler.finishAssembly(level, blockPositions)

        // Lock the influence-border orientation to the helm's facing at assembly (method 1): the Front/Back/Left/
        // Right faces follow the bow from the moment the ship is created, so the expand/contract commands and the
        // border wireframe are correct immediately -- no longer wrong until someone sits at the helm. The mount-time
        // observeForward calls (VSGamePackets / ShipMountingEntity) still run and write this same value, so they act
        // as a harmless refresh and keep legacy ships (assembled before this change) and the MP client wireframe correct.
        // Seeded reflectively: ShipInfluenceOrientation is a VS2-120 port addition absent from the official VS2 2.4.10
        // API Eureka compiles against (the one-jar strategy), so a direct call wouldn't build; on official VS2 the
        // class is absent and this no-ops, leaving the mount-time seeding as the sole path there.
        InfluenceOrientationBridge.seedForward(builtShip.id, forwardAtAssembly)

        // Apply the captured stats onto the ship's EurekaShipControl once its ShipObject exists (mirrors how the
        // block onPlace counters attach). Plain fields, so they never trip the attachment's deleteIfEmpty.
        EurekaShipControl.deferUntilLoaded(builtShip) {
            it.engines = engineCount
            it.assembledBlocks = blockCount
        }
    }

    @OptIn(GameTickOnly::class)
    fun disassemble() {
        val ship = ship ?: return
        val level = level ?: return
        val control = control ?: return

        if (!control.canDisassemble) {
            shouldDisassembleWhenPossible = true
            control.disassembling = true
            control.aligning = true
            return
        }

        val inWorld = ship.shipToWorld.transformPosition(this.blockPos.toJOMLD())

        ShipAssembler.unfillShip(
            level as ServerLevel,
            ship,
            this.blockPos,
            BlockPos.containing(inWorld.x, inWorld.y, inWorld.z)
        )
        // ship.die() TODO i think we do need this no? or autodetect on all air

        shouldDisassembleWhenPossible = false
    }

    fun align() {
        val control = control ?: return
        control.aligning = !control.aligning
    }

    override fun setRemoved() {
        if (level?.isClientSide == false) {
            for (i in seats.indices) {
                seats[i].kill()
            }
            seats.clear()
        }

        super.setRemoved()
    }

    fun sit(player: Player, force: Boolean = false): Boolean {
        // If player is already controlling the ship, open the helm menu
        if (!force && player.vehicle?.type == ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE && seats.contains(player.vehicle as ShipMountingEntity)) {
            player.openMenu(this)
            return true
        }

        // val seat = spawnSeat(blockPos, blockState, level as ServerLevel)
        // control?.seatedPlayer = player
        // return player.startRiding(seat, force)
        return startRiding(player, force, blockPos, blockState, level as ServerLevel)
    }
    private val logger by logger()
}

/**
 * Reflective bridge to VS2-120's `ShipInfluenceOrientation.observeForward`, which records a ship's forward (bow)
 * direction so the influence-border faces (Front/Back/Left/Right) follow the helm. That class is a VS2-120 port
 * addition and is NOT on the official VS2 2.4.10 API Eureka compiles against (the one-jar strategy), so we resolve
 * it reflectively against the runtime jar (cached after the first call). On official VS2 the class is absent and
 * [seedForward] is a no-op, so the border there relies solely on the mount-time seeding.
 */
private object InfluenceOrientationBridge {
    private var resolved = false
    private var instance: Any? = null
    private var method: java.lang.reflect.Method? = null

    fun seedForward(shipId: Long, forward: Direction) {
        if (!resolved) {
            try {
                val clazz = Class.forName("org.valkyrienskies.mod.common.util.ShipInfluenceOrientation")
                instance = clazz.getField("INSTANCE").get(null)
                method = clazz.getMethod("observeForward", java.lang.Long.TYPE, Direction::class.java)
            } catch (e: ReflectiveOperationException) {
                method = null // official VS2: no helm-oriented influence border to seed
            }
            resolved = true
        }
        try {
            method?.invoke(instance, shipId, forward)
        } catch (e: ReflectiveOperationException) {
            // best-effort: fall back to mount-time seeding
        }
    }
}
