package org.valkyrienskies.eureka.crew

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.eureka.armada.ArmadaGroup
import org.valkyrienskies.eureka.item.Cannonball
import org.valkyrienskies.eureka.item.CannonCharge
import org.valkyrienskies.eureka.item.CannonballItem
import org.valkyrienskies.eureka.registry.FuelRegistry
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * The ship's holds, read as one store -- but no longer as one undifferentiated heap.
 *
 * The crew operations screen supplies guns and engines "from the ship" -- and this is what that phrase
 * means: every chest and barrel aboard any hull of the armada, counted, drawn from and paid back into,
 * wherever on the vessel somebody happened to nail the box down. No registration, no special supply block:
 * build storage anywhere and the quartermaster finds it.
 *
 * ## Why an ALLOWLIST of chests and barrels, not "any container"
 * "Any container minus the bad ones" is a list of regrets waiting to be discovered one mod at a time: an
 * `is Container` sweep would drain cannons back into the pool they were just filled from, eat the coal
 * out of engines, empty hoppers mid-transfer, and unpack shulker boxes a player was about to carry off.
 * Chests and barrels ARE ship storage -- that is their whole job -- so they are the whole list. Trapped
 * chests ride along as the subclass they are; ender chests hold nothing themselves and are not block
 * containers at all. Each half of a double chest is its own 27 slots, which for pull-from-anywhere
 * semantics behaves identically to merging them and costs nothing.
 *
 * ## Where the boxes stopped being interchangeable
 * A ship with a powder room to port and a shot locker to starboard means those rooms differently, and the
 * old "first chest wins" rule could not: a few fights of swapping ammunition and the magazine was full of
 * coal. So every box now carries [HoldTag]s saying what it is for, and every draw and every payment prefers
 * the boxes tagged for what is moving, lowest number first, before falling back on the rest. The fallback
 * matters as much as the preference -- a ship on her first voyage has no tags at all and must still work.
 *
 * ## Resolve the holds ONCE
 * [Manifest] exists because the old API made a caller pay for a full armada-wide chunk walk per CALL, and
 * the callers are loops: restocking a sixty-gun broadside walked every container sixty times, and the
 * refueller does it in a `while` loop per fuel kind per engine. Labelling and tag-sorting that list would
 * have multiplied an already bad number. Take a [manifest] once per operation and spend it.
 */
object ShipStores {

    /** One cannonball kind in the holds. */
    data class AmmoCount(val ball: Cannonball, val charge: CannonCharge, val count: Int)

    /** One furnace-fuel kind in the holds, with how long one of it burns. */
    data class FuelCount(val itemId: String, val count: Int, val burnTicks: Int)

    /**
     * What the holds have to give, in the three currencies the operations screen spends. Doubles as the
     * client's model of the same -- it is ids and counts, nothing loader- or side-bound.
     */
    data class Stores(
        val gunpowder: Int,
        val ammo: List<AmmoCount>,
        val fuels: List<FuelCount>
    )

    /**
     * Every chest and barrel aboard, armada-wide, in a deterministic order.
     *
     * The same chunk-walk as `ShipGuns.aboard`: hull AABBs give chunk ranges, `getChunkNow` never forces
     * a chunk in (a miss is a chunk that is not part of this hull), and the corner check keeps a
     * neighbouring ship's boxes out of this ship's stores. Sorted by position so "the first chest" means
     * the same chest every time -- withdrawal order is part of the feature's behaviour, not an accident.
     */
    fun containersAboard(level: ServerLevel, ship: LoadedServerShip): List<BaseContainerBlockEntity> {
        val ships = level.shipObjectWorld.loadedShips
        val holds = ArrayList<BaseContainerBlockEntity>()

        for (id in ArmadaGroup.idsOf(level, ship)) {
            val hull = ships.getById(id) ?: continue
            val aabb = hull.shipAABB ?: continue

            val minChunkX = aabb.minX() shr 4
            val maxChunkX = aabb.maxX() shr 4
            val minChunkZ = aabb.minZ() shr 4
            val maxChunkZ = aabb.maxZ() shr 4

            for (chunkX in minChunkX..maxChunkX) {
                for (chunkZ in minChunkZ..maxChunkZ) {
                    val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                    for (blockEntity in chunk.blockEntities.values) {
                        if (blockEntity !is ChestBlockEntity && blockEntity !is BarrelBlockEntity) continue
                        val pos = blockEntity.blockPos
                        if (pos.x < aabb.minX() || pos.x > aabb.maxX() ||
                            pos.y < aabb.minY() || pos.y > aabb.maxY() ||
                            pos.z < aabb.minZ() || pos.z > aabb.maxZ()
                        ) {
                            continue
                        }
                        holds.add(blockEntity as BaseContainerBlockEntity)
                    }
                }
            }
        }

        return holds.sortedWith(
            compareBy({ it.blockPos.x }, { it.blockPos.z }, { it.blockPos.y })
        )
    }

    // region the manifest -- the holds resolved once, numbered, and sorted by what they are for

    /** One box, with the number a captain reads and what it has learned it is for. */
    class Hold(val box: BaseContainerBlockEntity, val label: String?) {
        val tags: Set<HoldTag> get() = HoldTags.tagsOf(box)
        fun isFor(tag: HoldTag): Boolean = HoldTags.has(box, tag)

        /** For a message. A hull with no claimed wheel has no numbers, so say where it is instead. */
        val name: String
            get() = label ?: box.blockPos.let { "the hold at ${it.x}, ${it.y}, ${it.z}" }
    }

    /** What a withdrawal actually did, and which boxes it came out of -- the restock message needs both. */
    class Draw(val taken: Int, val from: List<String>)

    /** What would not fit, and which boxes took the rest. */
    class Deposit(val leftover: ItemStack, val into: List<String>)

    /**
     * Every hold aboard, in reading order, resolved once.
     *
     * Prefers [HoldLabels] so the boxes carry their numbers, and falls back to the bare position-sorted
     * walk when the ship has no claimed wheel to number from. That fallback is not a nicety: refuelling
     * does not require a crew station, and a ship that could not be refuelled until somebody claimed the
     * articles would be a regression dressed as a feature. Tags still work in that state -- they live on
     * the box, not on the number -- so only the message loses its names.
     */
    fun manifest(level: ServerLevel, ship: LoadedServerShip): Manifest {
        val labelled = HoldLabels.labeled(level, ship)
        if (labelled.isNotEmpty()) {
            return Manifest(level, labelled.map { Hold(it.hold, it.label) })
        }
        return Manifest(level, containersAboard(level, ship).map { Hold(it, null) })
    }

    class Manifest(val level: ServerLevel, val holds: List<Hold>) {

        val isEmpty: Boolean get() = holds.isEmpty()

        /**
         * The holds in the order this job should visit them: those tagged for [tag] first, then everything
         * else, each group keeping its reading order so "lowest number first" means what it says.
         *
         * Untagged boxes are never excluded, only deprioritised. A ship on her first voyage has no tags at
         * all, and a quartermaster that refused to look in an unlabelled box would simply not work.
         */
        fun order(tag: HoldTag?): List<Hold> {
            if (tag == null) return holds
            val preferred = ArrayList<Hold>(holds.size)
            val rest = ArrayList<Hold>(holds.size)
            for (hold in holds) (if (hold.isFor(tag)) preferred else rest).add(hold)
            if (preferred.isEmpty()) return holds
            preferred.addAll(rest)
            return preferred
        }

        /**
         * Where stock of [tag]'s kind should be PAID BACK: the tagged boxes first, then the first box on the
         * lowest deck, then anywhere it will go.
         *
         * The middle step is the "cold ship" rule. Ammunition unloaded from the guns of a hull that has never
         * had a cannonball in a chest has to land somewhere, and scattering it into whatever had room is how
         * a captain ends up finding shot in three rooms. One predictable box, and the caller says which.
         */
        fun payInto(tag: HoldTag?): List<Hold> {
            if (tag == null) return holds
            val preferred = holds.filter { it.isFor(tag) }
            if (preferred.isNotEmpty()) return preferred + holds.filterNot { it.isFor(tag) }
            val fallback = holds.firstOrNull() ?: return holds
            return listOf(fallback) + holds.drop(1)
        }

        /** The box a cold ship falls back on, for the message that has to name it. */
        fun defaultHold(): Hold? = holds.firstOrNull()

        /**
         * How many items matching [predicate] the holds contain -- loose in the boxes, and packed in any
         * shulker box sitting in one.
         *
         * A hold full of shulkers is how anybody actually stores a thousand cannonballs, so a tally that
         * could not see inside them reported an empty ship to a captain standing on a magazine.
         */
        fun count(predicate: (ItemStack) -> Boolean): Int {
            var total = 0
            for (hold in holds) {
                val box = hold.box
                for (slot in 0 until box.containerSize) {
                    val stack = box.getItem(slot)
                    if (stack.isEmpty) continue
                    if (predicate(stack)) {
                        total += stack.count
                        continue
                    }
                    val packed = stack.get(DataComponents.CONTAINER) ?: continue
                    for (inner in packed.nonEmptyItems()) {
                        if (predicate(inner)) total += inner.count
                    }
                }
            }
            return total
        }

        /**
         * Take up to [amount] items matching [predicate], preferring boxes tagged for [tag].
         *
         * Loose stacks across every box first, then into the shulkers -- and the order matters. A captain
         * who leaves a few dozen balls in a chest beside a hold full of sealed shulkers expects the loose
         * ones spent first, exactly as they would reach for them by hand.
         *
         * A short answer is not an error -- it is the holds running dry, and the caller decides what that
         * means for the job in hand (usually: stop, and say how far the supplies went).
         */
        fun withdraw(tag: HoldTag?, predicate: (ItemStack) -> Boolean, amount: Int): Draw {
            if (amount <= 0) return Draw(0, emptyList())
            val visit = order(tag)
            val from = LinkedHashSet<String>()
            var taken = 0

            for (hold in visit) {
                if (taken >= amount) break
                val box = hold.box
                var touched = false
                for (slot in 0 until box.containerSize) {
                    if (taken >= amount) break
                    val stack = box.getItem(slot)
                    if (stack.isEmpty || !predicate(stack)) continue

                    val take = minOf(amount - taken, stack.count)
                    stack.shrink(take)
                    if (stack.isEmpty) box.setItem(slot, ItemStack.EMPTY)
                    taken += take
                    touched = true
                }
                if (touched) {
                    box.setChanged()
                    from.add(hold.name)
                }
            }

            if (taken < amount) taken += unpack(visit, predicate, amount - taken, from)
            return Draw(taken, from.toList())
        }

        /**
         * The second pass: break into the shulker boxes, once the loose stacks have run out.
         *
         * **The box never leaves its chest.** Its contents component is rewritten in place, on the very same
         * item stack in the very same slot -- there is no copy for anything to be lost by, and no journey for
         * it to be misfiled at the end of. That is what makes a part-spent shulker come back the same colour,
         * under the same name, with the same everything else still inside it and only the rounds taken
         * missing; and it is why one emptied to nothing is still sitting in the chest it was stored in rather
         * than wherever there happened to be room. A shulker emptied to nothing also KEEPS its component
         * rather than losing it: an empty box and a box with no contents component are the same thing to the
         * game, and rewriting it is what keeps the stack otherwise untouched.
         *
         * Within one box the emptiest shulker goes first, so part-used boxes get finished instead of
         * accumulating -- a magazine slowly filling with forty shulkers holding six rounds each is a real way
         * to run out of space while appearing to have plenty. Box preference still leads: this decides the
         * order WITHIN a chest, never which chest.
         */
        /**
         * Put stock back INSIDE the shulker boxes it most likely came out of.
         *
         * The mirror of [unpack], and deliberately its opposite in one respect: that one spends the EMPTIEST
         * shulker first so part-used boxes get finished, and this one fills the emptiest first for the same
         * reason -- a hold of half-full shulkers wastes space whichever direction the stock is moving.
         * Partly-filled boxes holding this item are topped up before an empty box is opened, so a captain
         * does not end up with two half boxes where one full one would do.
         *
         * Rewritten in place, exactly as [unpack] does: the shulker never leaves its slot, so its name,
         * colour and everything else already inside it survive untouched.
         *
         * Only boxes that are ALREADY shulkers are used -- nothing is ever packed into a shulker that was
         * not there, and no shulker is created. This tidies a hold; it does not reorganise one.
         */
        private fun repack(visit: List<Hold>, stack: ItemStack, into: MutableSet<String>) {
            for (hold in visit) {
                if (stack.isEmpty) break
                val box = hold.box
                var touched = false

                // Emptiest first, and matching boxes ahead of untouched ones.
                val candidates = (0 until box.containerSize)
                    .mapNotNull { slot ->
                        val item = box.getItem(slot)
                        if (item.isEmpty || item.count != 1) return@mapNotNull null
                        val packed = item.get(DataComponents.CONTAINER) ?: return@mapNotNull null
                        val items = packed.stream().map { it.copy() }.toList()
                        // A shulker holding something else entirely is somebody's filing; leave it be.
                        val holdsOther = items.any { !it.isEmpty && !ItemStack.isSameItemSameComponents(it, stack) }
                        if (holdsOther) return@mapNotNull null
                        Triple(slot, item, items)
                    }
                    .sortedBy { (_, _, items) -> items.sumOf { it.count } }

                for ((slot, boxStack, items) in candidates) {
                    if (stack.isEmpty) break
                    val cap = stack.maxStackSize
                    val room = ArrayList(items)
                    while (room.size < SHULKER_SLOTS) room.add(ItemStack.EMPTY)
                    var moved = 0

                    for (i in room.indices) {
                        if (stack.count - moved <= 0) break
                        val there = room[i]
                        if (there.isEmpty) {
                            val move = minOf(cap, stack.count - moved)
                            room[i] = stack.copyWithCount(move)
                            moved += move
                        } else if (ItemStack.isSameItemSameComponents(there, stack) && there.count < cap) {
                            val move = minOf(cap - there.count, stack.count - moved)
                            there.grow(move)
                            moved += move
                        }
                    }
                    if (moved == 0) continue

                    boxStack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(room))
                    box.setItem(slot, boxStack)
                    stack.shrink(moved)
                    touched = true
                }

                if (touched) {
                    box.setChanged()
                    into.add(hold.name)
                }
            }
        }

        private fun unpack(
            visit: List<Hold>,
            predicate: (ItemStack) -> Boolean,
            amount: Int,
            from: MutableSet<String>
        ): Int {
            var taken = 0
            for (hold in visit) {
                if (taken >= amount) break
                val box = hold.box
                var touched = false

                // Emptiest first, by how much of what we are AFTER each one holds -- a shulker of gunpowder
                // is not a part-used shulker of shot, and ranking them together would spend the wrong one.
                val slots = (0 until box.containerSize)
                    .filter { slot ->
                        val stack = box.getItem(slot)
                        !stack.isEmpty && !predicate(stack) && stack.get(DataComponents.CONTAINER) != null
                    }
                    .sortedBy { slot -> packedCount(box.getItem(slot), predicate) }

                for (slot in slots) {
                    if (taken >= amount) break
                    val packedBox = box.getItem(slot)
                    val packed = packedBox.get(DataComponents.CONTAINER) ?: continue

                    val items = packed.stream().map { it.copy() }.toList()
                    var drawn = 0
                    for (inner in items) {
                        if (taken + drawn >= amount) break
                        if (inner.isEmpty || !predicate(inner)) continue
                        val take = minOf(amount - taken - drawn, inner.count)
                        inner.shrink(take)
                        drawn += take
                    }
                    if (drawn == 0) continue

                    packedBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items))
                    taken += drawn
                    touched = true
                }
                if (touched) {
                    box.setChanged()
                    from.add(hold.name)
                }
            }
            return taken
        }

        private fun packedCount(box: ItemStack, predicate: (ItemStack) -> Boolean): Int {
            val packed = box.get(DataComponents.CONTAINER) ?: return 0
            var total = 0
            for (inner in packed.nonEmptyItems()) if (predicate(inner)) total += inner.count
            return total
        }

        /**
         * Pay [stack] back into the holds, preferring boxes tagged for [tag], and answer what would not fit.
         *
         * Merges onto matching stacks first -- respecting the smaller of the item's and the container's stack
         * limits -- then takes empty slots. The remainder is returned rather than dropped or voided, because
         * only the caller knows where in the world "here" is; a swap-out at a gun drops its overflow at the
         * gun, not at some chest.
         */
        fun deposit(tag: HoldTag?, stack: ItemStack): Deposit {
            if (stack.isEmpty) return Deposit(stack, emptyList())
            val visit = payInto(tag)
            val into = LinkedHashSet<String>()

            // Merge pass first, so part-stacks fill up before fresh slots are broken into.
            for (hold in visit) {
                if (stack.isEmpty) break
                val box = hold.box
                var touched = false
                val cap = minOf(stack.maxStackSize, box.maxStackSize)
                for (slot in 0 until box.containerSize) {
                    if (stack.isEmpty) break
                    val there = box.getItem(slot)
                    if (there.isEmpty || !ItemStack.isSameItemSameComponents(there, stack)) continue
                    val room = cap - there.count
                    if (room <= 0) continue
                    val move = minOf(room, stack.count)
                    there.grow(move)
                    stack.shrink(move)
                    touched = true
                }
                if (touched) {
                    box.setChanged()
                    into.add(hold.name)
                }
            }

            // Then back INTO the shulkers, before any fresh slot is broken into.
            //
            // A magazine is stored packed, and stock that comes home loose does not stay a magazine: a
            // withdrawal empties shulkers to source what it needs, and if the surplus returns as loose
            // stacks the box ends up with forty empty shulkers and its shelves full of coal. Repacking is
            // what closes that loop, so a hold looks the same after a restock as it did before one.
            if (!stack.isEmpty) repack(visit, stack, into)

            for (hold in visit) {
                if (stack.isEmpty) break
                val box = hold.box
                var touched = false
                val cap = minOf(stack.maxStackSize, box.maxStackSize)
                for (slot in 0 until box.containerSize) {
                    if (stack.isEmpty) break
                    if (!box.getItem(slot).isEmpty) continue
                    val move = minOf(cap, stack.count)
                    box.setItem(slot, stack.copyWithCount(move))
                    stack.shrink(move)
                    touched = true
                }
                if (touched) {
                    box.setChanged()
                    into.add(hold.name)
                }
            }

            return Deposit(stack, into.toList())
        }
    }

    // endregion

    // region the one-shot forms -- correct, and fine for a single call outside a loop

    fun count(level: ServerLevel, ship: LoadedServerShip, predicate: (ItemStack) -> Boolean): Int =
        manifest(level, ship).count(predicate)

    fun withdraw(
        level: ServerLevel,
        ship: LoadedServerShip,
        predicate: (ItemStack) -> Boolean,
        amount: Int
    ): Int = manifest(level, ship).withdraw(null, predicate, amount).taken

    fun deposit(level: ServerLevel, ship: LoadedServerShip, stack: ItemStack): ItemStack =
        manifest(level, ship).deposit(null, stack).leftover

    // endregion

    /**
     * One walk of the holds, tallied into the three currencies the operations screen shows: loose
     * gunpowder, cannonballs by kind, and furnace fuels by item -- fuels sorted longest-burning first,
     * which is also the order the refueller spends them in, so the list a player reads IS the plan.
     */
    fun tally(level: ServerLevel, ship: LoadedServerShip): Stores = tally(manifest(level, ship))

    fun tally(manifest: Manifest): Stores {
        val level = manifest.level
        var gunpowder = 0
        val ammo = HashMap<Pair<Cannonball, CannonCharge>, Int>()
        val fuels = HashMap<String, IntArray>() // itemId -> [count, burnTicks]
        val fuelValues = level.fuelValues()

        // One stack, counted into whichever pile it belongs to. Answers whether it was stock at all, which
        // is what tells the sweep below to look INSIDE a stack it did not recognise.
        fun record(stack: ItemStack): Boolean {
            val item = stack.item
            if (stack.`is`(Items.GUNPOWDER)) {
                gunpowder += stack.count
                return true
            }
            if (item is CannonballItem) {
                ammo.merge(item.ball to item.charge, stack.count, Int::plus)
                return true
            }
            val burn = FuelRegistry.INSTANCE.get(stack, fuelValues)
            if (burn > 0) {
                val id = BuiltInRegistries.ITEM.getKey(item).toString()
                fuels.getOrPut(id) { intArrayOf(0, burn) }[0] += stack.count
                return true
            }
            return false
        }

        for (hold in manifest.holds) {
            val box = hold.box
            for (slot in 0 until box.containerSize) {
                val stack = box.getItem(slot)
                if (stack.isEmpty) continue
                if (record(stack)) continue

                // A shulker box in the hold is stock like anything else, and the restocks can reach into
                // one -- so the tally has to see the same shelf they do, or the Operations tab reports an
                // empty ship to a captain standing on a full magazine.
                val packed = stack.get(DataComponents.CONTAINER) ?: continue
                for (inner in packed.nonEmptyItems()) record(inner)
            }
        }

        return Stores(
            gunpowder = gunpowder,
            ammo = ammo.entries
                .map { (kind, count) -> AmmoCount(kind.first, kind.second, count) }
                .sortedWith(compareBy({ it.charge.ordinal }, { it.ball.ordinal })),
            fuels = fuels.entries
                .map { (id, tallied) -> FuelCount(id, tallied[0], tallied[1]) }
                .sortedByDescending { it.burnTicks }
                .take(MAX_FUEL_KINDS)
        )
    }

    /** Wire and sanity bound on how many distinct fuel kinds are reported; nobody reads past this many. */
    const val MAX_FUEL_KINDS = 64

    /** A shulker box holds 27, and [Manifest.repack] has to know that to fill an empty one. */
    const val SHULKER_SLOTS = 27
}
