package org.valkyrienskies.eureka.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Where a job site actually IS, for a mob standing in the world.
 *
 * <p>A workstation aboard an assembled ship lives at shipyard coordinates -- millions of blocks from where the
 * hull is drawn -- while the villager standing in front of it is at ordinary world coordinates. {@code
 * MixinPOIManager} already teaches the POI SEARCHES to compare the two properly, so a villager finds and claims
 * a workstation on a ship. What it stores is the record's raw position, and every goal that then consumes that
 * memory compares it against the villager's world position directly.
 *
 * <p>That is the "further mixins to Goals" the original author flagged and never wrote, and its symptom was a
 * wheel with a gravity well round it: villagers set off for a helm twenty-eight million blocks away, gave up,
 * re-targeted, and set off again, forever -- while never being close enough for {@code WorkAtPoi} to fire or
 * for {@code ValidateNearbyPoi} to release them.
 *
 * <p>This is the one conversion those goals need. It is deliberately a no-op for anything not in the shipyard,
 * so a workstation on land costs one cheap coordinate test and behaves exactly as vanilla.
 */
public final class ShipJobSites {

    private ShipJobSites() {
    }

    /** Whether this position is inside a ship's own space rather than in the world. */
    public static boolean inShipyard(final BlockPos pos) {
        // A pure test against the chunk allocator -- no level lookup, because this sits on villager AI paths.
        return VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * The world position of a job site, which for a shipyard block is wherever its ship is holding it now.
     *
     * <p>Returns [pos] unchanged when it is not in the shipyard, so callers can apply it unconditionally.
     */
    public static BlockPos toWorld(final Level level, final BlockPos pos) {
        if (level == null || !inShipyard(pos)) {
            return pos;
        }
        final Vec3 world = VSGameUtilsKt.toWorldCoordinates(level, Vec3.atCenterOf(pos));
        return BlockPos.containing(world.x, world.y, world.z);
    }

    /** As {@link #toWorld(Level, BlockPos)}, for the cases that want the precise point rather than a block. */
    public static Vec3 toWorld(final Level level, final Vec3 pos) {
        if (level == null || !inShipyard(BlockPos.containing(pos.x, pos.y, pos.z))) {
            return pos;
        }
        return VSGameUtilsKt.toWorldCoordinates(level, pos);
    }

    /**
     * A job site in the shipyard whose ship no longer exists.
     *
     * <p>This is what a memory looks like after its ship is taken apart. The blocks moved back into the world
     * and the shipyard position is vacant, but the villager is still holding the old address -- and
     * {@link #toWorld} cannot help, because with no ship to ask, {@code toWorldCoordinates} hands the position
     * straight back. The mob then walks toward the raw shipyard coordinate, which from anywhere in the world
     * means setting off toward the border. Watching a crew drift to the stern after a disassembly is exactly
     * this.
     *
     * <p>Left alone it deadlocks the same way the original bug did: the memory is only cleared by
     * {@code ValidateNearbyPoi}, which first requires the mob to be NEAR the site, and it can never get near
     * something at that address. So an orphan is treated as always near -- see {@code MixinValidateNearbyPoi} --
     * which lets the check proceed to ask the index, find nothing, and release them.
     */
    public static boolean isOrphaned(final Level level, final BlockPos pos) {
        return level != null && inShipyard(pos) && VSGameUtilsKt.getShipManagingPos(level, pos) == null;
    }
}
