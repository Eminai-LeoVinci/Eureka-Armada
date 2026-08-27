package org.valkyrienskies.eureka.path

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * "/armada route list|info|rename|delete|stop" -- the housekeeping the hotkeys deliberately don't cover.
 *
 * "route" rather than "path": a path is the mechanism, a route is the thing a pilot means -- and every message
 * this feature prints already said "route", so the command was the odd one out. The code keeps the `path` package
 * naming, which describes the geometry it works on.
 *
 * The five SHIFT hotkeys handle the whole record-and-fly loop; this exists for the things you do rarely and
 * want to be exact about: naming a route something you'll recognise, deleting a bad take, and stopping a ship
 * you aren't standing on.
 *
 * Registered onto the same "armada" root literal as [org.valkyrienskies.eureka.armada.ArmadaCommand] -- separate
 * `dispatcher.register` calls on the same literal merge, the way Eureka's two "/vs" commands already do.
 */
object PathCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("armada").then(
                literal("route")
                    .then(literal("list").executes { list(it) })
                    .then(
                        literal("info").then(
                            argument("route", StringArgumentType.string()).executes { info(it) }
                        )
                    )
                    .then(
                        literal("rename").then(
                            argument("route", StringArgumentType.string()).then(
                                argument("name", StringArgumentType.string()).executes { rename(it) }
                            )
                        )
                    )
                    .then(
                        literal("delete").then(
                            argument("route", StringArgumentType.string()).executes { delete(it) }
                        )
                    )
                    .then(literal("stop").executes { stop(it) })
            )
        )
    }

    private fun list(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val level = src.level
        val store = PathStore.get(level)

        if (store.isEmpty) {
            src.sendSuccess({ Component.literal("No routes recorded in this dimension.") }, false)
            return 0
        }

        src.sendSuccess({
            Component.literal("${store.all.size} route(s) in this dimension:").withStyle(ChatFormatting.AQUA)
        }, false)
        for (path in store.all) {
            val flying = ShipPaths.followersIn(level).count { it.path.id == path.id }
            val suffix = if (flying > 0) " -- $flying ship(s) flying it" else ""
            // Which routes can be replayed at all is the one thing a list has to say now, since asking for it
            // on a route that has no timeline is otherwise a refusal you can only discover by trying.
            val replayable = if (path.motion != null) " [replayable]" else ""
            src.sendSuccess({
                Component.literal("  [${path.id}] ${path.name}: ${path.length.toInt()}m$replayable$suffix")
            }, false)
        }
        return store.all.size
    }

    private fun info(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val path = resolve(ctx) ?: return 0
        src.sendSuccess({
            Component.literal(
                "Route '${path.name}' [${path.id}]: ${path.length.toInt()}m, " +
                    "${path.control.size / 3} stored points, ${path.pointCount} followed points."
            ).withStyle(ChatFormatting.AQUA)
        }, false)

        val motion = path.motion
        if (motion == null) {
            src.sendSuccess({
                Component.literal("  No timing recorded -- SHIFT+P only. Record it again to replay it.")
            }, false)
            return 1
        }

        val stops = if (motion.dwellCount == 0) "no stops"
        else "${motion.dwellCount} stop(s) totalling ${dwellTotal(motion)}s"
        src.sendSuccess({
            Component.literal(
                "  Replayable: ${format(motion.lapSeconds)} a lap (${format(motion.movingSeconds)} flying), " +
                    "${"%.1f".format(motion.averageSpeed)} m/s average and " +
                    "${"%.1f".format(motion.topSpeed)} m/s at its fastest, $stops."
            )
        }, false)
        return 1
    }

    private fun dwellTotal(motion: MotionTrack): Int =
        (0 until motion.dwellCount).sumOf { motion.dwellSeconds(it) }.toInt()

    /** "1m 20s", or "45s" under a minute. */
    private fun format(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return if (total < 60) "${total}s" else "${total / 60}m ${total % 60}s"
    }

    private fun rename(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val path = resolve(ctx) ?: return 0
        val name = StringArgumentType.getString(ctx, "name")
        PathStore.get(src.level).rename(path.id, name)
        src.sendSuccess({ Component.literal("Renamed route ${path.id} to '$name'.") }, true)
        return 1
    }

    private fun delete(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val level = src.level
        val path = resolve(ctx) ?: return 0

        // Anything flying it has to be released first, or the follower would keep steering a route that no
        // longer exists for anyone else to see.
        val world = level.shipObjectWorld
        var stopped = 0
        for (follower in ShipPaths.followersIn(level)) {
            if (follower.path.id != path.id) continue
            world.loadedShips.getById(follower.shipId)?.let { if (ShipPaths.stopShip(it)) stopped++ }
        }

        PathStore.get(level).remove(path.id)
        val note = if (stopped > 0) " ($stopped ship(s) stopped)" else ""
        src.sendSuccess({ Component.literal("Deleted route '${path.name}'$note.") }, true)
        return 1
    }

    private fun stop(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val player = src.entity ?: run {
            src.sendFailure(Component.literal("Run this as a player standing on the ship."))
            return 0
        }
        val ship = ShipPaths.resolveShip(src.level, player) ?: run {
            src.sendFailure(Component.literal("Stand on a ship to stop it."))
            return 0
        }
        return if (ShipPaths.stopShip(ship)) {
            src.sendSuccess({ Component.literal("Stopped following.") }, false)
            1
        } else {
            src.sendFailure(Component.literal("This ship isn't following a route."))
            0
        }
    }

    private fun resolve(ctx: CommandContext<CommandSourceStack>): ShipPath? {
        val query = StringArgumentType.getString(ctx, "route")
        val path = PathStore.get(ctx.source.level).find(query)
        if (path == null) {
            ctx.source.sendFailure(Component.literal("No route named or numbered '$query' in this dimension."))
        }
        return path
    }
}
