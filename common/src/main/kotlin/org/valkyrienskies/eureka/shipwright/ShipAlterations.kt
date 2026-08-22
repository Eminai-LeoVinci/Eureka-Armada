package org.valkyrienskies.eureka.shipwright

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.valkyrienskies.eureka.template.BillOfMaterials
import org.valkyrienskies.eureka.template.ShipTemplate

/**
 * Applying a captain's alteration to a template, so the ship that goes up is the ship they were quoted for.
 *
 * ## Never to the original
 * `ShipTemplate.place` hands out the structure manager's CACHED template, and a template is immutable once
 * written -- which is the only reason every copy of a blueprint page can share one. Rewriting the original
 * would silently change every page in the world that points at it, and the symptom would surface on
 * somebody else's ship. So the caller copies first ([ShipTemplate.copy]) and rewrites the copy, exactly the
 * way `ShipTemplate.civilianize` does for a pirate hull.
 *
 * ## Exclusion is air, not deletion
 * An excluded block becomes an AIR entry rather than being removed from the palette. Removing entries
 * renumbers the list under the loop that is walking it, and the berth is already proven clear -- the
 * shipwright will not lay a keel until `fitsClear` says the water is empty -- so writing air there does
 * exactly what omitting it would, and says what it means when somebody reads the file back.
 *
 * ## Both ends of a double block
 * The upper half of a door and the head of a bed have no item and therefore no verdict of their own: they
 * follow their partner, because that is the rule the bill was counted by. Exclude a door and only the lower
 * half is recognised, so the top would be left standing in mid-air with nothing under it. The partner pass
 * is what stops that, and it is the same geometry `ShipRepair.partnerStands` walks, read the other way.
 */
object ShipAlterations {

    /**
     * Rewrite [templateName] in place according to [alteration], and save it. Returns whether it worked.
     *
     * The template named here must be a COPY that nothing else refers to. Handing this the original is the
     * one mistake this class cannot defend against.
     */
    fun rewrite(
        level: ServerLevel,
        templateName: String,
        alteration: Alteration,
        deliveries: Map<Item, List<ShipwrightLedger.Plans.ItemRun>> = emptyMap()
    ): Boolean {
        if (alteration.isEmpty) return true
        val template = ShipTemplate.find(level, templateName) ?: return false

        for (palette in template.palettes) {
            val blocks = palette.blocks()

            // Pass one: strike out what the captain left off the plans.
            val aired = HashSet<BlockPos>()
            for (i in blocks.indices) {
                val info = blocks[i]
                val item = BillOfMaterials.itemFor(info.state) ?: continue
                if (!alteration.isExcluded(item)) continue
                aired.add(info.pos)
                blocks[i] = StructureTemplate.StructureBlockInfo(info.pos, AIR, null)
            }
            if (aired.isEmpty()) continue

            // Pass two: the halves that were never charged for, and so were never recognised above. A door
            // whose foot has gone has no business keeping its head.
            for (i in blocks.indices) {
                val info = blocks[i]
                if (info.pos in aired) continue
                if (!orphaned(info, aired)) continue
                blocks[i] = StructureTemplate.StructureBlockInfo(info.pos, AIR, null)
            }
        }

        substitute(template, alteration, deliveries)

        // Fixtures are ENTITIES, not blocks -- a painting is no more part of the palette than a boat is.
        // They are dropped by kind rather than by the bill, because the bill never counted them.
        if (alteration.excludedCategories.isNotEmpty()) {
            template.entityInfoList.removeIf { entity ->
                val id = entity.nbt.getStringOr("id", "")
                id in FIXTURE_IDS &&
                    MaterialFamilies.Category.DECOR in alteration.excludedCategories
            }
        }

        return ShipTemplate.save(level, templateName)
    }

    /**
     * Build the swapped rows out of what the captain actually handed in.
     *
     * ## Fixed swaps are a straight exchange
     * Every birch slab becomes an oak slab, wherever it stands. Nothing about the ship changes but the
     * material, which is the whole promise of the feature.
     *
     * ## "Any" is built keel-up, in the order it arrived
     * A row set to Any is paid with a mixture, and something has to decide which slab lands where. The
     * captain's own delivery order does: hand over thirty stone slabs and then thirty oak, and the stone
     * goes in first -- lowest layer, bow to stern, port to starboard -- so the hull is banded the way the
     * materials came off the cart rather than shuffled at random.
     *
     * That order is [keelUp], which is the same comparator a repair mends in, so a hull built this way and
     * a hull mended later agree about what "first" means.
     */
    private fun substitute(
        template: StructureTemplate,
        alteration: Alteration,
        deliveries: Map<Item, List<ShipwrightLedger.Plans.ItemRun>>
    ) {
        if (alteration.swaps.isEmpty()) return

        // One cursor per ANY row, walked down as positions claim their material.
        val queues = HashMap<Item, ArrayDeque<ShipwrightLedger.Plans.ItemRun>>()
        for ((key, runs) in deliveries) {
            queues[key] = ArrayDeque(runs.map { ShipwrightLedger.Plans.ItemRun(it.item, it.count) })
        }

        for (palette in template.palettes) {
            val blocks = palette.blocks()
            // Keel-up, so an ANY row is laid in the order its materials arrived rather than in whatever
            // order the palette happens to hold. A template has no ship to ask which way is forward, and
            // its own axes ARE the shipyard's, so the comparator runs on the local positions directly.
            val order = blocks.indices.sortedWith(
                compareBy<Int> { blocks[it].pos.y }
                    .thenByDescending { blocks[it].pos.z }
                    .thenBy { blocks[it].pos.x }
            )

            for (i in order) {
                val info = blocks[i]
                val original = BillOfMaterials.itemFor(info.state) ?: continue
                val chosen = when (val swap = alteration.swaps[original]) {
                    null -> continue
                    is Alteration.Fixed -> swap.item
                    is Alteration.Any -> next(queues[swap.representative]) ?: swap.representative
                }
                if (chosen == original) continue
                val restated = restate(info.state, chosen) ?: continue
                // NBT is deliberately dropped: a chest's contents must not ride onto a barrel, and a
                // sign's text must not ride onto a different sign. A swap keeps the geometry, not what
                // was inside it -- placeInWorld makes a fresh block entity where one is needed.
                blocks[i] = StructureTemplate.StructureBlockInfo(info.pos, restated, null)
            }
        }
    }

    /** The next item off an ANY row's queue, or null once the captain's deliveries run out. */
    private fun next(queue: ArrayDeque<ShipwrightLedger.Plans.ItemRun>?): Item? {
        while (queue != null && queue.isNotEmpty()) {
            val run = queue.first()
            if (run.count <= 0) {
                queue.removeFirst()
                continue
            }
            run.count--
            return run.item
        }
        return null
    }

    /**
     * The state [target] would stand in for [original], carrying across every property they share.
     *
     * Generic on purpose -- no table of block types, no special case for stairs. A birch top slab that is
     * waterlogged becomes a stone top slab that is waterlogged; a north-facing stair keeps its facing, its
     * half and its shape. Anything the new block does not know about is dropped, and anything it knows that
     * the old one did not keeps its default.
     *
     * This is where a swap most visibly goes wrong if it goes wrong at all: get it backwards and a ship
     * comes out with its stairs upside down.
     */
    fun restate(original: BlockState, target: Item): BlockState? {
        val block = (target as? BlockItem)?.block ?: return null
        var state = block.defaultBlockState()
        for (property in original.properties) {
            state = carry(original, state, property, block)
        }
        return state
    }

    @Suppress("UNCHECKED_CAST")
    private fun carry(from: BlockState, to: BlockState, property: Property<*>, block: Block): BlockState {
        val mine = block.stateDefinition.getProperty(property.name) ?: return to
        if (mine.valueClass != property.valueClass) return to
        val target = mine as Property<Comparable<Any>>
        val value = from.getValue(property) as Comparable<Any>
        if (!target.possibleValues.contains(value)) return to
        return to.setValue(target, value)
    }

    /** Is this the far half of a double block whose near half was just struck out? */
    private fun orphaned(info: StructureTemplate.StructureBlockInfo, aired: Set<BlockPos>): Boolean {
        val state = info.state
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) &&
            state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
        ) {
            return info.pos.below() in aired
        }
        if (state.hasProperty(BlockStateProperties.BED_PART) &&
            state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD &&
            state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
        ) {
            val facing: Direction = state.getValue(BlockStateProperties.HORIZONTAL_FACING)
            return info.pos.relative(facing.opposite) in aired
        }
        return false
    }

    private val AIR = Blocks.AIR.defaultBlockState()

    /** The fixtures that hang on a ship without ever appearing on its bill. */
    private val FIXTURE_IDS = setOf(
        "minecraft:painting", "minecraft:item_frame", "minecraft:glow_item_frame"
    )
}
