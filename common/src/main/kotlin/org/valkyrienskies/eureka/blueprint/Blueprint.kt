package org.valkyrienskies.eureka.blueprint

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.pirate.PirateHelm
import org.valkyrienskies.eureka.template.ShipManifest
import org.valkyrienskies.eureka.template.ShipTemplate
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * A ship, written down.
 *
 * ## A blueprint is not a bottle
 * They share a primitive and almost nothing else. Bottling *moves* a ship: the hull stops existing, exactly one
 * vessel is in play at any moment, and it comes back under the name its captain gave it. Drafting *reads* a
 * ship: the original sails on untouched, the page can be built from any number of times, and every hull that
 * comes off it is a new vessel.
 *
 * That difference is why capture runs with `keepShipName = false` here and `true` there. Five ships built from
 * one page must not all answer to the same name -- a duplicate name breaks every `/vs` command for both ships,
 * since [org.valkyrienskies.mod.common.command.ShipArgument] resolves a name only when exactly one ship matches.
 * The source ship's name is still kept on the page, but as a *label* -- "a blueprint of the Sea Wolf" -- and
 * nothing built from it inherits the name.
 *
 * ## Why the manifest rides on the item
 * The blocks live where every captured ship's blocks live: an `.nbt` under `<world>/generated`, far too big to
 * put in an item component that syncs to every client which can see the stack. The *manifest* is different --
 * it is a few dozen item counts and five numbers, and it is the entire reason to pick up a blueprint. Carrying
 * it on the item means the page reads instantly, in any inventory, with no server round trip, and it keeps
 * reading after the ship it describes has sunk.
 *
 * The manifest is a snapshot taken at drafting. [ShipManifest] is otherwise derived-never-stored precisely so
 * it cannot go stale, and this is the one place that rule is bent -- because the alternative is a page that
 * cannot describe itself until a server answers. The template is immutable once written, so the snapshot has
 * nothing to drift against.
 */
object Blueprint {

    private const val TEMPLATE_KEY = "vs_eureka:blueprint_template"
    private const val SHIP_NAME_KEY = "vs_eureka:blueprint_ship"

    private const val SIZE_KEY = "vs_eureka:blueprint_size"
    private const val BLOCKS_KEY = "vs_eureka:blueprint_blocks"
    private const val ITEMS_KEY = "vs_eureka:blueprint_items"
    private const val MASS_KEY = "vs_eureka:blueprint_mass"
    private const val TOP_SPEED_KEY = "vs_eureka:blueprint_top_speed"
    private const val PROFILE_KEY = "vs_eureka:blueprint_profile"
    private const val CENSUS_KEY = "vs_eureka:blueprint_census"
    private const val CENSUS_ITEM = "id"
    private const val CENSUS_COUNT = "n"

    /**
     * Written only on a row the plans left OPEN -- "anything of the kind will do".
     *
     * An ANY choice cannot be baked into the template the way a fixed swap can. A template is blocks, and
     * "any slab" is not a block: [ShipAlterations.rewrite] resolves an Any row to its representative, which
     * IS the material the plans started with, so a page drawn from altered plans came out looking exactly
     * like the unaltered ones. The openness has to ride on the PAGE, beside the count, and be turned back
     * into an [Alteration.Any] when the page is filed.
     */
    private const val CENSUS_ANY = "any"

    /** Templates written by a blueprint are named for nothing else, so a player cannot collide with one. */
    private fun templateNameFor(id: UUID) = "blueprint/${id.toString().replace("-", "")}"

    /**
     * Read the ship this wheel steers onto [stack], and hand back the drafted page.
     *
     * The ship is not touched. Nothing here can fail in a way that costs the player anything except the blank
     * page, and even that is only spent once the template is safely on disk.
     */
    fun draft(level: ServerLevel, player: ServerPlayer, helm: BlockPos): ItemStack? {
        // Pirate gate, door 7 of 14: a drafted pirate ship is a pirate ship handed to the shipwright.
        // The blank page is kept -- nothing is spent on a refusal.
        if (PirateHelm.gated(level.getBlockState(helm))) {
            PirateHelm.deny(player)
            return null
        }
        val ship = level.getLoadedShipManagingPos(helm) as? LoadedServerShip ?: run {
            PathMessages.send(player, "That wheel is not part of an assembled ship.", PathMessages.Kind.WARN)
            return null
        }
        val shipName = ship.slug ?: "unnamed ship"

        val templateName = templateNameFor(UUID.randomUUID())
        // keepShipName = false: see the class note. The page remembers which ship it was taken from; the hulls
        // built from it must not.
        when (val outcome = ShipTemplate.capture(level, ship, templateName, keepShipName = false)) {
            is ShipTemplate.Failed -> {
                PathMessages.send(player, outcome.message, PathMessages.Kind.ERROR)
                return null
            }
            is ShipTemplate.Captured -> Unit
            else -> return null
        }

        val page = draftFromTemplate(level, templateName, shipName) ?: run {
            PathMessages.send(player, "The blueprint could not be written.", PathMessages.Kind.ERROR)
            return null
        }

        PathMessages.send(player, "Drafted a blueprint of '$shipName'.", PathMessages.Kind.GOOD)
        return page
    }

    /**
     * A drafted page for a template that already exists -- the tail of [draft], split out because the
     * special LOOT blueprint arrives by exactly this door: no live ship, no captain at a wheel, just a
     * civilianized template (ShipTemplate.civilianize) that needs its page written. The manifest is
     * computed against the template server-side, same as ever; only the capture is absent.
     */
    fun draftFromTemplate(
        level: ServerLevel,
        templateName: String,
        shipName: String,
        anyItems: Set<Item> = emptySet()
    ): ItemStack? {
        val template = ShipTemplate.find(level, templateName) ?: return null
        val manifest = ShipManifest.of(template)

        val page = ItemStack(EurekaItems.BLUEPRINT.get())
        val tag = CompoundTag()
        tag.putString(TEMPLATE_KEY, templateName)
        tag.putString(SHIP_NAME_KEY, shipName)
        tag.putIntArray(SIZE_KEY, intArrayOf(manifest.width, manifest.height, manifest.length))
        tag.putInt(BLOCKS_KEY, manifest.blocks)
        tag.putInt(ITEMS_KEY, manifest.items)
        tag.putDouble(MASS_KEY, manifest.mass)
        // Rated here rather than on the page's own numbers: top speed reads the settings block for the
        // vessel's category, and a client has no business deciding what a hull it has never seen will be.
        tag.putDouble(TOP_SPEED_KEY, manifest.topSpeed)
        tag.putString(PROFILE_KEY, manifest.profile.name)

        val census = ListTag()
        for ((item, count) in manifest.census) {
            val row = CompoundTag()
            row.putString(CENSUS_ITEM, BuiltInRegistries.ITEM.getKey(item).toString())
            row.putInt(CENSUS_COUNT, count)
            // Only when true, so an ordinary page's tag is exactly the shape it always was.
            if (item in anyItems) row.putBoolean(CENSUS_ANY, true)
            census.add(row)
        }
        tag.put(CENSUS_KEY, census)
        page.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        return page
    }

    /**
     * One line of a page's bill.
     *
     * A data class so that `for ((item, count) in census)` still reads as it always did -- the openness is
     * a third component nothing that does not care about it has to mention.
     */
    data class Row(val item: Item, val count: Int, val any: Boolean = false)

    /** Everything a drafted page knows about its ship. Null on a blank one. */
    class Page(
        val template: String,
        val shipName: String,
        val width: Int,
        val height: Int,
        val length: Int,
        val blocks: Int,
        val items: Int,
        val mass: Double,
        /** Estimated top speed in m/s, rated on the server at drafting. */
        val topSpeed: Double,
        /** BOAT / AIRSHIP / SUBMARINE, as the hull will classify once built. */
        val profile: String,
        /** One row per material, in the order the census produced -- the order the ship was walked in. */
        val census: List<Row>
    ) {
        val kinds: Int get() = census.size
    }

    /**
     * Read [stack] as a drafted page, or null if it is blank.
     *
     * Safe on the client: everything it needs travels in the item's component.
     */
    fun read(stack: ItemStack): Page? {
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val tag = data.copyTag()
        val template = tag.getString(TEMPLATE_KEY).orElse(null)?.takeIf { it.isNotEmpty() } ?: return null

        val size = tag.getIntArray(SIZE_KEY).orElse(null) ?: IntArray(3)
        val census = ArrayList<Row>()
        tag.getList(CENSUS_KEY).ifPresent { rows ->
            for (i in 0 until rows.size) {
                val row = rows.getCompound(i).orElse(null) ?: continue
                val id = row.getString(CENSUS_ITEM).orElse(null) ?: continue
                val item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null) ?: continue
                census.add(
                    Row(item, row.getIntOr(CENSUS_COUNT, 0), row.getBooleanOr(CENSUS_ANY, false))
                )
            }
        }

        return Page(
            template = template,
            shipName = tag.getString(SHIP_NAME_KEY).orElse(null)?.takeIf { it.isNotEmpty() } ?: "unnamed ship",
            width = size.getOrElse(0) { 0 },
            height = size.getOrElse(1) { 0 },
            length = size.getOrElse(2) { 0 },
            blocks = tag.getIntOr(BLOCKS_KEY, 0),
            items = tag.getIntOr(ITEMS_KEY, 0),
            mass = tag.getDoubleOr(MASS_KEY, 0.0),
            topSpeed = tag.getDoubleOr(TOP_SPEED_KEY, 0.0),
            profile = tag.getString(PROFILE_KEY).orElse(null)?.takeIf { it.isNotEmpty() } ?: "BOAT",
            census = census
        )
    }
}
