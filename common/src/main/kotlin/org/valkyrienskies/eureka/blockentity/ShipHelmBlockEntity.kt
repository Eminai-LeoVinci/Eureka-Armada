package org.valkyrienskies.eureka.blockentity

import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Direction.Axis
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponentGetter
import net.minecraft.core.component.DataComponentMap
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.world.MenuProvider
import net.minecraft.world.Nameable
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
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBd
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
import org.valkyrienskies.eureka.bottle.BottleBindings
import org.valkyrienskies.eureka.command.AssemblerPreferences
import org.valkyrienskies.eureka.crew.CrewData
import org.valkyrienskies.eureka.crew.CrewMuster
import org.valkyrienskies.eureka.crew.CrewStations
import org.valkyrienskies.eureka.crew.HelmNames
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.crew.CrewNameGenerator
import org.valkyrienskies.eureka.crew.CrewRoster
import org.valkyrienskies.eureka.crew.CrewTickets
import org.valkyrienskies.eureka.gui.shiphelm.ShipHelmScreenMenu
import org.valkyrienskies.eureka.ship.ControlProfile
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.util.BuoyancyMath
import org.valkyrienskies.eureka.util.EurekaAssembler
import org.valkyrienskies.eureka.util.ShipAssembler
import org.valkyrienskies.mod.api.SeatedControllingPlayer
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.executeIf
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils
import org.valkyrienskies.mod.common.util.settings
import org.valkyrienskies.mod.common.util.toDoubles
import org.valkyrienskies.mod.common.util.toJOMLD
import org.valkyrienskies.mod.util.logger
import java.util.UUID
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

// "There is no fit percentage to show" -- no ship, or a recommendation of zero. Far outside any real reading
// and comfortably inside the 16 bits a DataSlot carries, so it survives the sync as itself.
const val FIT_PERCENT_NONE = -1000

// --- Environment probes for the helm's per-category info boxes (see sampleEnvironment). ---
// All of these are readouts only: nothing in the physics steers by them, so they are budgeted for a screen
// rather than a control loop.

// Ticks between refreshes. 20 = once a second, which is faster than a helm's numbers need to move.
private const val ENVIRONMENT_SAMPLE_TICKS = 20L

// Ticks between POI ticket repairs (see CrewTickets). 600 = every thirty seconds: this fixes damage a save is
// already carrying, so it only has to be sooner than a player gives up on the wheel.
private const val TICKET_HEAL_TICKS = 600L

// The two Keep Name fields. Namespaced because they are written into `custom_data` on the ITEM as well as into
// block-entity NBT, and custom_data is a shared bag every mod may put things in.
private const val REMEMBERED_SHIP_KEY = "vs_eureka:remembered_ship"
private const val KEEP_NAME_KEY = "vs_eureka:keep_name"
private const val SHIP_SLUG_KEY = "vs_eureka:ship_slug"
private const val BOTTLE_BINDING_KEY = "vs_eureka:bottle_binding"

// How long after a ship loads before its remembered name is applied. Long enough for vs-core to have finished
// building the ship -- a slug set while it is still doing so does not survive -- and far too short to see.
private const val NAME_APPLY_DELAY_TICKS = 5L

// How far a vertical probe walks before giving up and reporting "not known". Deep enough to cross an ocean
// trench or clear a mountain, short enough that a ship over the void costs nothing.
private const val VERTICAL_PROBE_LIMIT = 128

// The shore probe: eight compass rays, sampled every SHORE_STEP blocks out to SHORE_LIMIT. Coarse on
// purpose -- 4 blocks is finer than the number is ever read to.
private const val SHORE_STEP = 4
private const val SHORE_LIMIT = 128
private val SHORE_RAYS = arrayOf(
    1 to 0, -1 to 0, 0 to 1, 0 to -1,
    1 to 1, 1 to -1, -1 to 1, -1 to -1
)

class ShipHelmBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EurekaBlockEntities.SHIP_HELM.get(), pos, state), MenuProvider, Nameable {

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

    // Water Lock lives on the BOATS & SHIPS settings block, and the helm only offers it on that tab. It is
    // still a server-wide setting rather than a per-ship one -- ticking it turns the waterline hold on for
    // every boat -- but being per-CATEGORY is what makes it behave the way it reads: an airship never gets
    // it, and a hybrid gets it exactly while it is being a boat, which is to say while it is on the water.
    val waterAltitudeHold: Boolean get() = EurekaConfig.BOAT.enableWaterAltitudeHold
    fun toggleWaterAltitudeHold() {
        EurekaConfig.BOAT.enableWaterAltitudeHold = !EurekaConfig.BOAT.enableWaterAltitudeHold
        EurekaConfigLoader.save()
    }

    // What category this vessel is steering by, for the helm's tab strip. Derived on the physics thread from
    // the ship's own floaters and balloons -- never chosen -- so this is a readout, not a setting. BOAT when
    // there is no ship yet, which is what the tab strip falls back to before assembly.
    val controlProfile: ControlProfile get() = control?.activeProfile ?: ControlProfile.BOAT
    // Whether this vessel has both floaters and balloons, i.e. its category can change under way.
    val isHybridVessel: Boolean get() = control?.isHybrid ?: false
    // Whether the whole vessel is under water. Detection only for now -- see EurekaShipControl.vesselSubmerged.
    val isSubmerged: Boolean get() = control?.vesselSubmerged ?: false

    // Environment readouts for the per-category info boxes, in blocks; -1 = not known, drawn as "--".
    val seabedDistance: Int get() = control?.seabedDistance ?: -1
    val surfaceDistance: Int get() = control?.surfaceDistance ?: -1
    val groundDistance: Int get() = control?.groundDistance ?: -1
    val shoreDistance: Int get() = control?.shoreDistance ?: -1

    // How far this ASSEMBLED ship's floaters and balloons already sit over what BuoyancyMath recommends for
    // its mass, as a percentage. This is the "now +N%" the Auto-Shipwright rows show, and the whole point of
    // showing it: a hull that flies well at +30% tells you what to type into the box next time you build one
    // like it. -1000 is the sentinel for "nothing to compare" (no ship, or no recommendation), which the
    // helm draws as "--"; it sits far outside any real reading and inside a DataSlot's 16 bits.
    val floaterFitPercent: Int get() = fitPercent(
        (control?.floaters ?: 0) / 15,
        BuoyancyMath::recommendedFloaters
    )
    val balloonFitPercent: Int get() = fitPercent(
        control?.balloons ?: 0,
        BuoyancyMath::recommendedBalloons
    )

    private fun fitPercent(placed: Int, recommend: (Double) -> Int): Int {
        val mass = ship?.inertiaData?.mass ?: return FIT_PERCENT_NONE
        return BuoyancyMath.fitPercent(placed, recommend(mass))?.coerceIn(-999, 9999) ?: FIT_PERCENT_NONE
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
    /**
     * The articles kept at this wheel: who is signed on, and who signed them.
     *
     * Held by the BLOCK ENTITY on purpose. Breaking the helm takes the block entity with it and the roster is
     * simply gone -- no listener, no cleanup pass, no chance of a list outliving the thing it described. That
     * is the behaviour, not a side effect of it: tear out the wheel and the crew has to be picked again.
     *
     * It survives assembly and disassembly for the same structural reason. Both directions RELOCATE blocks
     * rather than break and re-place them, carrying block-entity data across exactly as a chest carries its
     * contents, so a crew signed on at a dock is still signed on once the ship is built.
     *
     * Only the ship's PRIMARY helm's copy is ever read -- see [isCrewStation].
     */
    val crew = CrewRoster()

    /**
     * Whether this is the wheel a ship's articles are kept at.
     *
     * A ship can carry any number of helms and all of them steer, but exactly one holds the crew, so adding
     * wheels can never add berths. Claimed by the helm that assembles the ship, which makes "the first one" the
     * simple rule the feature was asked for. If that helm is destroyed the ship has no crew station at all
     * until someone aims at another wheel and presses the crew key, which is the moment the articles are
     * rewritten -- see `CrewStations`.
     */
    var isCrewStation = false

    /**
     * The name written on this wheel, or null while it is still blank.
     *
     * This is the ship's identity, not a label on a block. A named wheel lends its name to whatever hull it
     * assembles, and -- with the wood variant and the captain -- it is the key a crew is filed under, so two
     * wheels that agree on all three are the same berth even if one of them was crafted this morning.
     *
     * Stored as vanilla's own `CustomName`, deliberately NOT under a `vs_eureka:` prefix like the roster
     * beside it. That key is a contract with three pieces of vanilla we want for free: the implicit-component
     * round trip below, the `copy_custom_name` in the helm loot tables, and the anvil. Namespacing it would
     * mean re-implementing all three by hand.
     */
    var helmName: Component? = null
        private set

    /**
     * Write (or erase, with null) the name on this wheel.
     *
     * Marks dirty AND pushes a block update, because the name has to reach the client: it is drawn in the helm
     * menu, and unlike everything else on this block entity there is no menu slot carrying it. Blank and
     * whitespace-only are both stored as null, so "is this wheel named?" is one null check everywhere else.
     */
    fun setHelmName(name: Component?) {
        helmName = name?.takeIf { it.string.isNotBlank() }
        setChanged()
        level?.let { it.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_ALL) }
    }

    /**
     * The slug of the last ship this wheel steered, or null if it has never named one.
     *
     * A THIRD name, and deliberately not either of the other two. [helmName] is the crew's and never touches a
     * hull; the ship's own name is `Ship.slug` and dies with the ship at disassembly. This is what the wheel
     * REMEMBERS, so a hull can be taken apart and rebuilt -- or replaced entirely -- and still be the ship it
     * was. Stored already slugged, because that is the form vs-core takes and re-slugging on every assembly
     * would be a second place for the rules to drift.
     */
    var rememberedShipName: String? = null
        private set

    /**
     * Whether this wheel re-applies [rememberedShipName] to whatever it assembles.
     *
     * Defaults ON, because a captain who has named a ship almost always wants it to stay named, and the case
     * this protects against -- a forgotten wheel quietly renaming a hull you had called something else -- is
     * rarer than the case it serves. Unticking stops BOTH halves: nothing is remembered and nothing is
     * applied, so a wheel deliberately switched off cannot surprise anyone later.
     */
    var keepName: Boolean = true
        private set

    /**
     * The name of the ship this wheel is currently part of, as the CLIENT should display it.
     *
     * `Ship.slug` cannot be read on the client and trusted: VS2 tells a client a ship's name when the ship
     * loads and does not push later changes, so a renamed ship keeps its old name on every client until
     * something reloads it. The helm menu appeared to work only because it echoes what the player typed --
     * a name set by anything else (Keep Name, another player's `/vs rename`) showed the stale one.
     *
     * So the wheel carries it. This block entity already syncs to clients, so the name rides along with
     * everything else and the menu has a value it can trust. Refreshed on the same slow stagger as the
     * environment probes; a name that takes a second to appear is not a name anyone is watching change.
     */
    var shipSlug: String? = null
        private set

    /**
     * The durable name Ship Bottles know this wheel by, or null until one is marked here.
     *
     * A bottle cannot remember its ship by anything the ship has: capture DELETES the ship -- id, chunk
     * claim, shipyard address all die -- and release mints a new one of each. What survives the cycle is
     * this block entity's NBT, riding the bottle's template exactly as the ship's name does. So the wheel
     * carries the identity, every bottle marked here shares it, and [BottleBindings] (fed from [tick])
     * answers where the wheel is now. Minted lazily by [mintBottleBinding] so the overwhelming majority of
     * wheels -- never bottled -- carry nothing.
     */
    var bottleBinding: UUID? = null
        private set

    /** This wheel's bottle identity, minting one the first time a bottle is marked here. */
    fun mintBottleBinding(): UUID {
        bottleBinding?.let { return it }
        val minted = UUID.randomUUID()
        bottleBinding = minted
        setChanged()
        return minted
    }

    /** Toggle the Keep Name behaviour. Clearing it also forgets, so unticking is a complete off switch. */
    fun setKeepName(value: Boolean) {
        keepName = value
        if (!value) rememberedShipName = null
        setChanged()
        level?.let { it.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_ALL) }
    }

    /**
     * Record the name of the ship this wheel is currently part of. Ignored while Keep Name is off.
     *
     * A name vs-core made up is deliberately NOT remembered. The point of the feature is to keep the name a
     * captain chose, and remembering a generated one would mean any hull that came up unnamed -- including one
     * where re-applying had just failed -- would overwrite the real name and lose it for good.
     */
    fun rememberShipName(slug: String?) {        if (!keepName || slug.isNullOrEmpty()) return
        if (rememberedShipName == slug) return
        if (CrewNameGenerator.looksGenerated(slug)) return
        rememberedShipName = slug
        setChanged()
        level?.let { it.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_ALL) }
    }

    override fun getName(): Component = helmName ?: blockState.block.name

    override fun getCustomName(): Component? = helmName

    /**
     * Carries the name across the block/item boundary in both directions.
     *
     * [applyImplicitComponents] runs when a named helm is PLACED, reading the stack's `custom_name`;
     * [collectImplicitComponents] runs when one is picked up. Between them and the `copy_custom_name` in the
     * loot tables, a wheel can be mined, stacked, renamed on an anvil and re-placed without a line of code in
     * the block class. This is the same mechanism a shulker box uses.
     */
    override fun applyImplicitComponents(componentGetter: DataComponentGetter) {
        super.applyImplicitComponents(componentGetter)
        helmName = componentGetter.get(DataComponents.CUSTOM_NAME)

        // The remembered ship name has no vanilla component of its own, so it travels in custom_data. Read
        // defensively: this component belongs to everybody, and a stack that has been through another mod's
        // hands may carry anything at all under keys that are not ours.
        val custom = componentGetter.get(DataComponents.CUSTOM_DATA)
        if (custom != null) {
            val tag = custom.copyTag()
            rememberedShipName = tag.getString(REMEMBERED_SHIP_KEY).orElse(null)?.takeIf { it.isNotEmpty() }
            keepName = tag.getBooleanOr(KEEP_NAME_KEY, true)
        }
    }

    override fun collectImplicitComponents(components: DataComponentMap.Builder) {
        super.collectImplicitComponents(components)
        components.set(DataComponents.CUSTOM_NAME, helmName)

        // Only written when there is something to say. A wheel that has never named a ship and is switched on
        // -- which is most of them -- puts no custom_data on the item at all, so an ordinary helm stacks with
        // every other ordinary helm instead of splitting into singletons.
        if (rememberedShipName != null || !keepName) {
            val tag = CompoundTag()
            rememberedShipName?.let { tag.putString(REMEMBERED_SHIP_KEY, it) }
            if (!keepName) tag.putBoolean(KEEP_NAME_KEY, false)
            components.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }
    }

    /** Stops the name being written twice -- once as a component, once as raw block-entity NBT on the stack. */
    override fun removeComponentsFromTag(output: ValueOutput) {
        output.discard("CustomName")
    }

    /**
     * Sends this wheel's saved data to clients that can see it.
     *
     * The helm menu has to draw the name, and a container menu cannot carry one -- its sync slots are ints.
     * So the name rides the ordinary block-entity update instead, which is also what makes it correct after a
     * chunk reload rather than only right after someone typed it.
     *
     * [saveCustomOnly] sends the roster along with it. That is a few dozen bytes on a block nobody updates in
     * a hot loop, and the alternative is a hand-built tag that has to be kept in step with [saveAdditional]
     * every time a field is added -- a bug waiting on the next feature, to save nothing worth saving.
     */
    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? = ClientboundBlockEntityDataPacket.create(this)

    /**
     * Persisted alongside the roster so a reload cannot leave a ship with two crew stations or none.
     *
     * `super` first in both, then our own keys; the roster is stored under a namespaced name because block
     * entity NBT is a flat shared namespace and "Crew" is exactly the sort of key another mod would also pick.
     */
    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.storeNullable("CustomName", ComponentSerialization.CODEC, helmName)
        if (!crew.isEmpty) output.store("vs_eureka:crew", CrewRoster.CODEC, crew.entries())
        if (isCrewStation) output.putBoolean("vs_eureka:crew_station", true)
        rememberedShipName?.let { output.putString(REMEMBERED_SHIP_KEY, it) }
        // Written for the sync rather than for the save -- getUpdateTag is saveCustomOnly, so this is how the
        // client learns the ship's name at all. Harmless on disk; re-derived from the ship on the next tick.
        shipSlug?.let { output.putString(SHIP_SLUG_KEY, it) }
        // Written only when OFF, so the default costs nothing and every wheel already in a save keeps the
        // behaviour switched on without a migration.
        if (!keepName) output.putBoolean(KEEP_NAME_KEY, false)
        // As a string rather than an int array: ShipTemplate.stripShipName edits this tag as raw NBT in a
        // template palette, and a string key can be removed there without agreeing on an encoding.
        bottleBinding?.let { output.putString(BOTTLE_BINDING_KEY, it.toString()) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        helmName = BlockEntity.parseCustomNameSafe(input, "CustomName")
        crew.replaceAll(input.read("vs_eureka:crew", CrewRoster.CODEC).orElse(emptyList()))
        isCrewStation = input.getBooleanOr("vs_eureka:crew_station", false)
        rememberedShipName = input.getString(REMEMBERED_SHIP_KEY).orElse(null)?.takeIf { it.isNotEmpty() }
        keepName = input.getBooleanOr(KEEP_NAME_KEY, true)
        shipSlug = input.getString(SHIP_SLUG_KEY).orElse(null)?.takeIf { it.isNotEmpty() }
        bottleBinding = input.getString(BOTTLE_BINDING_KEY).orElse(null)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    private val seats = mutableListOf<ShipMountingEntity>()
    val assembled get() = ship != null
    val aligning get() = control?.aligning ?: false
    private var shouldDisassembleWhenPossible = false
    private var orphanScanCooldown = 0

    override fun createMenu(id: Int, playerInventory: Inventory, player: Player): AbstractContainerMenu {
        return ShipHelmScreenMenu(id, playerInventory, this)
    }

    /**
     * The menu title. A named wheel puts its own name up there, so you can tell at a glance which of a big
     * ship's helms you opened; a blank one keeps the generic title it always had.
     *
     * Resolves the [MenuProvider] / [Nameable] clash explicitly -- both declare `getDisplayName`, one abstract
     * and one defaulted -- which is why this override has to exist rather than being inherited.
     */
    override fun getDisplayName(): Component = helmName ?: Component.translatable("gui.vs_eureka.ship_helm")

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

    // Water contact and full submersion, sampled across a coarse grid of the hull's world FOOTPRINT at one Y.
    // [any] = true asks "is any of it in water" (the keel test); false asks "is all of it in water" (the
    // submersion test). Game-thread only -- it touches the world.
    //
    // This is what lets the waterline hold, and a hybrid's category, work on ANY body of water: VS2's
    // liquidOverlap only sees the dimension's flat sea-level plane, so it reads 0 for rivers, lakes and
    // man-made water away from that height.
    //
    // It samples the WORLD at the ship's world position. A ship's own blocks live in the shipyard, not there,
    // so water assembled INTO the hull is invisible here by construction -- an airship carrying a pool can
    // never read as touching water.
    private fun sampleWaterAt(level: ServerLevel, ship: LoadedServerShip, y: Int, any: Boolean): Boolean {
        val aabb = ship.worldAABB
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
                pos.set(x, y, z)
                val water = level.hasChunkAt(pos) && level.getFluidState(pos).`is`(FluidTags.WATER)
                if (any && water) return true
                if (!any && !water) return false
                z += stepZ
            }
            x += stepX
        }
        return !any
    }

    // How far down from [fromY] the water goes before something that is not water stops it: the depth of
    // water under the keel. -1 when the keel is not in water at all, or the floor is further than the cap.
    private fun sampleDepthBelow(level: ServerLevel, x: Int, z: Int, fromY: Int): Int {
        val pos = BlockPos.MutableBlockPos()
        var d = 0
        while (d < VERTICAL_PROBE_LIMIT) {
            val y = fromY - d
            if (y < level.minY) return -1
            pos.set(x, y, z)
            if (!level.hasChunkAt(pos)) return -1
            if (!level.getFluidState(pos).`is`(FluidTags.WATER)) return if (d == 0) -1 else d
            d++
        }
        return -1
    }

    // How far up from [fromY] to open air: the distance a submerged hull would have to rise to surface.
    // -1 when there is no water above at all, or the surface is further than the cap.
    private fun sampleSurfaceAbove(level: ServerLevel, x: Int, z: Int, fromY: Int): Int {
        val pos = BlockPos.MutableBlockPos()
        var d = 0
        while (d < VERTICAL_PROBE_LIMIT) {
            val y = fromY + d
            if (y > level.maxY) return -1
            pos.set(x, y, z)
            if (!level.hasChunkAt(pos)) return -1
            if (!level.getFluidState(pos).`is`(FluidTags.WATER)) return if (d == 0) -1 else d
            d++
        }
        return -1
    }

    // How far down from [fromY] to the first solid block -- ground under a flying ship, or the seabed under
    // one afloat, since it walks straight through water. -1 when nothing solid is within the cap.
    private fun sampleGroundBelow(level: ServerLevel, x: Int, z: Int, fromY: Int): Int {
        val pos = BlockPos.MutableBlockPos()
        var d = 1
        while (d <= VERTICAL_PROBE_LIMIT) {
            val y = fromY - d
            if (y < level.minY) return -1
            pos.set(x, y, z)
            if (!level.hasChunkAt(pos)) return -1
            val state = level.getBlockState(pos)
            if (!state.isAir && state.fluidState.isEmpty) return d
            d++
        }
        return -1
    }

    // Distance to the nearest column that is not water, at the keel's own height: how far off the beach the
    // ship is. Eight compass rays rather than a ring scan, stepped coarsely, because this is a readout and
    // not something the physics steers by -- 8 x 32 lookups on a 20-tick stagger. -1 when open water runs
    // past the cap in every direction, which is also what an inland ship reads (its first sample is land).
    private fun sampleShoreDistance(level: ServerLevel, x: Int, z: Int, y: Int): Int {
        val pos = BlockPos.MutableBlockPos()
        var best = -1
        for ((dx, dz) in SHORE_RAYS) {
            var d = SHORE_STEP
            while (d <= SHORE_LIMIT) {
                if (best in 1 until d) break // another ray already found something closer
                pos.set(x + dx * d, y, z + dz * d)
                if (!level.hasChunkAt(pos)) break
                if (!level.getFluidState(pos).`is`(FluidTags.WATER)) {
                    best = d
                    break
                }
                d += SHORE_STEP
            }
        }
        return best
    }

    // Refresh the environment readouts the helm's per-category info boxes draw. One column -- the centre of
    // the hull's world footprint -- is enough for a readout, and keeps this to a few hundred block lookups
    // on a 20-tick stagger.
    private fun sampleEnvironment(level: ServerLevel, ship: LoadedServerShip, control: EurekaShipControl) {
        val aabb = ship.worldAABB
        val cx = floor((aabb.minX() + aabb.maxX()) * 0.5).toInt()
        val cz = floor((aabb.minZ() + aabb.maxZ()) * 0.5).toInt()
        val keelY = floor(aabb.minY()).toInt()
        val topY = ceil(aabb.maxY()).toInt() - 1

        control.seabedDistance = sampleDepthBelow(level, cx, cz, keelY)
        control.surfaceDistance = sampleSurfaceAbove(level, cx, cz, topY)
        control.groundDistance = sampleGroundBelow(level, cx, cz, keelY)
        control.shoreDistance =
            if (control.keelInWater) sampleShoreDistance(level, cx, cz, keelY) else -1
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
        // Keep the bottle-binding registry current. Every tick rather than on load because assembly and
        // release MOVE this block entity (RelocationUtil), and no lifecycle hook fires at the new address;
        // the report is a no-op map read when nothing changed.
        bottleBinding?.let { binding ->
            (level as? ServerLevel)?.let { BottleBindings.report(it, binding, blockPos) }
        }

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
            // ~36 fluid reads per helm per sample, so only every 4th tick, staggered by block position so a
            // fleet of helms doesn't all sample on the same one. The hysteresis downstream (wet on contact,
            // dry only on a full clear -- see EurekaShipControl) absorbs a verdict up to 0.2s stale.
            //
            // UNCONDITIONAL, unlike before, when it was forced false whenever the water altitude hold was
            // switched off. Water contact is now also what puts a hybrid on the Boats & Ships category, and
            // that cannot hang off a feature toggle -- turning Water Lock off would otherwise have made every
            // hybrid fly as an airship on the sea. The hold checks its own config key at its own use site.
            if (sLevel.gameTime and 3L == (blockPos.hashCode() and 3).toLong()) {
                val keelY = floor(curShip.worldAABB.minY()).toInt()
                curControl.keelInWater = sampleWaterAt(sLevel, curShip, keelY, any = true)
                // Only worth asking once the keel is wet -- a ship out of the water is not under it.
                curControl.fullySubmerged = curControl.keelInWater &&
                    sampleWaterAt(sLevel, curShip, ceil(curShip.worldAABB.maxY()).toInt() - 1, any = false)
            }
            // The helm's info-box readouts: a few hundred lookups, so a much slower stagger than the water
            // contact above. Nothing steers by these -- they are numbers on a screen.
            if (sLevel.gameTime % ENVIRONMENT_SAMPLE_TICKS == (blockPos.hashCode().toLong() and 0xFL) % ENVIRONMENT_SAMPLE_TICKS) {
                sampleEnvironment(sLevel, curShip, curControl)
                // Keep Name: every wheel notes what its own ship is called, on the same slow stagger. Doing it
                // here rather than at the rename means it needs no notification and no walk of the hull -- a
                // wheel that was placed later, or renamed from a different wheel, still ends up knowing. The
                // write is a no-op unless the name actually changed.
                rememberShipName(curShip.slug)
                // And the name the MENU shows, which is a different question: this one follows the ship even
                // when Keep Name is off or the name is a generated one, because it is a readout rather than a
                // memory. Pushed to clients only when it actually changes.
                if (shipSlug != curShip.slug) {
                    shipSlug = curShip.slug
                    setChanged()
                    sLevel.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_ALL)
                }
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

        // Hand back berths that vanilla's job-site code took and never returned. Slow and staggered by
        // position: this is a repair, not a service, and a healthy helm bails out on one record read. See
        // CrewTickets -- a helm drained before the leak was fixed would otherwise stay drained for good.
        if (sLevel is ServerLevel &&
            sLevel.gameTime % TICKET_HEAL_TICKS == (blockPos.hashCode().toLong() and 0xFFL) % TICKET_HEAL_TICKS
        ) {
            CrewTickets.heal(sLevel, blockPos)
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

    /**
     * Turn this wheel's hull into a ship.
     *
     * @param knownBlocks the exact blocks to assemble, when the caller already knows them. Left null the hull
     * is discovered by flood-filling out from the wheel, which is right for a player pressing Assemble on
     * something they built. It is wrong for anything that placed the hull itself and can simply say what it
     * put down -- see the note at the branch below.
     */
    // Needs to get called server-side
    /**
     * @param holdEntities whether to freeze entities over the footprint through the swap.
     *
     * True for a wheel somebody pressed Assemble on, which is the case the hold was built for: the hull leaves
     * the world and reappears in the shipyard, neither copy is collidable for a moment, and anything standing
     * on the deck would drop through it.
     *
     * False for a ship coming out of a bottle, where the same hold is a menace. Nobody is standing on that deck
     * -- it did not exist a tick ago -- so there is nothing to catch, and the freeze grabs whatever happens to
     * be passing over the spot instead. A player gliding in on an elytra to drop a ship ahead of themselves
     * flies straight into their own two-second hold and falls out of the sky.
     */
    fun assemble(player: Player, knownBlocks: Set<BlockPos>? = null, holdEntities: Boolean = true) {
        val level = level as ServerLevel

        // Check the block state before assembling to avoid creating an empty ship
        val blockState = level.getBlockState(blockPos)
        if (blockState.block !is ShipHelmBlock) return

        // Capture the helm's forward (bow) direction NOW, while the helm is still in the world and ship space is
        // about to be frozen aligned to world axes. Seeds ShipInfluenceOrientation at ASSEMBLY so the influence-
        // border faces lock to the helm the instant the ship exists (see the seed call after finishAssembly).
        val forwardAtAssembly = helmSeatDirection

        // Keep Name, read NOW for the same reason the bow direction is.
        //
        // Assembly relocates this wheel into the shipyard, and the relocation RESETS the block entity it moved
        // FROM -- the destination copy gets the data, and this object is reloaded from an empty tag. `this` is
        // therefore blank by the time the deferred applyControl below runs, and reading the field there yields
        // null however long it waits. That is not a timing problem and no amount of deferring fixes it; the
        // value simply has to be taken off the block before the assembly starts.
        //
        // This is also what made the first attempt at naming a ship from its wheel fail: it read the helm's
        // name inside applyControl and wrote a null every time.
        val keepNameAtAssembly = keepName
        val rememberedAtAssembly = rememberedShipName

        // The crew's name and the wheel's wood, read here for exactly the same reason: mustering runs in the
        // deferred branch below, by which point this block entity has been blanked by its own relocation.
        val crewNameAtAssembly = helmName?.string
        val variantAtAssembly = HelmNames.variantOf(blockState)

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
        // What a ship is made of, counted the same way however the block set was arrived at. This used to be a
        // side effect of the flood-fill predicate, which welded "which blocks are the ship" to "what are they".
        fun tally(state: BlockState) {
            blockCount++
            when (state.block) {
                is ShipHelmBlock -> helmCount++
                is BalloonBlock -> balloonCount++
                // Floater buoyancy scales with 15 - redstone power, matching FloaterBlock.onPlace.
                is FloaterBlock -> floaterCount += 15 - state.getValue(BlockStateProperties.POWER)
                is EngineBlock -> engineCount++
                is AnchorBlock -> {
                    anchorCount++
                    if (state.getValue(BlockStateProperties.POWERED)) activeAnchorCount++
                }
            }
        }

        val blockPositions = if (knownBlocks != null) {
            // The caller already knows exactly which blocks are the ship -- a bottle letting out the hull it
            // just wrote down, for instance. Rediscovering them by flood-fill would be worse than redundant:
            // set a ship down on a player-built roof and the flood walks straight into it, because stripped
            // logs and smooth sandstone are no more "terrain" than the hull is, and the assembly then dies on
            // maxShipBlocks having swallowed somebody's building.
            for (pos in knownBlocks) tally(level.getBlockState(pos))
            knownBlocks
        } else {
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
            ShipAssembler.collectBlockPositions(
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
                if (allowed) tally(it)
                return@collectBlockPositions allowed
            }
        }

        if (blockPositions == null) {
            player.displayClientMessage(Component.translatable("gui.vs_eureka.too_big", EurekaConfig.SERVER.maxShipBlocks), true)
            logger.warn("Failed to assemble to large of a ship for ${player.name.string}")
            return
        }

        // Eureka Auto-Shipwright: if this player has floater/balloon auto-fill on, replace hull blocks with the
        // floaters/balloons the ship needs (gated on inventory) BEFORE the ship is built. A shortfall aborts
        // the whole assembly -- no ship, no world changes -- with a chat message the player can re-read.
        val prefs = AssemblerPreferences.get(player.uuid)
        if (prefs.enabled && (prefs.floater || prefs.balloon)) {
            when (
                val outcome = EurekaAssembler.apply(
                    level, player, blockPositions, prefs.floater, prefs.balloon,
                    existingFloaters = floaterCount / 15, existingBalloons = balloonCount,
                    floaterBonusPercent = prefs.floaterBonusPercent, balloonBonusPercent = prefs.balloonBonusPercent,
                    balloonReplaceAll = prefs.balloonReplaceAll
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

        // Fall-through hold through the BUILD, the mirror of the one disassemble() arms for the teardown. The
        // hull is about to be lifted out of the world and re-created in the shipyard, and for a moment neither
        // copy is collidable -- so anything standing on the deck drops. Disassembly has been holding entities
        // through that gap since the stuck-in-the-deck fix; assembly never was, which is why the blink could
        // drop you through the lower deck one time in several.
        //
        // Keyed on the WORLD footprint of the blocks being taken, computed while they are still in the world,
        // rather than on the new ship's id: the id-keyed spawn grace VS2 arms inside assembleToShip needs the
        // ship's chunk set to be populated before it can decide who is standing over it, and during the swap
        // it briefly isn't. A box needs nothing to be ready.
        //
        // Armed twice, before and after, for the same reason disassemble() does: the second one starts its
        // clock when the world-side work is finished rather than when it began.
        val holdAABB = worldFootprint(blockPositions)
        if (holdEntities) {
            EntityShipCollisionUtils.markWorldFreeze(level, holdAABB, 2_000_000_000L) // ~2s, self-expiring
        }

        // This wheel becomes the ship's crew station -- "the first helm wins", stated as the one that built the
        // ship. Set BEFORE the assembly, so the flag is part of the block entity data the relocation carries
        // into the shipyard rather than something that has to be written to it afterwards.
        isCrewStation = true
        setChanged()

        val builtShip = ShipAssembler.finishAssembly(level, blockPositions)

        if (holdEntities) {
            EntityShipCollisionUtils.markWorldFreeze(level, holdAABB, 2_000_000_000L)
        }


        // Where that wheel ended up, so the roster can be found in one block-entity lookup instead of a walk of
        // the ship's blocks. Rounds the same way the assembler does: the block containing the helm's centre.
        val helmInShip = builtShip.worldToShip.transformPosition(
            Vector3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        )
        val crewStation = BlockPos(
            floor(helmInShip.x).toInt(), floor(helmInShip.y).toInt(), floor(helmInShip.z).toInt()
        ).asLong()

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
            control.crewStationPos = crewStation

            // Keep Name: give the hull back the name this wheel last steered under, using the value read off
            // the block BEFORE the assembly reset it (see keepNameAtAssembly above).
            //
            // A ship's slug lives in TWO places and they are not the same object: the persisted record in
            // `allShips`, which is what reaches disk and what `/vs` selectors match, and the loaded ship in
            // `loadedShips`, which is what the helm menu and every client actually read. Writing only the
            // persisted one gets a ship that is correctly named on disk but shows its generated name until
            // something reloads the world -- which is exactly how this looked while it seemed broken.
            //
            // So both, a few ticks after the ship loads rather than immediately: a slug written into a ship
            // vs-core is still assembling does not survive. `renameShip` is a plain setter, so writing twice
            // costs nothing and is idempotent.
            if (keepNameAtAssembly && rememberedAtAssembly != null) {
                val named = loadedShip.id
                val server = level.server
                val applyAt = server.overworld().gameTime + NAME_APPLY_DELAY_TICKS
                server.executeIf({ server.overworld().gameTime >= applyAt }) {
                    val world = level.shipObjectWorld
                    world.allShips.getById(named)?.let {
                        ValkyrienSkiesMod.vsCore.renameShip(it, rememberedAtAssembly)
                    }
                    world.loadedShips.getById(named)?.let {
                        ValkyrienSkiesMod.vsCore.renameShip(it, rememberedAtAssembly)
                    }
                }
            }

            // Bring the crew filed under this wheel aboard. Deferred with everything else here because the
            // deck has to exist to stand on, and fed entirely from values read before the assembly, because
            // the wheel this ran from no longer holds them.
            if (crewNameAtAssembly != null && player is ServerPlayer) {
                CrewMuster.muster(
                    level, player, loadedShip,
                    crewNameAtAssembly, variantAtAssembly, CrewData.slots(player), crewStation
                )
            }
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

    /**
     * The world-space box the collected blocks occupy, with a block of slack on every side.
     *
     * [EntityShipCollisionUtils.isInWorldFreeze] tests an entity's POSITION POINT, not its bounding box, and
     * someone standing on the top deck has their feet at exactly the hull's upper face -- on the boundary,
     * where a strict test is a coin flip. The slack is what makes standing on deck unambiguous, and it also
     * covers a mob mid-step off the gunwale.
     *
     * [ShipAssembler.collectBlockPositions] always seeds the set with the helm, so it is never empty.
     */
    private fun worldFootprint(blockPositions: Set<BlockPos>): AABBd {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        for (pos in blockPositions) {
            if (pos.x < minX) minX = pos.x
            if (pos.y < minY) minY = pos.y
            if (pos.z < minZ) minZ = pos.z
            if (pos.x > maxX) maxX = pos.x
            if (pos.y > maxY) maxY = pos.y
            if (pos.z > maxZ) maxZ = pos.z
        }
        // maxN + 1 is the far face of the block at maxN; the extra 1 on each side is the slack.
        return AABBd(
            minX - 1.0, minY - 1.0, minZ - 1.0,
            maxX + 2.0, maxY + 2.0, maxZ + 2.0
        )
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

        // Keep Name: the last chance to read what this ship was called. A disassembly destroys the ship object
        // and its slug with it, so a wheel that has not written the name down by now has lost it. Deliberately
        // AFTER the canDisassemble gate, so a deferred teardown records the name once it actually happens
        // rather than on every waiting tick.
        rememberShipName(ship.slug)

        // Everything the crew stand-down below needs, read while the ship still exists and while this block
        // entity is still the one holding the articles. The unfill relocates the wheel back into the world,
        // which resets THIS object exactly as an assembly does -- see the top of `assemble`.
        //
        // The crew's name is the CREW STATION's, not necessarily this wheel's: any helm can order the
        // teardown, and on a hull with several wheels the pressed one need not be the named one. This wheel's
        // own name stays as the fallback for a ship that never recorded a station.
        val crewStation = CrewStations.stationOf(level as ServerLevel, ship)
        val crewName = crewStation?.helmName?.string ?: helmName?.string
        val crewVariant =
            if (crewStation != null) HelmNames.variantOf(crewStation.blockState)
            else HelmNames.variantOf(blockState)
        val shipIdAtTeardown = ship.id
        val sailors = ShipCrew.aboard(level as ServerLevel, ship)

        // Where each crew member is standing, measured NOW. The stand-down itself deliberately waits until
        // after the unfill -- a refused unfill must not strand a crew in the articles -- but by then there
        // is no ship left to measure against, and a post is only meaningful in the ship's own frame.
        // Measured in the lattice the unfill below is about to lay the ship into -- the same snapped matrix
        // and the same sub-block carry the blocks themselves get. "Where will they stand after the unfill,
        // minus where the wheel will" is exact by the same arithmetic that places the deck under them.
        //
        // Anchored on the crew station when there is one and on THIS wheel when there is not -- the same
        // fallback the crew's name takes, and for the same reason: a ship whose recorded station has gone
        // stale must still remember where its people were standing, or the fallback is a heap on the helm.
        val (plannedShipToWorld, plannedCarry) = ShipAssembler.unfillPlan(ship, this.blockPos)
        val crewPosts = CrewMuster.postsInLattice(
            level as ServerLevel, ship, plannedShipToWorld, plannedCarry, crewStation?.blockPos ?: this.blockPos
        )

        val inWorld = ship.shipToWorld.transformPosition(this.blockPos.toJOMLD())

        // Fall-through hold through the teardown: the shipyard collision vanishes for a split second before the
        // world blocks become collidable, so the player / mobs / animals / armor stands would otherwise drop a
        // block. Hold every entity over the ship's world footprint BEFORE the unfill, and once more (a touch
        // longer) AFTER it, in case the world-side blocks need an extra moment to register. World-AABB keyed
        // because unfillShip removes the ship (its id can't be used); the box is captured now, while the ship is
        // still valid, and reused for both arms.
        val holdAABB = EntityShipCollisionUtils.worldAABBForShip(ship)
        EntityShipCollisionUtils.markWorldFreeze(level, holdAABB, 2_000_000_000L) // ~2s, mobs and other entities

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

        // Take the crew off the deck and into the articles, now that there is definitely no deck. Deliberately
        // AFTER the unfill rather than before it: the unfill can decline (a hull that will not fit under the
        // world's height limit is left assembled), and a crew stood down for a disassembly that then did not
        // happen would be villagers vanishing off a ship that is still floating.
        //
        // The box is the one captured above, because the ship is gone by now and cannot be asked where it was.
        // The crew have not moved -- they are world-space entities standing where the deck used to be, which is
        // where its blocks now are.
        //
        // Unlike mustering, this is nobody's action in particular: disassembly has no player behind it, so
        // every berthed villager on the hull is stood down, whoever signed them on and whichever wheel their
        // articles hang from. The ship comes apart for everybody at once.
        val report =
            CrewMuster.standDownShip(serverLevel, shipIdAtTeardown, holdAABB, crewName, crewVariant, crewPosts)
        if (report.stood > 0) {
            val who = if (report.stood == 1) "crew member is" else "crew are"
            val shipTitle = crewName ?: "she"
            for (sailor in sailors) {
                PathMessages.send(
                    sailor,
                    "${report.stood} $who back on the articles. They muster when $shipTitle sails again.",
                    PathMessages.Kind.GOOD
                )
            }
        }
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
