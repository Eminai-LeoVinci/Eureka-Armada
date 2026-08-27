package org.valkyrienskies.eureka.pirate

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.EnchantmentTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.AABB
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.EurekaLootConfig
import org.valkyrienskies.eureka.EurekaLootConfig.LootEntry
import org.valkyrienskies.eureka.item.CannonCharge
import org.valkyrienskies.eureka.item.Cannonball
import org.valkyrienskies.eureka.blueprint.Blueprint
import org.valkyrienskies.eureka.template.ShipTemplate
import org.valkyrienskies.eureka.util.WeightedNames
import org.valkyrienskies.mod.util.logger

/**
 * Filling a pirate ship's holds: the one place loot enters the world.
 *
 * ## Why code, at adoption, and not vanilla loot-table references in the .nbt
 * Two placement paths exist -- jigsaw worldgen (pure datapack, no code runs) and site regeneration
 * (ShipTemplate.place, no jigsaw, so a structure processor would never fire) -- and BOTH funnel through
 * one point: the fresh wheel's berth adoption, on its first loaded tick. Filling there covers both with
 * a single call site, and reads [EurekaLootConfig]'s user-editable file rather than datapack JSON,
 * which is the point: the whole economy is meant to be tuned in a config, not a resource pack.
 *
 * Containers are CLEARED before filling. That makes the pass idempotent, and retro-fixes any hull
 * authored with stocked chests -- template capture strips chest and barrel contents going forward
 * (ShipTemplate.stripContainers), but hulls captured before that rule ship what they ship.
 */
object PirateLoot {

    private val log by logger()

    /**
     * Stock every chest and barrel inside [box] (world space -- the hull's own footprint).
     *
     * Swept by CHUNK, reading each one's block-entity map, rather than by walking every block in the
     * box. That is what makes a hull-sized box affordable: the first cut walked the adoption's crew box
     * -- a bubble sized for a sloop and centred on the WHEEL -- and on a large ship with the wheel aft,
     * every chest forward of midships sat outside it and shipped empty. Reading the chunks costs the
     * same whether the box is sixteen blocks across or a hundred and sixty, so the box can simply be
     * the whole ship.
     */
    fun stock(level: ServerLevel, box: AABB) {
        var filled = 0
        // DEV ONLY: chests filled and barrels came up empty, and one number for "containers" could not
        // tell whether the barrels were missed, skipped, or rolled nothing. Strip with the 6c sweep.
        val byKind = LinkedHashMap<String, Int>()
        val minChunkX = BlockPos.containing(box.minX, 0.0, box.minZ).x shr 4
        val maxChunkX = BlockPos.containing(box.maxX, 0.0, box.maxZ).x shr 4
        val minChunkZ = BlockPos.containing(box.minX, 0.0, box.minZ).z shr 4
        val maxChunkZ = BlockPos.containing(box.maxX, 0.0, box.maxZ).z shr 4
        val candidates = ArrayList<BlockPos>()
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                // getChunkNow, never getChunk: a hull's own chunks are loaded when its wheel reports,
                // and a miss here must never force-load a neighbour.
                val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                for (pos in chunk.blockEntities.keys) {
                    if (pos.x < box.minX || pos.x > box.maxX) continue
                    if (pos.y < box.minY || pos.y > box.maxY) continue
                    if (pos.z < box.minZ || pos.z > box.maxZ) continue
                    candidates.add(pos.immutable())
                }
            }
        }
        for (pos in candidates) {
            when (val be = level.getBlockEntity(pos)) {
                is ChestBlockEntity -> {
                    val type = be.blockState.getValue(ChestBlock.TYPE)
                    // LEFT is the canonical half of a pair; RIGHT is the same container seen twice.
                    if (type == ChestType.RIGHT) continue
                    if (type == ChestType.SINGLE) {
                        byKind.merge("single_chest", 1, Int::plus)
                        fill(level, listOf(be), "single_chest")
                    } else {
                        byKind.merge("double_chest", 1, Int::plus)
                        val other = level.getBlockEntity(
                            pos.relative(ChestBlock.getConnectedDirection(be.blockState))
                        ) as? ChestBlockEntity
                        fill(level, listOfNotNull(be, other), "double_chest")
                    }
                    filled++
                }
                is BarrelBlockEntity -> {
                    byKind.merge("barrel", 1, Int::plus)
                    fill(level, listOf(be), "barrel")
                    filled++
                }
                else -> Unit
            }
        }
    }

    /**
     * One container (a double chest arrives as its two halves): clear, roll, scatter. Clearing happens
     * even when every table is off -- an emptied hold is the configured outcome, not a skipped one.
     */
    private fun fill(level: ServerLevel, parts: List<BaseContainerBlockEntity>, kind: String) {
        if (parts.isEmpty()) return
        val cfg = EurekaLootConfig.LOOT
        val rules = cfg.containers[kind] ?: return

        for (part in parts) part.clearContent()

        val pool = rules.tables.filterValues { it }.keys
            .mapNotNull { cfg.tables[it] }
            .flatten()
        if (pool.isNotEmpty() && rules.rollsMax > 0) {
            val rolls = level.random.nextIntBetweenInclusive(
                rules.rollsMin.coerceAtLeast(0),
                rules.rollsMax.coerceAtLeast(rules.rollsMin.coerceAtLeast(0))
            )
            val stacks = ArrayList<ItemStack>()
            repeat(rolls) {
                draw(level, pool)?.let { entry -> stacks.addAll(realize(level, entry, depth = 0)) }
            }
            scatter(level, parts, stacks)
        }
        for (part in parts) part.setChanged()
    }

    // region the draw

    private fun draw(level: ServerLevel, pool: List<LootEntry>): LootEntry? {
        val total = pool.sumOf { it.weight.coerceAtLeast(0.0) }
        if (total <= 0.0) return null
        var roll = level.random.nextDouble() * total
        for (entry in pool) {
            roll -= entry.weight.coerceAtLeast(0.0)
            if (roll < 0.0) return entry
        }
        return pool.lastOrNull()
    }

    private fun realize(level: ServerLevel, entry: LootEntry, depth: Int): List<ItemStack> = when (entry.type) {
        "table" -> {
            // Bounded: a table naming itself must cost a wasted roll, not the server.
            val inner = entry.table?.let { EurekaLootConfig.LOOT.tables[it] }
            if (inner == null || depth >= MAX_TABLE_DEPTH) emptyList()
            else draw(level, inner)?.let { realize(level, it, depth + 1) } ?: emptyList()
        }
        "pool" -> {
            val ids = entry.items.orEmpty()
            if (ids.isEmpty()) emptyList()
            else stacksOf(level, ids[level.random.nextInt(ids.size)], entry)
        }
        "cannonball" -> cannonballs(level, entry)
        "blueprint" -> blueprints(level, entry)
        else -> entry.item?.let { stacksOf(level, it, entry) } ?: emptyList()
    }

    private fun stacksOf(level: ServerLevel, id: String, entry: LootEntry): List<ItemStack> {
        val item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElse(null)
        if (item == null) {
            log.warn("Loot table names no such item '{}' in table entry; skipped", id)
            return emptyList()
        }
        var count = rollCount(level, entry)
        val stacks = ArrayList<ItemStack>()
        while (count > 0) {
            val one = ItemStack(item)
            val take = count.coerceAtMost(one.maxStackSize)
            one.count = take
            stacks.add(enchant(level, one, entry))
            count -= take
        }
        return stacks
    }

    private fun cannonballs(level: ServerLevel, entry: LootEntry): List<ItemStack> {
        val metalName = weightedKey(level, entry.metalWeights) ?: "copper"
        val metal = Cannonball.entries.firstOrNull { it.name.equals(metalName, ignoreCase = true) }
            ?: Cannonball.COPPER
        val charge = if (level.random.nextDouble() < entry.effectChance) {
            val effectName = weightedKey(level, entry.effectWeights)
            CannonCharge.entries.firstOrNull { it.name.equals(effectName, ignoreCase = true) }
                ?: CannonCharge.PLAIN
        } else {
            CannonCharge.PLAIN
        }
        val item = EurekaItems.cannonball(metal, charge)
        var count = rollCount(level, entry)
        val stacks = ArrayList<ItemStack>()
        while (count > 0) {
            val one = ItemStack(item)
            val take = count.coerceAtMost(one.maxStackSize)
            one.count = take
            stacks.add(one)
            count -= take
        }
        return stacks
    }

    private fun blueprints(level: ServerLevel, entry: LootEntry): List<ItemStack> {
        if (level.random.nextDouble() < entry.specialChance) {
            specialBlueprint(level)?.let { return listOf(it) }
            // The special could not be drafted (no pool, template missing): fall through to blanks
            // rather than voiding a won roll.
        }
        val blank = ItemStack(EurekaItems.BLUEPRINT.get())
        blank.count = rollCount(level, entry).coerceAtMost(blank.maxStackSize)
        return listOf(blank)
    }

    /**
     * The 2%: ONE pre-drafted page naming a CIVILIANIZED copy of a hull from the blueprint pool --
     * crew stripped, wheel back to NORMAL, papers gone (ShipTemplate.civilianize), so the shipwright
     * builds a ship, not an incident. Null when the pool is empty or the template will not copy, and
     * the caller falls back to blank pages rather than voiding a won roll.
     */
    private fun specialBlueprint(level: ServerLevel): ItemStack? {
        val cfg = EurekaLootConfig.LOOT
        val source = WeightedNames.pick(cfg.blueprintTemplatePool, level.random) {
            ShipTemplate.find(level, it) != null
        } ?: return null
        val civil = ShipTemplate.civilianize(level, source) ?: return null
        val page = Blueprint.draftFromTemplate(level, civil, cfg.specialBlueprintName) ?: return null
        page.set(
            DataComponents.CUSTOM_NAME,
            Component.literal(cfg.specialBlueprintName)
                .withStyle { it.withColor(ChatFormatting.GOLD).withItalic(false) }
        )
        return page
    }

    private fun weightedKey(level: ServerLevel, weights: LinkedHashMap<String, Double>?): String? {
        if (weights.isNullOrEmpty()) return null
        val total = weights.values.sumOf { it.coerceAtLeast(0.0) }
        if (total <= 0.0) return null
        var roll = level.random.nextDouble() * total
        for ((key, weight) in weights) {
            roll -= weight.coerceAtLeast(0.0)
            if (roll < 0.0) return key
        }
        return weights.keys.last()
    }

    private fun rollCount(level: ServerLevel, entry: LootEntry): Int {
        val min = entry.countMin.coerceAtLeast(1)
        val max = entry.countMax.coerceAtLeast(min)
        return level.random.nextIntBetweenInclusive(min, max)
    }

    private fun enchant(level: ServerLevel, stack: ItemStack, entry: LootEntry): ItemStack {
        var out = stack
        if (entry.enchantRandomly) {
            val lo = entry.enchantLevelMin.coerceAtLeast(1)
            val hi = entry.enchantLevelMax.coerceAtLeast(lo)
            val power = level.random.nextIntBetweenInclusive(lo, hi)
            val registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            val tag = if (entry.treasureAllowed) EnchantmentTags.ON_RANDOM_LOOT
            else EnchantmentTags.IN_ENCHANTING_TABLE
            // Vanilla's own loot-enchant routine -- the one end city chests roll through. It also turns
            // a plain book into an enchanted book, which is exactly how the rack's books are meant to work.
            out = EnchantmentHelper.enchantItem(level.random, out, power, level.registryAccess(), registry.get(tag))
        }
        val fixed = entry.enchantments
        if (!fixed.isNullOrEmpty()) {
            val registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            EnchantmentHelper.updateEnchantments(out) { mutable ->
                for (spec in fixed) {
                    val key = ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.parse(spec.id))
                    registry.get(key).ifPresent { holder ->
                        mutable.set(holder, spec.level.coerceAtLeast(1))
                    }
                }
            }
        }
        return out
    }

    // endregion

    /** Scatter [stacks] across the parts' combined slots, vanilla chest-loot style: random, unbunched. */
    private fun scatter(level: ServerLevel, parts: List<BaseContainerBlockEntity>, stacks: List<ItemStack>) {
        val slots = ArrayList<Pair<BaseContainerBlockEntity, Int>>()
        for (part in parts) {
            for (i in 0 until part.containerSize) slots.add(part to i)
        }
        // Fisher-Yates on the level's own random, so a seeded world scatters reproducibly.
        for (i in slots.size - 1 downTo 1) {
            val j = level.random.nextInt(i + 1)
            val swap = slots[i]
            slots[i] = slots[j]
            slots[j] = swap
        }
        var next = 0
        for (stack in stacks) {
            if (stack.isEmpty) continue
            if (next >= slots.size) break // full hold; the remainder is forfeit, like vanilla
            val (part, slot) = slots[next++]
            part.setItem(slot, stack)
        }
    }

    private const val MAX_TABLE_DEPTH = 4
}
