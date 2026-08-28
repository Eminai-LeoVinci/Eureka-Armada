package org.valkyrienskies.eureka.crew;

import java.util.Map;
import net.minecraft.core.BlockPos;

/**
 * What a chest menu found in its boxes at the moment it was OPENED.
 *
 * <p>Implemented by a mixin on {@code ChestMenu}, and the reason a restock can empty a magazine without the
 * magazine forgetting what it is for.
 *
 * <p>The rule being served is that a captain emptying a box by hand clears its tag, while a RESTOCK emptying
 * it does not. Recomputing tags from contents on close cannot tell those apart: it would strip the tag off a
 * restock-drained box the moment somebody opened it to check whether it was empty -- which is exactly when a
 * captain looks. So the categories present at open are remembered, and on close a tag is cleared only if it
 * was there to begin with.
 *
 * <p>Keyed by position because a double chest is two boxes under one menu and each is tagged on its own. The
 * snapshot lives on the menu and dies with it, so there is no map to clean up and no way for one player's
 * open chest to be confused with another's.
 */
public interface HoldOpenSnapshot {

    /** Position to the tag mask present there at open. Null until the menu has been opened server-side. */
    Map<BlockPos, Integer> vs_eureka$openSnapshot();

    void vs_eureka$setOpenSnapshot(Map<BlockPos, Integer> snapshot);
}
