package org.valkyrienskies.eureka.fabric.client

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.phys.AABB
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.eureka.ship.ShipWreck
import org.valkyrienskies.mod.common.VSClientGameUtils
import org.valkyrienskies.mod.common.IShipObjectWorldClientProvider

/**
 * The wreck box, drawn -- "/vs wreck-box <bool>". DEV ONLY, strip-listed with the command that toggles it.
 *
 * [ShipWreck]'s box is a number -- the depth a hull buries herself to when she comes apart -- which leaves
 * "how far under is she actually going?" a question with no visible answer at all. This is the answer:
 *
 *  - **GREEN** -- a sound hull. This is the box she WOULD settle onto, and the size tier she falls into.
 *  - **RED** -- she is a wreck. When she breaks up she goes under by the distance from her keel to the
 *    bottom of that box, and onto her side.
 *
 * Drawn on healthy ships too, so the three size tiers can be compared at a glance before anything has
 * happened to any of them.
 *
 * ## Drawn in ship space, on purpose
 * The bounds cross from the server in SHIPYARD coordinates and are pushed out through the hull's own
 * interpolated render transform, exactly as VS2's own influence-border overlay does. That makes the box
 * ORIENTED -- it rolls as she rolls, which matters here more than anywhere, because "she tipped onto her
 * side" is the behaviour being checked and an axis-aligned box could not show it.
 *
 * [WorldRenderEvents.AFTER_ENTITIES] with camera-relative matrices, a kill-switch so a rendering fault
 * costs the decoration rather than the session, and per-vertex line width (mandatory on 1.21.11) -- the
 * shape [PirateZoneRenderer] and [CannonRangeRenderer] already established.
 */
@Environment(EnvType.CLIENT)
object WreckBoxRenderer {

    // Green: a sound hull, and the depth she would bury to. Red: a wreck, and the depth she will.
    private const val PLANNED_ARGB = 0xC03BE55A.toInt()
    private const val ACTIVE_ARGB = 0xF0FF3333.toInt()
    private const val LINE_WIDTH = 3.0f

    private var broken = false
    private val logger = org.slf4j.LoggerFactory.getLogger("vs_eureka")

    fun register() {
        WorldRenderEvents.AFTER_ENTITIES.register({ context ->
                if (broken || !ShipWreck.publishBoxes) return@register
                try {
                    render(context)
                } catch (t: Throwable) {
                    broken = true
                    logger.error("Wreck box renderer failed and has been disabled for this session", t)
                }
            }
        )
    }

    private fun render(context: WorldRenderContext) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val boxes = ShipWreck.publishedBoxes(level.dimension().location().toString())
        if (boxes.isEmpty()) return

        val poseStack: PoseStack = context.matrixStack() ?: return
        val consumer = context.consumers()?.getBuffer(RenderType.lines()) ?: return
        val cam = mc.gameRenderer.mainCamera.position

        // Reached through the provider interface rather than VS2's `shipObjectWorld` extension: this source
        // set can see VS2's CLASSES from Kotlin but not its top-level file facades (VSGameUtilsKt), which is
        // why the Java files here call that one as a static and this does not go near it.
        val ships = (mc as? IShipObjectWorldClientProvider)?.shipObjectWorld?.loadedShips ?: return

        for (view in boxes) {
            val ship = ships.getById(view.shipId) ?: continue

            // Built around the origin and the centre put back by the transform below: ship-space coordinates
            // run to the hundreds of thousands, and a wireframe built out there is a wireframe made of float
            // error. VS2's influence-border overlay does the same for the same reason.
            val cx = (view.minX + view.maxX) * 0.5
            val cy = (view.minY + view.maxY) * 0.5
            val cz = (view.minZ + view.maxZ) * 0.5
            val box = AABB(
                view.minX - cx, view.minY - cy, view.minZ - cz,
                view.maxX - cx, view.maxY - cy, view.maxZ - cz
            )

            // The INTERPOLATED transform where there is one: a box drawn off the tick transform judders
            // against the hull it is supposed to be inside, which would read as the box being wrong.
            val transform = (ship as? ClientShip)?.renderTransform ?: ship.transform

            poseStack.pushPose()
            VSClientGameUtils.transformRenderWithShip(
                transform, poseStack, cx, cy, cz, cam.x, cam.y, cam.z
            )
            // 1.21.1: renderLineBox is the public line-box path (the modern ShapeRenderer.renderShape,
            // and its per-vertex line width, arrive later). Colour unpacked from the same ARGB constants.
            val argb = if (view.active) ACTIVE_ARGB else PLANNED_ARGB
            LevelRenderer.renderLineBox(
                poseStack, consumer, box,
                (argb shr 16 and 0xFF) / 255f, (argb shr 8 and 0xFF) / 255f,
                (argb and 0xFF) / 255f, (argb ushr 24) / 255f
            )
            poseStack.popPose()
        }
    }
}
