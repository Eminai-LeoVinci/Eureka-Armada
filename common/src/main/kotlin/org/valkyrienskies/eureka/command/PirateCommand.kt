package org.valkyrienskies.eureka.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.block.HelmMark
import org.valkyrienskies.eureka.block.ShipHelmBlock

/**
 * "/vs pirate ..." -- the harness for the pirate-ship machinery.
 *
 * DEV ONLY, remove before release with the other debug commands (see ROADMAP 6c). Like
 * [ShipTemplateCommand] it merges into VS2's "vs" root and is gated on COMMANDS_GAMEMASTER --
 * `set-mark` mints the two helm states that are deliberately unobtainable in survival and creative alike.
 *
 * `set-mark` is also the testing vehicle for the whole gate: mark any placed helm PIRATE and every one of
 * the fourteen doors can be walked in game without a single generated ship existing yet.
 */
object PirateCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs").then(
                literal("pirate")
                    .requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
                    .then(
                        literal("set-mark")
                            .then(literal("normal").executes { setMark(it, HelmMark.NORMAL) })
                            .then(literal("pirate").executes { setMark(it, HelmMark.PIRATE) })
                            .then(literal("taken").executes { setMark(it, HelmMark.TAKEN) })
                    )
            )
        )
    }

    /**
     * Restamp the helm under the caller's crosshair. `setBlock` with the same block keeps the block entity,
     * so a wheel's name, crew station and bottle binding all survive the change of allegiance -- which is
     * exactly what the real machinery will rely on when it flips PIRATE <-> TAKEN.
     */
    private fun setMark(ctx: CommandContext<CommandSourceStack>, mark: HelmMark): Int {
        val player = ctx.source.playerOrException
        val level = ctx.source.level

        val hit = player.pick(player.blockInteractionRange(), 1.0f, false)
        val pos = (hit as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos ?: run {
            ctx.source.sendFailure(Component.literal("Look at a ship helm to mark it."))
            return 0
        }
        val state = level.getBlockState(pos)
        if (state.block !is ShipHelmBlock) {
            ctx.source.sendFailure(Component.literal("That is not a ship helm."))
            return 0
        }

        level.setBlock(pos, state.setValue(EurekaProperties.MARK, mark), Block.UPDATE_ALL)
        ctx.source.sendSuccess({
            Component.literal("Helm marked ${mark.serializedName}.").withStyle(ChatFormatting.GREEN)
        }, true)
        return 1
    }
}
