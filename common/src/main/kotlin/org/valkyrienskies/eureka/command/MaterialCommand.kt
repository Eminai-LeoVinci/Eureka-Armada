package org.valkyrienskies.eureka.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import org.valkyrienskies.eureka.shipwright.Alteration
import org.valkyrienskies.eureka.shipwright.MaterialFamilies
import org.valkyrienskies.eureka.shipwright.Shipwright
import org.valkyrienskies.eureka.shipwright.ShipwrightLedger

/**
 * DEV ONLY: what the shipwright makes of the block you are holding -- strip with the ROADMAP 6c sweep.
 *
 * The exclusion and swap rules are two tag lookups and one shape inference, and all three are invisible
 * until a ship is half built out of the wrong thing. This says the answer out loud for one item, which is
 * how the tags get tuned: hold a resin block, a carpet, a chest and a slab, and read four lines.
 */
object MaterialCommand {

    /** Chat cannot hold a family; a handful of names is enough to tell right from wrong. */
    private const val SAMPLE = 8

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs").then(
                literal("material")
                    .requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
                    .executes { describe(it.source) }
                    .then(
                        literal("save-as-new").then(
                            argument("plans", StringArgumentType.greedyString())
                                .executes { onPlans(it) { level, player, plans ->
                                    val refusal = Shipwright.saveAsNew(level, player, plans)
                                    if (refusal != null) it.source.sendFailure(Component.literal(refusal))
                                } }
                        )
                    )
                    .then(
                        literal("take-blueprint").then(
                            argument("plans", StringArgumentType.greedyString())
                                .executes { onPlans(it) { level, player, plans ->
                                    Shipwright.takeBlueprint(level, player, plans)
                                } }
                        )
                    )
                    .then(
                        literal("exclude").then(
                            argument("category", StringArgumentType.word()).then(
                                argument("plans", StringArgumentType.greedyString())
                                    .executes { exclude(it) }
                            )
                        )
                    )
            )
        )
    }

    /**
     * DEV ONLY: toggle a whole category off one set of filed plans, so the bill and the build can be tested
     * before the Operations screen can set an alteration. Strip with the rest of this file.
     */
    private fun exclude(ctx: CommandContext<CommandSourceStack>): Int {
        val source = ctx.source
        val player = source.entity as? ServerPlayer ?: return 0
        val level = source.level

        val wanted = StringArgumentType.getString(ctx, "category").uppercase()
        val category = MaterialFamilies.Category.entries.firstOrNull { it.name == wanted } ?: run {
            source.sendFailure(Component.literal("Category must be DECOR or FURNITURE."))
            return 0
        }

        val name = StringArgumentType.getString(ctx, "plans")
        val ledger = ShipwrightLedger.get(level.server)
        val plans = ledger.plansFor(player.uuid, name) ?: run {
            source.sendFailure(Component.literal("No plans named '$name' on your shelf."))
            return 0
        }

        val before = plans.cost.values.sum()
        val categories = plans.alteration.excludedCategories.toMutableSet()
        val on = if (category in categories) {
            categories.remove(category)
            false
        } else {
            categories.add(category)
            true
        }
        plans.alteration = Alteration(categories, plans.alteration.excludedItems, plans.alteration.swaps)
        ledger.setDirty()

        val after = plans.cost.values.sum()
        source.sendSuccess({
            Component.literal(
                "'$name': $category ${if (on) "excluded" else "restored"} -- " +
                    "bill $before -> $after items (${plans.cost.size} kinds)"
            )
        }, false)
        return 1
    }

    /** Resolve the named plans on the caller's shelf and hand them to [body]. */
    private fun onPlans(
        ctx: CommandContext<CommandSourceStack>,
        body: (ServerLevel, ServerPlayer, ShipwrightLedger.Plans) -> Unit
    ): Int {
        val player = ctx.source.entity as? ServerPlayer ?: return 0
        val name = StringArgumentType.getString(ctx, "plans")
        val plans = ShipwrightLedger.get(ctx.source.level.server).plansFor(player.uuid, name) ?: run {
            ctx.source.sendFailure(Component.literal("No plans named '$name' on your shelf."))
            return 0
        }
        body(ctx.source.level, player, plans)
        return 1
    }

    private fun describe(source: CommandSourceStack): Int {
        val player = source.entity as? ServerPlayer ?: run {
            source.sendFailure(Component.literal("Hold the block you want classified."))
            return 0
        }
        val stack = player.mainHandItem
        if (stack.isEmpty) {
            source.sendFailure(Component.literal("Hold the block you want classified."))
            return 0
        }

        val item = stack.item
        val category = MaterialFamilies.categoryOf(item)
        val family = MaterialFamilies.familyOf(item)
        val swaps = MaterialFamilies.replacementsFor(item)

        val familyName = family?.location()?.toString() ?: "none -- not swappable"
        source.sendSuccess({
            Component.literal(
                "${BuiltInRegistries.ITEM.getKey(item)}: $category, family $familyName, " +
                    "${swaps.size - 1} replacements"
            )
        }, false)

        val sample = swaps.drop(1).take(SAMPLE).joinToString(", ") {
            BuiltInRegistries.ITEM.getKey(it).path
        }
        if (sample.isNotEmpty()) {
            source.sendSuccess({ Component.literal("  e.g. $sample") }, false)
        }
        return 1
    }
}
