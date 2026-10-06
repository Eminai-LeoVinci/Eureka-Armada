package org.valkyrienskies.eureka.fabric.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaConfigLoader
import org.valkyrienskies.eureka.ship.ShipInfluenceOrientation
import org.valkyrienskies.mod.common.VSClientGameUtils
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.reflect.KMutableProperty0

/**
 * In-game tools for the ship influence border: how far past a ship's hull an airborne player stays carried by it
 * (see ShipInfluence). All client commands on the client `/vs` root, merged with VS2's server `/vs` by
 * MixinClientCommandInternals and MixinClientPacketListenerCommandMerge:
 *
 *  - `/vs influence-border <true|false>` draws every loaded ship's border as a thin blue wireframe. Off at every
 *    launch (a debug view, not a setting).
 *  - `/vs influence-border` prints the six current values.
 *  - `/vs expand-influence <blocks> <face>` and `/vs contract-influence <blocks> <face>` move one face (Top, Bottom,
 *    Left, Right, Front, Back) and save it to `vs_eureka_armada.json`. Never below 0, the bare hull. The carry reads
 *    the values live, so a change applies at once.
 *
 * The wireframe is the exact box ShipInfluence.insideBorder tests: the ship-space hull box grown per face, Front/Back/
 * Left/Right following the helm (ShipInfluenceOrientation), drawn under the ship's render transform so it turns and
 * tilts with the hull.
 *
 * Our VS2 build used to carry these; they live in Armada now so Armada runs on the official release.
 */
@Environment(EnvType.CLIENT)
object InfluenceBorder {

    // Slightly cyan blue, as in our VS2 build.
    private const val R = 0.0f
    private const val G = 0.45f
    private const val B = 1.0f
    private const val A = 1.0f

    private val FACES = listOf("Top", "Bottom", "Left", "Right", "Front", "Back")

    /** Is the wireframe showing? Not saved: off at every launch. */
    private var showBorder = false

    /** Call once from the client initializer. */
    fun register() {
        WorldRenderEvents.AFTER_ENTITIES.register { render(it) }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("vs")
                    .then(
                        ClientCommandManager.literal("influence-border")
                            .executes { report(it.source) }
                            .then(
                                ClientCommandManager.argument("enabled", BoolArgumentType.bool()).executes { ctx ->
                                    showBorder = BoolArgumentType.getBool(ctx, "enabled")
                                    ctx.source.sendFeedback(
                                        Component.literal("Influence border wireframe " + if (showBorder) "on" else "off")
                                    )
                                    1
                                }
                            )
                    )
                    .then(adjustCommand("expand-influence", 1.0))
                    .then(adjustCommand("contract-influence", -1.0))
            )
        }
    }

    private fun adjustCommand(name: String, sign: Double): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommandManager.literal(name).then(
            ClientCommandManager.argument("amount", DoubleArgumentType.doubleArg(0.0)).then(
                ClientCommandManager.argument("direction", StringArgumentType.word())
                    .suggests { _, builder ->
                        FACES.forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        adjust(
                            ctx.source,
                            StringArgumentType.getString(ctx, "direction"),
                            sign * DoubleArgumentType.getDouble(ctx, "amount")
                        )
                    }
            )
        )

    private fun face(name: String): KMutableProperty0<Double>? {
        val c = EurekaConfig.CLIENT
        return when (name.lowercase()) {
            "top" -> c::influenceExtendTop
            "bottom" -> c::influenceExtendBottom
            "left" -> c::influenceExtendLeft
            "right" -> c::influenceExtendRight
            "front" -> c::influenceExtendFront
            "back" -> c::influenceExtendBack
            else -> null
        }
    }

    /** Move one face by [delta] blocks (negative contracts), clamped at 0, and save. */
    private fun adjust(source: FabricClientCommandSource, direction: String, delta: Double): Int {
        val value = face(direction) ?: run {
            source.sendError(
                Component.literal("Unknown direction '$direction': use Top, Bottom, Left, Right, Front or Back.")
            )
            return 0
        }
        val before = value.get()
        val after = (before + delta).coerceAtLeast(0.0)
        value.set(after)
        EurekaConfigLoader.save()
        val name = direction.lowercase().replaceFirstChar { it.uppercase() }
        source.sendFeedback(Component.literal("Influence border: $name %.1f -> %.1f blocks".format(before, after)))
        return 1
    }

    private fun report(source: FabricClientCommandSource): Int {
        val values = FACES.joinToString(", ") { "$it %.1f".format(face(it)!!.get()) }
        source.sendFeedback(
            Component.literal(
                "Influence border (blocks past the hull): $values. Wireframe " + if (showBorder) "on." else "off."
            )
        )
        return 1
    }

    private fun render(context: WorldRenderContext) {
        if (!showBorder) return

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val poseStack: PoseStack = context.matrixStack() ?: return
        val consumer = context.consumers()?.getBuffer(RenderType.lines()) ?: return

        // matrixStack() is camera-relative.
        val cam = mc.gameRenderer.mainCamera.position

        // Read live each frame so a command or config edit shows at once.
        val cfg = EurekaConfig.CLIENT

        for (ship in level.shipObjectWorld.loadedShips) {
            val shipAABB = ship.shipAABB ?: continue

            // Same box as ShipInfluence.insideBorder; it can be lopsided, so centre on its own middle.
            val h = ShipInfluenceOrientation.horizontalExtents(
                ShipInfluenceOrientation.forwardFor(ship.id),
                cfg.influenceExtendFront, cfg.influenceExtendBack, cfg.influenceExtendLeft, cfg.influenceExtendRight
            )
            val minX = shipAABB.minX() - h[0]
            val maxX = shipAABB.maxX() + h[1]
            val minY = shipAABB.minY() - cfg.influenceExtendBottom
            val maxY = shipAABB.maxY() + cfg.influenceExtendTop
            val minZ = shipAABB.minZ() - h[2]
            val maxZ = shipAABB.maxZ() + h[3]

            // Build the box around the origin (shipyard coordinates are huge, floats lose precision there) and put
            // the centre back through the ship's render transform.
            val cx = (minX + maxX) * 0.5
            val cy = (minY + maxY) * 0.5
            val cz = (minZ + maxZ) * 0.5

            poseStack.pushPose()
            VSClientGameUtils.transformRenderWithShip(ship.renderTransform, poseStack, cx, cy, cz, cam.x, cam.y, cam.z)
            LevelRenderer.renderLineBox(
                poseStack, consumer,
                AABB(minX - cx, minY - cy, minZ - cz, maxX - cx, maxY - cy, maxZ - cz),
                R, G, B, A
            )
            poseStack.popPose()
        }
    }
}
