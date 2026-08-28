package org.valkyrienskies.eureka.block

import net.minecraft.util.StringRepresentable

/**
 * Which half of a cannon a block is.
 *
 * Declared stern-first -- [REAR] before [FRONT] -- because the ordinal doubles as the offset from the rear
 * block along the gun's facing. [CannonBlock] leans on that: the whole gun is `rear.relative(facing, ordinal)`
 * for each part, and the rear is `pos.relative(facing.opposite, ordinal)`. That is the only place the layout
 * is written down, which is why going from three blocks to two touched this file and the shapes and nothing
 * else.
 */
enum class CannonPart(private val partName: String) : StringRepresentable {
    /** Trail, handles, wheels and breech, and the block the player actually clicks to place the gun. */
    REAR("rear"),

    /** The barrel and muzzle. */
    FRONT("front");

    override fun getSerializedName(): String = partName
}
