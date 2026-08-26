package org.valkyrienskies.eureka

import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import org.valkyrienskies.eureka.block.BenchPart
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
     * Which of the six blocks of a Shipwright's Bench this is. See [BenchPart].
     *
     * Named "bench_part" rather than reusing "part": both blocks would otherwise offer a property of the same
     * name holding a different enum, which reads as one thing in a blockstate file and in `/setblock` while
     * being two.
     */
    val BENCH_PART: EnumProperty<BenchPart> = EnumProperty.create("bench_part", BenchPart::class.java)

    /**
     * How far a cannon's barrel is laid, in 5-degree steps from -45 to +45.
     *
     * Stored as an index rather than as degrees so every consumer (cycle gestures, crew orders, the
     * menus) walks the same integer ladder. The blockstate only poses the static carriage now; the barrel
     * itself is drawn by CannonBlockEntityRenderer, which is what frees the angle from vanilla's
     * five-value element-rotation limit. [ELEVATION_LEVEL] is flat.
     */
    val ELEVATION: IntegerProperty = IntegerProperty.create("elevation", 0, 18)

    /** The index at which the barrel is horizontal. Below it the gun is depressed, above it elevated. */
    const val ELEVATION_LEVEL = 9

    /** Degrees above horizontal for an [ELEVATION] index. */
    fun elevationDegrees(index: Int): Double = (index - ELEVATION_LEVEL) * 5.0
}
