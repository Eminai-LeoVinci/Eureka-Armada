package org.valkyrienskies.eureka.util

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.eureka.EurekaConfig

/**
 * What a ship loses on the way down.
 *
 * A hull that has been shot out of the sky and buried herself in a seabed should not come apart into a
 * tidy pile of everything she was built from. Salvaging a wreck ought to be worth less than taking the ship
 * -- which is the whole argument for boarding one and putting your own wheel on her instead of sinking her.
 *
 * So some of her is simply gone. Not evenly: a captain minds losing balloons far more than planks, because
 * balloons are how you get off the ground. Every entry is its own percentage, configured per block or per
 * tag, and rolled independently for each block as it is laid down -- so a wreck comes up ragged and holed
 * rather than uniformly thinned, which is what being torn apart actually looks like.
 *
 * ## Ordered, and first match wins
 * The rules are a LIST, not a map, and they are read in order. Tags overlap constantly -- a spruce plank is
 * wood and could easily be in a second list someone adds later -- and JSON gives no ordering guarantee at
 * all, so the config is a list of strings and its order is the priority. Same reasoning, and the same
 * shape, as [org.valkyrienskies.eureka.shipwright.MaterialFamilies.FAMILIES].
 *
 * ## Only on a wreck
 * Nothing here runs when a captain disassembles her own ship at the wheel, underwater or otherwise. The
 * damage belongs to the sinking, not to the taking apart.
 */
object WreckDamage {

    /** One line of the config: what it matches, and how often it takes it. */
    class Rule(private val tag: TagKey<Block>?, private val block: Block?, val percent: Int) {
        fun matches(state: BlockState): Boolean = when {
            tag != null -> state.`is`(tag)
            block != null -> state.`is`(block)
            else -> false
        }
    }

    /**
     * Parse the configured chances. Called once per teardown, never per block -- a hull is tens of thousands
     * of blocks and this is a handful of string splits.
     *
     * A line that cannot be read is skipped rather than fatal: a hand-edited config with one typo in it
     * should cost that one rule, not the whole feature.
     */
    fun rules(): List<Rule> {
        val out = ArrayList<Rule>()
        for (line in EurekaConfig.SERVER.wreckShatterChances) {
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("#") && !text.contains(' ')) continue
            val cut = text.lastIndexOf(' ')
            if (cut <= 0) continue
            val percent = text.substring(cut + 1).trim().toIntOrNull()?.coerceIn(0, 100) ?: continue
            if (percent <= 0) continue
            val selector = text.substring(0, cut).trim()
            if (selector.isEmpty()) continue

            if (selector.startsWith("#")) {
                val id = ResourceLocation.tryParse(selector.substring(1)) ?: continue
                out.add(Rule(TagKey.create(Registries.BLOCK, id), null, percent))
            } else {
                val id = ResourceLocation.tryParse(selector) ?: continue
                val block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null) ?: continue
                out.add(Rule(null, block, percent))
            }
        }
        return out
    }

    /** Whether this block was torn off on the way down. First matching rule decides; no match survives. */
    fun shatters(state: BlockState, rules: List<Rule>, random: RandomSource): Boolean {
        if (rules.isEmpty()) return false
        val rule = rules.firstOrNull { it.matches(state) } ?: return false
        return random.nextInt(100) < rule.percent
    }
}
