package org.valkyrienskies.eureka.shipwright

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.eureka.EurekaMod
import org.valkyrienskies.eureka.template.BillOfMaterials

/**
 * What kind of thing a block is, and what else could stand in its place.
 *
 * Two questions, one answer sheet. The shipwright needs to know whether a block is STRUCTURE or DRESSING
 * (so a captain may say "build the hull, skip the carpets"), and whether two items are the same KIND of
 * thing in different materials (so a captain with two thousand acacia planks may build a birch design).
 *
 * ## Everything is keyed on the ITEM, never the block
 * The bill is a map of items; the template is a palette of block states. Both sides have to reach the same
 * verdict or a ship gets built out of something the player was never charged for. So a palette entry is
 * classified by running it through [BillOfMaterials.itemFor] first and asking about THAT -- the same
 * mapping the bill was built from, so the two cannot drift.
 *
 * It also inherits that function's rules for free, including the one worth knowing about: the upper half of
 * a door and the head of a bed have no item and therefore no verdict of their own. They follow their
 * partner, which is correct, and it is why excluding a door has to air out both halves rather than one.
 *
 * ## The data is a datapack's, the order is this file's
 * Every list here lives in `data/vs_eureka/tags/item/` and can be widened, narrowed or replaced by any pack
 * without touching code. What cannot live in a tag is PRECEDENCE: an item belongs to several tags at once
 * -- an oak slab is in `#minecraft:slabs` and `#minecraft:wooden_slabs` and whatever else a pack has put it
 * in -- and "which family is it in" must have exactly one answer, or the same row offers different swaps on
 * different days. So [FAMILIES] is an ordered list in code and the first match wins.
 *
 * ## The deny list is not optional
 * `#minecraft:wool` contains this mod's balloons -- `data/minecraft/tags/block/wool.json` folds
 * `#vs_eureka:balloons` straight into it, so that balloons mine and burn like the wool they are made of.
 * Seed a swap family from that tag and "replace white wool with a balloon" is free lift, while the reverse
 * quietly grounds an airship. [NEVER] is subtracted from every family before it is offered, and it covers
 * the same class of problem for engines, floaters, helms, spawners and command blocks: things whose SHAPE
 * says "ordinary block" and whose behaviour does not.
 *
 * ## Whole blocks are authored, not measured
 * The first cut of this measured the collision shape instead -- if two blocks are both full cubes, let one
 * stand for the other -- which is elegant, needs no data, and is wrong. A furnace is a full cube. So is a
 * dispenser, a spawner, TNT, and this mod's own engine. Shape cannot see the difference between a block of
 * coal and a machine, so the rule would have offered an engine as a swap for planks.
 *
 * `swap/full_blocks` is therefore a seeded tag: a handful of `#`-references that pull in a few hundred
 * items at once and keep absorbing whatever a pack adds to them, with the deny list subtracted. The honest
 * limit is that a genuinely new whole block from another mod is not swappable until somebody adds it --
 * which is a line in a datapack, and is the failure that costs nothing rather than the one that costs an
 * airship its lift.
 */
object MaterialFamilies {

    /** What a block is FOR, which is what decides whether a captain may leave it out. */
    enum class Category {
        /** Structure. Never excludable: the ship is not a ship without it. */
        FOUNDATIONAL,

        /** Things with an interface a player opens -- chests, furnaces, beds, cannons. */
        FURNITURE,

        /** Dressing -- carpets, paintings, pots, torches, banners. */
        DECOR
    }

    private fun itemTag(path: String): TagKey<Item> =
        TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, path))

    val DECOR: TagKey<Item> = itemTag("excludable/decor")
    val FURNITURE: TagKey<Item> = itemTag("excludable/furniture")

    /** Never swappable, whatever family a tag would otherwise put it in. See the class note. */
    val NEVER: TagKey<Item> = itemTag("swap/never")

    /**
     * Families that swap only WITHIN themselves, and are exempt from [NEVER] for that reason.
     *
     * A helm and a balloon are both denied everywhere else on purpose: a balloon lives in `#minecraft:wool`
     * (that is what lets shears take one), so the moment it is allowed into an open family a captain can
     * order white wool where the lift used to be and ground the airship without being told. The deny list
     * is what stops that, and it must go on stopping it.
     *
     * But denying a thing everywhere also denies it to ITSELF, which is why an oak helm could never be
     * traded for a birch one and a red balloon never for a blue. Those are pure changes of material -- the
     * same block, the same lift, the same behaviour, a different colour of it -- and they are exactly what
     * the swap list is for.
     *
     * So these two are matched FIRST and answered from their own tag rather than an open one. Membership is
     * the whole guard: nothing outside `#vs_eureka:balloons` can be offered for a balloon, because the
     * candidate is tested against that same closed tag.
     */
    val CLOSED: List<TagKey<Item>> = listOf(itemTag("ship_helms"), itemTag("balloons"))

    /**
     * The swap families, most specific first. First match wins, so a change of order changes answers --
     * which is why it is here and not in a tag, where JSON gives no ordering guarantee at all.
     *
     * `full_blocks` is last on purpose: a stone slab is a slab before it is anything else, and that
     * ordering is the whole of the rule that a slab may never become a plank.
     */
    val FAMILIES: List<TagKey<Item>> = listOf(
        "beds", "doors", "trapdoors", "signs", "banners", "candles", "lanterns",
        "shulker_boxes", "buttons", "pressure_plates", "fence_gates", "fences",
        "walls", "stairs", "slabs", "carpets", "leaves", "anvils", "terracotta",
        "planks", "logs", "wool", "full_blocks"
    ).map { itemTag("swap/$it") }

    /** Which category this item falls in. Anything nobody classified is structure -- the safe default. */
    fun categoryOf(item: Item): Category {
        val stack = ItemStack(item)
        return when {
            stack.`is`(FURNITURE) -> Category.FURNITURE
            stack.`is`(DECOR) -> Category.DECOR
            else -> Category.FOUNDATIONAL
        }
    }

    /** The same question asked of a palette entry. Null for anything that costs nothing to place. */
    fun categoryOf(state: BlockState): Category? =
        BillOfMaterials.itemFor(state)?.let { categoryOf(it) }

    /** Whether a captain may leave this block out of a build at all. */
    fun excludable(state: BlockState): Boolean =
        categoryOf(state)?.let { it != Category.FOUNDATIONAL } ?: false

    /**
     * The family [item] belongs to, or null when it has none -- either because nothing claims it or
     * because [NEVER] does, which is a refusal rather than an absence.
     */
    fun familyOf(item: Item): TagKey<Item>? {
        val stack = ItemStack(item)
        // Closed families are asked BEFORE the deny list, because their members are ON it -- see [CLOSED].
        CLOSED.firstOrNull { stack.`is`(it) }?.let { return it }
        if (stack.`is`(NEVER)) return null
        return FAMILIES.firstOrNull { stack.`is`(it) }
    }

    /**
     * May [candidate] stand in for [wanted]?
     *
     * Same family, and never across families: a slab does not become a plank however much a player would
     * like it to, because that is a different thing rather than a different material.
     */
    fun interchangeable(wanted: Item, candidate: Item): Boolean {
        if (wanted == candidate) return true
        val family = familyOf(wanted) ?: return false
        val offered = ItemStack(candidate)
        // Inside a closed family the deny list is not consulted: every member is on it, and membership of
        // the closed tag is the guard instead.
        if (family !in CLOSED && offered.`is`(NEVER)) return false
        return offered.`is`(family)
    }

    /**
     * Everything that could stand in for [item], the item itself first.
     *
     * Walked from the registry rather than cached, because a datapack reload can change a family under us
     * and a stale list would offer swaps the build then refuses. It runs when a row is opened, not per tick.
     */
    fun replacementsFor(item: Item): List<Item> {
        val family = familyOf(item) ?: return listOf(item)
        val closed = family in CLOSED
        val peers = BuiltInRegistries.ITEM.filter { other ->
            other != item && ItemStack(other).`is`(family) && (closed || !ItemStack(other).`is`(NEVER))
        }.sortedBy { BuiltInRegistries.ITEM.getKey(it).toString() }
        return listOf(item) + peers
    }
}
