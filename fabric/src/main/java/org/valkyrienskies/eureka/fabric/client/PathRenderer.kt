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
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.path.ClientPathState
import org.valkyrienskies.eureka.path.PathRecorder
import org.valkyrienskies.eureka.path.ShipPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws recorded routes, the live trail while recording, and the two snap markers that close a loop.
 *
 * Hooked at [WorldRenderEvents.AFTER_ENTITIES] -- the same place VS2's ship influence border draws, and where
 * vanilla entity hitboxes used to. That pass runs inside the main level render with camera-relative matrices
 * already set and the level renderer's own buffer source wired up, so it is both the least code and the pass
 * Iris captures.
 *
 * ## What is drawn when
 * - A route being RECORDED: its trail, always, for the player recording it.
 * - The two SNAP MARKERS: only once the recording arms (back in the start chunk, far enough round to be a
 *   loop). They pulse, and brighten as the gap closes, so "nearly touching" reads at a glance.
 * - A route being FLOWN: unless hidden, offset to where the ship is actually flying it.
 * - Every SAVED route: only while the SHIFT+H toggle is on.
 *
 * Hiding beats both -- SHIFT+H clears everything on screen, whatever its reason for being there.
 *
 * ## Draw distance
 * Vertices are emitted in camera-relative double precision, so a route stays exact however far from the world
 * origin it was recorded. Visible RANGE, though, is currently bounded by the vanilla line layer: it runs a
 * `rendertype_` shader that applies fog, and the projection's far plane comes from render distance. Routes far
 * past your render distance will therefore fade out rather than draw. Lifting that needs a dedicated unfogged
 * pipeline with its own far plane -- the shape [ArmadaPocketOccluder] already uses for its depth pass.
 */
@Environment(EnvType.CLIENT)
object PathRenderer {

    /**
     * Set on the first failure. A route line is decoration; anything going wrong in here must cost the player
     * the decoration, not the session. Mirrors [ArmadaPocketOccluder]'s `broken` flag -- learned the hard way,
     * when one missing vertex element took the whole client down mid-recording.
     */
    private var broken = false

    private val logger = org.slf4j.LoggerFactory.getLogger("vs_eureka")

    fun register() {
        WorldRenderEvents.AFTER_ENTITIES.register({ context ->
                if (broken) return@register
                try {
                    render(context)
                } catch (t: Throwable) {
                    broken = true
                    logger.error("Path renderer failed and has been disabled for this session", t)
                }
            }
        )
    }

    private val a = Vector3d()
    private val b = Vector3d()

    /**
     * Per-vertex line width, read once per frame.
     *
     * 1.21.11's line format carries LineWidth as a vertex ELEMENT (the old global `glLineWidth` is gone), and
     * the buffer rejects any vertex that leaves it unset -- which is why `ShapeRenderer.renderShape` takes a
     * width argument. Every vertex emitted here must set it.
     */
    private var lineWidth = 2.0f

    private fun render(context: WorldRenderContext) {
        val state = ClientPathState
        if (state.routes.isEmpty() && state.recordings.isEmpty()) return

        val mc = Minecraft.getInstance()
        if (mc.level == null) return

        val poseStack: PoseStack = context.matrixStack() ?: return
        val consumer = context.consumers()?.getBuffer(RenderType.lines()) ?: return
        val cam = mc.gameRenderer.mainCamera.position

        val maxDist = EurekaConfig.CLIENT.pathRenderDistance
        val maxDistSq = maxDist * maxDist
        val pose = poseStack.last()
        lineWidth = EurekaConfig.CLIENT.pathLineWidth.toFloat().coerceIn(0.5f, 16.0f)

        // Routes currently being flown, so they can be drawn at the offset the ship is actually holding.
        val flown = HashMap<Long, Vector3d>()
        for (f in state.following.values) flown[f.pathId] = f.offset

        for (route in state.routes.values) {
            val offset = flown[route.id]
            // One predicate, shared with the hide key, so the two can't disagree about what is on screen.
            if (!state.isVisible(route.id, offset != null)) continue
            drawRoute(consumer, pose, route.path, offset, cam.x, cam.y, cam.z, maxDistSq, colorFor(route.id, offset != null))
            drawDwells(consumer, pose, route, offset, cam.x, cam.y, cam.z, maxDistSq)
        }

        for (recording in state.recordings.values) {
            drawTrail(consumer, pose, recording, cam.x, cam.y, cam.z, maxDistSq)
            if (recording.armed) drawSnapMarkers(consumer, pose, recording, cam.x, cam.y, cam.z)
        }
    }

    // region routes

    private fun drawRoute(
        consumer: VertexConsumer, pose: PoseStack.Pose, path: ShipPath, offset: Vector3d?,
        camX: Double, camY: Double, camZ: Double, maxDistSq: Double, argb: Int
    ) {
        val ox = offset?.x ?: 0.0
        val oy = offset?.y ?: 0.0
        val oz = offset?.z ?: 0.0

        // The dense polyline is 1 block per point, which is far finer than anyone can see. Striding keeps the
        // vertex count sane on a multi-kilometre loop without any visible change to the drawn curve.
        val n = path.pointCount
        val stride = if (n > MAX_DRAWN_POINTS) (n / MAX_DRAWN_POINTS) + 1 else 1

        var i = 0
        while (i < n) {
            val j = (i + stride).coerceAtMost(n)
            a.set(path.x(i) + ox, path.y(i) + oy, path.z(i) + oz)
            b.set(path.x(j) + ox, path.y(j) + oy, path.z(j) + oz)
            segmentIfNear(consumer, pose, a, b, camX, camY, camZ, maxDistSq, argb)
            i += stride
        }
    }

    /**
     * A small sphere wherever a replayed ship will stop and wait.
     *
     * Drawn on the route rather than only while a ship is actually stopped there, because the question these
     * answer is asked long before that: "did my pause record?" Without them the only way to find out is to fly
     * the whole loop and see whether the ship stops -- and a pause too short to register would look exactly
     * like a pause that recorded fine but has not come round yet.
     *
     * Deliberately not scaled by anything. A stop is a point on the line, not a hull-sized target to hit, so
     * unlike the snap markers there is nothing here for a size to mean.
     */
    private fun drawDwells(
        consumer: VertexConsumer, pose: PoseStack.Pose, route: ClientPathState.Route, offset: Vector3d?,
        camX: Double, camY: Double, camZ: Double, maxDistSq: Double
    ) {
        if (route.dwellArcs.isEmpty()) return
        val ox = offset?.x ?: 0.0
        val oy = offset?.y ?: 0.0
        val oz = offset?.z ?: 0.0

        for (arc in route.dwellArcs) {
            route.path.sampleAt(arc, a)
            a.add(ox, oy, oz)
            val dx = a.x - camX; val dy = a.y - camY; val dz = a.z - camZ
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue
            sphere(consumer, pose, a, DWELL_RADIUS, camX, camY, camZ, DWELL_ARGB)
        }
    }

    private fun drawTrail(
        consumer: VertexConsumer, pose: PoseStack.Pose, rec: ClientPathState.Recording,
        camX: Double, camY: Double, camZ: Double, maxDistSq: Double
    ) {
        val pts = rec.points
        val count = pts.size / 3
        if (count < 2) return
        for (i in 0 until count - 1) {
            a.set(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2])
            b.set(pts[(i + 1) * 3], pts[(i + 1) * 3 + 1], pts[(i + 1) * 3 + 2])
            segmentIfNear(consumer, pose, a, b, camX, camY, camZ, maxDistSq, RECORDING_ARGB)
        }
    }

    // endregion

    // region snap markers

    /**
     * The two spheres that close a loop: one anchored at the route's start, one riding the ship's keel.
     *
     * Both brighten as the gap shrinks, and the start marker swells slightly with the pulse, so the moment
     * they are about to touch is obvious without having to judge distance by eye.
     */
    private fun drawSnapMarkers(
        consumer: VertexConsumer, pose: PoseStack.Pose, rec: ClientPathState.Recording,
        camX: Double, camY: Double, camZ: Double
    ) {
        // Both spheres scale with the recording ship, so a big hull gets a target it can actually park on --
        // and since the spheres ARE the snap distance, what you see is still exactly what closes the loop.
        val scale = rec.markerScale
        val snapRadius = EurekaConfig.SERVER.pathSnapRadius * scale
        val touchAt = PathRecorder.touchDistance(scale)

        // 1 when touching, 0 when a chunk and a half further out. Drives both brightness and pulse rate.
        // Scaled with the markers: a hull that snaps from 13 blocks out needs its warning to start further
        // away too, or the whole ramp would be over before the spheres were anywhere near each other.
        val closeness = (1.0 - ((rec.gap - touchAt) / (CLOSENESS_RANGE * scale))).coerceIn(0.0, 1.0)

        val phase = (System.currentTimeMillis() % PULSE_PERIOD_MS).toDouble() / PULSE_PERIOD_MS
        val pulse = 0.5 + 0.5 * sin(phase * 2.0 * PI * (1.0 + closeness * 2.0))
        val alpha = (110 + (145 * (0.35 + 0.65 * pulse) * (0.4 + 0.6 * closeness))).toInt().coerceIn(0, 255)

        val startArgb = (alpha shl 24) or SNAP_START_RGB
        val shipArgb = (alpha shl 24) or SNAP_SHIP_RGB

        sphere(consumer, pose, rec.start, snapRadius * (1.0 + 0.06 * pulse), camX, camY, camZ, startArgb)
        sphere(consumer, pose, rec.keel, PathRecorder.SHIP_MARKER_RADIUS * scale, camX, camY, camZ, shipArgb)
    }

    /** A wireframe sphere as three great circles -- cheap, and reads as a sphere from any angle. */
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

    // endregion

    // region primitives

    private fun segmentIfNear(
        consumer: VertexConsumer, pose: PoseStack.Pose, from: Vector3d, to: Vector3d,
        camX: Double, camY: Double, camZ: Double, maxDistSq: Double, argb: Int
    ) {
        val dx = from.x - camX; val dy = from.y - camY; val dz = from.z - camZ
        if (dx * dx + dy * dy + dz * dz > maxDistSq) return
        segment(consumer, pose, from, to, camX, camY, camZ, argb)
    }

    /**
     * One line segment, emitted camera-relative.
     *
     * The subtraction happens in DOUBLE precision before the cast, which is what keeps a route recorded far
     * from the origin from shimmering: at 10 million blocks a float world coordinate has lost whole blocks,
     * but a float OFFSET from the camera is accurate to well under a millimetre.
     */
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

    /**
     * A stable colour per route, so the same loop is the same colour every session and two routes side by side
     * are told apart at a glance. Hue is hashed from the id; a route being flown is drawn brighter.
     */
    private fun colorFor(id: Long, flying: Boolean): Int {
        val hue = ((id * 2654435761L) and 0xFFFF).toDouble() / 65535.0
        val value = if (flying) 1.0 else 0.75
        val alpha = if (flying) 235 else 165
        return (alpha shl 24) or hsvToRgb(hue, 0.65, value)
    }

    private fun hsvToRgb(h: Double, s: Double, v: Double): Int {
        val i = (h * 6.0).toInt()
        val f = h * 6.0 - i
        val p = v * (1 - s)
        val q = v * (1 - f * s)
        val t = v * (1 - (1 - f) * s)
        val (r, g, bl) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (bl * 255).toInt()
    }

    // endregion

    /** Bright green-white; a recording in progress should not be mistakable for a saved route. */
    private const val RECORDING_ARGB = 0xFFB4FF64.toInt()

    private const val SNAP_START_RGB = 0x33FF88
    private const val SNAP_SHIP_RGB = 0xFFCC33

    /** Blocks of gap over which the markers fade in as they approach touching. */
    private const val CLOSENESS_RANGE = 24.0

    /** Amber, and distinct from both snap spheres: a stop is a property of the route, not of a recording. */
    private const val DWELL_ARGB = 0xCCFF9933.toInt()

    private const val DWELL_RADIUS = 1.25

    private const val PULSE_PERIOD_MS = 1400L
    private const val CIRCLE_SEGMENTS = 24

    /** Cap on drawn points per route; long loops are strided down to this. */
    private const val MAX_DRAWN_POINTS = 2000
}
