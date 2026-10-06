package org.valkyrienskies.eureka.client

import net.minecraft.client.Minecraft
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.mod.common.getShipMountedToData

/**
 * Scroll-wheel zoom for VS2's ship-mounted third-person camera.
 *
 * VS2 pulls that camera back by a distance it works out from the ship's size. This multiplier scales it: 1.0 is
 * VS2's own distance, and the client config's `shipCameraZoomMin` / `shipCameraZoomMax` bound how far in and out
 * the wheel goes. While that camera is showing, the wheel zooms instead of changing hotbar slot
 * (MixinMouseHandlerShipZoom), and MixinCameraShipZoom applies the multiplier before VS2 clips the camera against
 * terrain, so zooming out never puts it through a wall. It goes back to 1.0 when you leave the seat.
 *
 * Our VS2 build used to carry this; it lives in Armada now so Armada runs on the official release.
 */
object ShipCameraZoom {

    /** Scroll notches for a full sweep from the closest zoom to the furthest. */
    private const val STEPS = 8.0

    private var multiplier = 1.0
    private var wasMounted = false

    private val minZoom: Double get() = EurekaConfig.CLIENT.shipCameraZoomMin.coerceAtLeast(0.1)
    private val maxZoom: Double get() = EurekaConfig.CLIENT.shipCameraZoomMax.coerceAtLeast(minZoom)

    /** The current multiplier, re-clamped so a config edit applies without scrolling. */
    @JvmStatic
    fun getMultiplier(): Double = multiplier.coerceIn(minZoom, maxZoom)

    /**
     * Is VS2's ship-mounted third-person camera the one showing? In a ship seat only in the ship view slot (see
     * HelmCamera; the other slots are vanilla's camera).
     */
    @JvmStatic
    fun isShipCameraActive(mc: Minecraft): Boolean {
        val player = mc.player ?: return false
        if (HelmCamera.onShipSeat(mc)) return HelmCamera.shipView
        return player.vehicle != null && !mc.options.cameraType.isFirstPerson &&
            getShipMountedToData(player, null) != null
    }

    /** Scroll up (+1) zooms in, down (-1) zooms out. */
    @JvmStatic
    fun scroll(notches: Double) {
        val step = (maxZoom - minZoom) / STEPS
        multiplier = (getMultiplier() - notches * step).coerceIn(minZoom, maxZoom)
    }

    /** Put back a remembered zoom after a relog (see CameraMemory). */
    @JvmStatic
    fun restore(zoom: Double) {
        multiplier = zoom.coerceIn(minZoom, maxZoom)
    }

    /** Client tick: back to VS2's own distance once the player leaves a ship seat. */
    @JvmStatic
    fun tick(mc: Minecraft) {
        val mounted = mc.player?.let { it.vehicle != null && getShipMountedToData(it, null) != null } == true
        if (wasMounted && !mounted) multiplier = 1.0
        wasMounted = mounted
    }
}
