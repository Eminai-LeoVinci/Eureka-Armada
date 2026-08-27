package org.valkyrienskies.eureka.shipwright

import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Clearable
import net.minecraft.world.Container
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.BlockAttachedEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.crew.CrewMuster
import org.valkyrienskies.eureka.crew.CrewStations
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.template.BillOfMaterials
import org.valkyrienskies.mod.common.assembly.ShipAssembler as VSShipAssembler
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/**
 * Taking a ship apart, and writing down what came off it.
 *
 * ## Why a survey, and not a pile of items
 * A ship the size of a barn is tens of thousands of blocks. Handing them over as entities would be a lag
 * spike and a scattered mess on the seabed; handing them over as inventory needs an inventory several
 * hundred slots deep. So the hull is **counted**, the count goes in the ledger, and the captain draws it
 * down at their own pace from the shipwright's book. Nothing in this feature ever puts items on the
 * ground -- the same rule the rest of the shipwright keeps.
 *
 * ## The tally is written before the ship is deleted
 * `deleteShip` cannot be undone and leaves nothing behind. If the survey were taken afterwards, or
 * committed afterwards, any failure in between would destroy a ship and hand back nothing. So: survey,
 * commit, and only then delete.
 *
 * ## Honest limits
 * - The hull is costed through [BillOfMaterials.itemFor], the same rule that prices a blueprint. That makes
 *   what you get back agree with what the plans said the ship cost -- including where that rule
 *   under-counts, as a double slab does. Agreement with the plans is worth more here than being right in
 *   isolation, because the two numbers are read side by side.
 * - A stack carrying components a count cannot hold -- damaged, enchanted, renamed, or a shulker box with
 *   something in it -- is kept **whole**, in [Survey.keepsakes], and never reduced to a number. A
 *   `Map<Item, Int>` cannot hold an enchanted pickaxe, and a dismantle that quietly ate one while reporting
 *   success would be the worst bug in this mod.
 */
object ShipSalvage {

    /** What came off a ship: the hull it was built from, the cargo it carried, and what could not be counted. */
    class Survey(
        val shipName: String,
        val hull: MutableMap<Item, Int> = LinkedHashMap(),
        val cargo: MutableMap<Item, Int> = LinkedHashMap(),
        val keepsakes: MutableList<ItemStack> = ArrayList()
    ) {
        val empty: Boolean get() = hull.isEmpty() && cargo.isEmpty() && keepsakes.isEmpty()
    }

    private fun add(into: MutableMap<Item, Int>, item: Item, count: Int) {
        if (count <= 0) return
        into[item] = (into[item] ?: 0) + count
    }

    /**
     * A line saying what makes this stack worth keeping whole.
     *
     * Read here, on the server, because it is the components that answer it -- and sent as a finished
     * string rather than as the stack, since encoding an enchanted tool onto the wire needs a
     * registry-aware buffer the shelf payload does not have.
     */
    fun describe(stack: ItemStack): String {
        val packed = stack.get(DataComponents.CONTAINER)
        if (packed != null) {
            val inside = LinkedHashMap<Item, Int>()
            for (held in packed.stream()) {
                if (held.isEmpty) continue
                inside[held.item] = (inside[held.item] ?: 0) + held.count
            }
            if (inside.isNotEmpty()) {
                val sorted = inside.entries.sortedByDescending { it.value }
                val lead = sorted.first()
                val name = ItemStack(lead.key).hoverName.string
                val count = String.format("%,d", lead.value)
                return if (sorted.size == 1) "$name x$count"
                else "$name x$count  +${sorted.size - 1} more"
            }
        }
        val notes = ArrayList<String>()
        if (stack.isEnchanted) notes.add("enchanted")
        if (stack.isDamaged) notes.add("damaged")
        if (stack.get(DataComponents.CUSTOM_NAME) != null) notes.add("named")
        return if (notes.isEmpty()) "kept whole" else notes.joinToString(", ")
    }

    /**
     * Count everything aboard [ship] without touching any of it.
     *
     * One walk of the shipyard box: each block priced into the hull tally, each container emptied into the
     * cargo tally on paper. Read-only, so a survey that goes wrong costs nothing.
     */
    fun surveyOf(level: ServerLevel, ship: LoadedServerShip): Survey {
        val survey = Survey(ship.slug ?: "unnamed ship")
        val hull = ship.shipAABB ?: return survey

        val cursor = BlockPos.MutableBlockPos()
        for (x in hull.minX()..hull.maxX()) {
            for (y in hull.minY()..hull.maxY()) {
                for (z in hull.minZ()..hull.maxZ()) {
                    cursor.set(x, y, z)
                    val state = level.getBlockState(cursor)
                    if (state.isAir) continue

                    BillOfMaterials.itemFor(state)?.let { add(survey.hull, it, 1) }

                    val container = level.getBlockEntity(cursor) as? Container ?: continue
                    for (slot in 0 until container.containerSize) {
                        val stack = container.getItem(slot)
                        if (stack.isEmpty) continue
                        // A plain stack is a number. Anything else is itself.
                        if (stack.componentsPatch.isEmpty) {
                            add(survey.cargo, stack.item, stack.count)
                        } else {
                            survey.keepsakes.add(stack.copy())
                        }
                    }
                }
            }
        }
        return survey
    }

    /**
     * Take [ship] apart into [player]'s claim list.
     *
     * The order here is load-bearing and mirrors the bottle: empty the containers so nothing rains out,
     * discard the hangings so no item frame breaks itself off a wall that is about to stop existing, put the
     * crew into the articles so nobody falls, and only then delete.
     *
     * Returns the survey, or null with a message already sent.
     */
    fun dismantle(level: ServerLevel, player: ServerPlayer, ship: LoadedServerShip): Survey? {
        val survey = surveyOf(level, ship)
        if (survey.empty) {
            PathMessages.send(player, "There is nothing aboard to salvage.", PathMessages.Kind.WARN)
            return null
        }

        // In writing FIRST. Everything below this line is irreversible.
        ShipwrightLedger.get(level.server).recordSalvage(player.uuid, survey)

        ship.shipAABB?.let { box ->
            val cursor = BlockPos.MutableBlockPos()
            for (x in box.minX()..box.maxX()) {
                for (y in box.minY()..box.maxY()) {
                    for (z in box.minZ()..box.maxZ()) {
                        cursor.set(x, y, z)
                        (level.getBlockEntity(cursor) as? Clearable)?.clearContent()
                    }
                }
            }
        }

        // Item frames and paintings hang on the OUTSIDE face of their block, so the box needs a block of
        // slack. discard() rather than a break: their contents are already on the claim list.
        ship.worldAABB.let { box ->
            val slack = AABB(
                box.minX() - 1.0, box.minY() - 1.0, box.minZ() - 1.0,
                box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0
            )
            for (hanging in level.getEntitiesOfClass(BlockAttachedEntity::class.java, slack)) {
                hanging.discard()
            }
        }

        // The crews come off the CREW STATION rather than any wheel that happens to be nearby: on a hull with
        // several wheels the marked one is usually not the one holding the articles, and reading the wrong one
        // skips the whole stand-down. That mistake once cost an entire crew.
        val station = CrewStations.stationOf(level, ship)
        val crewIds = station?.crewBindings()?.values ?: emptyList()
        val posts = station?.let { CrewMuster.postsOf(level, ship, it.blockPos) } ?: emptyMap()
        val crewReport = CrewMuster.standDownShip(level, ship.id, ship.worldAABB, crewIds, posts)

        val shipId = ship.id
        val deck = ship.worldAABB.let {
            AABB(
                it.minX() - 2.0, it.minY() - 2.0, it.minZ() - 2.0,
                it.maxX() + 2.0, it.maxY() + 2.0, it.maxZ() + 2.0
            )
        }

        VSShipAssembler.deleteShip(level, ship, true, false)

        // Let go of anyone the ship was carrying the instant it stops existing, or VS2 keeps towing them for
        // its full drag count behind a hull that is no longer there -- and cashes in a fall that never
        // happened when the hold expires.
        for (entity in level.getEntities(null as Entity?, deck)) {
            val dragging = (entity as? IEntityDraggingInformationProvider)?.draggingInformation ?: continue
            if (dragging.lastShipStoodOn != shipId) continue
            dragging.lastShipStoodOn = null
            dragging.addedMovementLastTick = Vector3d()
            dragging.addedYawRotLastTick = 0.0
            entity.fallDistance = 0.0f
        }

        val kinds = survey.hull.size + survey.cargo.size
        PathMessages.send(
            player,
            "'${survey.shipName}' is broken up -- $kinds kinds waiting to be claimed.",
            PathMessages.Kind.GOOD, PathMessages.Topic.SALVAGE_DISMANTLE
        )
        if (survey.keepsakes.isNotEmpty()) {
            PathMessages.send(
                player,
                "${survey.keepsakes.size} items are being kept whole rather than counted.",
                PathMessages.Kind.GOOD, PathMessages.Topic.SALVAGE_DISMANTLE
            )
        }
        if (crewReport.stood > 0) {
            val crew = if (crewReport.stood == 1) "crew member" else "crew"
            PathMessages.send(
                player,
                "${crewReport.stood} $crew stood down into the articles.",
                PathMessages.Kind.GOOD, PathMessages.Topic.CREW_STAND_DOWN
            )
        }
        return survey
    }
}
