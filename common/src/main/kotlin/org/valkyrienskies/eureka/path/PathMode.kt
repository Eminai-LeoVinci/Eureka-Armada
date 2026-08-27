package org.valkyrienskies.eureka.path

/**
 * The two ways a ship can fly a recorded route.
 *
 * Both steer identically -- heading always comes from the line's own tangent. They differ only in who owns the
 * THROTTLE, which turns out to be the whole difference between a route and a recording.
 */
enum class PathMode {

    /**
     * Fly the line; the pilot owns the speed. SHIFT+P, and the only mode that existed before replay.
     *
     * Its virtue is that one recorded loop can be flown at any speed without re-recording, which is exactly
     * right for a patrol or a ferry run. Its limit is that cruise control is a CONSTANT, so a route that needs
     * to creep out between rooftops and then open up over open water has no setting that is right for both.
     */
    GEOMETRY,

    /**
     * Fly the whole recording: the drawn line exactly, at the pace it was flown, with every deliberate
     * pause. CTRL+SHIFT+P.
     *
     * Needs a [MotionTrack], so it cannot be used on a route recorded before timing was captured.
     */
    REPLAY;

    val isReplay: Boolean get() = this == REPLAY

    /** Name for chat. */
    val label: String get() = if (this == REPLAY) "full replay" else "line only"

    companion object {
        /**
         * Read a persisted ordinal back, defaulting to [GEOMETRY].
         *
         * Anything unrecognised -- an older binding with no mode at all, or one written by a future version --
         * reads as the mode that works on every route, which is the safe way to be wrong.
         */
        fun fromOrdinal(ordinal: Int): PathMode = entries.getOrElse(ordinal) { GEOMETRY }
    }
}
