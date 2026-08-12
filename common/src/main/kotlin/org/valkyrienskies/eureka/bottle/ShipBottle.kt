package org.valkyrienskies.eureka.bottle

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Clearable
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.Block
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.crew.CrewMuster
import org.valkyrienskies.eureka.crew.HelmNames
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.template.PlacementCheck
import org.valkyrienskies.eureka.template.ShipTemplate
import org.valkyrienskies.mod.common.assembly.ShipAssembler as VSShipAssembler
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

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

    /** Templates written by a bottle are named for nothing else, so a player cannot collide with one by hand. */
    private fun templateNameFor(id: UUID) = "bottled/${id.toString().replace("-", "")}"

    /**
     * Take [ship] into the empty bottle [stack] is holding.
     *
     * Order matters and is not negotiable: the template must be safely on disk before the hull is destroyed.
     * A capture that fails after disassembly would delete somebody's ship in exchange for an empty bottle.
     */
    fun fill(level: ServerLevel, player: ServerPlayer, helm: BlockPos, stack: ItemStack): Boolean {
        val helmEntity = level.getBlockEntity(helm) as? ShipHelmBlockEntity ?: return false

        // A wheel sitting in the world steers nothing -- only an assembled hull can be bottled. Resolved here
        // rather than at the call site because VS2's ship lookups are Kotlin extensions that only resolve in
        // the common module.
        val ship = level.getLoadedShipManagingPos(helm) as? LoadedServerShip ?: run {
            PathMessages.send(player, "That wheel is not part of an assembled ship.", PathMessages.Kind.WARN)
            return false
        }
        val shipName = ship.slug ?: "unnamed ship"

        val templateName = templateNameFor(UUID.randomUUID())
        when (val outcome = ShipTemplate.capture(level, ship, templateName, keepShipName = true)) {
            is ShipTemplate.Failed -> {
                PathMessages.send(player, outcome.message, PathMessages.Kind.ERROR)
                return false
            }
            is ShipTemplate.Captured -> Unit
            else -> return false
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

        // The crew go into the articles before the deck goes away, so muster can put them back on release.
        // Their villagers are world-space entities standing on a hull that is about to stop existing; standing
        // them down snapshots each one into the CrewLedger rather than leaving them to fall into the sea.
        val crewName = helmEntity.customName?.string
        if (crewName != null) {
            CrewMuster.standDown(level, ship.worldAABB, crewName, HelmNames.variantOf(level.getBlockState(helm)))
        }

        // Only now that the ship exists in writing does the original stop existing -- and it must actually stop.
        // disassemble() hands the hull back to the WORLD, which would leave the ship standing there as well as
        // sitting in the bottle: one ship in, two ships out. deleteShip removes it and its blocks outright, and
        // dropBlocks = false because the materials left with the template, not onto the seabed.
        VSShipAssembler.deleteShip(level, ship, true, false)

        val bottled = ItemStack(EurekaItems.BOTTLED_SHIP.get())
        val tag = CompoundTag()
        tag.putString(TEMPLATE_KEY, templateName)
        tag.putString(SHIP_NAME_KEY, shipName)
        bottled.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))

        stack.shrink(1)
        if (!player.inventory.add(bottled)) player.drop(bottled, false)

        PathMessages.send(player, "'$shipName' is in the bottle.", PathMessages.Kind.GOOD)
        return true
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
            helmEntity.assemble(player, placed)
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

    /** Where the wheel landed, in world coordinates, so the release can hand it its own assembly. */
    private fun helmIn(level: ServerLevel, template: net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate, corner: BlockPos): BlockPos? {
        for (palette in template.palettes) {
            for (info in palette.blocks()) {
                if (info.state.block !is ShipHelmBlock) continue
                val at = BlockPos(corner.x + info.pos.x, corner.y + info.pos.y, corner.z + info.pos.z)
                if (level.getBlockState(at).block is ShipHelmBlock) return at
            }
        }
        return null
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
}
