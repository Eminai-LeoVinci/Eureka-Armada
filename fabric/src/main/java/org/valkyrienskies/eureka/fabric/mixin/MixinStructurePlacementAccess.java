package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to a structure placement's frequency, which is how pirate worldgen is switched OFF.
 *
 * Turning pirateShipsEnabled off used to stop only the RUNTIME machinery -- adoption, zones, wake-up and
 * pursuit -- while the hulls themselves kept generating, because placement is datapack data and no code of
 * ours ran when one was placed. Server owners had a config switch that left pillager ships all over their
 * ocean, and no way at all to stop them short of writing a datapack.
 *
 * Frequency is the honest lever for it. isStructureChunk consults it on every query and short-circuits when
 * the roll fails, and the roll is nextFloat() < frequency over [0, 1) -- so zero can never pass, at any
 * seed, in any chunk. Nothing else has to be touched: the structure set keeps its entry, the pool keeps its
 * hulls, and flipping the config back simply stops us writing the zero, since the registries are rebuilt
 * from the datapack on every world load.
 *
 * The alternative was emptying the structure set's own list, and it was rejected: StructureSet is a RECORD,
 * and stripping final off a record component to write it is a much larger promise to the JVM than setting
 * one float on an ordinary class.
 *
 * Declared against the BASE class, deliberately. frequency belongs to StructurePlacement rather than to
 * RandomSpreadStructurePlacement, and an accessor does not walk up the hierarchy to find a field -- pointing
 * this at the subclass would fail to bind, quietly, exactly the way a @Shadow on an inherited field does.
 *
 * @Mutable because the field is final; see MixinRandomSpreadAccess for what that costs when it is missing.
 */
@Mixin(StructurePlacement.class)
public interface MixinStructurePlacementAccess {

    @Accessor("frequency")
    float vs_eureka_getFrequency();

    @Mutable
    @Accessor("frequency")
    void vs_eureka_setFrequency(float frequency);
}
