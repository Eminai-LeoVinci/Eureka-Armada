package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to a random-spread placement's grid, so how often pirate ships generate can be a config
 * value rather than a datapack edit.
 *
 * Vanilla builds this object from JSON and treats it as immutable, which is why the numbers normally can
 * only be changed by overriding data/vs_eureka/worldgen/structure_set/pirate_ships.json in a datapack.
 * Both fields are read live on every placement query -- getPotentialStructureChunk consults them each time
 * rather than precomputing a grid -- so writing them once before any chunk generates is enough, and is
 * what PirateWorldgen does at server start.
 *
 * The fields, not the accessors: RandomSpreadStructurePlacement.getPotentialStructureChunk reads
 * this.spacing and this.separation directly, so overriding spacing() and separation() in a subclass would
 * change nothing. That is also why this is an accessor rather than a custom StructurePlacementType.
 */
@Mixin(RandomSpreadStructurePlacement.class)
public interface MixinRandomSpreadAccess {

    @Accessor("spacing")
    int vs_eureka_getSpacing();

    @Accessor("spacing")
    void vs_eureka_setSpacing(int spacing);

    @Accessor("separation")
    int vs_eureka_getSeparation();

    @Accessor("separation")
    void vs_eureka_setSeparation(int separation);
}
