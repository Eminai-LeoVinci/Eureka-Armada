package org.valkyrienskies.eureka.registry

import net.minecraft.world.item.Item
import net.minecraft.world.level.block.state.BlockBehaviour

/**
 * Fresh block and item Properties, under the names the 1.21.11 registration code uses.
 *
 * From 1.21.2 on, a Block or Item has to have its registry id stamped onto its Properties before it is built,
 * so the newer trees thread the id through a registration context. 1.20.1 has no such rule, so these are plain
 * fresh Properties. They exist so EurekaBlocks and EurekaItems read the same on every version.
 */
fun blockProps(): BlockBehaviour.Properties = BlockBehaviour.Properties.of()

/** See [blockProps]. */
fun itemProps(): Item.Properties = Item.Properties()
