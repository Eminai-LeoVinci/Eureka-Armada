package org.valkyrienskies.eureka.cannon

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.EurekaProperties
import org.valkyrienskies.eureka.EurekaProperties.CANNON_PART
import org.valkyrienskies.eureka.EurekaProperties.ELEVATION
import org.valkyrienskies.eureka.block.CannonBlock
import org.valkyrienskies.eureka.block.CannonPart
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
import org.valkyrienskies.eureka.item.CannonCharge
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING

/**
 * Touching a linstock to the touch-hole.
 *
 * ## The gun does not aim
 * A cannon fires exactly where its barrel points, and the barrel is bolted to the hull. Pointing it is the
 * *ship's* job -- which is the whole reason a fight is about manoeuvring rather than about clicking faster,
 * and why the existing pursuit code turns into a combat AI almost for free.
 *
 * ## Shipyard in, world out
 * Everything about a cannon lives in shipyard space: the block, its facing, the muzzle offset derived from
 * the model. A projectile lives in world space. The conversion is the only fiddly part, and it is done by
 * transforming **two** points down the bore and subtracting them, rather than by rotating a direction vector
 * -- that way the ship's rotation and its scale are both handled by the same transform that placed the muzzle,
 * and there is no second code path to get subtly wrong.
 */
object CannonFire {

    // One shot per gun per reload, and how long that is now depends on the powder measure -- see
    // PowderCharge.reloadTicks. Broadside weight still comes from gun count, not from fire rate.

    /**
     * The bore, measured off the model, relative to the rear block's centre and in blocks.
     *
     * The trunnions are the pivot, so the muzzle is not at a fixed offset -- it swings on an arc as the gun
     * is laid. Deriving it from the pivot and the barrel's length means the flash always leaves the actual
     * mouth of the bore, at every elevation, without a lookup table to keep in step with the models.
     */
    private const val TRUNNION_FORWARD = -0.3125
    private const val TRUNNION_HEIGHT = 0.53
    private const val BORE_LENGTH = 1.375

    /** Why a gun would not fire, or null if it did. */
    fun fire(level: ServerLevel, clicked: BlockPos, player: ServerPlayer?): Component? {
        val state = level.getBlockState(clicked)
        if (state.block !is CannonBlock) return null

        // Either half of the gun is a valid place to strike a spark; the magazine is on the breech.
        val facing = state.getValue(HORIZONTAL_FACING)
        val rear = clicked.relative(facing.opposite, state.getValue(CANNON_PART).ordinal)
        val magazine = level.getBlockEntity(rear) as? CannonBlockEntity
            ?: return Component.translatable("info.vs_eureka.cannon_broken")

        // The gun's own transform, resolved once: it puts the muzzle into world space further down.
        val ship = level.getLoadedShipManagingPos(rear)

        // What the breech is set to, which decides the arc, the powder cost AND the reload.
        val charge = magazine.powderCharge
        val cooldown = charge.reloadTicks

        // Anything beyond a full cooldown means the world clock moved rather than the gun being genuinely
        // hot -- a restored backup, most likely -- so let it fire instead of locking the gun out for a week.
        val remaining = magazine.readyAt - level.gameTime
        if (remaining in 1..cooldown) {
            return Component.translatable("info.vs_eureka.cannon_cooling", (remaining / 20.0 + 0.5).toInt().coerceAtLeast(1))
        }

        // A gun set heavier than it is stocked refuses rather than quietly firing light. Dropping to the
        // charge it CAN afford would send the ball down an arc the breech does not claim, which in a fight
        // reads as the gun being broken rather than as the player being out of powder.
        if (magazine.powderCount <= 0) return Component.translatable("info.vs_eureka.cannon_no_powder")
        if (magazine.powderCount < charge.powder) {
            return Component.translatable(
                "info.vs_eureka.cannon_not_enough_powder", charge.powder, magazine.powderCount
            )
        }
        val load = CannonShot.loadOf(magazine.shot) ?: return Component.translatable("info.vs_eureka.cannon_no_shot")

        // Bore geometry, in whatever space the block lives in. The shot leaves along the barrel, so the
        // elevation the player set is the elevation it flies at -- the model and the trajectory are driven
        // by the same number rather than merely looking like they agree.
        val pitch = Math.toRadians(EurekaProperties.elevationDegrees(state.getValue(ELEVATION)))
        val alongBore = Math.cos(pitch)
        val upBore = Math.sin(pitch)

        val forward = TRUNNION_FORWARD + BORE_LENGTH * alongBore
        val height = TRUNNION_HEIGHT + BORE_LENGTH * upBore

        val shipyardMuzzle = Vector3d(
            rear.x + 0.5 + facing.stepX * forward,
            rear.y + 0.5 + height,
            rear.z + 0.5 + facing.stepZ * forward
        )
        val shipyardAhead = Vector3d(shipyardMuzzle).add(
            facing.stepX * alongBore, upBore, facing.stepZ * alongBore
        )

        // ...and the same two points in world space. On solid ground the transform is the identity, so this
        // costs nothing and needs no special case.
        val muzzle = ship?.shipToWorld?.transformPosition(Vector3d(shipyardMuzzle)) ?: shipyardMuzzle
        val ahead = ship?.shipToWorld?.transformPosition(Vector3d(shipyardAhead)) ?: shipyardAhead

        val from = Vec3(muzzle.x, muzzle.y, muzzle.z)
        val direction = Vec3(ahead.x - muzzle.x, ahead.y - muzzle.y, ahead.z - muzzle.z)
        if (direction.lengthSqr() < 1.0e-6) return Component.translatable("info.vs_eureka.cannon_broken")

        // The shot flies as the PLAIN ball of its metal. A charge is inside the shell, so an explosive round
        // in the air is still just an iron ball -- and the gunpowder pip on the item sprite is a label for
        // the player's inventory, not something you would see going past you.
        val shown = ItemStack(EurekaItems.cannonball(load.ball, CannonCharge.PLAIN))
        magazine.consumePowder(charge.powder)
        magazine.shot.shrink(1)
        magazine.readyAt = level.gameTime + cooldown
        magazine.setChanged()

        // Hand the shot its own gun's blocks so it cannot detonate against the barrel it just left.
        val gun = CannonPart.entries.map { rear.relative(facing, it.ordinal) }.toTypedArray()
        CannonShot.spawn(level, from, direction, load, shown, charge, player, gun)

        level.playSound(null, from.x, from.y, from.z, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0f, 1.2f)
        level.playSound(null, from.x, from.y, from.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 4.0f, 1.4f)
        val puff = direction.normalize()
        level.sendParticles(
            ParticleTypes.LARGE_SMOKE,
            from.x + puff.x, from.y + puff.y, from.z + puff.z,
            18, 0.25, 0.25, 0.25, 0.05
        )
        level.sendParticles(ParticleTypes.FLAME, from.x + puff.x, from.y + puff.y, from.z + puff.z, 6, 0.15, 0.15, 0.15, 0.03)

        player?.let { level.gameEvent(it, net.minecraft.world.level.gameevent.GameEvent.EXPLODE, from) }
        return null
    }

    /**
     * The whole gesture: fire the gun, explain if it will not, and spend the flint only on a shot that
     * actually happened.
     *
     * Shared because the same gesture arrives by two routes -- standing, it reaches the block's `useItemOn`
     * the ordinary way; crouching, vanilla skips the block entirely and a Fabric hook has to catch it. Both
     * must behave identically, so both call this.
     */
    fun strike(
        level: ServerLevel,
        clicked: BlockPos,
        player: ServerPlayer?,
        stack: ItemStack,
        hand: InteractionHand
    ) {
        val refusal = fire(level, clicked, player)
        if (refusal != null) {
            // Asking a gun whether it is loaded should be free; charging durability for a refusal would
            // punish a player for checking.
            player?.displayClientMessage(refusal, true)
        } else if (player != null && !player.hasInfiniteMaterials()) {
            stack.hurtAndBreak(1, player, hand)
        }
    }

    /**
     * What lights a gun: flint and steel, and nothing else.
     *
     * The spark, not a flame -- which is also why the gesture must claim the click rather than let the item
     * run. A cannon is iron and oak and does not burn, so a fire block appearing on the barrel would be wrong
     * twice over: wrong about the material, and sitting in the way of the next shot.
     */
    fun isIgniter(item: net.minecraft.world.item.Item): Boolean = item == Items.FLINT_AND_STEEL
}
