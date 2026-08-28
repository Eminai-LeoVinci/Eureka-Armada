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
                // TEMPORARY -- submarine feasibility experiment, remove with ArmadaSubExperiment.
                .then(literal("sealed").executes { sealed(it) })
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
                .then(
                    literal("debug").then(
                        argument("ship", ShipArgument.ships()).executes { debug(it) }
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

    /**
     * TEMPORARY -- reports what VS2's sealed-area machinery believes at the caller's position, so the
     * submarine experiment can tell "the feature is off" apart from "the feature is on and disagrees with
     * me". Remove with [ArmadaSubExperiment].
     */
    private fun sealed(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val player = src.player
        if (player == null) {
            src.sendFailure(Component.literal("Run this as a player -- it reports on where you're standing."))
            return 0
        }
        src.sendSuccess({ ArmadaSubExperiment.report(src.level, player) }, false)
        return 1
    }

    /**
     * Dumps the live dynamics of a bound ship so we can SEE whether the weld is holding and how the two
     * ships are moving, instead of inferring it from feel. For a child it reports the weld DRIFT (how far
     * the child's live pose in the parent frame has slipped from where it was welded) plus both ships'
     * speeds and spin rates and the gap between their centres.
     */
    private fun debug(ctx: CommandContext<CommandSourceStack>): Int {
        val src = ctx.source
        val shipAny: Ship = ShipArgument.getShip(ctx, "ship")
        if (shipAny !is LoadedServerShip) {
            src.sendFailure(Component.literal("That ship must be loaded."))
            return 0
        }
        val ship = shipAny
        val armada = ArmadaShipControl.get(ship)
        if (armada == null || (!armada.isChild && armada.childShipIds.isEmpty())) {
            src.sendFailure(Component.literal("${name(ship)} isn't part of an armada."))
            return 0
        }

        val msg = Component.literal("Armada debug: ${name(ship)}").withStyle(ChatFormatting.AQUA)
        fun line(s: String, color: ChatFormatting = ChatFormatting.GRAY) =
            msg.append(Component.literal("\n  $s").withStyle(color))

        line("speed ${fmt(ship.velocity.length())} m/s, spin ${fmt(ship.angularVelocity.length())} rad/s")

        val parentId = armada.parentShipId
        if (parentId != null) {
            line("role: CHILD of $parentId (welded)")
            val parent = src.level.shipObjectWorld.loadedShips.getById(parentId)
            if (parent == null) {
                line("parent not loaded -- can't measure drift", ChatFormatting.RED)
            } else {
                // Live child pose expressed in the parent's model frame.
                val livePos = parent.transform.worldToShip.transformPosition(
                    Vector3d(ship.transform.positionInWorld), Vector3d()
                )
                val liveRot = Quaterniond(parent.transform.shipToWorldRotation).invert()
                    .mul(ship.transform.shipToWorldRotation)

                val posDrift = armada.intendedPosInParent?.distance(livePos) ?: Double.NaN
                val rotDriftDeg = armada.intendedRotInParent?.let {
                    Math.toDegrees(Quaterniond(it).invert().mul(liveRot).angle())
                } ?: Double.NaN
                val comGap = Vector3d(parent.transform.positionInWorld)
                    .distance(Vector3d(ship.transform.positionInWorld))

                line(
                    "weld drift: ${fmt(posDrift)} blocks, ${fmt(rotDriftDeg)}deg",
                    if (posDrift > 1.0 || rotDriftDeg > 5.0) ChatFormatting.RED else ChatFormatting.GREEN
                )
                line("centre gap ${fmt(comGap)} blocks; parent speed ${fmt(parent.velocity.length())} m/s")
            }
            val weldState = when {
                armada.weldJointIds.isNotEmpty() -> "welded (${armada.weldJointIds.size} joints)"
                armada.weldPending -> "weld pending"
                else -> "NOT WELDED"
            }
            line("weld: $weldState", if (armada.weldJointIds.isNotEmpty()) ChatFormatting.GREEN else ChatFormatting.RED)
        } else {
            line("role: PARENT of ${armada.childShipIds}")
            // Each child is welded to us by a rigid joint and collides with the world itself; the engine resolves
            // the whole armada's terrain contacts. Report how many of those welds are actually live.
            val welded = armada.childShipIds.count { id ->
                src.level.shipObjectWorld.loadedShips.getById(id)
                    ?.let { ArmadaShipControl.get(it)?.weldJointIds?.isNotEmpty() } == true
            }
            line(
                "welds: $welded/${armada.childShipIds.size} children joined (engine-resolved collision)",
                if (welded == armada.childShipIds.size) ChatFormatting.GREEN else ChatFormatting.YELLOW
            )
        }
        src.sendSuccess({ msg }, false)
        return 1
    }

    private fun name(ship: Ship): String = ship.slug ?: ship.id.toString()

    private fun fmt(v: Double): String = if (v.isNaN()) "?" else String.format("%.2f", v)
}
