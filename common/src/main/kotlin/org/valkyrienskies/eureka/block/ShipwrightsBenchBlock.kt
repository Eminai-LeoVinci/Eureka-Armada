package org.valkyrienskies.eureka.block

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.material.MapColor
import org.valkyrienskies.mod.common.blockProps

/**
 * The Shipwright's workstation.
 *
 * ## It is the spawn rule, and nothing else
 * A shipwright is not spawned by anything. A villager becomes one by claiming this bench, exactly as one
 * becomes a librarian by claiming a lectern -- so "shipwrights are only found at harbors" is true because the
 * bench is only *placed* at harbors, not because anything checks where a villager is standing. No custom spawn
 * logic exists, and none is wanted: the rule is the block.
 *
 * That only holds while the bench stays unobtainable. It has no recipe on purpose. The moment one can be
 * crafted, a shipwright can be manufactured in a basement and the harbor stops meaning anything -- which makes
 * the missing recipe a design decision rather than an unfinished one.
 *
 * ## Deliberately not interactive
 * Plans, materials and deliveries all go through the **villager**, not through this. A workbench that answered
 * questions would make the shipwright decorative, and the point of the profession is that a harbor without a
 * shipwright in it is a harbor that cannot build you anything. See
 * [org.valkyrienskies.eureka.shipwright.ShipwrightMenu].
 */
class ShipwrightsBenchBlock : Block(
    // Softer than a helm and axe-mineable: it is a carpenter's bench, and a player who finds one in a harbor
    // should be able to take it home even though they cannot make one.
    blockProps().mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(2.5f)
)
