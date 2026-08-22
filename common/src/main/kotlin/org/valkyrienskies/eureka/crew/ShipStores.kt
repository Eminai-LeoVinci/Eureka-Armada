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
 * The ship's holds, read as one store.
 *
 * The crew operations screen supplies guns and engines "from the ship" -- and this is what that phrase
 * means: every chest and barrel aboard any hull of the armada, counted, drawn from and paid back into as
 * a single pool, wherever on the vessel somebody happened to nail the box down. No registration, no
 * special supply block: build storage anywhere and the quartermaster finds it.
 *
 * ## Why an ALLOWLIST of chests and barrels, not "any container"
 * "Any container minus the bad ones" is a list of regrets waiting to be discovered one mod at a time: an
 * `is Container` sweep would drain cannons back into the pool they were just filled from, eat the coal
 * out of engines, empty hoppers mid-transfer, and unpack shulker boxes a player was about to carry off.
 * Chests and barrels ARE ship storage -- that is their whole job -- so they are the whole list. Trapped
 * chests ride along as the subclass they are; ender chests hold nothing themselves and are not block
 * containers at all. Each half of a double chest is its own 27 slots, which for pull-from-anywhere
 * semantics behaves identically to merging them and costs nothing.
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

    /**
     * How many items matching [predicate] the holds contain -- loose in the boxes, and packed in any
     * shulker box sitting in one.
     *
     * A hold full of shulkers is how anybody actually stores a thousand cannonballs, so a tally that could
     * not see inside them reported an empty ship to a captain standing on a magazine.
     */
    fun count(level: ServerLevel, ship: LoadedServerShip, predicate: (ItemStack) -> Boolean): Int {
        var total = 0
        for (hold in containersAboard(level, ship)) {
            for (slot in 0 until hold.containerSize) {
                val stack = hold.getItem(slot)
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
     * Take up to [amount] items matching [predicate] out of the holds, and answer how many actually came.
     *
     * Draws in container order, front stacks first, marking each touched box changed. A short answer is
     * not an error -- it is the holds running dry, and the caller decides what that means for the job in
     * hand (usually: stop, and say how far the supplies went).
     */
    fun withdraw(
        level: ServerLevel,
        ship: LoadedServerShip,
        predicate: (ItemStack) -> Boolean,
        amount: Int
    ): Int {
        if (amount <= 0) return 0
        var taken = 0

        for (hold in containersAboard(level, ship)) {
            var touched = false
            for (slot in 0 until hold.containerSize) {
                if (taken >= amount) break
                val stack = hold.getItem(slot)
                if (stack.isEmpty || !predicate(stack)) continue

                val take = minOf(amount - taken, stack.count)
                stack.shrink(take)
                if (stack.isEmpty) hold.setItem(slot, ItemStack.EMPTY)
                taken += take
                touched = true
            }
            if (touched) hold.setChanged()
            if (taken >= amount) break
        }
        if (taken < amount) taken += unpack(level, ship, predicate, amount - taken)
        return taken
    }

    /**
     * The second pass: break into the shulker boxes, once the loose stacks have run out.
     *
     * Loose first is the rule and the order matters -- a captain who leaves a few dozen balls in a chest
     * beside a hold full of sealed shulkers expects the loose ones spent first, exactly as they would
     * reach for them by hand.
     *
     * The shulker is not consumed and rebuilt, whatever it looks like from the deck: its contents
     * component is rewritten in place, on the very same item stack in the very same slot. That is what
     * makes the box come back the same colour, under the same name, with the same everything else still
     * inside it and only the rounds taken missing -- there is no copy for any of that to be lost by.
     */
    private fun unpack(
        level: ServerLevel,
        ship: LoadedServerShip,
        predicate: (ItemStack) -> Boolean,
        amount: Int
    ): Int {
        var taken = 0
        for (hold in containersAboard(level, ship)) {
            var touched = false
            for (slot in 0 until hold.containerSize) {
                if (taken >= amount) break
                val box = hold.getItem(slot)
                if (box.isEmpty || predicate(box)) continue
                val packed = box.get(DataComponents.CONTAINER) ?: continue

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

                // A shulker emptied to nothing keeps its component rather than losing it: an empty box
                // and a box with no contents component are the same thing to the game, and rewriting it
                // is what keeps the stack -- name, colour, slot -- otherwise untouched.
                box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items))
                taken += drawn
                touched = true
            }
            if (touched) hold.setChanged()
            if (taken >= amount) break
        }
        return taken
    }

    /**
     * Pay [stack] back into the holds, and answer what would not fit.
     *
     * Merges onto matching stacks first -- respecting the smaller of the item's and the container's stack
     * limits -- then takes empty slots. The remainder is returned rather than dropped or voided, because
     * only the caller knows where in the world "here" is; a swap-out at a gun drops its overflow at the
     * gun, not at some chest.
     */
    fun deposit(level: ServerLevel, ship: LoadedServerShip, stack: ItemStack): ItemStack {
        if (stack.isEmpty) return stack
        val holds = containersAboard(level, ship)

        // Merge pass first, so part-stacks fill up before fresh slots are broken into.
        for (hold in holds) {
            if (stack.isEmpty) break
            var touched = false
            val cap = minOf(stack.maxStackSize, hold.maxStackSize)
            for (slot in 0 until hold.containerSize) {
                if (stack.isEmpty) break
                val there = hold.getItem(slot)
                if (there.isEmpty || !ItemStack.isSameItemSameComponents(there, stack)) continue
                val room = cap - there.count
                if (room <= 0) continue
                val move = minOf(room, stack.count)
                there.grow(move)
                stack.shrink(move)
                touched = true
            }
            if (touched) hold.setChanged()
        }

        for (hold in holds) {
            if (stack.isEmpty) break
            var touched = false
            val cap = minOf(stack.maxStackSize, hold.maxStackSize)
            for (slot in 0 until hold.containerSize) {
                if (stack.isEmpty) break
                if (!hold.getItem(slot).isEmpty) continue
                val move = minOf(cap, stack.count)
                hold.setItem(slot, stack.copyWithCount(move))
                stack.shrink(move)
                touched = true
            }
            if (touched) hold.setChanged()
        }

        return stack
    }

    /**
     * One walk of the holds, tallied into the three currencies the operations screen shows: loose
     * gunpowder, cannonballs by kind, and furnace fuels by item -- fuels sorted longest-burning first,
     * which is also the order the refueller spends them in, so the list a player reads IS the plan.
     */
    fun tally(level: ServerLevel, ship: LoadedServerShip): Stores {
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

        for (hold in containersAboard(level, ship)) {
            for (slot in 0 until hold.containerSize) {
                val stack = hold.getItem(slot)
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
}
