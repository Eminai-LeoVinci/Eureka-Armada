package org.valkyrienskies.eureka

import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.EntityRenderers
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.Level
import org.valkyrienskies.eureka.bottle.ThrownShipBottle
import org.valkyrienskies.eureka.registry.DeferredRegister
import org.valkyrienskies.eureka.registry.RegistrySupplier

private typealias EFactory<T> = (EntityType<T>, Level) -> T
private typealias RFactory<T> = EntityRendererProvider<T>

private data class ToRegEntityRenderer<T : Entity>(
    val type: RegistrySupplier<EntityType<T>>,
    val renderer: RFactory<in T>
) {
    fun register() =
        EntityRenderers.register(type.get(), renderer)
}

object EurekaEntities {
    private val ENTITIES = DeferredRegister.create(EurekaMod.MOD_ID, Registries.ENTITY_TYPE)
    private val ENTITY_RENDERERS = mutableListOf<ToRegEntityRenderer<*>>()

    // val SEAT = ::SeatEntity category MobCategory.MISC byName "seat" registerRenderer ::EmptyRenderer

    /**
     * A Ship Bottle in flight. Built to the eye of ender's numbers on purpose -- same hitbox, same update
     * interval -- because it is the same gesture and should carry the same weight in the air.
     *
     * Tracked further than an eye of ender is (16 chunks against 4): this one is worth watching all the way to
     * the ship, and the ship it is going for may well be further off than the eye's range.
     *
     * The renderer is registered from the Fabric client entrypoint rather than through [registerRenderer], next
     * to the helm's. Naming a client-only renderer here would drag it into this object's initialiser, which the
     * dedicated server runs.
     */
    val THROWN_BOTTLE: RegistrySupplier<EntityType<ThrownShipBottle>> =
        (::ThrownShipBottle category MobCategory.MISC)
            .sized(0.25f, 0.25f)
            .clientTrackingRange(16)
            // Every tick, unlike the eye of ender's four. Its path is decided server-side from ship transforms
            // the client cannot reproduce, so there is nothing to dead-reckon with between updates.
            .updateInterval(1)
            // A bottle carrying a ship must survive a throw into lava. Losing a whole vessel to a bad arc is
            // not a punishment anyone would read as fair.
            .fireImmune()
            .byName("thrown_ship_bottle")

    fun register() {
        ENTITIES.applyAll()
    }

    private infix fun <T : Entity> EFactory<T>.category(category: MobCategory) =
        EntityType.Builder.of(this, category)

    private infix fun <T : Entity> EntityType.Builder<T>.configure(run: EntityType.Builder<T>.() -> Unit) =
        this.apply { run(this) }

    private infix fun <T : Entity> RegistrySupplier<EntityType<T>>.registerRenderer(factory: RFactory<in T>) =
        this.apply { ENTITY_RENDERERS += ToRegEntityRenderer(this, factory) }

    private infix fun <T : Entity> EntityType.Builder<T>.byName(name: String) =
        ENTITIES.register(name) {
            this.build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, name)))
        }

    @JvmStatic
    fun registerRenderers() =
        ENTITY_RENDERERS.forEach { it.register() }
}
