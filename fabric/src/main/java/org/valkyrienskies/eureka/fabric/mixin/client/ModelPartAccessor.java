package org.valkyrienskies.eureka.fabric.mixin.client;

import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Named access to a part's children. Vanilla 1.21.1 exposes {@code getAllParts()} (no names) and
 * {@code getChild(name)} (throws on a miss, direct children only); the EMF arms-at-helm pose needs to
 * resolve parts BY NAME through the whole hierarchy, which later versions serve with
 * {@code createPartLookup()} -- absent here, so the map itself is opened instead.
 */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {

    @Accessor("children")
    Map<String, ModelPart> vs_eureka$children();
}
