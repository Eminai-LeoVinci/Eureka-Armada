package org.valkyrienskies.eureka.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.getShipManaging
import kotlin.math.atan2

/**
 * Small top-center readouts for the ship the player is piloting -- speed / altitude / heading, each toggled
 * independently by the helm menu's display checkboxes ([EurekaConfig.Client]). Client-only; wired to Fabric's
 * HudRenderCallback in EurekaModFabric. Drawn at a reduced scale for small, subtle text.
 */
object EurekaSpeedHud {
    private const val COLOR = 0xC8FFFFFF.toInt() // subtle translucent white
    private const val SCALE = 0.66f // render smaller than the default font (slightly smaller than before)
    private const val TOP_MARGIN = 4f // screen pixels from the top edge
    private const val SEPARATOR = "  |  " // between the horizontal readouts

    // Indexed by round(yaw / 45) % 8 with MC's yaw convention (0 = +Z = south).
    private val COMPASS = arrayOf("S", "SW", "W", "NW", "N", "NE", "E", "SE")

    fun render(guiGraphics: GuiGraphics) {
        val cfg = EurekaConfig.CLIENT
        // displayHud is the master gate; the three are individual readouts under it.
        if (!cfg.displayHud) return
        if (!cfg.displaySpeed && !cfg.displayAltitude && !cfg.displayHeading) return
        val mc = Minecraft.getInstance()
        // Only while actually piloting a ship (seated on a helm); the seat sits in the ship's shipyard, so
        // getShipManaging() resolves the controlled ship client-side.
        val seat = mc.player?.vehicle as? ShipMountingEntity ?: return
        val ship = seat.getShipManaging() ?: return

        val parts = ArrayList<String>(3)
        if (cfg.displaySpeed) parts.add(String.format("%.1f", ship.velocity.length()) + "m/s")
        if (cfg.displayAltitude) parts.add("Y " + String.format("%.0f", ship.transform.positionInWorld.y()))
        if (cfg.displayHeading) parts.add(heading(ship))
        if (parts.isEmpty()) return

        // One horizontal line at top-center: "12.3m/s  |  Y 84  |  N 12°".
        // GuiGraphics.pose() is a 3D PoseStack, so push/scale(s,s,1f) and draw in the scaled space -- divide
        // the (already GUI-scaled) screen width by SCALE to keep the line top-centered at any GUI scale.
        val line = parts.joinToString(SEPARATOR)
        val font = mc.font
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.scale(SCALE, SCALE, 1f)
        val sx = (mc.window.guiScaledWidth / SCALE - font.width(line)) / 2f
        val sy = TOP_MARGIN / SCALE
        guiGraphics.drawString(font, line, Math.round(sx), Math.round(sy), COLOR, true)
        pose.popPose()
    }

    // Compass heading from the ship's facing (its local -Z = "forward" at assembly, rotated to world). Turns
    // with the ship (incl. cruise turning). Absolute value reflects the ship's orientation since assembly.
    private fun heading(ship: Ship): String {
        val fwd = Vector3d(0.0, 0.0, -1.0)
        ship.transform.shipToWorldRotation.transform(fwd)
        var yaw = Math.toDegrees(-atan2(fwd.x(), fwd.z()))
        yaw = ((yaw % 360.0) + 360.0) % 360.0
        return COMPASS[Math.round(yaw / 45.0).toInt() % 8] + " " + Math.round(yaw) + "°"
    }
}
