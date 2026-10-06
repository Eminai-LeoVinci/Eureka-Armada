package org.valkyrienskies.eureka.client

import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import org.valkyrienskies.eureka.entity.DeckSeat
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import java.nio.file.Files
import java.nio.file.Path

/**
 * Log out seated on a ship and you come back to the same camera: the same F5 slot (the helm's ship view included)
 * and the same scroll zoom.
 *
 * While the player is seated (a helm or a deck seat) the camera is noted every tick. Disconnecting while seated
 * writes that note to `config/vs_eureka_armada_camera.txt`, keyed by the world or server, and disconnecting on foot
 * clears it. After the next join into the same world, the first moment the player is seated again (the relog seat
 * or the helm, within [RESTORE_WINDOW_TICKS]) the camera is put back. Client side only.
 */
object CameraMemory {

    /** How long after joining a remembered camera waits for the player to be seated. */
    private const val RESTORE_WINDOW_TICKS = 200

    private class Snapshot(val world: String, val type: CameraType, val shipView: Boolean, val zoom: Double)

    private var last: Snapshot? = null
    private var pending: Snapshot? = null
    private var restoreTicksLeft = 0

    private val file: Path get() = Minecraft.getInstance().gameDirectory.toPath().resolve("config/vs_eureka_armada_camera.txt")

    private fun worldKey(mc: Minecraft): String =
        mc.singleplayerServer?.worldData?.levelName?.let { "sp:$it" } ?: mc.currentServer?.ip?.let { "mp:$it" } ?: ""

    private fun seated(mc: Minecraft): Boolean {
        val vehicle = mc.player?.vehicle ?: return false
        return vehicle is ShipMountingEntity || vehicle is DeckSeat
    }

    /** Client tick: note the camera while seated, or put a remembered one back. */
    @JvmStatic
    fun tick(mc: Minecraft) {
        if (mc.player == null) return
        if (restoreTicksLeft > 0) {
            restoreTicksLeft--
            val snap = pending
            if (snap != null && seated(mc)) {
                pending = null
                restoreTicksLeft = 0
                HelmCamera.restore(mc, snap.type, snap.shipView)
                ShipCameraZoom.restore(snap.zoom)
            } else if (restoreTicksLeft == 0) {
                pending = null
            }
            return
        }
        last = if (seated(mc)) {
            Snapshot(worldKey(mc), mc.options.cameraType, HelmCamera.shipView, ShipCameraZoom.getMultiplier())
        } else {
            null
        }
    }

    /** Leaving the world: remember the camera if seated, forget it if not. */
    @JvmStatic
    fun onDisconnect() {
        val snap = last
        try {
            if (snap == null || snap.world.isEmpty()) {
                Files.deleteIfExists(file)
            } else {
                Files.createDirectories(file.parent)
                Files.writeString(file, "${snap.world}\n${snap.type.name}\n${snap.shipView}\n${snap.zoom}\n")
            }
        } catch (_: Exception) {
            // A camera preference is not worth an error on the way out.
        }
        last = null
    }

    /** Joining a world: load a camera remembered for it, to apply once the player is seated. */
    @JvmStatic
    fun onJoin(mc: Minecraft) {
        pending = null
        restoreTicksLeft = 0
        val lines = try {
            if (Files.exists(file)) Files.readAllLines(file) else return
        } catch (_: Exception) {
            return
        }
        if (lines.size < 4 || lines[0] != worldKey(mc)) return
        val type = CameraType.values().firstOrNull { it.name == lines[1] } ?: return
        pending = Snapshot(lines[0], type, lines[2].toBoolean(), lines[3].toDoubleOrNull() ?: 1.0)
        restoreTicksLeft = RESTORE_WINDOW_TICKS
    }
}
