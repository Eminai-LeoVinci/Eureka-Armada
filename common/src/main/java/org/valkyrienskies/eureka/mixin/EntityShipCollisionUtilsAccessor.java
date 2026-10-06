package org.valkyrienskies.eureka.mixin;

import java.util.stream.Stream;
import net.minecraft.world.level.Level;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;

/** VS2's own two private steps of its unloaded-ship check, so MixinUnloadedShipSpawnGrace can repeat it exactly. */
@Mixin(value = EntityShipCollisionUtils.class, remap = false)
public interface EntityShipCollisionUtilsAccessor {

    @Invoker("getAllShipsIntersectingEvenIfNotYetFullyLoaded")
    Stream<Ship> vs_eureka$shipsTouching(Level level, AABBd aabb);

    @Invoker("areAllChunksLoaded")
    boolean vs_eureka$chunksLoaded(Ship ship, AABBdc aabbInShip, Level level);
}
