package org.valkyrienskies.eureka.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code PathNavigation.mob} for {@link MixinGroundPathNavigationShips}. A subclass mixin can't @Shadow a superclass
 * field, so it is reached through an accessor on the declaring class.
 */
@Mixin(PathNavigation.class)
public interface PathNavigationMobAccessor {

    @Accessor("mob")
    Mob vs_eureka$getMob();
}
