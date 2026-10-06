package org.valkyrienskies.eureka.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.phys.BlockHitResult
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaConfigLoader
import org.valkyrienskies.eureka.entity.DeckSeat
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/**
 * Snow on ships, two commands under VS2's `/vs` (Brigadier merges this server "vs" literal into VS2's own):
 *
 * - `/vs desnow` clears every snow layer off the ship you are on (or looking at). Anyone can use it on their own
 *   ship. `/vs desnow <ships>` takes a VS2 ship selector (`@v` for every ship) and needs operator rights.
 * - `/vs snow-accumulation on|off` lets snow settle on ships or keeps it off them entirely (MixinNoSnowOnShips),
 *   saved as the server config's `snowOnShips`. Bare `/vs snow-accumulation` says which. Setting it needs operator
 *   rights.
 *
 * Only snow LAYERS are cleared; whole snow blocks are building material and stay. The whole chunk claim is walked,
 * not the ship's box: snow lying on the top deck sits one block above the box, which only grows when the ship is
 * rebuilt from its blocks.
 *
 * Our VS2 build carried `/vs desnow`; it lives in Armada now so Armada runs on the official release.
 */
object SnowCommands {

    /** VS2's own level for ship commands (its old `/vs desnow` used the same). */
    private const val OPERATOR = 2

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs")
                .then(
                    literal("desnow")
                        .executes { desnowOwnShip(it) }
                        .then(
                            argument("ships", ShipArgument.ships())
                                .requires { it.hasPermission(OPERATOR) }
                                .executes { desnowSelected(it) }
                        )
                )
                .then(
                    literal("snow-accumulation")
                        .executes { ctx ->
                            ctx.source.sendSuccess({ Component.literal(state()).withStyle(ChatFormatting.AQUA) }, false)
                            1
                        }
                        .then(literal("on").requires { it.hasPermission(OPERATOR) }.executes { setSnow(it, true) })
                        .then(literal("off").requires { it.hasPermission(OPERATOR) }.executes { setSnow(it, false) })
                )
        )
    }

    private fun state(): String =
        if (EurekaConfig.SERVER.snowOnShips) "Snow settles on ships (/vs snow-accumulation off to keep it off them)."
        else "Snow is kept off ships (/vs snow-accumulation on to let it settle)."

    private fun setSnow(ctx: CommandContext<CommandSourceStack>, on: Boolean): Int {
        EurekaConfig.SERVER.snowOnShips = on
        EurekaConfigLoader.save()
        ctx.source.sendSuccess({ Component.literal(state()).withStyle(ChatFormatting.GREEN) }, true)
        return 1
    }

    private fun desnowOwnShip(ctx: CommandContext<CommandSourceStack>): Int {
        val ship = shipOf(ctx.source.entity)
        if (ship == null) {
            ctx.source.sendFailure(Component.literal("No ship here. Stand on a ship or look at one, then /vs desnow."))
            return 0
        }
        return report(ctx, listOf(ship))
    }

    private fun desnowSelected(ctx: CommandContext<CommandSourceStack>): Int {
        @Suppress("UNCHECKED_CAST")
        val ships = ShipArgument.getShips(ctx, "ships").toList() as List<ServerShip>
        return report(ctx, ships)
    }

    private fun report(ctx: CommandContext<CommandSourceStack>, ships: List<ServerShip>): Int {
        var layers = 0
        for (ship in ships) layers += desnow(ctx.source.level, ship)
        val where = ships.singleOrNull()?.let { it.slug ?: "the ship" } ?: "${ships.size} ships"
        ctx.source.sendSuccess(
            { Component.literal("Cleared $layers snow ${if (layers == 1) "layer" else "layers"} from $where.") }, true
        )
        return 1
    }

    /** Remove every snow layer in [ship]'s chunk claim. Returns how many. */
    private fun desnow(level: ServerLevel, ship: ServerShip): Int {
        var cleared = 0
        val cursor = BlockPos.MutableBlockPos()
        ship.activeChunksSet.forEach { chunkX, chunkZ ->
            val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: return@forEach
            for (index in chunk.sections.indices) {
                val section = chunk.sections[index]
                if (section.hasOnlyAir()) continue
                val bottomY = (index shl 4) + level.minBuildHeight
                for (x in 0..15) for (y in 0..15) for (z in 0..15) {
                    if (section.getBlockState(x, y, z).block !is SnowLayerBlock) continue
                    cursor.set((chunkX shl 4) + x, bottomY + y, (chunkZ shl 4) + z)
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)
                    cleared++
                }
            }
        }
        return cleared
    }

    /**
     * The ship [entity] is on: the one it sits in (helm or deck seat), stands on or is carried by, or failing those
     * the one it is looking at (VS2 lets a pick see ship blocks).
     */
    private fun shipOf(entity: Entity?): ServerShip? {
        val level = entity?.level() as? ServerLevel ?: return null
        val ships = level.shipObjectWorld.loadedShips
        val vehicle = entity.vehicle
        if (vehicle is DeckSeat) DeckSeat.anchorOf(vehicle)?.first?.let { ships.getById(it) }?.let { return it }
        if (vehicle != null) level.getShipManagingPos(vehicle.blockPosition())?.let { return it }
        (entity as? IEntityDraggingInformationProvider)?.draggingInformation?.lastShipStoodOn
            ?.let { ships.getById(it) }?.let { return it }
        val hit = entity.pick(10.0, 1.0f, false)
        return if (hit is BlockHitResult) level.getShipManagingPos(hit.blockPos) else null
    }
}
