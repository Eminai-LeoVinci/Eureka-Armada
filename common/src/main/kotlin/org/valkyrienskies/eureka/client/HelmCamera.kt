package org.valkyrienskies.eureka.client

import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import org.valkyrienskies.eureka.entity.DeckSeat
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.getShipMountedToData

/**
 * The F5 cycle in a ship seat (helm or deck seat): first person, third person behind, third person in front, then the ship view, then back.
 *
 * VS2 normally swaps in its ship-mounted camera for a helm rider in every view: a ship-relative first person that
 * rolls with the hull, and a pulled-back third person whose front slot is mirrored. At the helm Armada instead
 * gives the three vanilla views as they are, plain, and adds VS2's camera as a fourth slot, the "ship view":
 * pulled back, behind you, not mirrored, and scroll-zoomable (ShipCameraZoom).
 *
 * Vanilla only has three camera types, so the ship view rides on THIRD_PERSON_BACK with [shipView] telling it
 * apart. F5 presses at the helm are taken here, at the start of the client tick, before vanilla sees them.
 * In the plain slots, MixinGameRendererHelmCamera and MixinShipMountedPlainCamera make VS2's camera hook see no
 * ship mount for this frame's camera, so VS2 steps aside and the vanilla camera that was already set up stands.
 *
 * The same cycle applies in a deck seat (the sit key, or seated on relog). Leaving a seat puts you back in first person.
 * Our VS2 build did this inside VS2's renderer; it lives in Armada now.
 */
object HelmCamera {

    /** True while the helm's fourth slot, the ship view, is the one selected. */
    @JvmStatic
    var shipView = false
        private set

    private var wasSeated = false

    /**
     * Raised by MixinGameRendererHelmCamera for exactly the length of VS2's camera hook, in the plain slots; while it
     * is up, MixinShipMountedPlainCamera tells VS2 the local player is not ship-mounted. Render thread only.
     */
    @JvmField
    var hideMount = false

    /** Is the local player in a ship seat: a helm, or a deck seat (the sit key, or seated on relog)? */
    @JvmStatic
    fun onShipSeat(mc: Minecraft): Boolean {
        val player = mc.player ?: return false
        val vehicle = player.vehicle
        return (vehicle is ShipMountingEntity || vehicle is DeckSeat) && getShipMountedToData(player, null) != null
    }

    /** Should VS2's ship camera stand aside this frame, leaving the vanilla camera? */
    @JvmStatic
    fun plainCamera(mc: Minecraft): Boolean = onShipSeat(mc) && !shipView

    /** Start of the client tick: take the helm's F5 presses, and drop to first person on standing up. */
    @JvmStatic
    fun tick(mc: Minecraft) {
        val player = mc.player
        val seated = player != null && (player.vehicle is ShipMountingEntity || player.vehicle is DeckSeat)
        if (wasSeated && !seated) {
            shipView = false
            if (!mc.options.cameraType.isFirstPerson) setCamera(mc, CameraType.FIRST_PERSON)
        }
        wasSeated = seated

        if (!onShipSeat(mc)) {
            shipView = false
            return
        }
        while (mc.options.keyTogglePerspective.consumeClick()) {
            val current = mc.options.cameraType
            when {
                current == CameraType.FIRST_PERSON -> {
                    shipView = false
                    setCamera(mc, CameraType.THIRD_PERSON_BACK)
                }
                current == CameraType.THIRD_PERSON_BACK && !shipView -> setCamera(mc, CameraType.THIRD_PERSON_FRONT)
                current == CameraType.THIRD_PERSON_FRONT -> {
                    shipView = true
                    setCamera(mc, CameraType.THIRD_PERSON_BACK)
                }
                else -> {
                    shipView = false
                    setCamera(mc, CameraType.FIRST_PERSON)
                }
            }
        }
    }

    /** Put back a remembered camera after a relog (see CameraMemory). The ship view only exists at a helm. */
    @JvmStatic
    fun restore(mc: Minecraft, type: CameraType, shipView: Boolean) {
        this.shipView = shipView && onShipSeat(mc) && type == CameraType.THIRD_PERSON_BACK
        if (mc.options.cameraType != type) setCamera(mc, type)
    }

    /** What vanilla's own F5 handler does around a camera change. */
    private fun setCamera(mc: Minecraft, type: CameraType) {
        val before = mc.options.cameraType
        mc.options.cameraType = type
        if (before.isFirstPerson != type.isFirstPerson) {
            mc.gameRenderer.checkEntityPostEffect(if (type.isFirstPerson) mc.cameraEntity else null)
        }
        mc.levelRenderer.needsUpdate()
    }
}
