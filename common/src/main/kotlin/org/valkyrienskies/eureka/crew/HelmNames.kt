package org.valkyrienskies.eureka.crew

import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.StringUtil
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import org.joml.Vector3d
import org.valkyrienskies.eureka.armada.ArmadaGroup
import org.valkyrienskies.eureka.block.ShipHelmBlock
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.eureka.pirate.PirateHelm
import org.valkyrienskies.mod.common.executeIf
import org.valkyrienskies.mod.common.shipObjectWorld
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
     * The third seam: name the wheel the player is HOLDING. No position, because there isn't one.
     *
     * Same shape as the blueprint page's rename, and for the same reason -- the only wheel a captain can
     * be reading in this mode is one in their own hands, and a slot index is a number a client could make
     * up. The server looks in the two hands and nowhere else.
     */
    @Volatile
    @JvmField
    var clientItemNameSender: (String) -> Unit = { _ -> }

    /**
     * Write [raw] onto the ship's wheel in [player]'s hands.
     *
     * Vanilla's `CUSTOM_NAME` and nothing else, which is the whole trick: it is the same component the
     * block entity reads back in `applyImplicitComponents`, the same one the loot tables copy out, and the
     * one that draws the hover name -- so a wheel named in hand is named on the item, named when placed,
     * and names the hull it goes on to assemble, with no new storage anywhere.
     *
     * Blank clears the name, exactly as it does at a placed wheel.
     */
    fun renameHeld(player: ServerPlayer, raw: String): Boolean {
        val cleaned = StringUtil.filterText(raw).trim().take(MAX_NAME_LENGTH)
        for (hand in InteractionHand.entries) {
            val stack = player.getItemInHand(hand)
            val item = stack.item
            if (item !is BlockItem || item.block !is ShipHelmBlock) continue
            // No pirate gate needed here, unlike every other naming path: the pirate mark is a BLOCK STATE
            // property, applied when the manager places a wheel. Every helm item is the plain one.
            if (cleaned.isEmpty()) {
                stack.remove(DataComponents.CUSTOM_NAME)
            } else {
                stack.set(DataComponents.CUSTOM_NAME, Component.literal(cleaned))
            }
            return true
        }
        return false
    }

    /**
     * Rename the CREW [player] keeps at the wheel at [pos]. Nothing to do with the wheel's own name.
     *
     * The reach test is the point of this function. A name arrives as a packet, and a packet can say anything;
     * without it, any client could rename any crew in the world. Distance is measured to where the wheel
     * ACTUALLY is: an assembled helm is filed at shipyard coordinates, so its address and its position are
     * different places.
     *
     * A field write and nothing more. This used to move every crew on the wheel to a new key and REFUSE when
     * the destination was occupied or when clearing a name would strand somebody, because the name WAS the
     * address -- a crew was found by what it was called. A crew is a minted id now, so a name is a label:
     * nothing can collide, nothing can be orphaned, and there is nothing left to refuse.
     *
     * Returns false when the wheel is gone, out of reach, or keeps no crew of this captain's -- so the caller
     * can decline silently rather than report a failure the player cannot act on.
     */
    fun rename(level: ServerLevel, player: ServerPlayer, pos: BlockPos, raw: String): Boolean {
        val helm = level.getBlockEntity(pos) as? ShipHelmBlockEntity ?: return false
        if (!withinReach(level, player, helm)) return false
        // Pirate gate, door 11 of 14 (first half): silent -- these arrive from screens door 1 already refused.
        if (PirateHelm.gated(helm.blockState)) return false

        val cleaned = StringUtil.filterText(raw).trim().take(MAX_NAME_LENGTH)
        val ledger = CrewLedger.get(level.server)

        // A wheel carrying a roster from before the ledger existed hands it over the moment somebody asks
        // about its crew. Done first, so renaming immediately after adopting renames the adopted crew too.
        ledger.adoptLegacy(level.server, helm)

        // Off the ARTICLES wheel, not the one that was clicked, and through bindingFor rather than the raw
        // map. Both halves matter and both were missing: a multi-helm ship keeps its bindings on the crew
        // station, so every other wheel answered null and the rename returned false without a word -- and a
        // wheel from before crew ids carries a name and no binding at all until bindingFor adopts it. Same
        // pair of mistakes, and the same fix, as the helm menu's crew dropdown. See CrewRoll.build.
        val articles = CrewStations.shipOf(level, helm)?.let { CrewStations.stationOf(level, it) } ?: helm
        val crewId = ledger.bindingFor(articles, player.uuid) ?: return false
        // Blank is not a rename. A crew with no name would be a blank row in the helm's list with no way to
        // pick it out from any other, so the old name stands.
        if (cleaned.isEmpty()) return false
        if (!ledger.rename(crewId, cleaned)) return false

        // Say so, to the screen that asked. The captain is LOOKING at that name -- it is drawn in the corner
        // of the roster they just renamed it from -- and a rename that only appears after closing the book
        // and opening it again reads exactly like a rename that did not take. Nothing polls; every other
        // action in this menu answers with a fresh snapshot, and this one was the exception.
        //
        // The roll goes with it so the helm's own crew dropdown is right the moment they press Back, rather
        // than right because the menu happened to be rebuilt on the way. Same pair, and the same reasoning,
        // as ShipCrews.renameCrew -- which is the path that always did update, and the difference the
        // captain could see between the two tabs.
        CrewManifest.sender(player, CrewManifest.build(level, player, helm))
        CrewRoll.sender(player, CrewRoll.build(player, helm))
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
        // Pirate gate, door 11 of 14 (second half).
        if (PirateHelm.gated(helm.blockState)) return false
        val cleaned = StringUtil.filterText(raw).trim().take(MAX_NAME_LENGTH)
        val slug = slugOf(Component.literal(cleaned)) ?: return false

        // Named even when there is no hull to name: renaming at a wheel on the ground has to stick, or the
        // name would have to be typed again after assembling. On the ground the wheel IS the identity, so
        // the name goes straight onto it -- the half that survives being mined, since a wheel's name is
        // vanilla `CustomName` and rides the item through the loot table, the anvil and the placement.
        val ship = CrewStations.shipOf(level, helm)
        if (ship == null) {
            helm.setHelmName(Component.literal(cleaned))
            return true
        }

        // Assembled, the wheels are terminals and the name belongs to the SHIP -- so it is written on the
        // MASTER (the crew station), whichever terminal it was typed at. The terminals stay blank until
        // disassembly stamps the master's name onto all of them.
        (CrewStations.stationOf(level, ship) ?: helm).setHelmName(Component.literal(cleaned))
        // The loaded object this tick, so every menu and readout answers immediately -- and then BOTH
        // stores through applyShipName, because a slug written onto the loaded ship alone never reaches
        // disk: it read back correctly all session and reverted to the generated name on the next login,
        // which is how this hid. See applyShipName for the two-store story.
        vsCore.renameShip(ship, slug)
        applyShipName(level, ship.id, slug)
        // The menu readout, synchronously on EVERY wheel. Left to the tick stagger, each wheel kept its
        // stale copy for up to a second -- and the block update the master's own name-write just sent
        // carried the OLD slug, actively re-teaching every client the wrong name in the meantime.
        CrewStations.helmsAboard(level, ship)?.forEach { wheel ->
            if (!PirateHelm.gated(wheel.blockState)) wheel.noteShipSlug(slug)
        }
        // And the authoritative source the open menu draws from, in the same breath -- the same pair, and
        // the same reasoning, as the crew rename above.
        CrewRoll.sender(player, CrewRoll.build(player, helm))
        return true
    }

    /**
     * Write [slug] onto ship [shipId] until it sticks.
     *
     * A ship's slug lives in TWO places and they are not the same object: the persisted record in
     * `allShips`, which is what reaches disk and what `/vs` selectors match, and the loaded ship in
     * `loadedShips`, which is what the helm menu, the shipwright and every client actually read. A rename
     * must land in both or it is a rumour -- loaded-only evaporates at the next login, persisted-only
     * shows the generated name until something reloads the world.
     *
     * And once is not enough either. A slug written into a ship vs-core is still assembling does not
     * survive, and how long the build takes scales with the hull -- a bottle-released first-rate is not a
     * dinghy. The old single delayed write was tuned to the dinghy, which is why a released ship
     * SOMETIMES came up under its generated name with the wheel remembering the right one and nothing
     * left to apply it. So this checks its own work: write both stores, come back a few ticks later, and
     * go again until both read [slug] back or patience runs out.
     *
     * One yield: if some OTHER real name turns up meanwhile -- a captain typing at the wheel mid-retry --
     * theirs wins and the loop stands down. A generated slug is never deferred to; overwriting one is the
     * whole job.
     */
    fun applyShipName(level: ServerLevel, shipId: Long, slug: String, attemptsLeft: Int = NAME_APPLY_ATTEMPTS) {
        if (attemptsLeft <= 0) return
        val server = level.server
        val applyAt = server.overworld().gameTime + NAME_APPLY_INTERVAL_TICKS
        server.executeIf({ server.overworld().gameTime >= applyAt }) {
            val world = level.shipObjectWorld
            val persisted = world.allShips.getById(shipId)
            val loaded = world.loadedShips.getById(shipId)
            // Ship gone entirely: deleted, or never came up. Nothing to name.
            if (persisted == null && loaded == null) return@executeIf
            val current = loaded?.slug ?: persisted?.slug
            // Somebody chose a different real name while this was retrying; theirs is newer.
            if (current != null && current != slug && !CrewNameGenerator.looksGenerated(current)) return@executeIf
            val settled = (persisted == null || persisted.slug == slug) &&
                (loaded == null || loaded.slug == slug)
            if (settled) return@executeIf
            persisted?.let { vsCore.renameShip(it, slug) }
            loaded?.let { vsCore.renameShip(it, slug) }
            applyShipName(level, shipId, slug, attemptsLeft - 1)
        }
    }

    /**
     * Whether [player] is close enough to [helm] to be naming anything of hers.
     *
     * An UNASSEMBLED wheel is where its block position says it is. An assembled one is filed in the shipyard
     * and is really wherever the ship has carried it, so its position has to be transformed before the
     * distance means anything. Getting this backwards would make a docked ship's helm unnameable and a distant
     * one nameable.
     *
     * Sixteen blocks of the wheel, OR standing anywhere on her -- the armada included. The radius alone is the
     * wrong test for anything reached through a MENU, and that is how both callers get here: a captain opens
     * the helm at the wheel and then walks their own deck while it is still up. On a first-rate a hundred
     * blocks long that puts them out of range of a wheel they are standing thirty feet above, and the rename
     * refused in silence -- there being nothing sensible to say to someone who looks like they are nowhere
     * near the ship.
     *
     * This is [org.valkyrienskies.eureka.crew.ShipCrews]'s own book reach, and the crew manifest has always
     * used it. The Crews tab's rename went through that path and worked; the Roster tab's went through this
     * one and did not, for no reason a captain could see, because the two gestures are the same gesture.
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
        if (dx * dx + dy * dy + dz * dz <= REACH_SQ) return true
        return ship != null &&
            ShipCrew.standingOn(player)?.let { it in ArmadaGroup.idsOf(level, ship) } == true
    }

    /** Matches the crew menu's own reach, and comfortably more than a player can actually touch a block from. */
    private const val REACH_SQ = 16.0 * 16.0

    /** The helm menu's name box allows this many; vanilla's anvil allows 50. A ship's name reads better short. */
    const val MAX_NAME_LENGTH = 32

    // endregion

    /** Characters the ship selector can read back, beyond plain ASCII letters and digits. */
    private const val SLUG_SAFE = "_.+-"

    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * How [applyShipName] paces itself: a beat between write and check, and enough beats that even the
     * heaviest bottle-release assembly has long finished before patience runs out (~10 seconds). The
     * loop stands down the moment both stores read the name back, so the ceiling is almost never met.
     */
    private const val NAME_APPLY_INTERVAL_TICKS = 5L
    private const val NAME_APPLY_ATTEMPTS = 40
}
