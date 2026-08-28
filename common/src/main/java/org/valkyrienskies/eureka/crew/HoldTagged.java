package org.valkyrienskies.eureka.crew;

/**
 * A chest or barrel that remembers what it is for.
 *
 * <p>Implemented by a mixin on {@code BlockEntity} and meaningful only for chests and barrels; everything
 * else in the game carries the field and never sets it. The tags are a bitmask of {@code HoldTag} ordinals,
 * which is one int to persist, one int to compare, and cheap enough to read inside a loop over every box on
 * a first-rate.
 *
 * <p>The tags live on the BOX and not in a ship-wide ledger keyed by the box's number, because the number is
 * derived from geometry: inserting one chest on a lower deck renumbers every box after it, and a ledger keyed
 * that way would quietly hand each one its neighbour's tags -- turning the powder room into the shot locker,
 * which is the exact thing this feature exists to prevent. Living on the block entity also means the tags
 * ride through disassembly, reassembly and a bottle cycle for free, because VS2 saves and restores full
 * block-entity metadata whenever it relocates a block.
 */
public interface HoldTagged {

    /** Bitmask of {@code HoldTag} ordinals. 0 means untagged, which is what every box starts as. */
    int vs_eureka$holdTags();

    void vs_eureka$setHoldTags(int mask);
}
