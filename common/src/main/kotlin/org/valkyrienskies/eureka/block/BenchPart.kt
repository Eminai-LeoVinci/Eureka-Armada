package org.valkyrienskies.eureka.block

import net.minecraft.util.StringRepresentable

/**
 * Which of the six blocks of a Shipwright's Bench this one is.
 *
 * The bench is a desk: three blocks wide, one deep, two tall, stood against a wall with its front turned
 * into the room. [across] and [up] are the offset from [MIDDLE_LOWER] -- the block the player actually
 * clicks to place it -- so every position is
 * `anchor.relative(facing.clockWise, across).above(up)` and the anchor from any part is
 * `pos.relative(facing.clockWise, -across).below(up)`.
 *
 * Same arrangement as [CannonPart] and for the same reason: the layout is written down **here and nowhere
 * else**, so changing the footprint is an edit to this file and to the collision shapes, and to no logic at
 * all. The cannon encodes its offset in the ordinal because it is a line of blocks; a bench is a rectangle,
 * so it needs the two numbers spelled out.
 *
 * ## Only [MIDDLE_LOWER] is the job site
 * A point of interest is registered per *blockstate*, so all six parts joining the POI would make one bench
 * six job sites and multiply its employment by six. [org.valkyrienskies.eureka.shipwright.ShipwrightProfession]
 * registers this part alone, which is why a shipwright always walks to the middle of the desk.
 */
enum class BenchPart(private val partName: String, val across: Int, val up: Int) : StringRepresentable {
    LEFT_LOWER("left_lower", -1, 0),

    /** The anchor: clicked to place, carries the job site, and draws nothing the other columns draw. */
    MIDDLE_LOWER("middle_lower", 0, 0),
    RIGHT_LOWER("right_lower", 1, 0),
    LEFT_UPPER("left_upper", -1, 1),
    MIDDLE_UPPER("middle_upper", 0, 1),
    RIGHT_UPPER("right_upper", 1, 1);

    override fun getSerializedName(): String = partName

    companion object {
        /** The part a player places and every other part measures itself from. */
        @JvmField
        val ANCHOR = MIDDLE_LOWER
    }
}
