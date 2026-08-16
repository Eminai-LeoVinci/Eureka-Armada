package org.valkyrienskies.eureka.crew

/**
 * What a crew member does for their berth.
 *
 * ## Exclusive by construction, not by checking
 * One crew member has one duty, because a berth carries one of these and not a set. That is the whole of the
 * "only that task" rule: there is no state in which somebody is both a gunner and a fire party, so there is no
 * combination to validate, no order to apply two jobs in, and nothing to go wrong when a third duty is added
 * later. Anyone who wants both jobs done buys another berth, which is the point -- a duty is what a berth is
 * FOR, and Hearts of the Sea are what limit them.
 *
 * ## [NONE] is a real answer
 * A crew member with no duty is not a bug or an unset field: they are ballast, and most of a crew starts that
 * way. Signing somebody on and giving them a job are deliberately two separate decisions, because the first is
 * about who is aboard and the second is about what the ship is for. It is also what makes the assignment
 * button a cycle rather than a one-way choice -- there is always somewhere to put a duty back to.
 *
 * The [id] is the wire and disk spelling, and it is the reason this is not persisted by ordinal: inserting a
 * duty in the middle of this list later must not silently re-employ every crew in the world.
 */
enum class CrewDuty(val id: String) {

    /** Aboard, fed, and doing nothing in particular. */
    NONE("none"),

    /** Mans one gun. Fires it when the ship is ordered to fire, and only then -- see `CrewDuties.broadside`. */
    GUNNER("gunner"),

    /** Watches for fire and runs to put it out. See `FireBrigade`. */
    FIREFIGHTER("firefighter");

    /** The next duty round the cycle, which is how the manifest's assignment button steps through them. */
    val next: CrewDuty get() = entries[(ordinal + 1) % entries.size]

    val translationKey: String get() = "gui.vs_eureka.crew_duty.$id"

    companion object {

        /** A duty read back off disk or the wire. Anything unrecognised is [NONE], never an exception. */
        fun byId(id: String): CrewDuty = entries.firstOrNull { it.id == id } ?: NONE

        /**
         * A duty by position, for the one hop where ordinals are safe: a single packet, encoded and decoded by
         * the same build. Never used for anything that outlives the connection.
         */
        fun byOrdinal(ordinal: Int): CrewDuty = entries.getOrElse(ordinal) { NONE }
    }
}
