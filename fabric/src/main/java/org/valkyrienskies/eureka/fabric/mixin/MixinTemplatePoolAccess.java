package org.valkyrienskies.eureka.fabric.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to a template pool's drawn list, so the pirate hull MIX can be a config value.
 *
 * A pool holds its weights twice: rawTemplates keeps (element, weight) pairs as the JSON declared them,
 * and templates is that list EXPANDED -- an element of weight 60 appears sixty times. Only the expanded
 * list is ever drawn from, so re-weighting means rebuilding it, which is a plain mutable ObjectArrayList
 * and can be done in place.
 *
 * maxSize is a lazily computed cache of the largest piece in the pool, so it is reset alongside; the
 * pirate structure is a single-piece jigsaw and never expands, but a stale cache would be a trap for
 * whoever adds a multi-piece hull later.
 */
@Mixin(StructureTemplatePool.class)
public interface MixinTemplatePoolAccess {

    @Accessor("templates")
    ObjectArrayList<StructurePoolElement> vs_eureka_getTemplates();

    @Accessor("maxSize")
    void vs_eureka_setMaxSize(int maxSize);
}
