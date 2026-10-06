package org.valkyrienskies.eureka.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.eureka.ship.keepActive
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * `/vs set-keep-active <true|false>`          the ship the player is looking at
 * `/vs set-keep-active <ships> <true|false>`  an explicit ship selector, e.g. `@v[id=123]`
 *
 * Sets the same Keep Active flag as the helm's checkbox (see [org.valkyrienskies.eureka.ship.ShipKeepActive]):
 * a kept-active ship keeps simulating with no player near it. Uses VS2's `/vs set-static` permission level.
 *
 * The bare form exists so the command works from chat without a selector: the client only forwards a `/vs`
 * command to the server when it parses to completion, so `/vs set-keep-active true` has to be complete on its
 * own.
 */
object KeepActiveCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs").then(
                literal("set-keep-active")
                    .requires { it.hasPermission(VSGameConfig.SERVER.Commands.setStaticShipCommandPerms) }
                    .then(
                        argument("keep-active", BoolArgumentType.bool()).executes { ctx ->
                            val value = BoolArgumentType.getBool(ctx, "keep-active")
                            val ship = lookedAtShip(ctx.source.entity)
                            if (ship == null) {
                                ctx.source.sendFailure(
                                    Component.literal(
                                        "No ship found. Look at a block on the ship, " +
                                            "or use /vs set-keep-active <ships> $value"
                                    )
                                )
                                0
                            } else {
                                apply(ctx, listOf(ship), value)
                            }
                        }
                    )
                    .then(
                        argument("ships", ShipArgument.ships()).then(
                            argument("keep-active", BoolArgumentType.bool()).executes { ctx ->
                                @Suppress("UNCHECKED_CAST")
                                val ships = ShipArgument.getShips(ctx, "ships").toList() as List<ServerShip>
                                apply(ctx, ships, BoolArgumentType.getBool(ctx, "keep-active"))
                            }
                        )
                    )
            )
        )
    }

    /** VS2 patches [Entity.pick] to see ship blocks, so this finds the ship even while it is moving. */
    private fun lookedAtShip(sourceEntity: Entity?): ServerShip? {
        val level = sourceEntity?.level() as? ServerLevel ?: return null
        val hit = sourceEntity.pick(10.0, 1.0f, false)
        return if (hit is BlockHitResult) level.getShipManagingPos(hit.blockPos) else null
    }

    private fun apply(ctx: CommandContext<CommandSourceStack>, ships: List<ServerShip>, value: Boolean): Int {
        var changed = 0
        for (ship in ships) {
            val loaded = ship as? LoadedServerShip ?: continue
            loaded.keepActive = value
            changed++
        }
        ctx.source.sendSuccess({ Component.literal("Set keepActive=$value on $changed ship(s)") }, true)
        return changed
    }
}
