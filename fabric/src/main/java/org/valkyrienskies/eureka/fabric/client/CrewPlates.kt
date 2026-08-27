package org.valkyrienskies.eureka.fabric.client

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment

/**
 * Crew nameplates, expressed as two small edits to vanilla's own name-tag pipeline rather than as a renderer.
 *
 * The first attempt drew the plates by hand in `WorldRenderEvents.AFTER_ENTITIES` -- billboard the text, two
 * `Font.drawInBatch` passes, flush the buffer source. It never appeared on screen. Rather than chase which of
 * the four things that depends on had moved in 1.21.11, the plates now go through the path the game already
 * uses for every name tag it draws: the render state carries a name, `NameTagFeatureRenderer` draws it. That
 * gets billboarding, the see-through pass, distance sorting and shader compatibility for free, and none of it
 * is ours to keep working.
 *
 * Two seams are needed:
 *
 * 1. **`EntityRenderer.extractRenderState`** fills `state.nameTag` only for entities vanilla would label --
 *    the one under your crosshair, or a mob wearing a name tag. Crew are neither, so we fill it ourselves for
 *    anyone on [ClientCrewMarkers]. That is the whole of "show the crew's names".
 * 2. **The backdrop colour** is computed inside `NameTagFeatureRenderer$Storage.add`, which has no entity in
 *    scope -- only a matrix, a string and a distance. So the fact that a plate is a crew plate has to be
 *    carried there out of band: [active] is raised for the length of one `submitNameTag` call and read as the
 *    submit records are built. Both happen synchronously on the render thread, one entity at a time, which is
 *    what makes a plain flag safe here.
 */
@Environment(EnvType.CLIENT)
object CrewPlates {

    /**
     * True while the name tag currently being submitted belongs to a crew member.
     *
     * Not `@Volatile` on purpose: this is written and read on the render thread inside a single synchronous
     * call chain, and making it volatile would only advertise a concurrency that does not exist.
     */
    @JvmStatic
    var active = false

    /**
     * Recolour a name-tag backdrop for a crew member, keeping the alpha vanilla chose.
     *
     * Vanilla builds the backdrop as pure black at the player's Text Background Opacity, and passes 0 for the
     * opaque near pass which has no backdrop at all. Keeping the alpha byte and replacing only the colour is
     * what makes a crew plate sit at exactly the transparency of every other name tag on screen -- turn the
     * setting down and crew plates fade with everything else. Zero is left alone, or the pass that is meant to
     * have no box behind it would grow one.
     */
    @JvmStatic
    fun tint(backgroundColor: Int): Int {
        if (!active || backgroundColor == 0) return backgroundColor
        return (backgroundColor and ALPHA_MASK.toInt()) or PLATE_RGB
    }

    /** Eureka's dark cyan, of the Heart of the Sea family -- the item that buys the berth the plate sits over. */
    private const val PLATE_RGB = 0x0B3A44
    private const val ALPHA_MASK = 0xFF000000L
}
