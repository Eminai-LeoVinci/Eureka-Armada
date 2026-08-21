package org.valkyrienskies.eureka.fabric.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes
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
        WorldRenderEvents.AFTER_ENTITIES.register(
            WorldRenderEvents.AfterEntities { context ->
                if (broken || !PirateGunnery.publishRanges) return@AfterEntities
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
        val spheres = PirateGunnery.publishedRanges(level.dimension().identifier().toString())
        if (spheres.isEmpty()) return

        val poseStack: PoseStack = context.matrices() ?: return
        val consumer = context.consumers()?.getBuffer(RenderTypes.lines()) ?: return
        val cam = mc.gameRenderer.mainCamera.position()
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

        consumer.addVertex(pose, (from.x - camX).toFloat(), (from.y - camY).toFloat(), (from.z - camZ).toFloat())
            .setColor(argb)
            .setNormal(pose, nx.toFloat(), ny.toFloat(), nz.toFloat())
            .setLineWidth(LINE_WIDTH)
        consumer.addVertex(pose, (to.x - camX).toFloat(), (to.y - camY).toFloat(), (to.z - camZ).toFloat())
            .setColor(argb)
            .setNormal(pose, nx.toFloat(), ny.toFloat(), nz.toFloat())
            .setLineWidth(LINE_WIDTH)
    }

    /** Gunmetal amber: not one of the zone ladder's four, so the two overlays read apart when both are up. */
    private const val RANGE_ARGB = 0xD0FFD766.toInt()

    private const val CIRCLE_SEGMENTS = 24
    private const val LINE_WIDTH = 2.0f
}
