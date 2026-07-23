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
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * "/armada bind <parent> <child>", "/armada unbind <child>", "/armada list", "/armada debug <ship>" --
 * the command-driven, no-GUI first cut of the Armada parent/child feature. Rigidly welds a child ship
 * into a parent ship's frame with a VS core [VSFixedJoint] so a fleet moves as one rigid body.
 *
 * Registered as its OWN root literal (not under /vs) to sidestep the client-/vs-tree-first parse ambiguity
 * that Eureka's other commands document.
 */
object ArmadaCommand {

    // Both ships must be this close to stationary (m/s) to bind, so the orientation snap and the freshly
    // created joint are born stress-free rather than fighting live momentum.
    private const val REST_VELOCITY_EPS = 0.5

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

        if (parentAny.id == childAny.id) {
            src.sendFailure(Component.literal("A ship can't be its own parent."))
            return 0
        }
        if (parentAny !is LoadedServerShip || childAny !is LoadedServerShip) {
            src.sendFailure(Component.literal("Both ships must be loaded -- get closer to them and try again."))
            return 0
        }
        val parent: LoadedServerShip = parentAny
        val child: LoadedServerShip = childAny

        if (parent.chunkClaimDimension != child.chunkClaimDimension) {
            src.sendFailure(Component.literal("Both ships must be in the same dimension."))
            return 0
        }

        val childArmada = ArmadaShipControl.getOrCreate(child)
        if (childArmada.isChild) {
            src.sendFailure(Component.literal("That ship is already bound to a parent -- unbind it first."))
            return 0
        }

        if (parent.velocity.length() > REST_VELOCITY_EPS || child.velocity.length() > REST_VELOCITY_EPS) {
            src.sendFailure(Component.literal("Bring both ships to a stop before binding."))
            return 0
        }

        // The fixed offset the child holds: its current centre of mass expressed in the parent's model
        // (shipyard) frame. The follow provider maps this back through the parent's live pose each physics
        // tick, so the child keeps this exact spot in the armada. Orientation is locked to the parent
        // exactly (identity relative rotation), so on the first tick the child snaps to face forward -- no
        // pre-align needed, since pose-slaving simply places it there.
        val childCenterInParentModel = parent.transform.worldToShip.transformPosition(
            Vector3d(child.transform.positionInWorld), Vector3d()
        )
        val relRot = Quaterniond()

        // Lock the child to the parent by POSITIONING it every physics tick (no joint, nothing to flex).
        child.transformProvider = ArmadaFollowProvider(
            parent,
            Vector3d(childCenterInParentModel),
            Quaterniond(relRot),
            Vector3d(child.transform.shipToWorldScaling),
            Vector3d(child.transform.positionInShip)
        )

        // Ships collide with each other by default; a locked child doesn't need to (its pose is forced), and
        // an overlapping close armada could otherwise shove the parent. Turn it off between these two.
        ValkyrienSkiesMod.getOrCreateGTPA(parent.chunkClaimDimension).disableCollisionBetween(parent.id, child.id)

        childArmada.parentShipId = parent.id
        childArmada.intendedPosInParent = Vector3d(childCenterInParentModel)
        childArmada.intendedRotInParent = Quaterniond(relRot)
        ArmadaShipControl.getOrCreate(parent).childShipIds.add(child.id)

        src.sendSuccess({
            Component.literal("Bound ${name(child)} to parent ${name(parent)}.").withStyle(ChatFormatting.GREEN)
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
            line("role: CHILD of $parentId (pose-locked)")
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
        } else {
            line("role: PARENT of ${armada.childShipIds}")
        }
        src.sendSuccess({ msg }, false)
        return 1
    }

    private fun name(ship: Ship): String = ship.slug ?: ship.id.toString()

    private fun fmt(v: Double): String = if (v.isNaN()) "?" else String.format("%.2f", v)
}
