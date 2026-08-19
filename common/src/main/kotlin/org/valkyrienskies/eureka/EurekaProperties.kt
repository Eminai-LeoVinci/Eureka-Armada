package org.valkyrienskies.eureka

import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import org.valkyrienskies.eureka.block.CannonPart
import org.valkyrienskies.eureka.block.HelmMark

object EurekaProperties {
    val HEAT = IntegerProperty.create("heat", 0, 4)

    /**
     * Who a ship helm answers to. See [HelmMark]. Shared by ShipHelmBlock and the virtual
     * ShipHelmWheelBlock so the renderer can copy it across with a single setValue.
     */
    val MARK: EnumProperty<HelmMark> = EnumProperty.create("mark", HelmMark::class.java)

    /** Which half of a two-block cannon this is. See [CannonPart]. */
    val CANNON_PART: EnumProperty<CannonPart> = EnumProperty.create("part", CannonPart::class.java)

    /**
     * How far a cannon's barrel is laid, in 22.5-degree steps from -45 to +45.
     *
     * Stored as 0..4 rather than as degrees because it indexes the five baked models directly, and because
     * every one of those five angles is a value vanilla's element rotation already permits -- which is the
     * only reason the barrel can elevate without a block entity renderer. [ELEVATION_LEVEL] is flat.
     */
    val ELEVATION: IntegerProperty = IntegerProperty.create("elevation", 0, 4)

    /** The index at which the barrel is horizontal. Below it the gun is depressed, above it elevated. */
    const val ELEVATION_LEVEL = 2

    /** Degrees above horizontal for an [ELEVATION] index. */
    fun elevationDegrees(index: Int): Double = (index - ELEVATION_LEVEL) * 22.5
}
