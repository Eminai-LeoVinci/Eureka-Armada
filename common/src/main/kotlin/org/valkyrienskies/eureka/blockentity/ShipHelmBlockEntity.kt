package org.valkyrienskies.eureka.blockentity

import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Direction.Axis
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaBlockEntities
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaBindings
import org.valkyrienskies.eureka.armada.ArmadaSelection
import org.valkyrienskies.eureka.armada.ArmadaShipControl
import org.valkyrienskies.eureka.EurekaConfigLoader
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.block.AnchorBlock
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
import org.valkyrienskies.mod.common.executeIf
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils
import org.valkyrienskies.mod.common.util.settings
import org.valkyrienskies.mod.common.util.toDoubles
import org.valkyrienskies.mod.common.util.toJOMLD
import org.valkyrienskies.mod.util.logger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// Blocks that never assemble, full stop: fluids, portals, and the world's own guard blocks.
val ASSEMBLE_BLACKLIST: TagKey<Block> =
    TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "assemble_blacklist"))

// Blocks the WORLD is made of -- stone, dirt, sand, ice, vegetation. These are neither banned nor free:
// whether one assembles depends on whether the patch it belongs to is a player's build or the landscape,
// which ShipAssembler.TerrainPocketClassifier decides by extent (a deck ends; a beach doesn't). This is
// what keeps a ship from swallowing the hillside next to it while still letting a grass-decked raft fly.
val ASSEMBLE_TERRAIN: TagKey<Block> =
    TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "assemble_terrain"))

private const val ORPHAN_SCAN_INTERVAL_TICKS = 20

class ShipHelmBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EurekaBlockEntities.SHIP_HELM.get(), pos, state), MenuProvider {

    private val ship: LoadedServerShip? get() = (level as ServerLevel).getLoadedShipManagingPos(this.blockPos)
    private val control: EurekaShipControl? get() = ship?.getAttachment(EurekaShipControl::class.java)

    // True when this helm's ship is currently bound as an armada child. The helm menu greys out this ship's
    // controls (assemble/disassemble/cruise/mode/keep-active) while so -- a child is steered only by its parent.
    // Unticking the menu's "Armada Child" checkbox (or "/armada unbind") releases it. Server truth, synced to the menu.
    val isArmadaChild: Boolean get() = ship?.let { ArmadaShipControl.get(it)?.isChild == true } ?: false

    // region Armada Parent / Child checkboxes
    // The helm menu's two armada markers, which together do what "/armada bind <parent> <child>" does from a
    // place that only knows one ship: tick Parent at the lead ship's helm to mark it (per-player, see
    // ArmadaSelection), then tick Child at each other ship's helm to lock it into that parent's formation.
    // The two are mutually exclusive -- a child can't be marked parent, and a marked/actual parent can't be
    // ticked child -- and unticking either releases exactly what it holds.

    /** True when this ship actually leads an armada (has at least one bound child). */
    val isArmadaParent: Boolean get() = ship?.let { ArmadaShipControl.get(it)?.childShipIds?.isNotEmpty() == true } ?: false

    /** True when [player] has marked this ship as the parent to bind their next children to (children optional). */
    fun isArmadaParentMarkedBy(player: Player): Boolean =
        ship?.let { ArmadaSelection.isSelected(player.uuid, it.id) } ?: false

    /**
     * "Armada Parent" checkbox. Ticking marks this ship as [player]'s parent; unticking clears the mark AND
     * releases every child it has picked up, so one click dissolves the armada from its lead ship.
     */
    fun toggleArmadaParent(player: Player) {
        val ship = ship ?: return
        val level = level as? ServerLevel ?: return
        if (isArmadaChild) {
            armadaFeedback(player, "This ship is an armada child -- untick Armada Child first.")
            return
        }

        if (isArmadaParent || isArmadaParentMarkedBy(player)) {
            // releaseFromArmada also drops the mark, so unticking at a parent someone ELSE marked doesn't
            // silently clear this player's mark on a different ship.
            val released = ArmadaShipControl.get(ship)?.childShipIds?.size ?: 0
            ArmadaBindings.releaseFromArmada(level, ship)
            armadaFeedback(
                player,
                if (released > 0) "Armada dissolved -- released $released ship(s)." else "Armada parent unmarked."
            )
            return
        }

        // Keep Active from the moment a ship is nominated to lead, before any child has bound: a parent that
        // falls out of simulation strands the formation, and the point of marking one is that ships are about to
        // be locked to it. ArmadaBindings.bindChild does the same for both ends of each bond.
        ship.settings.keepActive = true
        ArmadaSelection.select(player.uuid, ship.id)
        armadaFeedback(player, "Marked as armada parent. Tick Armada Child at another ship's helm to add it.")
    }

    /**
     * "Armada Child" checkbox. Ticking locks this ship into the parent [player] marked; unticking releases only
     * this ship, leaving the rest of the armada flying.
     */
    fun toggleArmadaChild(player: Player) {
        val ship = ship ?: return
        val level = level as? ServerLevel ?: return

        if (isArmadaChild) {
            ArmadaBindings.unbindChild(level, ship)
            armadaFeedback(player, "Released from the armada.")
            return
        }
        if (isArmadaParent || isArmadaParentMarkedBy(player)) {
            armadaFeedback(player, "This ship is the armada parent -- untick Armada Parent first.")
            return
        }

        val parentId = ArmadaSelection.get(player.uuid)
        if (parentId == null) {
            armadaFeedback(player, "No armada parent marked -- tick Armada Parent at the lead ship's helm first.")
            return
        }
        val parent = level.shipObjectWorld.loadedShips.getById(parentId)
        if (parent == null) {
            ArmadaSelection.forgetShip(parentId)
            armadaFeedback(player, "The marked parent ship isn't loaded -- get closer to it and try again.")
            return
        }

        val refusal = ArmadaBindings.bindChild(parent, ship)
        player.displayClientMessage(refusal ?: Component.literal("Bound into the armada."), false)
    }

    // Armada checkbox feedback goes to CHAT, not the action bar: the helm menu is open when these fire and the
    // HUD is hidden behind it, so an action-bar line would be gone before the player could read it. Chat keeps
    // the message in the log for when they close the menu.
    private fun armadaFeedback(player: Player, message: String) =
        player.displayClientMessage(Component.literal(message), false)
    // endregion

    // Keep-active (VS2 ShipSettings), toggled from the helm menu's "Keep Active?" checkbox -- equivalent to
    // `/vs set-keep-active <ship> <bool>` but usable by players without command access. Server-side only.
    val keepActive: Boolean get() = ship?.settings?.keepActive ?: false
    fun setKeepActive(value: Boolean) {
        ship?.let { it.settings.keepActive = value }
    }

    // Ship stats surfaced to the helm menu (synced to the client via DataSlots in ShipHelmScreenMenu).
    // Both are captured at assembly and persisted on the EurekaShipControl attachment, so a ship assembled
    // before this feature existed reads 0 until it is re-assembled.
    val assembledBlockCount: Int get() = control?.assembledBlocks ?: 0
    val estimatedTopSpeed: Int get() = ceil(control?.estimateTopSpeed() ?: 0.0).toInt()
    // Live ship mass (kg) for the helm menu's read-only "Ship's Weight" box; 0 when this helm has no ship yet.
    // Same source as /vs get-ship-weight (ship.inertiaData.mass), synced to the client via two DataSlots.
    val shipMass: Int get() = (ship?.inertiaData?.mass ?: 0.0).roundToInt()

    // Water altitude-hold is a GLOBAL server setting (EurekaConfig.SERVER), not per-ship. The helm's
    // "Water Altitude Lock" checkbox flips it for everyone; the DataSlot reflects the current value back.
    val waterAltitudeHold: Boolean get() = EurekaConfig.SERVER.enableWaterAltitudeHold
    fun toggleWaterAltitudeHold() {
        EurekaConfig.SERVER.enableWaterAltitudeHold = !EurekaConfig.SERVER.enableWaterAltitudeHold
        EurekaConfigLoader.save()
    }

    // Vanilla controls is PER-SHIP (mirrors the keep-active pattern, but against `control` not ship.settings).
    // The helm's "Vanilla" checkbox flips this ONE ship's mode and cancels its cruise, since `control` resolves
    // via getLoadedShipManagingPos(blockPos). Server-side only.
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
    private val seats = mutableListOf<ShipMountingEntity>()
    val assembled get() = ship != null
    val aligning get() = control?.aligning ?: false
    private var shouldDisassembleWhenPossible = false
    private var orphanScanCooldown = 0

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
        val newShape = newState.getShape(level, newPos)
        val newBlock = newState.block
        var height = 0.5
        if (!newState.isAir) {
            height = if (
                newBlock is StairBlock &&
                (!newState.hasProperty(StairBlock.HALF) || newState.getValue(StairBlock.HALF) == Half.BOTTOM)
            )
                0.5 // Valid StairBlock
            else
                newShape.max(Axis.Y)
        }
        // Standing-helmsman foot leveling: the rider stands centred on the block in front of the
        // helm, so their feet rest on that block's floor (newPos.below()). If that floor sits
        // lower than the block the helm is placed on -- e.g. a bottom slab against a full-block
        // deck -- drop the seat by the difference so the feet meet the lower floor instead of
        // hovering. Capped at half a block; a flush floor, or a gap with nothing solid to stand
        // on, leaves the rider at the normal deck height.
        val deckTopY = floorTopWorldY(level, blockPos.below())
        val frontFloorTopY = floorTopWorldY(level, newPos.below())
        val standDrop = if (deckTopY != null && frontFloorTopY != null)
            (deckTopY - frontFloorTopY).coerceIn(0.0, 0.5)
        else
            0.0

        val entity = ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE.create(level, EntitySpawnReason.MOB_SUMMONED)!!.apply {
            val seatEntityPos: Vector3dc = Vector3d(newPos.x + .5, (newPos.y - .5) + height - standDrop, newPos.z + .5)
            snapTo(seatEntityPos.x(), seatEntityPos.y(), seatEntityPos.z(), yRot, xRot)

            lookAt(
                EntityAnchorArgument.Anchor.EYES,
                state.getValue(HORIZONTAL_FACING).unitVec3i.toDoubles().add(position())
            )

            isController = true
        }

        level.addFreshEntityWithPassengers(entity)
        return entity
    }

    // World-space Y of the top surface of the block at [pos], or null if there is nothing solid
    // there to stand on (air / empty shape). Used to level the standing helmsman onto the floor
    // in front of the helm.
    private fun floorTopWorldY(level: ServerLevel, pos: BlockPos): Double? {
        val state = level.getBlockState(pos)
        if (state.isAir) return null
        val shape = state.getShape(level, pos)
        if (shape.isEmpty) return null
        return pos.y + shape.max(Axis.Y)
    }

    // Is the ship's keel touching real-world water? Samples a coarse grid of the hull's world footprint
    // at the keel Y for water. Game-thread only (touches the world). This is what lets the water altitude
    // hold work on ANY body of water -- VS2's liquidOverlap only sees the flat dimension sea-level plane.
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

    fun startRiding(player: Player, force: Boolean, blockPos: BlockPos, state: BlockState, level: ServerLevel): Boolean {
        for (i in seats.size - 1 downTo 0) {
            if (!seats[i].isVehicle) {
                seats[i].kill(level)
                seats.removeAt(i)
            } else if (!seats[i].isAlive) {
                seats.removeAt(i)
            }
        }

        val seat = spawnSeat(blockPos, blockState, level)
        val ride = player.startRiding(seat, force, true)

        if (ride) {
            control?.seatedPlayer = player
            seats.add(seat)
        }

        return ride
    }

    fun tick() {
        // One shipyard lookup per tick: the [ship]/[control] getters walk the loaded-ship index
        // on every call, and this tick used to do that two or three times.
        val curShip = ship
        val curControl = curShip?.getAttachment(EurekaShipControl::class.java)
        if (shouldDisassembleWhenPossible && curControl?.canDisassemble == true) {
            this.disassemble()
        }
        curControl?.ship = curShip

        // Feed the control real-world water contact at the keel (game thread = safe world access). VS2's
        // liquidOverlap only sees the dimension's flat sea-level plane, so this is what lets the water
        // altitude hold engage on man-made / elevated water bodies, not just the ocean at sea level.
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
            // Measure per-axis input-hold time on the fixed-rate game thread (physics TPS is variable) so the
            // physics turn law can gate the acceleration phase and all three sets can do hold-to-cancel.
            val seat = curShip.getAttachment(SeatedControllingPlayer::class.java)
            curControl.updateInputHolds(
                sLevel.gameTime,
                seat?.forwardImpulse ?: 0.0f,
                seat?.leftImpulse ?: 0.0f,
                seat?.upImpulse ?: 0.0f
            )
            // Path playback has no seated pilot and so no seat facing to thrust along. Stamping it from here
            // means a ship that has never cruised can still be told to fly a route -- the same forward the
            // helm menu's cruise uses, so both agree on which way is ahead.
            curControl.helmSeatDir = helmSeatDirection
        }

        // The ShipMountingEntity seat does not tick server-side: shipyard chunks are only
        // promoted to BLOCK_TICKING (see VS2 MixinChunkHolder), never ENTITY_TICKING, so the
        // vanilla Player.rideTick() sneak-to-dismount check never runs for a seated player.
        // The helm block entity itself does tick (BLOCK_TICKING), so drive the dismount here.
        val lvl = level
        if (lvl is ServerLevel) {
            // A world reload recreates this block entity with an empty [seats] list, but the
            // ShipMountingEntity seat (with its rider) was persisted. Re-adopt that orphaned
            // seat so the dismount loop below still works after a reload. Orphans only appear
            // right after a reload or a late player join, so probe at most once a second —
            // every unmanned helm hits this branch every tick otherwise.
            if (seats.isEmpty()) {
                if (--orphanScanCooldown <= 0) {
                    orphanScanCooldown = ORPHAN_SCAN_INTERVAL_TICKS
                    val seatBox = AABB(blockPos.relative(blockState.getValue(HORIZONTAL_FACING))).inflate(0.5)
                    for (player in lvl.players()) {
                        val vehicle = player.vehicle
                        if (vehicle is ShipMountingEntity && vehicle.isAlive &&
                            seatBox.contains(vehicle.x, vehicle.y, vehicle.z)
                        ) {
                            vehicle.isController = true
                            seats.add(vehicle)
                        }
                    }
                }
            }

            val iter = seats.iterator()
            while (iter.hasNext()) {
                val seat = iter.next()
                val rider = seat.passengers.firstOrNull()
                if (rider == null || !seat.isAlive) {
                    seat.kill(lvl)
                    iter.remove()
                } else if (rider is Player && rider.isShiftKeyDown) {
                    rider.stopRiding()
                    if (curShip != null && rider is ServerPlayer) {
                        val inWorld = curShip.shipToWorld.transformPosition(
                            Vector3d(seat.x, seat.y, seat.z)
                        )
                        rider.teleportTo(inWorld.x, inWorld.y, inWorld.z)
                    }
                    seat.kill(lvl)
                    iter.remove()
                } else {
                    // Drive the rider's server position from the seat each tick. The seat
                    // sits in shipyard chunks (BLOCK_TICKING only per VS2 MixinChunkHolder),
                    // so the seat never ticks server-side, so vanilla rideTick() ->
                    // positionRider() never runs. Without this, the rider's server pos
                    // stays frozen at mount-time world coords even though the seat is
                    // being carried around by ship physics. That caused two bugs:
                    //   - Other clients see the rider drift away as the ship moves
                    //     (LAN: visible body floats off behind / through the ship).
                    //   - On descent, the dismount teleportTo() jumps the rider from the
                    //     stale (high) Y to the current (low) seat Y, which produced
                    //     instant fall-death in singleplayer survival.
                    // Vanilla EntityDragger explicitly skips mounted entities, so it can't
                    // close this gap either. With the per-tick sync the dismount teleport
                    // becomes a no-op (rider is already at the seat's world pos).
                    if (curShip != null) {
                        val worldPos = curShip.shipToWorld.transformPosition(
                            Vector3d(seat.x, seat.y, seat.z)
                        )
                        rider.snapTo(worldPos.x, worldPos.y, worldPos.z, rider.yRot, rider.xRot)
                    }
                }
            }
        }
    }

    // Needs to get called server-side
    fun assemble(player: Player) {
        val level = level as ServerLevel

        // Check the block state before assembling to avoid creating an empty ship
        val blockState = level.getBlockState(blockPos)
        if (blockState.block !is ShipHelmBlock) return

        // Capture the helm's forward (bow) direction NOW, while the helm is still in the world and ship space is
        // about to be frozen aligned to world axes. Seeds ShipInfluenceOrientation at ASSEMBLY so the influence-
        // border faces lock to the helm the instant the ship exists (see the seed call after finishAssembly).
        val forwardAtAssembly = helmSeatDirection

        // Assembly places blocks straight into the shipyard without firing onPlace, so the
        // counters BalloonBlock/FloaterBlock/AnchorBlock/ShipHelmBlock maintain via onPlace
        // would all stay zero on a freshly assembled ship -- leaving it with no buoyancy.
        // Tally them during the collect pass and apply the totals to EurekaShipControl below.
        var helmCount = 0
        var balloonCount = 0
        var floaterCount = 0
        var anchorCount = 0
        var activeAnchorCount = 0
        var engineCount = 0
        var blockCount = 0 // total non-air assembled blocks (block entities like chests count too)

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
            val allowed = when {
                it.isAir -> false
                EurekaConfig.SERVER.blockBlacklist.contains(BuiltInRegistries.BLOCK.getKey(it.block).toString()) -> false
                it.`is`(ASSEMBLE_BLACKLIST) -> false
                it.`is`(ASSEMBLE_TERRAIN) -> terrain.isBoundedPocket(pos)
                else -> true
            }
            if (allowed) {
                blockCount++
                when (it.block) {
                    is ShipHelmBlock -> helmCount++
                    is BalloonBlock -> balloonCount++
                    // Floater buoyancy scales with 15 - redstone power, matching FloaterBlock.onPlace.
                    is FloaterBlock -> floaterCount += 15 - it.getValue(BlockStateProperties.POWER)
                    is EngineBlock -> engineCount++
                    is AnchorBlock -> {
                        anchorCount++
                        if (it.getValue(BlockStateProperties.POWERED)) activeAnchorCount++
                    }
                }
            }
            return@collectBlockPositions allowed
        }

        if (blockPositions == null) {
            player.displayClientMessage(Component.translatable("gui.vs_eureka.too_big", EurekaConfig.SERVER.maxShipBlocks), true)
            logger.warn("Failed to assemble to large of a ship for ${player.name.string}")
            return
        }

        // Eureka Assembler: if this player has floater/balloon auto-fill on, replace hull blocks with the
        // floaters/balloons the ship needs (gated on inventory) BEFORE the ship is built. A shortfall aborts
        // the whole assembly -- no ship, no world changes -- with a chat message the player can re-read.
        val prefs = AssemblerPreferences.get(player.uuid)
        if (prefs.enabled && (prefs.floater || prefs.balloon)) {
            when (
                val outcome = EurekaAssembler.apply(
                    level, player, blockPositions, prefs.floater, prefs.balloon,
                    existingFloaters = floaterCount / 15, existingBalloons = balloonCount,
                    floaterBonusPercent = prefs.floaterBonusPercent, balloonBonusPercent = prefs.balloonBonusPercent
                )
            ) {
                is EurekaAssembler.Cancelled -> {
                    // Send to chat (overlay=false), not the action bar: the shortfall detail can run several
                    // lines and the action bar neither wraps nor persists, so it was getting cut off. In chat
                    // it word-wraps and stays in the scrollback (press T) for the player to re-read.
                    // Bonuses are LEFT intact on a cancel so the player can fix inventory and retry the same %.
                    player.displayClientMessage(outcome.message, false)
                    return
                }
                is EurekaAssembler.Applied -> {
                    // Replacements bypass onPlace, so fold the newly-placed buoyancy into the tally the
                    // applyControl below writes onto EurekaShipControl (floaters are stored in fifteenths).
                    floaterCount += outcome.floatersPlaced * 15
                    balloonCount += outcome.balloonsPlaced
                    // Report the swap in chat (overlay=false) like the cancel paths, so it survives in the
                    // scrollback. In creative nothing leaves the inventory, so this is the only feedback.
                    player.displayClientMessage(EurekaAssembler.placementSummary(outcome), false)
                    // The manual % boxes are per-assembly: reset them now that a ship was built (syncs 0% back).
                    AssemblerPreferences.clearBonuses(player.uuid)
                }
            }
        }

        val builtShip = ShipAssembler.finishAssembly(level, blockPositions)

        // A freshly-assembled ship is created as ShipData and only becomes a
        // LoadedServerShip once vs-core builds its ShipObject (usually the next
        // ship-world tick). Attachments require a LoadedServerShip, so attach
        // EurekaShipControl now if the ship is already loaded, otherwise defer.
        val shipId = builtShip.id

        // Lock the influence-border orientation to the helm's facing at assembly: the Front/Back/Left/Right faces
        // follow the bow from the moment the ship is created, so the expand/contract commands and the border
        // wireframe are correct immediately -- no longer wrong until someone sits at the helm. Seeded reflectively
        // because ShipInfluenceOrientation is a VS2 port addition that isn't on the VS2 API version Eureka compiles
        // against; the deployed port VS2 jar has it at runtime. On stock VS2 the class is absent and this no-ops.
        InfluenceOrientationBridge.seedForward(shipId, forwardAtAssembly)

        fun applyControl(loadedShip: LoadedServerShip) {
            // Keep Active on by default for anything Eureka assembles. A ship only physics-ticks while a player
            // is within vanilla simulation distance, so without this a ship left cruising, hovering or holding
            // altitude quietly stops the moment you walk away and is found wherever it stalled. Turn it off per
            // ship from the helm or `/vs set-keep-active` if you'd rather a parked hull cost nothing.
            loadedShip.settings.keepActive = true
            val control = EurekaShipControl.getOrCreate(loadedShip)
            // Set helms (>= 1 for any real ship) first so the deleteIfEmpty() in the
            // remaining setters can't drop the attachment mid-update when a count is 0.
            control.helms = helmCount
            control.balloons = balloonCount
            control.floaters = floaterCount
            control.anchors = anchorCount
            control.anchorsActive = activeAnchorCount
            control.engines = engineCount
            control.assembledBlocks = blockCount
        }

        val loaded = level.shipObjectWorld.loadedShips.getById(shipId)
        if (loaded != null) {
            applyControl(loaded)
        } else {
            level.server.executeIf({ level.shipObjectWorld.loadedShips.getById(shipId) != null }) {
                level.shipObjectWorld.loadedShips.getById(shipId)?.let { loadedShip ->
                    applyControl(loadedShip)
                }
            }
        }
    }

    fun disassemble() {
        val ship = ship ?: return
        val level = level ?: return
        val control = control ?: return

        // Release this ship from any armada first: a welded ship must not tear down with a live joint (the
        // joint would reference a ship that no longer exists), and a child dragged by a weld can end up
        // tilted/out of position, which makes unfillShip relocate blocks out of the world's height range and
        // crash. Idempotent, so it's safe to call every waiting tick. See ArmadaBindings.
        if (level is ServerLevel) ArmadaBindings.releaseFromArmada(level, ship)

        if (!control.canDisassemble) {
            shouldDisassembleWhenPossible = true
            control.disassembling = true
            control.aligning = true
            return
        }

        val inWorld = ship.shipToWorld.transformPosition(this.blockPos.toJOMLD())

        // Fall-through hold through the teardown: the shipyard collision vanishes for a split second before the
        // world blocks become collidable, so the player / mobs / animals / armor stands would otherwise drop a
        // block. Hold every entity over the ship's world footprint BEFORE the unfill, and once more (a touch
        // longer) AFTER it, in case the world-side blocks need an extra moment to register. World-AABB keyed
        // because unfillShip removes the ship (its id can't be used); the box is captured now, while the ship is
        // still valid, and reused for both arms.
        val holdAABB = EntityShipCollisionUtils.worldAABBForShip(ship)
        EntityShipCollisionUtils.markWorldFreeze(level, holdAABB, 2_000_000_000L) // ~2s gravity-hold through teardown (mobs/entities only)

        val serverLevel = level as ServerLevel
        if (!ShipAssembler.unfillShip(serverLevel, ship, this.blockPos, BlockPos.containing(inWorld.x, inWorld.y, inWorld.z))) {
            // The hull doesn't fit under (or over) the world's height limit where it's floating, so it was left
            // alone rather than relocating blocks into a chunk section that doesn't exist. Hand control back --
            // otherwise the ship stays stuck aligning, retrying this every tick -- and say why, because from the
            // helm it just looks like the button stopped working.
            shouldDisassembleWhenPossible = false
            control.disassembling = false
            control.aligning = false
            val centre = ship.transform.positionInWorld
            for (player in serverLevel.players()) {
                if (player.distanceToSqr(centre.x(), centre.y(), centre.z()) < 128.0 * 128.0) {
                    player.displayClientMessage(Component.translatable("gui.vs_eureka.disassemble_out_of_world"), true)
                }
            }
            return
        }
        // ship.die() TODO i think we do need this no? or autodetecting on all air

        EntityShipCollisionUtils.markWorldFreeze(level, holdAABB, 2_000_000_000L)
        shouldDisassembleWhenPossible = false
    }

    fun align() {
        val control = control ?: return
        control.aligning = !control.aligning
    }

    override fun setRemoved() {
        if (level?.isClientSide == false) {
            for (i in seats.indices) {
                seats[i].kill(level as ServerLevel)
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

        return startRiding(player, force, blockPos, blockState, level as ServerLevel)
    }
    private val logger by logger()
}

/**
 * Reflective bridge to VS2's `ShipInfluenceOrientation.observeForward`, which records a ship's forward (bow)
 * direction so the influence-border faces (Front/Back/Left/Right) follow the helm. That class is a VS2 port
 * addition that isn't on the VS2 API version Eureka compiles against, so we resolve it reflectively against the
 * runtime jar (cached after the first call). On stock VS2 the class is absent and [seedForward] is a no-op.
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
                method = null // stock VS2: no helm-oriented influence border to seed
            }
            resolved = true
        }
        try {
            method?.invoke(instance, shipId, forward)
        } catch (e: ReflectiveOperationException) {
            // best-effort: fall back to mount-time seeding (if present)
        }
    }
}
