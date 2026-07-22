package org.valkyrienskies.eureka.block

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty

// Virtual block: never placed in the world and has no item (see EurekaBlocks.registerItems).
// It exists only so its blockstate (assets/vs_eureka/blockstates/ship_helm_wheel.json) is loaded
// and its per-wood-type models are baked, giving ShipHelmBlockEntityRenderer a BlockState whose
// model it can draw as the spinning steering-wheel overlay.
class ShipHelmWheelBlock(properties: Properties) : Block(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(WOOD, WoodType.OAK))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(WOOD)
    }

    companion object {
        val WOOD: EnumProperty<WoodType> = EnumProperty.create("wood", WoodType::class.java)
    }
}
