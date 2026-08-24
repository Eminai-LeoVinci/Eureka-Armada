package org.valkyrienskies.eureka.bottle

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Clearable
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.BlockAttachedEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.crew.CrewMuster
import org.valkyrienskies.eureka.crew.CrewStations
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.pirate.PirateHelm
import org.valkyrienskies.eureka.template.PlacementCheck
import org.valkyrienskies.eureka.template.ShipTemplate
import org.valkyrienskies.mod.common.assembly.ShipAssembler as VSShipAssembler
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/**
 * A whole ship, carried in one hand.
 *
 * ## Why the bottle holds a name, not a ship
 * The hull lives where every other captured ship lives -- a `.nbt` under `<world>/generated`, written by
 * [ShipTemplate]. The item carries only the template's name and the ship's, because an item component is
 * synced to every client that can see the stack and a real ship is megabytes. A bottle in a chest in a loaded
 * chunk would otherwise be a permanent broadcast.
 *
 * The consequence worth knowing: a bottled ship is meaningful only in the world it was made in. That is the
 * right trade for a survival item, and it is the same trade blueprints make.
 *
 * ## Why the name comes with it, unlike a blueprint
 * Capture runs with `keepShipName = true`. A blueprint is a design and deliberately forgets which ship it was
 * taken from -- five hulls from one page must not share a name. A bottle is not a copy: the original ship
 * stops existing the moment it goes in, so there is exactly one vessel at any time and it should come back
 * under the name its captain gave it.
 */
object ShipBottle {

    private const val TEMPLATE_KEY = "vs_eureka:bottle_template"
    private const val SHIP_NAME_KEY = "vs_eureka:bottle_ship"
    private const val MARKED_HELM_KEY = "vs_eureka:marked_helm"

    // Mirrors the key ShipHelmBlockEntity.saveAdditional writes for its crew-station flag; read here off the
    // template's block NBT so the release can tell which of several wheels holds the articles.
    private const val CREW_STATION_KEY = "vs_eureka:crew_station"

    // The wheel's durable identity (ShipHelmBlockEntity.bottleBinding), written alongside the position.
    // The position alone was the whole bug: capture deletes the ship and release builds a new one on a new
    // chunk claim, so every OTHER bottle marked on the same wheel was left holding a shipyard address into
    // dead space -- "not loaded anywhere nearby" at best, some other ship's recycled chunk at worst.
    private const val BINDING_KEY = "vs_eureka:bottle_binding"

    /** Templates written by a bottle are named for nothing else, so a player cannot collide with one by hand. */
    private fun templateNameFor(id: UUID) = "bottled/${id.toString().replace("-", "")}"

    /**
     * Mark [stack] for the ship this wheel steers, without taking it yet.
     *
     * Marking and taking are two acts on purpose. A bottle that swallowed a ship the instant you touched its
     * wheel would give the player nothing to watch and no moment to change their mind; marked, it becomes a
     * thing you then *throw*, and the throw is where the ship goes.
     */
    fun mark(level: ServerLevel, player: ServerPlayer, helm: BlockPos, stack: ItemStack): Boolean {
        // Pirate gate, door 5 of 14: without this, a Ship Bottle is a free ship -- walk up, click, throw.
        if (PirateHelm.gated(level.getBlockState(helm))) {
            PirateHelm.deny(player)
            return false
        }
        val ship = level.getLoadedShipManagingPos(helm) as? LoadedServerShip ?: run {
            PathMessages.send(player, "That wheel is not part of an assembled ship.", PathMessages.Kind.WARN)
            return false
        }
        val helmEntity = level.getBlockEntity(helm) as? ShipHelmBlockEntity ?: run {
            PathMessages.send(player, "That wheel is broken -- it has no workings.", PathMessages.Kind.WARN)
            return false
        }
        val shipName = ship.slug ?: "unnamed ship"

        val tag = CompoundTag()
        tag.putString(SHIP_NAME_KEY, shipName)
        tag.putLong(MARKED_HELM_KEY, helm.asLong())
        // The identity that outlives the address. Every bottle ever marked on this wheel carries the same
        // binding, which is what lets a whole stack stay valid across any number of capture/release cycles.
        tag.putString(BINDING_KEY, helmEntity.mintBottleBinding().toString())

        // Mark ONE bottle, not the stack. Components belong to the stack, so writing the mark in place brands
        // every bottle the player is holding -- and throwing spends only one, leaving the rest apparently
        // marked for a ship that is about to stop existing. They read as duplicates of the bottle that was
        // just used, and their mark is a helm position that no longer resolves, so they cannot even be thrown.
        if (stack.count > 1) {
            val marked = stack.copyWithCount(1)
            marked.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
            stack.shrink(1)
            if (!player.inventory.add(marked)) player.drop(marked, false)
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }

        // GOOD, not PROMPT: the prompt channel is a single slot meant to be re-sent every tick while a key is
        // held, and it expires a quarter second after its last refresh. This is said once and needs to survive
        // long enough to walk away on.
        PathMessages.send(player, "Marked '$shipName' -- throw the bottle to take it.", PathMessages.Kind.GOOD)
        return true
    }

    /**
     * Where a marked wheel is right now, in world space, or null if its ship is not loaded.
     *
     * Everything else here works in the wheel's own coordinates, because that is what a click on a ship block
     * arrives as and what every ship lookup wants. A thrown bottle is the one thing that needs the other space:
     * the wheel of an assembled ship sits in the shipyard, millions of blocks from where the hull is drawn, and
     * a bottle aimed at the raw position would set off across the world and never arrive.
     *
     * Resolved fresh on every tick of the flight rather than once at the throw, which is also what lets the
     * bottle run down a ship that is still under sail.
     */
    fun helmWorldPos(level: ServerLevel, helm: BlockPos): Vec3? {
        val ship = level.getLoadedShipManagingPos(helm) ?: return null
        val centre = ship.shipToWorld.transformPosition(Vector3d(helm.x + 0.5, helm.y + 0.5, helm.z + 0.5))
        return Vec3(centre.x, centre.y, centre.z)
    }

    /** The wheel a marked bottle is pointed at, or null on an unmarked one. */
    fun markedHelm(stack: ItemStack): BlockPos? {
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val tag = data.copyTag()
        if (tag.getString(TEMPLATE_KEY).isPresent) return null // already full, not a mark
        return tag.getLong(MARKED_HELM_KEY).orElse(null)?.let { BlockPos.of(it) }
    }

    /** The binding a marked bottle carries, or null on an unmarked or pre-binding one. */
    private fun markedBinding(stack: ItemStack): UUID? {
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val tag = data.copyTag()
        if (tag.getString(TEMPLATE_KEY).isPresent) return null
        return tag.getString(BINDING_KEY).orElse(null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    /**
     * Where the marked wheel is RIGHT NOW -- the address a throw should actually fly at -- or null when it
     * cannot be answered. Null is honest: it means the wheel has not reported from anywhere reachable, not
     * that the bottle is broken, so callers should say "not loaded" and leave the mark alone.
     *
     * The stored position is only the wheel's address AT MARKING TIME, and a capture/release cycle re-homes
     * the wheel to a fresh shipyard address. So resolution goes identity-first: the binding names the wheel,
     * [BottleBindings] says where it last reported from, and the block entity there is re-checked for the
     * same binding because positions outlive the things at them -- a registry answer pointing at a mined
     * wheel, or at a chunk some other ship now claims, must read as "nowhere" rather than as a target.
     *
     * A pre-binding bottle falls back to its stored position, and when that still points at a real wheel the
     * bottle is quietly upgraded in place -- marked stacks from before this existed heal on their next throw.
     * A pre-binding bottle whose position has already died stays dead (one re-mark is its migration).
     */
    fun resolveMarkedHelm(level: ServerLevel, stack: ItemStack): BlockPos? {
        val binding = markedBinding(stack)
        if (binding != null) {
            val pos = BottleBindings.find(level, binding) ?: return null
            val wheel = level.getBlockEntity(pos) as? ShipHelmBlockEntity ?: return null
            return if (wheel.bottleBinding == binding) pos else null
        }

        // Legacy mark: position only. Deliberately requires the wheel itself, not just a ship owning the
        // chunk -- the old chunk-claim lookup is exactly what sent bottles after recycled claims.
        val stored = markedHelm(stack) ?: return null
        val wheel = level.getBlockEntity(stored) as? ShipHelmBlockEntity ?: return null
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null
        tag.putString(BINDING_KEY, wheel.mintBottleBinding().toString())
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        return stored
    }

    /**
     * Take the ship this wheel steers and hand back the bottle holding it, or null if it could not be taken.
     *
     * @return the Bottled Ship stack. The caller decides where it goes -- a thrown bottle drops it, and
     * anything taking a ship by hand would put it straight in the inventory.
     */
    // Order is not negotiable: the template must be safely on disk before the hull is destroyed. A capture
    // that failed after deletion would trade somebody's ship for an empty bottle.
    fun take(level: ServerLevel, player: ServerPlayer, helm: BlockPos): ItemStack? {
        val helmEntity = level.getBlockEntity(helm) as? ShipHelmBlockEntity ?: return null

        // Pirate gate, door 6 of 14 -- the DEFERRED door. A bottle marked while this ship was still honest
        // resolves its wheel all the way to the taking, so the gate at mark() alone would leave a pre-marked
        // bottle as a free conquest. Null here is the flight's own "could not take" answer: the thrown
        // bottle keeps its empty self and flies home.
        if (PirateHelm.gated(level.getBlockState(helm))) {
            PirateHelm.deny(player)
            return null
        }

        // A wheel sitting in the world steers nothing -- only an assembled hull can be bottled. Resolved here
        // rather than at the call site because VS2's ship lookups are Kotlin extensions that only resolve in
        // the common module.
        val ship = level.getLoadedShipManagingPos(helm) as? LoadedServerShip ?: run {
            PathMessages.send(player, "That wheel is not part of an assembled ship.", PathMessages.Kind.WARN)
            return null
        }
        val shipName = ship.slug ?: "unnamed ship"

        val templateName = templateNameFor(UUID.randomUUID())
        when (val outcome = ShipTemplate.capture(level, ship, templateName, keepShipName = true)) {
            is ShipTemplate.Failed -> {
                PathMessages.send(player, outcome.message, PathMessages.Kind.ERROR)
                return null
            }
            is ShipTemplate.Captured -> Unit
            else -> return null
        }

        // Empty every container before the hull goes, or their contents rain onto the ground as items -- the
        // coal in each engine, the cargo in each chest. It is already safe in the template's block-entity NBT,
        // so this is emptying a copy, not throwing anything away. deleteShip's dropBlocks flag governs the
        // BLOCKS; what is inside them is a separate question and nothing else asks it.
        ship.shipAABB?.let { hull ->
            val cursor = BlockPos.MutableBlockPos()
            for (x in hull.minX()..hull.maxX()) {
                for (y in hull.minY()..hull.maxY()) {
                    for (z in hull.minZ()..hull.maxZ()) {
                        cursor.set(x, y, z)
                        (level.getBlockEntity(cursor) as? Clearable)?.clearContent()
                    }
                }
            }
        }

        // The same for anything hanging on the hull. Item frames and paintings are ENTITIES, so deleting the
        // ship's blocks does not delete them -- it pulls the wall out from behind them, and on the next tick
        // each one notices it has nothing to hang on, breaks, and drops itself plus whatever it held. They are
        // already safe in the template (captureEntities takes them), so that is pure duplication: the frames
        // come back with the ship and a copy of every one is left floating where it used to be.
        //
        // discard() rather than kill()/remove-with-drops: the original is being replaced by the captured copy,
        // not destroyed, so it should leave nothing behind at all.
        ship.worldAABB.let { hull ->
            // A little slack, because a frame hangs on the OUTSIDE face of the block it is attached to and so
            // sits fractionally beyond the hull's own box.
            val box = AABB(
                hull.minX() - 1.0, hull.minY() - 1.0, hull.minZ() - 1.0,
                hull.maxX() + 1.0, hull.maxY() + 1.0, hull.maxZ() + 1.0
            )
            for (hanging in level.getEntitiesOfClass(BlockAttachedEntity::class.java, box)) {
                hanging.discard()
            }
        }

        // The crew go into the articles before the deck goes away, so muster can put them back on release.
        // Their villagers are world-space entities standing (or seated) on a hull that is about to stop
        // existing; standing them down snapshots each one into the CrewLedger rather than leaving them to fall.
        //
        // The crews are read off the CREW STATION, not the marked wheel. The bottle is thrown at whichever
        // helm it was marked on, and on a hull with several wheels that is usually NOT the one holding the
        // articles -- the old gate read the marked wheel's NAME and skipped the entire stand-down whenever it
        // was blank or wrong, which is how a whole crew once went over the side. The marked wheel's own
        // bindings are unioned in as the fallback for a ship with no recorded station.
        val station = CrewStations.stationOf(level, ship)
        val bottleCrewIds = LinkedHashSet<UUID>().apply {
            station?.let { addAll(it.crewBindings().values) }
            (level.getBlockEntity(helm) as? ShipHelmBlockEntity)?.let { addAll(it.crewBindings().values) }
        }
        // Where everybody is standing, taken before they are taken off: a bottled crew comes back out of the
        // bottle onto the same spots, so a ship that was a shop is still a shop when it is poured out.
        val posts = CrewMuster.postsOf(level, ship, station?.blockPos ?: helm)
        val crewReport =
            CrewMuster.standDownShip(level, ship.id, ship.worldAABB, bottleCrewIds, posts)

        // Only now that the ship exists in writing does the original stop existing -- and it must actually stop.
        // disassemble() hands the hull back to the WORLD, which would leave the ship standing there as well as
        // sitting in the bottle: one ship in, two ships out. deleteShip removes it and its blocks outright, and
        // dropBlocks = false because the materials left with the template, not onto the seabed.
        val shipId = ship.id
        val deck = ship.worldAABB.let {
            AABB(
                it.minX() - 2.0, it.minY() - 2.0, it.minZ() - 2.0,
                it.maxX() + 2.0, it.maxY() + 2.0, it.maxZ() + 2.0
            )
        }

        VSShipAssembler.deleteShip(level, ship, true, false)

        // Let go of anyone the ship was carrying, the instant it stops existing.
        //
        // VS2 keeps carrying an entity for TICKS_TO_DRAG_ENTITIES after it last stood on a hull, which is right
        // for stepping off a deck and wrong for a deck that has been put in a bottle: there is no longer a ship
        // to be carried BY. Left alone the carry ran on for its full count with the hull's last movement still
        // applied, so a captain standing on their own ship as they took it was towed for a beat by a vessel that
        // was already in their hand -- and kept being towed after they landed, ashore, on solid ground.
        //
        // fallDistance goes too. The carry holds an entity up while gravity keeps counting, so the tick the hold
        // expired cashed in a fall that never happened -- half a heart bar for standing still. Whatever fall
        // follows the capture should be measured from here.
        //
        // A ship taken by a bottle is meant to drop whoever was aboard, and this is what makes it drop them
        // NOW rather than a second and a half later.
        for (entity in level.getEntities(null as Entity?, deck)) {
            val dragging = (entity as? IEntityDraggingInformationProvider)?.draggingInformation ?: continue
            if (dragging.lastShipStoodOn != shipId) continue
            dragging.lastShipStoodOn = null
            dragging.addedMovementLastTick = Vector3d()
            dragging.addedYawRotLastTick = 0.0
            entity.fallDistance = 0.0
        }

        val bottled = bottleOf(templateName, shipName)
        PathMessages.send(player, "'$shipName' is in the bottle.", PathMessages.Kind.GOOD)
        // The crew's fate is said out loud, not just logged. Silence here once meant eighty villagers falling
        // out of the sky with nobody the wiser until the death messages started; a captain who bottles a
        // crewed ship should see the articles close over them -- and a count of zero with crew aboard is the
        // one outcome that must never again pass without a word.
        if (crewReport.stood > 0) {
            val crew = if (crewReport.stood == 1) "crew member" else "crew"
            PathMessages.send(player, "${crewReport.stood} $crew stood down into the articles.", PathMessages.Kind.GOOD)
        } else if (crewReport.berthedAboard > 0) {
            PathMessages.send(
                player,
                "Crew were aboard but none could be stood down -- check the server log.",
                PathMessages.Kind.WARN
            )
        }
        return bottled
    }

    /**
     * Let the ship out onto open water, keel at the waterline and centred on where the player aimed.
     *
     * The surface is found by walking up from [water] until the fluid stops, never assumed: lakes sit at
     * whatever height the terrain gave them, and a hardcoded sea level would strand a ship in the air over one
     * and bury it in another. Terrain Diffusion makes that worse, not better.
     *
     * Nothing here worries about the ship assembling the sea along with itself. The hull that gets assembled is
     * the exact block list the template describes, so water is not on it -- the keel can rest in as much of it
     * as it likes.
     */
    fun releaseOnWater(level: ServerLevel, player: ServerPlayer, stack: ItemStack, water: BlockPos): Boolean {
        val templateName = templateOf(stack) ?: return false
        val template = ShipTemplate.find(level, templateName) ?: run {
            PathMessages.send(player, "That bottle is empty -- its ship is missing.", PathMessages.Kind.ERROR)
            return false
        }
        val size = template.size

        var surface = water.y
        val probe = BlockPos.MutableBlockPos()
        while (surface < level.maxY) {
            probe.set(water.x, surface + 1, water.z)
            if (level.getFluidState(probe).isEmpty) break
            surface++
        }

        // Centred on the aim rather than cornered on it: a ship set down at sea should appear where the player
        // was looking, not offset by its own beam.
        val corner = BlockPos(water.x - size.x / 2, surface, water.z - size.z / 2)
        return release(level, player, stack, corner)
    }

    /**
     * Let the ship out onto dry land, keel resting on [ground] and centred on it.
     *
     * Centred rather than cornered because the bottle was *thrown* there: a ship should appear around the spot
     * it landed on, not hanging off one corner of it.
     */
    fun releaseOnGround(level: ServerLevel, player: ServerPlayer, stack: ItemStack, ground: BlockPos): Boolean {
        val templateName = templateOf(stack) ?: return false
        val template = ShipTemplate.find(level, templateName) ?: run {
            PathMessages.send(player, "That bottle is empty -- its ship is missing.", PathMessages.Kind.ERROR)
            return false
        }
        val size = template.size
        return release(level, player, stack, BlockPos(ground.x - size.x / 2, ground.y, ground.z - size.z / 2))
    }

    /**
     * Let the ship in [stack] out, with its keel resting at [corner].
     *
     * Refuses before writing anything if the hull will not fit -- the bottle stays in hand and the player keeps
     * their ship. That check is the whole reason [PlacementCheck] exists.
     */
    fun release(level: ServerLevel, player: ServerPlayer, stack: ItemStack, corner: BlockPos): Boolean {
        val templateName = templateOf(stack) ?: return false
        val template = ShipTemplate.find(level, templateName) ?: run {
            PathMessages.send(player, "That bottle is empty -- its ship is missing.", PathMessages.Kind.ERROR)
            return false
        }

        when (val fit = PlacementCheck.test(level, template, corner)) {
            is PlacementCheck.Blocked -> {
                PathMessages.send(
                    player,
                    "There is no room here -- ${fit.by} is in the way.",
                    PathMessages.Kind.WARN
                )
                return false
            }
            is PlacementCheck.OutOfWorld -> {
                PathMessages.send(player, "There is no room here -- not enough sky.", PathMessages.Kind.WARN)
                return false
            }
            is PlacementCheck.Fits -> Unit
        }

        // Hand over the exact blocks rather than letting the wheel rediscover them. A released hull is usually
        // resting ON something, and the flood-fill cannot tell a deck from the roof it is sitting on -- stripped
        // logs and smooth sandstone are not terrain-tagged, so it walks into the building and the assembly dies
        // on maxShipBlocks. We placed these blocks; there is nothing to discover.
        val placed = HashSet<BlockPos>()
        for (palette in template.palettes) {
            for (info in palette.blocks()) {
                if (info.state.isAir) continue
                placed.add(BlockPos(corner.x + info.pos.x, corner.y + info.pos.y, corner.z + info.pos.z))
            }
        }

        // Remember the sea we are about to build into. Assembly relocates the hull to the shipyard and leaves
        // air behind it, with neighbour updates suppressed for speed -- so nothing tells the surrounding water
        // to flow back, and a ship launched into the ocean leaves a ship-shaped hole in it.
        val flooded = HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>()
        for (pos in placed) {
            val existing = level.getBlockState(pos)
            if (!existing.fluidState.isEmpty) flooded[pos] = existing
        }

        if (ShipTemplate.place(level, templateName, corner) !is ShipTemplate.Placed) {
            PathMessages.send(player, "The ship would not come out of the bottle.", PathMessages.Kind.ERROR)
            return false
        }

        // Loose blocks are not a ship. Hand them to the wheel's own assemble, which is what sets up control,
        // counts floaters, arms the world-freeze and musters the crew -- reimplementing any of that here would
        // be a second copy destined to drift from the first.
        val helm = helmIn(level, template, corner)
        val helmEntity = helm?.let { level.getBlockEntity(it) as? ShipHelmBlockEntity }
        val shipName = shipNameOf(stack) ?: "The ship"
        if (helmEntity == null) {
            PathMessages.send(
                player,
                "$shipName is out, but its wheel is missing -- place one to sail it.",
                PathMessages.Kind.WARN
            )
        } else {
            // holdEntities = false: see ShipHelmBlockEntity.assemble. The fall-through hold exists to catch
            // people standing on a deck that is briefly not collidable, and a hull coming out of a bottle has
            // nobody standing on it -- so all the freeze can do here is grab whoever is passing overhead, which
            // on a thrown bottle is very often the person who threw it, mid-elytra.
            // Her name goes back on the wheel BEFORE she is built, and Keep Name goes on with it.
            //
            // A bottle is the one case where the name is not a preference -- it is what was captured, it is
            // printed on the bottle, and the message the captain is about to read says it out loud. Leaving
            // it to the wheel's own switch meant a hull came out under a freshly invented slug while the
            // bottle in the player's hand still said otherwise; and WHICH wheel gets asked is decided by
            // helmIn's scoring rather than by the captain, so its switch is not theirs in any real sense.
            //
            // Stamped on the wheel rather than applied to the ship afterwards, so it travels the same road
            // every other assembly name takes -- HelmNames.applyShipName's verify-loop included, which is
            // what makes a name survive a hull vs-core is still building.
            shipNameOf(stack)?.takeIf { it.isNotBlank() }?.let { captured ->
                helmEntity.setHelmName(Component.literal(captured.replace('-', ' ')))
                helmEntity.setKeepName(true)
            }
            helmEntity.assemble(player, placed, holdEntities = false)
            // Said out loud on purpose: a hull resting on the ground looks exactly like a pile of blocks, so
            // "it came out" and "it came out as a ship" are indistinguishable without being told which.
            PathMessages.send(player, "$shipName is afloat again.", PathMessages.Kind.GOOD)
        }

        // Put the sea back. The hull has moved to the shipyard by now, so every one of these is air again, and
        // UPDATE_ALL is deliberate -- these need the neighbour updates the relocation suppressed, or the water
        // sits in a grid of disconnected source blocks instead of settling.
        for ((pos, fluid) in flooded) {
            if (level.getBlockState(pos).isAir) level.setBlock(pos, fluid, Block.UPDATE_ALL)
        }

        // One bottle, one ship. The empty does not come back: a reusable bottle would make a ship freely
        // portable forever, and the whole point of a bottle is that using it spends something.
        stack.shrink(1)

        ShipTemplate.forget(level, templateName)
        return true
    }

    /**
     * Where the wheel landed, in world coordinates, so the release can hand it its own assembly.
     *
     * Not just ANY wheel. Whichever helm this returns is the one `assemble` runs from, and assembling is a
     * claim: that wheel becomes the crew station, and its name is what the muster looks the crew up under. A
     * hull can carry several wheels, and palette order is build order, not importance -- first-found once
     * handed a release to a blank spare, which mustered nobody (no name to look up) and re-dealt every gun
     * label from the wrong bow. So the wheel already holding the articles wins, a named wheel beats a blank
     * one, and only a hull with neither falls back to first-found.
     */
    private fun helmIn(level: ServerLevel, template: net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate, corner: BlockPos): BlockPos? {
        var best: BlockPos? = null
        var bestScore = -1
        for (palette in template.palettes) {
            for (info in palette.blocks()) {
                if (info.state.block !is ShipHelmBlock) continue
                val at = BlockPos(corner.x + info.pos.x, corner.y + info.pos.y, corner.z + info.pos.z)
                if (level.getBlockState(at).block !is ShipHelmBlock) continue
                var score = 0
                if (info.nbt?.getBooleanOr(CREW_STATION_KEY, false) == true) score += 2
                if (info.nbt?.contains("CustomName") == true) score += 1
                if (score > bestScore) {
                    bestScore = score
                    best = at
                }
            }
        }
        return best
    }

    /**
     * A Bottled Ship holding [templateName], labelled [shipName].
     *
     * Exists so that anything which can produce a ship -- a shipwright taking delivery, and in time a pirate
     * hold or a harbor's stock -- writes the same three keys in the same way. The tag layout is this object's
     * business and nowhere else's; a second place assembling it by hand is a second place to get it subtly
     * wrong, and the failure would look like an empty bottle rather than a mistake.
     *
     * The caller owns the template. Whatever is named here will be **forgotten** when the ship comes out, so
     * hand this a template nothing else depends on.
     */
    /**
     * Rub out a mark whose wheel is gone, so a returning bottle comes back usable.
     *
     * A capture that never happened hands the bottle back on purpose -- a hull that will not fit must never
     * cost somebody their ship. But it was handing it back still pointed at a wheel that had since stopped
     * existing, which is worse than an empty bottle: it cannot be thrown at all ("not loaded anywhere
     * nearby") and gives no hint that the fix is to mark something again. An unmarked bottle at least says
     * what to do with itself.
     */
    fun forgetDeadMark(level: ServerLevel, stack: ItemStack) {
        // A bound mark is never wiped. "Unresolvable right now" is not "dead": after a restart the wheel's
        // chunk may simply not have loaded and reported yet, and wiping the mark would throw away an
        // identity that was about to start working again. Only the old position-only marks -- which have no
        // way back once their address dies -- still get the courtesy of being cleared.
        if (markedBinding(stack) != null) return
        val helm = markedHelm(stack) ?: return
        if (helmWorldPos(level, helm) != null) return
        stack.remove(DataComponents.CUSTOM_DATA)
    }

    /**
     * Spend one bottle out of the hand, in a way creative mode cannot undo.
     *
     * `ServerPlayerGameMode.useItemOn` reads the count before the interaction and, for a player with infinite
     * materials, calls `setCount` afterwards to put it back -- so shrinking the stack in place is silently
     * reverted while the bottle in the air, a separate copy, goes on doing its job. A creative captain kept
     * the marker after a capture, and kept a bottled ship that would release the same hull again for free.
     *
     * The restore writes to the stack OBJECT it captured, not to whatever the slot holds by then, so putting
     * a different object in the slot leaves it correcting one nothing else can see. Deferring the shrink to
     * the server's task queue does not work: on the server thread `execute` runs the task inline, still
     * inside the interaction.
     */
    fun spendOne(player: ServerPlayer, hand: InteractionHand, stack: ItemStack) {
        val left = stack.count - 1
        player.setItemInHand(hand, if (left > 0) stack.copyWithCount(left) else ItemStack.EMPTY)
    }

    fun bottleOf(templateName: String, shipName: String): ItemStack {
        val bottled = ItemStack(EurekaItems.BOTTLED_SHIP.get())
        val tag = CompoundTag()
        tag.putString(TEMPLATE_KEY, templateName)
        tag.putString(SHIP_NAME_KEY, shipName)
        bottled.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        return bottled
    }

    fun templateOf(stack: ItemStack): String? {
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        return data.copyTag().getString(TEMPLATE_KEY).orElse(null)?.takeIf { it.isNotEmpty() }
    }

    /** The ship's name, for the tooltip. Null on an empty bottle. */
    fun shipNameOf(stack: ItemStack): String? {
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        return data.copyTag().getString(SHIP_NAME_KEY).orElse(null)?.takeIf { it.isNotEmpty() }
    }

    /** The block flag placement uses, kept here so release and the debug command cannot drift apart. */
    const val PLACE_FLAGS = Block.UPDATE_CLIENTS

    /**
     * How far a marked ship may be when the bottle is thrown.
     *
     * Checked at the throw and nowhere else. Once a bottle is in the air its chase accelerates without limit up
     * to [ThrownShipBottle.MAX_CHASE], so the range is not a thing the ship can escape by running -- it is a
     * statement about how far a captain can reach, and it wants to be answered before the bottle leaves the
     * hand rather than after it has flown off over the horizon.
     */
    const val CAPTURE_RANGE = 160.0
}
