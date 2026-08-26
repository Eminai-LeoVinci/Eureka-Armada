package org.valkyrienskies.eureka.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * "/vs get-ship-weight <floater|balloon>" -- prints the mass of the ship the player is LOOKING AT and
 * how many floaters (to float on water) or balloons (to lift off the ground) that ship needs, next to
 * how many are currently placed on it. Thresholds are computed from the LIVE EurekaConfig values, so
 * packs that rebalance massPerBalloon / floaterBuoyantFactorPerKg get matching numbers.
 *
 * 1.20.1 port note: the 1.21.1 source selected the ship with a `<ship>` ShipArgument. Eureka-120 is
 * built against OFFICIAL VS2 2.4.10 (the one-jar strategy), whose published API does NOT expose
 * `org.valkyrienskies.mod.common.command.arguments.ShipArgument`, so this resolves the target from a
 * ship-aware raytrace instead (VS2 patches Entity.pick to hit ship blocks) -- the same mechanism VS2's
 * own `/vs set-keep-active` bare form uses. All-literal, so VS2's vs_command_passthrough forwards it
 * from the client cleanly. Lives in Eureka rather than VSCommands because the math reads EurekaConfig.
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
 * floaters are weakened. Mass is `ship.inertiaData.shipMass` on the 1.20.1 vs-core pin.
 */
object ShipWeightCommand {

    // Added buoyant factor at which a ship stops sinking (factor 2x neutral): Eureka's per-kg
    // normalization puts this at exactly mass/floaterBuoyantFactorPerKg floaters for every ship.
    // At this point the ship rides very low -- awash, interior flooding at the waterline.
    private const val AFLOAT_ADDED_FACTOR = 1.0

    // Added factor for a "high and dry" ride (keel around the waterline, interior dry).
    // Calibrated in-game on a 556t fishing boat: keel-dry was reached at ~6.3 added factor and the
    // hull was fully emerged by ~9.0; 7.5 clears keel-dry with headroom. Somewhat shape-dependent
    // (denser/taller hulls sit deeper) and ride height keeps rising with extra floaters.
    private const val DRY_ADDED_FACTOR = 7.5

    // Reported needed-counts sit this much above the computed threshold: at the exact tie the ship
    // is only neutrally buoyant (hovers/sits awash but won't climb), so recommend going one over.
    // Floater-only now -- the balloon count instead uses assemblerBalloonAscendRate for real climb headroom.
    private const val SAFETY_MARGIN = 1

    // |EurekaShipControl.GRAVITY| (10.0). Balloons sized for neutral hover (mass/liftPerBalloon) hold the
    // ship up but won't climb; the usable climb force is the lift SURPLUS over weight. Terminal climb speed
    // when balloon-limited is balloonForce/mass - g, so the count for a target climb speed V is the hover
    // count times (1 + V/g). This mirrors EurekaAssembler so the reported "needed" matches what it builds.
    private const val GRAVITY_MAGNITUDE = 10.0

    // Ship-aware raytrace reach (blocks): far enough to point at a hull from the deck or nearby ground.
    private const val PICK_REACH = 24.0

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("vs").then(
                literal("get-ship-weight")
                    .then(literal("floater").executes { getShipWeight(it, floater = true) })
                    .then(literal("balloon").executes { getShipWeight(it, floater = false) })
            )
        )
    }

    /** Ship-aware raytrace: VS2 patches [Entity.pick], so this resolves ship blocks even on a moving ship. */
    private fun lookedAtShip(sourceEntity: Entity?): ServerShip? {
        if (sourceEntity == null) return null
        val level = sourceEntity.level() as? ServerLevel ?: return null
        val rayTrace = sourceEntity.pick(PICK_REACH, 1.0f, false)
        if (rayTrace is BlockHitResult) {
            return level.getShipManagingPos(rayTrace.blockPos)
        }
        return null
    }

    private fun getShipWeight(ctx: CommandContext<CommandSourceStack>, floater: Boolean): Int {
        val ship = lookedAtShip(ctx.source.entity)
        if (ship == null) {
            ctx.source.sendFailure(
                Component.literal("No ship found. Look at a block on the ship and try again.")
            )
            return 0
        }
        if (ship !is LoadedServerShip) {
            ctx.source.sendFailure(
                Component.literal("Ship '${ship.slug}' is not loaded -- get closer to it and try again.")
            )
            return 0
        }

        val mass = ship.inertiaData.shipMass
        val cfg = EurekaConfig.SERVER
        val control = ship.getAttachment(EurekaShipControl::class.java)
        val slug = ship.slug ?: "unnamed ship"

        val msg = Component.literal("Ship '$slug' weighs ${fmtKg(mass)}").withStyle(ChatFormatting.WHITE)
        if (floater) {
            // Per unpowered floater BLOCK (the attachment counter stores fifteenths of this).
            val perFloater = min(cfg.floaterBuoyantFactorPerKg / mass, 15.0 * cfg.maxFloaterBuoyantFactor)
            if (perFloater <= 0.0) {
                ctx.source.sendFailure(
                    Component.literal(
                        "Floaters provide no buoyancy with the current config " +
                            "(floaterBuoyantFactorPerKg = ${cfg.floaterBuoyantFactorPerKg})."
                    )
                )
                return 0
            }
            val afloat = ceil(AFLOAT_ADDED_FACTOR / perFloater).toInt() + SAFETY_MARGIN
            val dry = maxOf(ceil(DRY_ADDED_FACTOR / perFloater).toInt(), afloat)
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
            val liftPerBalloon = cfg.massPerBalloon * cfg.balloonLiftMultiplier // kg of ship per balloon
            if (liftPerBalloon <= 0.0) {
                ctx.source.sendFailure(
                    Component.literal(
                        "Balloons provide no lift with the current config (massPerBalloon = " +
                            "${cfg.massPerBalloon}, balloonLiftMultiplier = ${cfg.balloonLiftMultiplier})."
                    )
                )
                return 0
            }
            // Match EurekaAssembler: size for a target climb speed, not neutral hover, so this count is what
            // the assembler would place and "enough" means the ship can actually ascend (not just hover).
            val ascendFactor = 1.0 + max(0.0, cfg.assemblerBalloonAscendRate) / GRAVITY_MAGNITUDE
            val needed = ceil(mass * ascendFactor / liftPerBalloon).toInt()
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
