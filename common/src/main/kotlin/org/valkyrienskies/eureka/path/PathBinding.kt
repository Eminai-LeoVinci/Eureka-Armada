package org.valkyrienskies.eureka.path

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.core.api.ships.LoadedServerShip
import java.util.UUID

/**
 * Which route a ship is bound to, saved with the ship so following survives a world reload.
 *
 * ## Why this is persisted at all
 * The original design kept following entirely at runtime, on the grounds that a ship mid-route should not wake
 * up under its own power in a chunk nobody is standing in. That reasoning does not survive contact with the
 * rest of the mod: **cruise is already persisted** ([org.valkyrienskies.eureka.ship.EurekaShipControl] saves
 * `isCruising`, the throttle and the course), so a ship that was under way still wakes up under way. All the old
 * behaviour actually achieved was dropping the STEERING, which left a reloaded ship driving in a dead straight
 * line off its route. Restoring the binding is the safer half of the pair, not the riskier one.
 *
 * ## The shape of this mirrors the armada bind exactly
 * [org.valkyrienskies.eureka.armada.ArmadaShipControl] has the same problem -- a persisted relationship whose
 * runtime machinery (there a physics weld, here a [PathFollower]) cannot be serialized -- and solves it the same
 * way: save the RELATIONSHIP on the ship, and let a per-tick reconcile pass rebuild the machinery for any loaded
 * ship that is missing it (see [ShipPaths.tick]). Nothing here is authoritative while a follower is live; the
 * follower mirrors its progress onto these fields every tick, and they are only read back on a re-arm.
 *
 * Kept out of [org.valkyrienskies.eureka.ship.EurekaShipControl] because that class is about control INPUTS --
 * it never needs to know which route produced them -- and this is route bookkeeping.
 */
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE
)
@JsonIgnoreProperties(ignoreUnknown = true)
class PathBinding {

    /** The route this ship is flying, or [NO_ROUTE] when it isn't flying one. */
    @JsonProperty("route")
    var routeId: Long = NO_ROUTE

    /**
     * The world-space displacement held from the line (see [PathFollower]).
     *
     * Saved rather than re-measured on load, so a reload resumes the parallel course the ship was actually
     * flying. Re-measuring would instead bake in whatever the hull did while it was unloaded -- an airship that
     * settled a few blocks would quietly adopt a lower route every time the world was saved.
     */
    @JsonProperty("offset")
    var offset: Vector3d = Vector3d()

    /** Progress along the route in blocks of arc length; negative means "not established". */
    @JsonProperty("arc")
    var arc: Double = -1.0

    /** Completed laps, purely so the status readout doesn't restart at zero. */
    @JsonProperty("laps")
    var laps: Int = 0

    /**
     * Whether the ship is bound to the route but sitting still on it -- SHIFT+P pressed once.
     *
     * Persisted, and it has to be. A pause is a deliberate "not now", so a ship left paused overnight must not
     * be under way the moment the world comes back. Missing from an older save's JSON simply reads as false,
     * which is the right answer for a binding written before pausing existed.
     */
    @JsonProperty("paused")
    var paused: Boolean = false

    /**
     * Which of [PathMode]'s two playbacks this ship is running, as an ordinal.
     *
     * This one is not a nicety. A replayed ship has handed the hull to the route, so a reload that forgot the
     * mode would bring it back in geometry mode with nothing driving it at all -- a ship sitting motionless on
     * its line, which reads as the resume having failed. Absent from an older save's JSON reads as 0, which is
     * [PathMode.GEOMETRY]: correct for every binding written before replay existed.
     */
    @JsonProperty("mode")
    var mode: Int = PathMode.GEOMETRY.ordinal

    /**
     * Where a replay had got to, in MOVING seconds -- the whole of its progress.
     *
     * The replay clock, not a measurement of the ship (see [PathFollower.clock]). Saving it rather than
     * re-deriving it from [arc] is what lets a route resume mid-way through a leg it has flown before at a
     * different pace, and what makes [dwellNext] mean anything.
     */
    @JsonProperty("clock")
    var clock: Double = -1.0

    /** Which recorded pause the clock is running toward. */
    @JsonProperty("dwellNext")
    var nextDwell: Int = -1

    /** The recorded pause being served, or -1 for none. */
    @JsonProperty("dwell")
    var dwellIndex: Int = -1

    /**
     * Seconds left of that pause.
     *
     * Saved so a ship held at a dock over a reload finishes waiting rather than starting the wait again -- or,
     * worse, deciding it has already been there and pulling straight out.
     */
    @JsonProperty("dwellLeft")
    var dwellRemaining: Double = 0.0

    /** [mode] as the enum, tolerating anything unrecognised. */
    val pathMode: PathMode get() = PathMode.fromOrdinal(mode)

    /**
     * Who set this ship going, as a UUID string, so a resumed route can report itself to them.
     *
     * A string because that is the one representation Jackson round-trips through the legacy attachment
     * serializer without any assumption about the backing format.
     */
    @JsonProperty("owner")
    var owner: String? = null

    val isBound: Boolean get() = routeId != NO_ROUTE

    /** The owner as a UUID, or null if it was never recorded or came back unreadable. */
    val ownerId: UUID? get() = owner?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun bind(routeId: Long, offset: Vector3dc, owner: UUID?, arc: Double, laps: Int, mode: PathMode) {
        this.routeId = routeId
        this.offset.set(offset)
        this.arc = arc
        this.laps = laps
        this.owner = owner?.toString()
        this.paused = false
        this.mode = mode.ordinal
        this.clock = -1.0
        this.nextDwell = -1
        this.dwellIndex = -1
        this.dwellRemaining = 0.0
    }

    fun clear() {
        routeId = NO_ROUTE
        offset.set(0.0, 0.0, 0.0)
        arc = -1.0
        laps = 0
        owner = null
        paused = false
        mode = PathMode.GEOMETRY.ordinal
        clock = -1.0
        nextDwell = -1
        dwellIndex = -1
        dwellRemaining = 0.0
    }

    companion object {
        /** Route ids start at 1, so 0 is free to mean "not bound". */
        const val NO_ROUTE = 0L

        fun get(ship: LoadedServerShip): PathBinding? = ship.getAttachment(PathBinding::class.java)

        fun getOrCreate(ship: LoadedServerShip): PathBinding =
            ship.getAttachment<PathBinding>() ?: PathBinding().also { ship.setAttachment(it) }

        /** Forget any binding on this ship. Safe on a ship that never had one. */
        fun clear(ship: LoadedServerShip) {
            get(ship)?.clear()
        }
    }
}
