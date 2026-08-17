package org.valkyrienskies.eureka

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import org.valkyrienskies.eureka.item.BlueprintItem
import org.valkyrienskies.eureka.item.BottledShipItem
import org.valkyrienskies.eureka.item.Cannonball
import org.valkyrienskies.eureka.item.CannonCharge
import org.valkyrienskies.eureka.item.Load
import org.valkyrienskies.eureka.item.CannonballItem
import org.valkyrienskies.eureka.item.ShipBottleItem
import org.valkyrienskies.eureka.registry.DeferredRegister
import org.valkyrienskies.eureka.registry.RegistrySupplier
import org.valkyrienskies.mod.common.itemProps

@Suppress("unused")
object EurekaItems {
    internal val ITEMS = DeferredRegister.create(EurekaMod.MOD_ID, Registries.ITEM)
    val TAB: ResourceKey<CreativeModeTab> =
        ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "eureka_tab"))

    /**
     * The empty bottle. Sneak and right-click a ship's wheel to mark it, then throw the bottle to take the ship.
     *
     * The mark is a block interaction and lives in a UseBlockCallback on the Fabric side; the throw is an
     * ordinary use and lives on the item.
     */
    val SHIP_BOTTLE: RegistrySupplier<Item> = ITEMS.register("ship_bottle") { ShipBottleItem(itemProps()) }

    /** The same bottle with a ship in it. Right-click a block to let it out. */
    val BOTTLED_SHIP: RegistrySupplier<Item> = ITEMS.register("bottled_ship") { BottledShipItem(itemProps()) }

    /**
     * A ship written down. Sneak and right-click a wheel with a blank one to draft it; right-click a drafted
     * one to read it.
     *
     * Unlike a bottle, drafting leaves the ship alone -- a blueprint is a reading, not a removal.
     */
    val BLUEPRINT: RegistrySupplier<Item> = ITEMS.register("blueprint") { BlueprintItem(itemProps()) }

    /**
     * Every round: each metal, plain and charged.
     *
     * Registered charge-major so the creative tab lists all five plain rounds together and then all five
     * explosive ones, which is how a player thinks about them -- pick a metal, then decide whether it is
     * worth the powder.
     */
    val CANNONBALLS: Map<Pair<Cannonball, CannonCharge>, RegistrySupplier<Item>> = buildMap {
        for (charge in CannonCharge.entries) {
            for (ball in Cannonball.entries) {
                val load = Load(ball, charge)
                put(ball to charge, ITEMS.register(load.itemName) {
                    // Read at registration, which is why EurekaMod loads the config first: a stack size is
                // baked into the item's properties and cannot change after this line runs.
                CannonballItem(ball, charge, itemProps().stacksTo(EurekaConfig.SERVER.cannonballStackSize.coerceIn(1, 99)))
                })
            }
        }
    }

    /** The item for one metal-and-charge combination. */
    fun cannonball(ball: Cannonball, charge: CannonCharge): Item = CANNONBALLS.getValue(ball to charge).get()

    fun register() {
        // Declared above so they land before the block items; the creative tab walks this register in order.
        EurekaBlocks.registerItems(ITEMS)
        ITEMS.applyAll()
    }

    private infix fun Item.byName(name: String) = ITEMS.register(name) { this }
}
