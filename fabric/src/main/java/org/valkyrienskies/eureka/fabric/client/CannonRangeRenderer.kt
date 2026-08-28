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
import org.valkyrienskies.eureka.pirate.PirateGunnery
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cannon engage ranges as wireframe spheres -- "/vs cannon-range <bool>". DEV ONLY, strip-listed with
 * the command that toggles it: the gunnery bench's picture, for reading solved arcs against the range
 * the AI actually fires inside.
 *
 * [PirateZoneRenderer]'s shape wholesale -- AFTER_ENTITIES, kill-switch, camera-relative doubles,
 * per-vertex line width -- reading [PirateGunnery.publishedRanges]'s immutable snapshots, never live
 * server state. One colour: a sphere here means one thing (guns reach this far), unlike the zones'
 * four-state ladder.
 */
@Environment(EnvType.CLIENT)
object CannonRangeRenderer {

    private var broken = false
    private val logger = org.slf4j.LoggerFactory.getLogger("vs_eureka")

    private val a = Vector3d()
    private val b = Vector3d()
    private val centre = Vector3d()

    fun register() {
        WorldRenderEvents.AFTER_ENTITIES.register({ context ->
                if (broken || !PirateGunnery.publishRanges) return@register
                try {
                    render(context)
                } catch (t: Throwable) {
                    broken = true
                    logger.error("Cannon range renderer failed and has been disabled for this session", t)
                }
            }
        )
    }

    private fun render(context: WorldRenderContext) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val spheres = PirateGunnery.publishedRanges(level.dimension().location().toString())
        if (spheres.isEmpty()) return

        val poseStack: PoseStack = context.matrixStack() ?: return
        val consumer = context.consumers()?.getBuffer(RenderType.lines()) ?: return
        val cam = mc.gameRenderer.mainCamera.position
        val pose = poseStack.last()

        for (range in spheres) {
            centre.set(range.x, range.y, range.z)
            sphere(consumer, pose, centre, range.radius, cam.x, cam.y, cam.z, RANGE_ARGB)
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

        consumer.vertex(pose.pose(), (from.x - camX).toFloat(), (from.y - camY).toFloat(), (from.z - camZ).toFloat()).color(argb)
            .normal(pose.normal(), nx.toFloat(), ny.toFloat(), nz.toFloat()).endVertex()
        consumer.vertex(pose.pose(), (to.x - camX).toFloat(), (to.y - camY).toFloat(), (to.z - camZ).toFloat()).color(argb)
            .normal(pose.normal(), nx.toFloat(), ny.toFloat(), nz.toFloat()).endVertex()
    }

    /** Gunmetal amber: not one of the zone ladder's four, so the two overlays read apart when both are up. */
    private const val RANGE_ARGB = 0xD0FFD766.toInt()

    private const val CIRCLE_SEGMENTS = 24
    private const val LINE_WIDTH = 2.0f
}
