package org.valkyrienskies.eureka.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.util.BuoyancyMath
import org.valkyrienskies.mod.common.command.arguments.ShipArgument

/**
 * "/vs get-ship-weight <ship> <floater|balloon>" -- prints the ship's mass and how many floaters
 * (to float on water) or balloons (to lift off the ground) the ship needs, next to how many are
 * currently placed on it. Thresholds are computed from the LIVE EurekaConfig values, so packs that
 * rebalance massPerBalloon / floaterBuoyantFactorPerKg get matching numbers.
 *
 * Registered from the platform initializer onto the server dispatcher; Brigadier merges the "vs"
 * literal into VS2's existing /vs root, and VS2's vs_command_passthrough mixin forwards this
 * server-only subcommand from the client. Lives in Eureka rather than VSCommands because the math
 * reads EurekaConfig.
 *
 * The balloon count is sized for a real ASCEND, not just neutral hover: hovering needs
 * balloons * (massPerBalloon * g * balloonLiftMultiplier) >= mass * g, but climbing needs surplus lift
 * over that, so the count is the hover count scaled by (1 + assemblerBalloonAscendRate / g) -- matching
 * what EurekaAssembler builds. The floater thresholds come
 * from how EurekaShipControl.physTick feeds floaters into the core buoyantFactor: 1.0 is NEUTRAL
 * (a floaterless ship sinks regardless of what it's built from -- hull density does not decide
 * floating in VS), and each unpowered floater ADDS min(floaterBuoyantFactorPerKg / mass,
 * 15 * maxFloaterBuoyantFactor). The ship settles where the still-submerged hull fraction balances
 * its weight, so the FACTOR sets the RIDE HEIGHT: added factor 1.0 (= mass /
 * floaterBuoyantFactorPerKg floaters, Eureka's per-kg design point) only STOPS THE SINKING -- the
 * ship rides awash with a flooded interior. Riding high and dry takes several times that, so the
 * command reports both counts (see AFLOAT_ADDED_FACTOR / DRY_ADDED_FACTOR).
 *
 * Placed counts are read off the EurekaShipControl attachment -- the exact counters the physics
 * consumes -- rather than rescanning blocks. The floater counter is stored as unpowered-equivalent
 * fifteenths (a redstone-powered floater counts for less), so a fractional readout means some
 * floaters are weakened.
 */
object ShipWeightCommand {

    // The thresholds themselves live in BuoyancyMath, shared with the Auto-Shipwright that does the fitting
    // and the helm's "now +N%" readout. They used to be duplicated here, which meant a change to one could
    // leave this command confidently reporting a number the fill would never actually place.

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs").then(
                literal("get-ship-weight").then(
                    argument("ship", ShipArgument.ships())
                        .then(literal("floater").executes { getShipWeight(it, floater = true) })
                        .then(literal("balloon").executes { getShipWeight(it, floater = false) })
                )
            )
        )
    }

    private fun getShipWeight(ctx: CommandContext<CommandSourceStack>, floater: Boolean): Int {
        val ship = ShipArgument.getShip(ctx, "ship")
        if (ship !is LoadedServerShip) {
            ctx.source.sendFailure(
                Component.literal("Ship '${ship.slug}' is not loaded -- get closer to it and try again.")
            )
            return 0
        }

        val mass = ship.inertiaData.mass
        val cfg = EurekaConfig.SERVER
        val control = ship.getAttachment(EurekaShipControl::class.java)
        val slug = ship.slug ?: "unnamed ship"

        val msg = Component.literal("Ship '$slug' weighs ${fmtKg(mass)}").withStyle(ChatFormatting.WHITE)
        if (floater) {
            // Per unpowered floater BLOCK (the attachment counter stores fifteenths of this).
            val perFloater = BuoyancyMath.perFloater(mass)
            if (perFloater <= 0.0) {
                ctx.source.sendFailure(
                    Component.literal(
                        "Floaters provide no buoyancy with the current config " +
                            "(floaterBuoyantFactorPerKg = ${cfg.floaterBuoyantFactorPerKg})."
                    )
                )
                return 0
            }
            val afloat = BuoyancyMath.afloatFloaters(mass)
            val dry = maxOf(BuoyancyMath.recommendedFloaters(mass), afloat)
            val units = control?.floaters ?: 0
            val effective = units / 15.0 // placed floaters in unpowered-equivalents

            msg.append(newline())
            msg.append(
                Component.literal("Floaters to stay afloat: ${fmtInt(afloat)} -- rides low, interior may flood")
                    .withStyle(ChatFormatting.AQUA)
            )
            msg.append(newline())
            msg.append(
                Component.literal("Floaters to ride high and dry: ${fmtInt(dry)}")
                    .withStyle(ChatFormatting.AQUA)
            )

            val placed = StringBuilder("Floaters on ship: ")
            if (units % 15 == 0) {
                placed.append(fmtInt(units / 15))
            } else {
                placed.append("%.1f".format(effective)).append(" (some are redstone-weakened)")
            }
            val color = when {
                effective >= dry -> {
                    placed.append(" -- enough to ride dry!")
                    ChatFormatting.GREEN
                }
                effective >= afloat -> {
                    placed.append(
                        " -- stays afloat but rides low; " +
                            "${fmtInt(ceil(dry - effective).toInt())} more to ride dry"
                    )
                    ChatFormatting.YELLOW
                }
                else -> {
                    placed.append(
                        " -- will sink! need ${fmtInt(ceil(afloat - effective).toInt())} more to stay afloat"
                    )
                    ChatFormatting.RED
                }
            }
            msg.append(newline())
            msg.append(Component.literal(placed.toString()).withStyle(color))
            msg.append(newline())
            msg.append(
                Component.literal("Ride height scales with floater count -- add more to sit higher.")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
            )
        } else {
            val liftPerBalloon = BuoyancyMath.liftPerBalloon() // kg of ship per balloon
            if (liftPerBalloon <= 0.0) {
                ctx.source.sendFailure(
                    Component.literal(
                        "Balloons provide no lift with the current config (massPerBalloon = " +
                            "${cfg.massPerBalloon}, balloonLiftMultiplier = ${cfg.balloonLiftMultiplier})."
                    )
                )
                return 0
            }
            // Sized for a target climb speed, not neutral hover, so this count is what the Auto-Shipwright
            // would fit and "enough" means the ship can actually ascend rather than merely hover.
            val needed = BuoyancyMath.recommendedBalloons(mass)
            val balloons = control?.balloons ?: 0

            msg.append(newline())
            msg.append(
                Component.literal("Balloons needed to ascend: ${fmtInt(needed)} (each lifts ${fmtKg(liftPerBalloon)})")
                    .withStyle(ChatFormatting.AQUA)
            )
            val enough = balloons >= needed
            msg.append(newline())
            msg.append(
                Component.literal(
                    "Balloons on ship: ${fmtInt(balloons)}" +
                        if (enough) " -- enough to ascend!" else " -- need ${fmtInt(needed - balloons)} more"
                ).withStyle(if (enough) ChatFormatting.GREEN else ChatFormatting.YELLOW)
            )
            if (cfg.maxBalloonsPerEngine > 0) {
                msg.append(newline())
                msg.append(
                    Component.literal(
                        "Note: maxBalloonsPerEngine = ${cfg.maxBalloonsPerEngine} -- lift is also limited by engine power."
                    ).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
                )
            }
        }

        // Floaters/balloons only act in EurekaShipControl.physTick, which bails without a helm.
        if (control == null || control.helms < 1) {
            msg.append(newline())
            msg.append(
                Component.literal("Note: this ship has no helm -- floaters and balloons only work on helm-assembled ships.")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)
            )
        }

        ctx.source.sendSuccess({ msg }, false)
        return 1
    }

    private fun newline() = Component.literal("\n")
    private fun fmtInt(v: Int) = String.format("%,d", v)
    private fun fmtKg(v: Double) = String.format("%,.0f kg", v)
}
