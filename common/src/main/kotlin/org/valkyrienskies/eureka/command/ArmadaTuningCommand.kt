package org.valkyrienskies.eureka.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaConfigLoader
import org.valkyrienskies.eureka.item.ChanceSpec
import kotlin.reflect.KMutableProperty0

/**
 * "/armada cannons ..." and "/armada cannonballs ..." -- live gunnery tuning from chat.
 *
 * Every leaf is a GET when bare and a SET with a value. A SET writes the live [EurekaConfig.SERVER]
 * singleton -- each firing site reads the config per shot, so the next shot obeys -- and then persists it
 * with [EurekaConfigLoader.save], so a tuned battle survives a restart. No clamps beyond type sanity on
 * purpose: a one-hour reload and a per-tick reload are both legitimate answers here (the runtime already
 * floors reload at one tick), and the damage ladders take whatever ladder the operator writes.
 *
 * Gated at gamemaster level on the `cannons`/`cannonballs` literals rather than the root: these rewrite
 * the server's config file, where the root's bind/list/route siblings stay open to everyone.
 *
 * Registered as its own "armada" literal; Brigadier merges it with
 * [org.valkyrienskies.eureka.armada.ArmadaCommand]'s and PathCommand's roots. It must never gain a
 * CLIENT-side "armada" root -- see the registration note in EurekaModFabric.
 */
object ArmadaTuningCommand {

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            literal("armada")
                .then(cannons())
                .then(cannonballs())
        )
    }

    /** The one line that differs between the 1.21.11 and 1.21.1 trees. */
    private fun gate(src: CommandSourceStack): Boolean =
        src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)

    // region Node builders -- GET when bare, SET-and-save with a value

    private fun store(ctx: CommandContext<CommandSourceStack>, label: String, write: () -> Unit, shown: String): Int {
        write()
        EurekaConfigLoader.save()
        ctx.source.sendSuccess({ Component.literal("$label = $shown").withStyle(ChatFormatting.GREEN) }, true)
        return 1
    }

    private fun show(ctx: CommandContext<CommandSourceStack>, label: String, shown: String): Int {
        ctx.source.sendSuccess({ Component.literal("$label = $shown").withStyle(ChatFormatting.AQUA) }, false)
        return 1
    }

    private fun doubleNode(word: String, label: String, unit: String, prop: KMutableProperty0<Double>):
        LiteralArgumentBuilder<CommandSourceStack> =
        literal(word)
            .executes { show(it, label, "${prop.get()} $unit") }
            .then(
                argument("value", DoubleArgumentType.doubleArg(0.0)).executes { ctx ->
                    val value = DoubleArgumentType.getDouble(ctx, "value")
                    store(ctx, label, { prop.set(value) }, "$value $unit")
                }
            )

    private fun intNode(word: String, label: String, unit: String, prop: KMutableProperty0<Int>):
        LiteralArgumentBuilder<CommandSourceStack> =
        literal(word)
            .executes { show(it, label, "${prop.get()} $unit") }
            .then(
                argument("value", IntegerArgumentType.integer(0)).executes { ctx ->
                    val value = IntegerArgumentType.getInteger(ctx, "value")
                    store(ctx, label, { prop.set(value) }, "$value $unit")
                }
            )

    /**
     * A percent-ladder field ("80,70,50"). greedyString so no quoting is needed. [ChanceSpec.parse] cannot
     * reject -- it drops what it cannot read -- so a malformed entry is stored as written and WARNED about,
     * matching the config file's own lenient philosophy.
     */
    private fun chancesNode(word: String, label: String, meaning: String, prop: KMutableProperty0<String>):
        LiteralArgumentBuilder<CommandSourceStack> =
        literal(word)
            .executes { show(it, label, "\"${prop.get()}\" -> ${ChanceSpec.parse(prop.get()).size} $meaning") }
            .then(
                argument("spec", StringArgumentType.greedyString()).executes { ctx ->
                    val spec = StringArgumentType.getString(ctx, "spec")
                    val result = store(
                        ctx, label, { prop.set(spec) },
                        "\"$spec\" -> ${ChanceSpec.parse(spec).size} $meaning"
                    )
                    val tokens = spec.split(',', ';').count { it.isNotBlank() }
                    val parsed = ChanceSpec.parse(spec).size
                    if (parsed < tokens) {
                        ctx.source.sendSuccess({
                            Component.literal("${tokens - parsed} of $tokens entries were not numbers and will be ignored.")
                                .withStyle(ChatFormatting.YELLOW)
                        }, false)
                    }
                    result
                }
            )

    /** One node covering a per-powder-charge triple, plus "all" to sweep the three at once. */
    private fun chargeNode(name: String, unit: String, charges: List<Pair<String, KMutableProperty0<Double>>>):
        LiteralArgumentBuilder<CommandSourceStack> {
        val node = literal(name)
        for ((chargeLabel, prop) in charges) {
            node.then(doubleNode(chargeLabel, "$name $chargeLabel", unit, prop))
        }
        node.then(
            literal("all")
                .executes { ctx ->
                    show(ctx, name, charges.joinToString("  ") { "${it.first}=${it.second.get()}" } + " $unit")
                }
                .then(
                    argument("value", DoubleArgumentType.doubleArg(0.0)).executes { ctx ->
                        val value = DoubleArgumentType.getDouble(ctx, "value")
                        store(ctx, "$name 1x/2x/3x", { charges.forEach { it.second.set(value) } }, "$value $unit")
                    }
                )
        )
        return node
    }

    // endregion

    // region /armada cannons

    private fun cannons(): LiteralArgumentBuilder<CommandSourceStack> {
        val cfg = EurekaConfig.SERVER
        return literal("cannons")
            .requires { gate(it) }
            .executes { cannonsInfo(it) }
            .then(literal("info").executes { cannonsInfo(it) })
            .then(
                chargeNode(
                    "reload", "s",
                    listOf(
                        "1x" to cfg::cannonReloadSeconds1x,
                        "2x" to cfg::cannonReloadSeconds2x,
                        "3x" to cfg::cannonReloadSeconds3x
                    )
                )
            )
            .then(
                chargeNode(
                    "speed", "blocks/tick",
                    listOf(
                        "1x" to cfg::cannonShotSpeed1x,
                        "2x" to cfg::cannonShotSpeed2x,
                        "3x" to cfg::cannonShotSpeed3x
                    )
                )
            )
            .then(
                chargeNode(
                    "gravity", "blocks/tick^2",
                    listOf(
                        "1x" to cfg::cannonShotGravity1x,
                        "2x" to cfg::cannonShotGravity2x,
                        "3x" to cfg::cannonShotGravity3x
                    )
                )
            )
            .then(
                chargeNode(
                    "drag", "kept/tick",
                    listOf(
                        "1x" to cfg::cannonShotDrag1x,
                        "2x" to cfg::cannonShotDrag2x,
                        "3x" to cfg::cannonShotDrag3x
                    )
                )
            )
            .then(
                literal("fire-at-will")
                    .then(doubleNode("crew", "fire-at-will crew", "s", cfg::cannonFireAtWillFireRateSeconds))
                    .then(doubleNode("pirate", "fire-at-will pirate", "s", cfg::pirateFireAtWillFireRateSeconds))
            )
            .then(doubleNode("max-flight-seconds", "max-flight-seconds", "s", cfg::cannonShotMaxFlightSeconds))
            .then(
                literal("voxy-lod")
                    .executes { show(it, "voxy-lod", if (cfg.cannonballVoxyLodUpdates) "on" else "off") }
                    .then(literal("on").executes { store(it, "voxy-lod", { cfg.cannonballVoxyLodUpdates = true }, "on") })
                    .then(literal("off").executes { store(it, "voxy-lod", { cfg.cannonballVoxyLodUpdates = false }, "off") })
            )
    }

    private fun cannonsInfo(ctx: CommandContext<CommandSourceStack>): Int {
        val cfg = EurekaConfig.SERVER
        val src = ctx.source
        src.sendSuccess({ Component.literal("Cannon tuning:").withStyle(ChatFormatting.GOLD) }, false)
        val lines = listOf(
            "reload 1x/2x/3x = ${cfg.cannonReloadSeconds1x}/${cfg.cannonReloadSeconds2x}/${cfg.cannonReloadSeconds3x} s",
            "speed 1x/2x/3x = ${cfg.cannonShotSpeed1x}/${cfg.cannonShotSpeed2x}/${cfg.cannonShotSpeed3x} blocks/tick",
            "gravity 1x/2x/3x = ${cfg.cannonShotGravity1x}/${cfg.cannonShotGravity2x}/${cfg.cannonShotGravity3x} blocks/tick^2",
            "drag 1x/2x/3x = ${cfg.cannonShotDrag1x}/${cfg.cannonShotDrag2x}/${cfg.cannonShotDrag3x} kept/tick",
            "fire-at-will crew = ${cfg.cannonFireAtWillFireRateSeconds} s, pirate = ${cfg.pirateFireAtWillFireRateSeconds} s",
            "max-flight-seconds = ${cfg.cannonShotMaxFlightSeconds} s",
            "voxy-lod = " + (if (cfg.cannonballVoxyLodUpdates) "on" else "off"),
            "render distance is client-side -- each player sets it with /vs cannonball-render-distance"
        )
        for (line in lines) src.sendSuccess({ Component.literal("  $line").withStyle(ChatFormatting.AQUA) }, false)
        return 1
    }

    // endregion

    // region /armada cannonballs

    private class Metal(
        val name: String,
        val guaranteed: KMutableProperty0<Int>,
        val chances: KMutableProperty0<String>,
        val incendiary: KMutableProperty0<Int>
    )

    private fun metals(): List<Metal> {
        val cfg = EurekaConfig.SERVER
        return listOf(
            Metal("copper", cfg::cannonballCopperGuaranteed, cfg::cannonballCopperExtraChances, cfg::cannonballCopperIncendiary),
            Metal("iron", cfg::cannonballIronGuaranteed, cfg::cannonballIronExtraChances, cfg::cannonballIronIncendiary),
            Metal("steel", cfg::cannonballSteelGuaranteed, cfg::cannonballSteelExtraChances, cfg::cannonballSteelIncendiary),
            Metal("gold", cfg::cannonballGoldGuaranteed, cfg::cannonballGoldExtraChances, cfg::cannonballGoldIncendiary),
            Metal("netherite", cfg::cannonballNetheriteGuaranteed, cfg::cannonballNetheriteExtraChances, cfg::cannonballNetheriteIncendiary)
        )
    }

    private fun cannonballs(): LiteralArgumentBuilder<CommandSourceStack> {
        val cfg = EurekaConfig.SERVER
        val root = literal("cannonballs")
            .requires { gate(it) }
            .executes { cannonballsInfo(it) }
            .then(literal("info").executes { cannonballsInfo(it) })
        for (metal in metals()) {
            root.then(
                literal(metal.name)
                    .then(intNode("guaranteed", "${metal.name} guaranteed", "blocks", metal.guaranteed))
                    .then(chancesNode("extra-chances", "${metal.name} extra-chances", "extra-block chances", metal.chances))
                    .then(intNode("incendiary", "${metal.name} incendiary", "fires", metal.incendiary))
            )
        }
        root.then(
            literal("explosive")
                .then(intNode("guaranteed", "explosive bonus guaranteed", "blocks", cfg::cannonExplosiveBonusGuaranteed))
                .then(chancesNode("chances", "explosive bonus chances", "bonus chances", cfg::cannonExplosiveBonusChances))
        )
        root.then(
            literal("armor-piercing")
                .then(
                    chancesNode(
                        "strikes", "armor-piercing strikes", "strikes (list length = impacts)",
                        cfg::cannonArmorPiercingStrikePercents
                    )
                )
        )
        root.then(
            literal("fire")
                .then(
                    literal("spreads")
                        .executes { show(it, "fire spreads", "${cfg.shipFireSpreads}") }
                        .then(
                            argument("value", BoolArgumentType.bool()).executes { ctx ->
                                val value = BoolArgumentType.getBool(ctx, "value")
                                val result = store(ctx, "fire spreads", { cfg.shipFireSpreads = value }, "$value")
                                if (value) {
                                    ctx.source.sendSuccess({
                                        Component.literal("While fire spreads, the fire watch stands down -- crews will not douse it.")
                                            .withStyle(ChatFormatting.YELLOW)
                                    }, false)
                                }
                                result
                            }
                        )
                )
                .then(doubleNode("burn-min", "fire burn-min", "s", cfg::shipFireBurnSecondsMin))
                .then(doubleNode("burn-max", "fire burn-max", "s", cfg::shipFireBurnSecondsMax))
                .then(doubleNode("watch-horizontal", "fire watch-horizontal", "blocks", cfg::fireWatchHorizontalBlocks))
                .then(doubleNode("watch-vertical", "fire watch-vertical", "blocks", cfg::fireWatchVerticalBlocks))
                .then(doubleNode("watch-douse", "fire watch-douse", "blocks", cfg::fireWatchDouseBlocks))
                .then(doubleNode("watch-rest", "fire watch-rest", "s", cfg::fireWatchRestSeconds))
        )
        return root
    }

    private fun cannonballsInfo(ctx: CommandContext<CommandSourceStack>): Int {
        val cfg = EurekaConfig.SERVER
        val src = ctx.source
        src.sendSuccess({ Component.literal("Cannonball tuning:").withStyle(ChatFormatting.GOLD) }, false)
        val lines = ArrayList<String>()
        for (metal in metals()) {
            lines.add(
                "${metal.name}: guaranteed ${metal.guaranteed.get()}, extra \"${metal.chances.get()}\", " +
                    "incendiary ${metal.incendiary.get()}"
            )
        }
        lines.add(
            "explosive bonus: guaranteed ${cfg.cannonExplosiveBonusGuaranteed}, " +
                "chances \"${cfg.cannonExplosiveBonusChances}\""
        )
        lines.add("armor-piercing strikes: \"${cfg.cannonArmorPiercingStrikePercents}\"")
        lines.add(
            "fire: spreads ${cfg.shipFireSpreads}, burn ${cfg.shipFireBurnSecondsMin}-${cfg.shipFireBurnSecondsMax} s, " +
                "watch ${cfg.fireWatchHorizontalBlocks}x${cfg.fireWatchVerticalBlocks} douse ${cfg.fireWatchDouseBlocks} " +
                "rest ${cfg.fireWatchRestSeconds} s"
        )
        for (line in lines) src.sendSuccess({ Component.literal("  $line").withStyle(ChatFormatting.AQUA) }, false)
        return 1
    }

    // endregion
}
