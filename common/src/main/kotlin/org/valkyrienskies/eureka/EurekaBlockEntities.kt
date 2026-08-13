package org.valkyrienskies.eureka

import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.blockentity.EngineBlockEntity
import org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity
import org.valkyrienskies.eureka.registry.DeferredRegister
import org.valkyrienskies.eureka.registry.RegistrySupplier

@Suppress("unused")
object EurekaBlockEntities {
    private val BLOCKENTITIES = DeferredRegister.create(EurekaMod.MOD_ID, Registries.BLOCK_ENTITY_TYPE)

    val SHIP_HELM = setOf(
        EurekaBlocks.OAK_SHIP_HELM,
        EurekaBlocks.SPRUCE_SHIP_HELM,
        EurekaBlocks.BIRCH_SHIP_HELM,
        EurekaBlocks.PALE_OAK_SHIP_HELM,
        EurekaBlocks.JUNGLE_SHIP_HELM,
        EurekaBlocks.ACACIA_SHIP_HELM,
        EurekaBlocks.DARK_OAK_SHIP_HELM,
        EurekaBlocks.CRIMSON_SHIP_HELM,
        EurekaBlocks.WARPED_SHIP_HELM
    ) withBE ::ShipHelmBlockEntity byName "ship_helm"

    val ENGINE = EurekaBlocks.ENGINE withBE ::EngineBlockEntity byName "engine"

    // Only the rear half of a cannon ever builds one -- CannonBlock.newBlockEntity returns null for the
    // front -- but the type still has to name the block, which is the same block for both halves.
    val CANNON = EurekaBlocks.CANNON withBE ::CannonBlockEntity byName "cannon"

    fun register() {
        BLOCKENTITIES.applyAll()
    }

    private infix fun <T : BlockEntity> Set<RegistrySupplier<out Block>>.withBE(blockEntity: (BlockPos, BlockState) -> T) =
        Pair(this, blockEntity)

    private infix fun <T : BlockEntity> RegistrySupplier<out Block>.withBE(blockEntity: (BlockPos, BlockState) -> T) =
        Pair(setOf(this), blockEntity)

    private infix fun <T : BlockEntity> Block.withBE(blockEntity: (BlockPos, BlockState) -> T) = Pair(this, blockEntity)
    private infix fun <T : BlockEntity> Pair<Set<RegistrySupplier<out Block>>, (BlockPos, BlockState) -> T>.byName(name: String): RegistrySupplier<BlockEntityType<T>> =
        BLOCKENTITIES.register(name) {
            BlockEntityType(
                this.second,
                this.first.map { it.get() }.toSet()
            )
        }
}
