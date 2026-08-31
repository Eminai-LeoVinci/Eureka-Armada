package org.valkyrienskies.eureka.armada

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * "/armada bind <parent> <child>", "/armada unbind <child>", "/armada list", "/armada debug <ship>" --
 * the command-driven face of the Armada parent/child feature (the helm menu's Armada Parent/Child checkboxes
 * are the other). Locks a child ship into a parent ship's frame so a fleet flies as one vessel; see
 * [ArmadaBindings.bindChild] for the bond itself.
 *
 * Registered as its OWN root literal (not under /vs) to sidestep the client-/vs-tree-first parse ambiguity
 * that Eureka's other commands document.
 */
object ArmadaCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("armada")
                .then(
                    literal("bind").then(
                        argument("parent", ShipArgument.ships()).then(
                            argument("child", ShipArgument.ships()).executes { bind(it) }
                        )
                    )
                )
                .then(
                    literal("unbind").then(
                        argument("child", ShipArgument.ships()).executes { unbind(it) }
                    )
                )
                .then(literal("list").executes { list(it) })
                // Fills/clears a ship's enclosed air with sub air by hand. Stands in for the helm's
                // "mark as sub" checkbox until that lands, so the mechanism can be tested on its own.
                .then(
                    literal("subair").then(
                        argument("ship", ShipArgument.ships())
                            // Explicit modes rather than a bool: "enclosed" only claims air the outside can't
                            // reach, which under-fills an open-topped hull badly (31 cells where the interior
                            // is ~174). "all" claims every air cell in the ship's AABB.
                            .then(literal("enclosed").executes { subAir(it, SubAir.FillMode.ENCLOSED) })
                            .then(literal("all").executes { subAir(it, SubAir.FillMode.ALL) })
                            .then(literal("clear").executes { subAir(it, null) })
                    )
                )
        )
    }

    private fun bind(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val parentAny: Ship = ShipArgument.getShip(ctx, "parent")
        val childAny: Ship = ShipArgument.getShip(ctx, "child")

        if (parentAny !is LoadedServerShip || childAny !is LoadedServerShip) {
            src.sendFailure(Component.literal("Both ships must be loaded -- get closer to them and try again."))
            return 0
        }

        // Every rule and the bond itself live in ArmadaBindings, so the helm menu's Child checkbox binds
        // identically to this command.
        ArmadaBindings.bindChild(parentAny, childAny)?.let {
            src.sendFailure(it)
            return 0
        }

        src.sendSuccess({
            Component.literal("Bound ${name(childAny)} to parent ${name(parentAny)}.").withStyle(ChatFormatting.GREEN)
        }, true)
        return 1
    }

    private fun unbind(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val childAny: Ship = ShipArgument.getShip(ctx, "child")
        if (childAny !is LoadedServerShip) {
            src.sendFailure(Component.literal("That ship must be loaded -- get closer to it and try again."))
            return 0
        }
        if (!ArmadaBindings.unbindChild(src.level, childAny)) {
            src.sendFailure(Component.literal("${name(childAny)} isn't bound to a parent."))
            return 0
        }
        src.sendSuccess({
            Component.literal("Unbound ${name(childAny)}.").withStyle(ChatFormatting.YELLOW)
        }, true)
        return 1
    }

    private fun list(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val msg = Component.literal("Armada bindings:").withStyle(ChatFormatting.AQUA)
        var count = 0
        for (ship in src.level.shipObjectWorld.loadedShips) {
            val armada = ArmadaShipControl.get(ship) ?: continue
            if (!armada.isChild && armada.childShipIds.isEmpty()) continue
            count++
            val line = StringBuilder("\n  ${name(ship)}")
            armada.parentShipId?.let { line.append(" -> parent $it") }
            if (armada.childShipIds.isNotEmpty()) line.append(" -> children ${armada.childShipIds}")
            msg.append(Component.literal(line.toString()).withStyle(ChatFormatting.GRAY))
        }
        if (count == 0) msg.append(Component.literal("\n  (none)").withStyle(ChatFormatting.GRAY))
        src.sendSuccess({ msg }, false)
        return count
    }

    /**
     * Fill a ship's enclosed air with sub air, or put it back to plain air. The helm's "mark as sub" checkbox
     * will drive this at assembly time; until then this is how a sub gets made.
     */
    private fun subAir(ctx: CommandContext<CommandSourceStack>, mode: SubAir.FillMode?): Int {
        val src = ctx.source
        val shipAny: Ship = ShipArgument.getShip(ctx, "ship")
        if (shipAny !is LoadedServerShip) {
            src.sendFailure(Component.literal("That ship must be loaded -- get closer to it and try again."))
            return 0
        }
        val result =
            if (mode == null) SubAir.clear(src.level, shipAny) else SubAir.fill(src.level, shipAny, mode)
        result.error?.let {
            src.sendFailure(Component.literal(it))
            return 0
        }
        src.sendSuccess({
            val verb = if (mode == null) "Cleared" else "Filled"
            val how = if (mode == null) "" else " (${mode.name.lowercase()})"
            Component.literal("$verb ${result.changed} sub air blocks in ${name(shipAny)}$how.")
                .withStyle(if (mode == null) ChatFormatting.YELLOW else ChatFormatting.GREEN)
        }, true)
        return 1
    }

    private fun name(ship: Ship): String = ship.slug ?: ship.id.toString()

    private fun fmt(v: Double): String = if (v.isNaN()) "?" else String.format("%.2f", v)
}
