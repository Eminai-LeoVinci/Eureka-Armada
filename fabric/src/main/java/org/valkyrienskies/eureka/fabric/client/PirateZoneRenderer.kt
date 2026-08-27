package org.valkyrienskies.eureka.fabric.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import org.joml.Vector3d
import org.valkyrienskies.eureka.pirate.PirateShips
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pirate proximity zones as wireframe spheres -- "/vs pirate-zones <bool>". DEV ONLY, strip-listed
 * with the command that toggles it; the finished feature has no visible zone at all.
 *
 * [PathRenderer]'s shape exactly: AFTER_ENTITIES, a kill-switch so a rendering fault costs the decoration
 * and not the session, camera-relative doubles, per-vertex line width (mandatory on 1.21.11). The sphere and
 * segment are small local copies of PathRenderer's rather than shared internals -- that renderer's scratch
 * state is its own, and forty duplicated lines in a file that ships dormant beat coupling two renderers.
 *
 * Zone data crosses from the integrated server as the immutable snapshots [PirateShips.publishedZones]
 * publishes per tick -- never the manager's live maps, which belong to the server thread.
 */
@Environment(EnvType.CLIENT)
object PirateZoneRenderer {

    private var broken = false
    private val logger = org.slf4j.LoggerFactory.getLogger("vs_eureka")

    private val a = Vector3d()
    private val b = Vector3d()
    private val centre = Vector3d()

    fun register() {
        WorldRenderEvents.AFTER_ENTITIES.register({ context ->
                if (broken || !PirateShips.publishZones) return@register
                try {
                    render(context)
                } catch (t: Throwable) {
                    broken = true
                    logger.error("Pirate zone renderer failed and has been disabled for this session", t)
                }
            }
        )
    }

    private fun render(context: WorldRenderContext) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val zones = PirateShips.publishedZones(level.dimension().location().toString())
        if (zones.isEmpty()) return

        val poseStack: PoseStack = context.matrixStack() ?: return
        val consumer = context.consumers()?.getBuffer(RenderType.lines()) ?: return
        val cam = mc.gameRenderer.mainCamera.position
        val pose = poseStack.last()

        for (zone in zones) {
            centre.set(zone.x, zone.y, zone.z)
            // The ship's state at a glance, cool to hot: sleeping, awake, winding up, coming for you.
            val argb = when (zone.state) {
                PirateShips.ZoneState.DORMANT -> 0xA044DDEE.toInt()  // blue
                PirateShips.ZoneState.AWAKE -> 0xC055EE77.toInt()    // green
                PirateShips.ZoneState.COUNTING -> 0xE0FFAA33.toInt() // orange
                PirateShips.ZoneState.PURSUING -> 0xE0FF4444.toInt() // red
            }
            sphere(consumer, pose, centre, zone.radius, cam.x, cam.y, cam.z, argb)
        }
    }

    /** A wireframe sphere as three great circles -- PathRenderer's drawing, copied. */
    private fun sphere(
        consumer: VertexConsumer, pose: PoseStack.Pose, centre: Vector3d, radius: Double,
        camX: Double, camY: Double, camZ: Double, argb: Int
    ) {
        for (axis in 0..2) {
            var prevU = radius
            var prevV = 0.0
            for (step in 1..CIRCLE_SEGMENTS) {
                val t = step.toDouble() / CIRCLE_SEGMENTS * 2.0 * PI
                val u = cos(t) * radius
                val v = sin(t) * radius
                when (axis) {
                    0 -> { a.set(centre.x + prevU, centre.y + prevV, centre.z); b.set(centre.x + u, centre.y + v, centre.z) }
                    1 -> { a.set(centre.x + prevU, centre.y, centre.z + prevV); b.set(centre.x + u, centre.y, centre.z + v) }
                    else -> { a.set(centre.x, centre.y + prevU, centre.z + prevV); b.set(centre.x, centre.y + u, centre.z + v) }
                }
                segment(consumer, pose, a, b, camX, camY, camZ, argb)
                prevU = u
                prevV = v
            }
        }
    }

    private fun segment(
        consumer: VertexConsumer, pose: PoseStack.Pose, from: Vector3d, to: Vector3d,
        camX: Double, camY: Double, camZ: Double, argb: Int
    ) {
        var nx = to.x - from.x
        var ny = to.y - from.y
        var nz = to.z - from.z
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1.0e-9) return
        nx /= len; ny /= len; nz /= len

        consumer.addVertex(pose, (from.x - camX).toFloat(), (from.y - camY).toFloat(), (from.z - camZ).toFloat())
            .setColor(argb)
            .setNormal(pose, nx.toFloat(), ny.toFloat(), nz.toFloat())
        consumer.addVertex(pose, (to.x - camX).toFloat(), (to.y - camY).toFloat(), (to.z - camZ).toFloat())
            .setColor(argb)
            .setNormal(pose, nx.toFloat(), ny.toFloat(), nz.toFloat())
    }

    private const val CIRCLE_SEGMENTS = 24
    private const val LINE_WIDTH = 2.0f
}
