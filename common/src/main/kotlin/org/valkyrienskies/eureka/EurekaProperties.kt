package org.valkyrienskies.eureka

import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import org.valkyrienskies.eureka.block.CannonPart

object EurekaProperties {
    val HEAT = IntegerProperty.create("heat", 0, 4)

    /** Which third of a three-block cannon this is. See [CannonPart]. */
    val CANNON_PART: EnumProperty<CannonPart> = EnumProperty.create("part", CannonPart::class.java)
}
