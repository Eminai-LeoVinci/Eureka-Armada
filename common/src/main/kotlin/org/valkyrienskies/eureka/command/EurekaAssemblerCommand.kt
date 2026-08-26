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
 * "/vs eureka-assembler <floater|balloon> <true|false>" -- toggles this player's Eureka Assembler
 * auto-fill modes. When a mode is on, assembling a ship from a helm auto-fills it with floaters
 * (to sail with the keel at the waterline) and/or balloons (to fly) by REPLACING hull blocks, gated
 * on the player having the required floater/balloon items in inventory (see [EurekaAssembler]).
 *
 * SERVER command (the effect is consumed server-side in ShipHelmBlockEntity.assemble). Brigadier
 * merges this "vs" literal into VS2's /vs root and VS2's vs_command_passthrough mixin forwards it
 * from the client. Lives in Eureka rather than VSCommands because it drives Eureka assembly state.
 *
 * Toggles are per-player (keyed by UUID) and STICKY until turned off -- see [AssemblerPreferences].
 */
object EurekaAssemblerCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs").then(
                literal("eureka-assembler")
                    .then(
                        literal("floater").then(
                            argument("enabled", BoolArgumentType.bool())
                                .executes { set(it, floater = true) }
                        )
                    )
                    .then(
                        literal("balloon").then(
                            argument("enabled", BoolArgumentType.bool())
                                .executes { set(it, floater = false) }
                        )
                    )
            )
        )
    }

    private fun set(ctx: CommandContext<CommandSourceStack>, floater: Boolean): Int {
        val player = ctx.source.playerOrException
        val enabled = BoolArgumentType.getBool(ctx, "enabled")

        if (floater) {
            AssemblerPreferences.setFloater(player.uuid, enabled)
        } else {
            AssemblerPreferences.setBalloon(player.uuid, enabled)
        }

        val what = if (floater) "floater" else "balloon"
        val state = if (enabled) "ON" else "OFF"
        val color = if (enabled) ChatFormatting.GREEN else ChatFormatting.YELLOW
        ctx.source.sendSuccess({
            Component.literal("Eureka Assembler $what auto-fill: $state").withStyle(color)
        }, false)
        return 1
    }
}
