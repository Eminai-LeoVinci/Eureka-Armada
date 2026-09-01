package org.valkyrienskies.eureka.fabric.mixin;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to which structures a set actually carries -- how pirate worldgen is switched OFF.
 *
 * Emptying this list DETACHES the pirate structure from its placement, and that second effect is the whole
 * reason this is the lever rather than zeroing the placement's frequency.
 *
 * Frequency was tried first and was a bad trade. It does stop generation -- isStructureChunk rolls
 * nextFloat() against it and zero can never pass -- but the structure stays attached to its placement, so
 * "/locate structure vs_eureka:pirate_ship" still finds a placement to search, commits to the full hunt, and
 * now MISSES every one of its forty thousand candidate cells instead of hitting on the first. That is not
 * merely slow: it pins the server thread hard enough that block updates stop, water stops spreading, sand
 * stops falling, and the instance has to be killed. Before the switch existed the same command answered
 * instantly, because it found a ship almost at once.
 *
 * With the list empty, ChunkGeneratorStructureState builds no placement entry for the structure at all, and
 * findNearestMapStructure returns null on its own first line -- so locate answers immediately AND nothing
 * generates, from the same edit.
 *
 * StructureSet is a RECORD, so the field is final and this needs @Mutable. That is a real thing to be
 * careful about and it is worth saying why it is safe here: a record's generated equals, hashCode and
 * toString all read the field rather than caching it, so they stay truthful afterwards, and this instance
 * belongs to a dynamic registry that is rebuilt from the datapack on every world load -- nothing is written
 * to the save, and switching the config back simply means not emptying it next launch.
 *
 * Declared List<?> deliberately: the element type is a nested class whose name has moved between versions,
 * and generics erase, so the wildcard matches the field on all three and this file stays identical across
 * the trees.
 */
@Mixin(StructureSet.class)
public interface MixinStructureSetAccess {

    @Mutable
    @Accessor("structures")
    void vs_eureka_setStructures(List<?> structures);
}
