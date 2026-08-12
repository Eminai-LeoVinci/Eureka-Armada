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
import net.minecraft.server.permissions.Permissions
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.template.ShipTemplate
import org.valkyrienskies.mod.common.command.arguments.ShipArgument

/**
 * "/vs template save|load|list" -- the harness for [ShipTemplate].
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

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs").then(
                literal("template")
                    .requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
                    .then(
                        literal("save").then(
                            argument("ship", ShipArgument.ships()).then(
                                argument("name", StringArgumentType.word()).executes { save(it) }
                            )
                        )
                    )
                    .then(
                        literal("load").then(
                            argument("name", StringArgumentType.word()).executes { load(it) }
                        )
                    )
                    .then(literal("list").executes { list(it) })
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
                            "${"%,d".format(outcome.blocks)} blocks, ${size.x}x${size.y}x${size.z}"
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

    private fun list(ctx: CommandContext<CommandSourceStack>): Int {
        val templates = ShipTemplate.list(ctx.source.level)
        if (templates.isEmpty()) {
            ctx.source.sendSuccess({
                Component.literal("No ship templates saved yet.").withStyle(ChatFormatting.GRAY)
            }, false)
            return 0
        }

        val msg = Component.literal("${templates.size} ship template(s):").withStyle(ChatFormatting.WHITE)
        templates.sortedBy { it.path }.forEach {
            msg.append(Component.literal("\n  ${it.path}").withStyle(ChatFormatting.AQUA))
        }
        ctx.source.sendSuccess({ msg }, false)
        return templates.size
    }
}
