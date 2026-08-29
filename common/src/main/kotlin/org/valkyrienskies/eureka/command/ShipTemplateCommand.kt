package org.valkyrienskies.eureka.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.template.BillOfMaterials
import org.valkyrienskies.eureka.template.PlacementCheck
import org.valkyrienskies.eureka.template.ShipManifest
import org.valkyrienskies.eureka.template.ShipTemplate
import org.valkyrienskies.mod.common.command.arguments.ShipArgument

/**
 * "/vs template save|load|list", with a listing per family -- the harness for [ShipTemplate].
 *
 * This exists to answer one question before six features are built on the answer: does a ship survive being
 * serialized to a vanilla `.nbt` and placed back into the world, with its block entities and their contents
 * intact? Everything in the Armada roadmap that copies a ship -- blueprints, ships in a bottle, the shipwright,
 * pirate worldgen -- assumes yes.
 *
 * It is a development tool, not a feature, and should come out before release along with the other debug toggles.
 * Registered the same way as [ShipWeightCommand]: Brigadier merges the "vs" literal into VS2's existing root, and
 * VS2's `vs_command_passthrough` mixin forwards the server-only subcommand from the client.
 *
 * Gated on COMMANDS_GAMEMASTER (1.21.11's replacement for the old integer level 2) because `load` writes a
 * potentially large volume of blocks wherever the caller is standing -- unlike the read-only
 * /vs get-ship-weight, which anyone may run.
 */
object ShipTemplateCommand {

    /** Chat cannot hold a full manifest for a real ship; the rest is the blueprint screen's problem. */
    private const val MATERIAL_ROWS = 12

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs").then(
                literal("template")
                    .requires { it.hasPermission(2) }
                    // greedyString, not word(): template names may be namespaced ("pirate/sloop",
                    // "blueprint/<uuid>") and word() refuses the slash outright -- which silently made every
                    // namespaced template unreachable from chat. Greedy is safe because the name is the last
                    // argument in all four shapes; ShipTemplate.idFor still validates what it is handed.
                    .then(
                        literal("save").then(
                            argument("ship", ShipArgument.ships()).then(
                                argument("name", StringArgumentType.greedyString()).executes { save(it) }
                            )
                        )
                    )
                    .then(
                        literal("load").then(
                            argument("name", StringArgumentType.greedyString()).executes { load(it) }
                        )
                    )
                    .then(
                        literal("info").then(
                            argument("name", StringArgumentType.greedyString()).executes { info(it) }
                        )
                    )
                    .then(
                        // The authoring undo: a design you have finished with, gone from the pool and
                        // off the disk. See ShipTemplate.delete for why `forget` was not enough.
                        literal("delete").then(
                            argument("name", StringArgumentType.greedyString()).executes { delete(it) }
                        )
                    )
                    .then(
                        literal("check").then(
                            argument("name", StringArgumentType.greedyString()).executes { check(it) }
                        )
                    )
                    // Each family also lists on its own -- "/vs template pirate list". Woven in from the
                    // table rather than spelled out four times, because the literal is the only thing that
                    // differs between them.
                    .apply {
                        GROUPS.forEach { group ->
                            then(literal(group.literal).then(literal("list").executes { listOne(it, group) }))
                        }
                    }
                    .then(literal("list").executes { listAll(it) })
            )
        )
    }

    private fun save(ctx: CommandContext<CommandSourceStack>): Int {
        val ship = ShipArgument.getShip(ctx, "ship")
        if (ship !is LoadedServerShip) {
            ctx.source.sendFailure(
                Component.literal("Ship '${ship.slug}' is not loaded -- get closer to it and try again.")
            )
            return 0
        }
        val name = StringArgumentType.getString(ctx, "name")

        return when (val outcome = ShipTemplate.capture(ctx.source.level, ship, name)) {
            is ShipTemplate.Failed -> {
                ctx.source.sendFailure(Component.literal(outcome.message))
                0
            }
            is ShipTemplate.Captured -> {
                val size = outcome.size
                ctx.source.sendSuccess({
                    Component.literal(
                        "Captured '${ship.slug ?: "unnamed ship"}' as ${outcome.id} -- " +
                            "${"%,d".format(outcome.blocks)} blocks, " +
                            "${"%,d".format(outcome.entities)} entities, ${size.x}x${size.y}x${size.z}"
                    ).withStyle(ChatFormatting.GREEN)
                }, true)
                1
            }
            else -> 0
        }
    }

    private fun load(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val at = BlockPos.containing(ctx.source.position)

        return when (val outcome = ShipTemplate.place(ctx.source.level, name, at)) {
            is ShipTemplate.Failed -> {
                ctx.source.sendFailure(Component.literal(outcome.message))
                0
            }
            is ShipTemplate.Placed -> {
                val size = outcome.size
                ctx.source.sendSuccess({
                    Component.literal(
                        "Placed ${outcome.id} at ${at.x}, ${at.y}, ${at.z} -- ${size.x}x${size.y}x${size.z}. " +
                            "These are loose blocks; assemble them with a helm."
                    ).withStyle(ChatFormatting.GREEN)
                }, true)
                1
            }
            else -> 0
        }
    }

    /** The bill of materials, which is what a blueprint page will show a player. */
    private fun info(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val template = ShipTemplate.find(ctx.source.level, name)
            ?: run {
                ctx.source.sendFailure(Component.literal("No template named '$name'."))
                return 0
            }

        val manifest = ShipManifest.of(template)
        val census = manifest.census
        val msg = Component.literal(
            "$name -- ${manifest.width}x${manifest.height}x${manifest.length}, " +
                "${"%,d".format(manifest.blocks)} blocks, ${"%,d".format(manifest.items)} items"
        ).withStyle(ChatFormatting.WHITE)
        msg.append(
            Component.literal(
                "\nWeighs ${"%,.0f".format(manifest.mass)} kg -- " +
                    "${"%,d".format(manifest.floatersToRideDry)} floaters to ride dry, " +
                    "${"%,d".format(manifest.balloonsToAscend)} balloons to ascend"
            ).withStyle(ChatFormatting.YELLOW)
        )

        // Heaviest first: the top of a shopping list is the part that decides whether you can afford it.
        census.entries.sortedByDescending { it.value }.take(MATERIAL_ROWS).forEach { (item, count) ->
            msg.append(Component.literal("\n  ${"%,d".format(count)} x ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable(item.descriptionId).withStyle(ChatFormatting.AQUA))
        }
        if (census.size > MATERIAL_ROWS) {
            msg.append(
                Component.literal("\n  ...and ${census.size - MATERIAL_ROWS} more kinds")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
            )
        }

        ctx.source.sendSuccess({ msg }, false)
        return 1
    }

    /** Would it fit here? Writes nothing -- this is the test the bottle runs before it commits. */
    private fun check(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val template = ShipTemplate.find(ctx.source.level, name)
            ?: run {
                ctx.source.sendFailure(Component.literal("No template named '$name'."))
                return 0
            }

        val at = BlockPos.containing(ctx.source.position)
        return when (val result = PlacementCheck.test(ctx.source.level, template, at)) {
            is PlacementCheck.Fits -> {
                ctx.source.sendSuccess({
                    Component.literal("'$name' fits here.").withStyle(ChatFormatting.GREEN)
                }, false)
                1
            }
            is PlacementCheck.Blocked -> {
                ctx.source.sendFailure(
                    Component.literal(
                        "'$name' does not fit -- ${result.by} at " +
                            "${result.at.x}, ${result.at.y}, ${result.at.z} is in the way."
                    )
                )
                0
            }
            is PlacementCheck.OutOfWorld -> {
                ctx.source.sendFailure(
                    Component.literal(
                        "'$name' does not fit -- it would reach y=${result.at.y}, outside the world."
                    )
                )
                0
            }
        }
    }

    private fun delete(ctx: CommandContext<CommandSourceStack>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val outcome = ShipTemplate.delete(ctx.source.level, name)
        val deleted = outcome.startsWith("Deleted")
        if (deleted) {
            ctx.source.sendSuccess({ Component.literal(outcome).withStyle(ChatFormatting.GREEN) }, true)
        } else {
            ctx.source.sendFailure(Component.literal(outcome))
        }
        return if (deleted) 1 else 0
    }

    /**
     * The families a template name can belong to, in the order a listing shows them.
     *
     * Names are namespaced by whatever minted them: "pirate/large1" is a hull raiders sail, "blueprint/<uuid>"
     * is a set of plans a shipwright works from, "bottled/<uuid>" is a ship somebody corked. Nothing enforces
     * that -- it is a convention four call sites happen to share, and this is the only place that reads it
     * back, so it is written down here and nowhere else needs to know.
     *
     * It is written down because of what a world in use looks like. Blueprints and bottles are named after a
     * UUID, so they arrive as thirty-two characters of hex that nobody will ever type; a few dozen of them in
     * one flat alphabetical run buries the handful of pirate hulls that are the only names anybody uses by
     * hand. Sorting cannot help -- "blueprint/" and "bottled/" sort adjacent and both sort above "pirate/".
     *
     * A null [prefix] is the catch-all, and comes last for that reason: a name matching none of the others was
     * typed by a person, which makes it the most interesting group and not the least.
     */
    private class Group(
        val literal: String,
        val prefix: String?,
        val heading: String,
        val colour: ChatFormatting
    )

    private val GROUPS = listOf(
        Group("pirate", "pirate/", "Pirate hulls", ChatFormatting.RED),
        Group("blueprint", "blueprint/", "Blueprints", ChatFormatting.AQUA),
        Group("bottled", "bottled/", "Bottled ships", ChatFormatting.LIGHT_PURPLE),
        Group("other", null, "Saved by hand", ChatFormatting.GOLD)
    )

    /** How many names one family shows in the combined listing before it defers to its own. */
    private const val GROUPED_ROWS = 8

    /** A ceiling on a single family's listing: one chat component can only grow so far before it is dropped. */
    private const val CATEGORY_ROWS = 100

    private fun groupOf(path: String): Group =
        GROUPS.firstOrNull { it.prefix != null && path.startsWith(it.prefix) } ?: GROUPS.last()

    private fun heading(group: Group, count: Int): MutableComponent =
        Component.literal("${group.heading} ($count)").withStyle(group.colour, ChatFormatting.BOLD)

    private fun row(path: String): Component =
        Component.literal("\n  $path").withStyle(ChatFormatting.GRAY)

    private fun overflow(hidden: Int, tail: String): Component =
        Component.literal("\n  ...and $hidden more$tail").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)

    /** Everything, each family under its own heading, each family trimmed to a readable few. */
    private fun listAll(ctx: CommandContext<CommandSourceStack>): Int {
        val all = ShipTemplate.list(ctx.source.level)
        if (all.isEmpty()) {
            ctx.source.sendSuccess({
                Component.literal("No ship templates saved yet.").withStyle(ChatFormatting.GRAY)
            }, false)
            return 0
        }

        val families = all.map { it.path }.groupBy { groupOf(it) }
        val msg = Component.literal("${all.size} ship template(s):").withStyle(ChatFormatting.WHITE)
        for (group in GROUPS) {
            val paths = families[group]?.sorted() ?: continue
            msg.append(Component.literal("\n")).append(heading(group, paths.size))
            paths.take(GROUPED_ROWS).forEach { msg.append(row(it)) }
            if (paths.size > GROUPED_ROWS) {
                msg.append(overflow(paths.size - GROUPED_ROWS, " -- /vs template ${group.literal} list"))
            }
        }
        ctx.source.sendSuccess({ msg }, false)
        return all.size
    }

    /** One family, in full -- the listing the combined one hands off to. */
    private fun listOne(ctx: CommandContext<CommandSourceStack>, group: Group): Int {
        val paths = ShipTemplate.list(ctx.source.level)
            .map { it.path }
            .filter { groupOf(it) === group }
            .sorted()
        if (paths.isEmpty()) {
            ctx.source.sendSuccess({
                Component.literal("No templates saved under ${group.heading.lowercase()}.")
                    .withStyle(ChatFormatting.GRAY)
            }, false)
            return 0
        }

        val msg = heading(group, paths.size)
        paths.take(CATEGORY_ROWS).forEach { msg.append(row(it)) }
        if (paths.size > CATEGORY_ROWS) {
            msg.append(overflow(paths.size - CATEGORY_ROWS, ""))
        }
        ctx.source.sendSuccess({ msg }, false)
        return paths.size
    }
}
