package org.valkyrienskies.eureka.block

import net.minecraft.world.level.block.Block

// Virtual block: never placed in the world and has no item (see EurekaBlocks.registerItems).
// It exists only so its blockstate (assets/vs_eureka/blockstates/cannon_barrel.json) is loaded and
// the barrel model baked, giving CannonBlockEntityRenderer a BlockState whose model it can pitch to
// the gun's exact elevation -- the barrel left the cannon's own baked models when the elevation went
// from five 22.5-degree steps to nineteen 5-degree ones, angles vanilla element rotation can't bake.
// It carries no properties: facing and pitch are the renderer's pose-stack business.
class CannonBarrelBlock(properties: Properties) : Block(properties)
