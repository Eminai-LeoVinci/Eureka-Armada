package org.valkyrienskies.eureka

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import org.valkyrienskies.eureka.item.BlueprintItem
import org.valkyrienskies.eureka.item.BottledShipItem
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

    fun register() {
        // Declared above so they land before the block items; the creative tab walks this register in order.
        EurekaBlocks.registerItems(ITEMS)
        ITEMS.applyAll()
    }

    private infix fun Item.byName(name: String) = ITEMS.register(name) { this }
}
