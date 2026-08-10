package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.StringUtil
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.mod.common.vsCore

/**
 * The name written on a wheel, and the two things a name has to turn into.
 *
 * A helm's name is typed by a player, so it can be anything: mixed case, spaces, punctuation, an emoji. Two
 * different consumers need it in two different shapes, and neither can take it raw.
 *
 * The first is the SHIP. `vsCore.renameShip` takes a slug, and a slug is read back by VS2's own ship selector
 * with `StringReader.readUnquotedString`, which accepts only `[0-9A-Za-z_.+-]`. A space or an apostrophe in a
 * ship's slug does not fail loudly -- it makes `@v[slug=...]` stop matching that ship, so `/vs` commands
 * quietly address the wrong hull or none at all. [slugOf] is what keeps that from happening.
 *
 * The second is the LEDGER KEY. Crew are filed under (captain, name, wood variant), and a captain re-naming a
 * replacement wheel is typing from memory, not copying a string. Matching is therefore deliberately forgiving
 * about the things people get wrong -- case, stray outer whitespace, doubled spaces -- and strict about
 * everything else. [keyOf] is that form.
 *
 * They are separate on purpose. Two wheels named "Black Pearl" and "black-pearl" produce the same slug and
 * would look like the same ship, but they are NOT the same berth, and a crew must not transfer between them.
 */
object HelmNames {

    /**
     * The ship-slug form of [name]: safe for `@v[slug=...]`, and still readable.
     *
     * Whitespace becomes a single dash, characters the selector cannot read are dropped rather than replaced
     * (dropping "Anne's" to "Annes" reads better than "Anne-s"), and runs of dashes collapse so "Black  --
     * Pearl" does not come out full of them. Returns null when nothing legible survives -- a name of pure
     * punctuation is not a slug, and the caller leaves the ship's generated name alone rather than renaming it
     * to something meaningless.
     */
    fun slugOf(name: Component): String? {
        val out = StringBuilder()
        for (ch in name.string) {
            when {
                ch.isWhitespace() -> if (out.isNotEmpty() && out.last() != '-') out.append('-')
                ch in SLUG_SAFE -> out.append(ch)
                ch.isLetterOrDigit() && ch.code < 128 -> out.append(ch)
                // Everything else -- apostrophes, quotes, brackets, anything non-ASCII -- is dropped.
            }
        }
        while (out.isNotEmpty() && out.last() == '-') out.setLength(out.length - 1)
        return out.toString().takeIf { it.isNotEmpty() }
    }

    /**
     * The ledger-key form of [name]: what "the same name" means when a captain re-names a wheel to get a crew
     * back.
     *
     * Lowercased, trimmed, and internal whitespace runs collapsed to one space. Nothing else is touched --
     * punctuation stays significant, because a captain who typed one thing and meant it should not have their
     * crew handed to someone who typed something else.
     */
    fun keyOf(name: Component): String = keyOf(name.string)

    /** The same normalisation for a name already in string form -- one already read back out of the ledger. */
    fun keyOf(name: String): String = name.trim().lowercase().replace(WHITESPACE_RUN, " ")

    /**
     * The wood variant of a helm, as the block's registry id.
     *
     * The variant is half of what identifies a berth -- an oak wheel named Black Pearl is not the pale oak one
     * -- and the block id is the variant said exactly, with no enum of our own to keep in step with
     * `EurekaBlocks` every time a wood type is added.
     */
    fun variantOf(state: BlockState): String = BuiltInRegistries.BLOCK.getKey(state.block).toString()

    // region Naming a wheel

    /**
     * Sends a typed name from the helm menu to the server. Installed by the loader layer; a no-op until then.
     *
     * The helm menu lives in :common and the networking in :fabric, and a container menu cannot carry a name
     * anyway -- its sync slots are ints. So the screen calls through this seam, the same shape
     * [CrewManifest.sender] uses in the other direction.
     */
    @Volatile
    @JvmField
    var clientSender: (BlockPos, String) -> Unit = { _, _ -> }

    /**
     * The same seam for naming the SHIP, used by the helm menu's Rename button.
     *
     * Separate from [clientSender] rather than a flag on it, because the two names are separate things now: one
     * is the crew's, one is the hull's, and a button that could accidentally send the wrong one is a bug
     * waiting to be typed.
     */
    @Volatile
    @JvmField
    var clientShipSender: (BlockPos, String) -> Unit = { _, _ -> }

    /**
     * Write [raw] onto the wheel at [pos], if [player] is close enough to have typed it.
     *
     * The reach test is the point of this function. A name arrives as a packet, and a packet can say anything;
     * without it, any client could name any wheel in the world -- including one on someone else's ship, whose
     * crew are filed under its name. Distance is measured to where the wheel ACTUALLY is: an assembled helm is
     * filed at shipyard coordinates, so its address and its position are different places.
     *
     * Blank clears the name. That is the one way back to an unnamed wheel, and it is deliberate -- a captain
     * who wants to retire a name should not have to break the block to do it.
     *
     * Returns false when the wheel is gone or out of reach, so the caller can decline silently rather than
     * report a failure the player cannot act on.
     */
    fun rename(level: ServerLevel, player: ServerPlayer, pos: BlockPos, raw: String): Boolean {
        val helm = level.getBlockEntity(pos) as? ShipHelmBlockEntity ?: return false
        if (!withinReach(level, player, helm)) return false

        val cleaned = StringUtil.filterText(raw).trim().take(MAX_NAME_LENGTH)
        val ledger = CrewLedger.get(level.server)
        val previous = helm.helmName

        // A wheel carrying a roster from before the ledger existed hands it over the moment it has a name to
        // file it under. Done first, so a rename immediately after naming moves the adopted crew too.
        ledger.adoptLegacy(level.server, helm)

        if (cleaned.isEmpty()) {
            // Clearing a name would leave any crew filed under it unreachable -- there would be no key to look
            // them up by, and no way to type the old name back except from memory. Refuse while anyone is
            // signed on; paying the crew off is the deliberate way to retire a name.
            if (previous != null && ledger.anyUnder(keyOf(previous), variantOf(helm.blockState))) {
                PathMessages.send(
                    player,
                    "${previous.string} still has crew signed on. Pay them off before clearing the name.",
                    PathMessages.Kind.ERROR
                )
                return false
            }
            helm.setHelmName(null)
            return true
        }

        // Typing the name it already has is not a rename -- nothing to move, nothing to write.
        if (previous?.string == cleaned) return true

        // Move the crew with the name. Refused rather than merged when the captain already has a crew of the
        // new name on this wood -- see CrewLedger.renameAll.
        if (previous != null && !ledger.renameAll(previous.string, cleaned, variantOf(helm.blockState))) {
            PathMessages.send(
                player,
                "You already have a crew called $cleaned on this wood. Pick another name.",
                PathMessages.Kind.ERROR
            )
            return false
        }

        helm.setHelmName(Component.literal(cleaned))
        return true
    }

    /**
     * Rename the SHIP the wheel at [pos] belongs to. Nothing to do with the wheel's own name.
     *
     * This is the helm menu's old Rename button, moved off `sendCommand("vs rename ...")` and onto a payload.
     * The command route worked in single-player only by accident: VS2 gates `/vs rename` behind an op
     * permission, so on a server an ordinary player pressed the button and nothing happened, silently. A
     * captain naming their own ship is not an administrative act, so there is no permission check here -- the
     * reach test is the check, exactly as it is for naming the wheel.
     *
     * The typed name is slugged because [slugOf]'s constraint is vs-core's: `Ship.slug` is read back by
     * `@v[slug=...]` with an unquoted-string parser, so a space would make the ship unaddressable by the very
     * commands this name exists for. A name that slugs to nothing leaves the generated slug alone rather than
     * blanking it.
     */
    fun renameShip(level: ServerLevel, player: ServerPlayer, pos: BlockPos, raw: String): Boolean {
        val helm = level.getBlockEntity(pos) as? ShipHelmBlockEntity ?: return false
        if (!withinReach(level, player, helm)) return false
        val ship = CrewStations.shipOf(level, helm) ?: return false
        val slug = slugOf(Component.literal(StringUtil.filterText(raw).trim().take(MAX_NAME_LENGTH)))
            ?: return false
        vsCore.renameShip(ship, slug)
        // The wheel this was typed at remembers immediately, so Keep Name is right even if the ship is taken
        // apart in the same breath. Every OTHER wheel on the hull picks the new name up from its own tick --
        // see ShipHelmBlockEntity.tick -- which is why this does not have to go looking for them.
        helm.rememberShipName(slug)
        return true
    }

    /**
     * Whether [player] is standing close enough to [helm] to be naming it.
     *
     * An UNASSEMBLED wheel is where its block position says it is. An assembled one is filed in the shipyard
     * and is really wherever the ship has carried it, so its position has to be transformed before the
     * distance means anything. Getting this backwards would make a docked ship's helm unnameable and a distant
     * one nameable.
     */
    private fun withinReach(level: ServerLevel, player: ServerPlayer, helm: ShipHelmBlockEntity): Boolean {
        val pos = helm.blockPos
        val ship = CrewStations.shipOf(level, helm)
        val world = if (ship == null) {
            Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        } else {
            ship.shipToWorld.transformPosition(Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))
        }
        val dx = player.x - world.x
        val dy = player.y - world.y
        val dz = player.z - world.z
        return dx * dx + dy * dy + dz * dz <= REACH_SQ
    }

    /** Matches the crew menu's own reach, and comfortably more than a player can actually touch a block from. */
    private const val REACH_SQ = 16.0 * 16.0

    /** The helm menu's name box allows this many; vanilla's anvil allows 50. A ship's name reads better short. */
    const val MAX_NAME_LENGTH = 32

    // endregion

    /** Characters the ship selector can read back, beyond plain ASCII letters and digits. */
    private const val SLUG_SAFE = "_.+-"

    private val WHITESPACE_RUN = Regex("\\s+")
}
