package org.valkyrienskies.eureka.fabric.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads which template a pool element actually points at, so a config hull name can be matched to the
 * element already in the pool.
 *
 * The field is an Either: LEFT is a template id for a piece loaded from a .nbt file, which is what every
 * pirate hull is, and RIGHT is an inline template built in code. Deliberately declared with wildcards --
 * the id type is spelled ResourceLocation on 1.20.1 and 1.21.1 and Identifier on 1.21.11, and generics
 * erase, so one accessor matches the field on all three and this class stays identical across the trees.
 */
@Mixin(SinglePoolElement.class)
public interface MixinSinglePoolElementAccess {

    @Accessor("template")
    Either<?, ?> vs_eureka_getTemplate();
}
