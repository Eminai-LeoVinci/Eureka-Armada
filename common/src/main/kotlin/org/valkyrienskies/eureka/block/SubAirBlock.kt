package org.valkyrienskies.eureka.block

import net.minecraft.world.level.block.AirBlock
import net.minecraft.world.level.block.state.BlockBehaviour

/**
 * Air that shields whatever stands in it from the world's water -- the block a submarine's interior is filled
 * with on assembly. Modelled on vanilla's own marker airs ([net.minecraft.world.level.block.Blocks.CAVE_AIR],
 * [net.minecraft.world.level.block.Blocks.VOID_AIR]): identical to air in every respect, existing only so that a
 * position can be recognised later.
 *
 * ## Why a block, when VS2 already detects sealed pockets
 * VS2's connectivity engine flood-fills a ship's air and classifies each voxel CONNECTED / DISCONNECTED /
 * UNKNOWN, and its entity mixins suppress water for anyone in a DISCONNECTED pocket. Three reasons to mark the
 * volume explicitly instead:
 *  1. Enabling connectivity also arms VS2's ship SPLITTER (`SplitHandler` is fed from the same flag), so any
 *     ship whose blocks form more than one component gets torn apart. That is a steep price for a submarine.
 *  2. Connectivity only recognises a SEALED pocket. An open-topped hull sitting keel-under is CONNECTED to the
 *     sky, so nothing fires -- yet that is exactly the case we want to support.
 *  3. `isPositionSealed` counts UNKNOWN as sealed, i.e. it guesses. A block does not.
 *
 * Because it extends [AirBlock] it reports `isAir`, which matters more than it looks: VS2 maps every `isAir`
 * state onto `vsCore.blockTypes.airState`, so a hull filled with this has no mass and no collision, exactly as
 * if it were empty. Deliberately kept `replaceable` too -- the shipyard is a separate region of the world that
 * the ocean cannot flow into, so there is nothing to keep out, and a non-replaceable "air" would mean breaking
 * it before you could place anything inside your own sub.
 */
class SubAirBlock(properties: BlockBehaviour.Properties) : AirBlock(properties)
