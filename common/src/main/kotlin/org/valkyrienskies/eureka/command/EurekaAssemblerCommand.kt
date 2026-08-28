package org.valkyrienskies.eureka.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component

/**
 * "/vs auto-shipwright <floater|balloon|balloon-replace-all> <true|false>" -- toggles this player's Eureka
 * Auto-Shipwright modes. When a mode is on, assembling a ship from a helm fits it out with floaters
 * (to sail with the keel at the waterline) and/or balloons (to fly) by REPLACING hull blocks, gated
 * on the player having the required floater/balloon items in inventory (see [EurekaAssembler]).
 *
 * `balloon-replace-all` converts every convertible block rather than sizing a target -- for a ship built to
 * be all lift. It implies `balloon`.
 *
 * SERVER command (the effect is consumed server-side in ShipHelmBlockEntity.assemble), registered
 * exactly like [ShipWeightCommand]: Brigadier merges this "vs" literal into VS2's /vs root and VS2's
 * vs_command_passthrough mixin forwards it from the client. Lives in Eureka rather than VSCommands
 * because it drives Eureka assembly state.
 *
 * Toggles are per-player (keyed by UUID) and STICKY until turned off -- see [AssemblerPreferences].
 */
object EurekaAssemblerCommand {

    private const val FLOATER = 0
    private const val BALLOON = 1
    private const val REPLACE_ALL = 2

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs").then(
                literal("auto-shipwright")
                    .then(
                        literal("floater").then(
                            argument("enabled", BoolArgumentType.bool())
                                .executes { set(it, FLOATER) }
                        )
                    )
                    .then(
                        literal("balloon").then(
                            argument("enabled", BoolArgumentType.bool())
                                .executes { set(it, BALLOON) }
                        )
                    )
                    .then(
                        literal("balloon-replace-all").then(
                            argument("enabled", BoolArgumentType.bool())
                                .executes { set(it, REPLACE_ALL) }
                        )
                    )
            )
        )
    }

    private fun set(ctx: CommandContext<CommandSourceStack>, which: Int): Int {
        val player = ctx.source.playerOrException
        val enabled = BoolArgumentType.getBool(ctx, "enabled")

        when (which) {
            FLOATER -> AssemblerPreferences.setFloater(player.uuid, enabled)
            BALLOON -> AssemblerPreferences.setBalloon(player.uuid, enabled)
            else -> AssemblerPreferences.setBalloonReplaceAll(player.uuid, enabled)
        }

        val what = when (which) {
            FLOATER -> "floater fitting"
            BALLOON -> "balloon fitting"
            else -> "balloon Replace All"
        }
        val state = if (enabled) "ON" else "OFF"
        val color = if (enabled) ChatFormatting.GREEN else ChatFormatting.YELLOW
        ctx.source.sendSuccess({
            Component.literal("Eureka Auto-Shipwright $what: $state").withStyle(color)
        }, false)
        return 1
    }
}
