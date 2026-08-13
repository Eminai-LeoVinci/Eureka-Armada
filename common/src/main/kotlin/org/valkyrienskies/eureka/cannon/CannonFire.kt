package org.valkyrienskies.eureka.cannon

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.eureka.EurekaProperties.CANNON_PART
import org.valkyrienskies.eureka.block.CannonBlock
import org.valkyrienskies.eureka.block.CannonPart
import org.valkyrienskies.eureka.blockentity.CannonBlockEntity
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

    /** One shot per gun per four seconds. Broadside weight comes from gun count, not from fire rate. */
    const val COOLDOWN_TICKS = 80L

    /**
     * Where the bore sits relative to the rear block's centre, in blocks.
     *
     * Straight off the model: the muzzle is 17 units ahead of the rear block's centre along the facing, and
     * the bore's axis is a shade over one block up.
     */
    private const val MUZZLE_FORWARD = 1.0625
    private const val MUZZLE_HEIGHT = 0.53

    /** Why a gun would not fire, or null if it did. */
    fun fire(level: ServerLevel, clicked: BlockPos, player: ServerPlayer?): Component? {
        val state = level.getBlockState(clicked)
        if (state.block !is CannonBlock) return null

        // Either half of the gun is a valid place to strike a spark; the magazine is on the breech.
        val facing = state.getValue(HORIZONTAL_FACING)
        val rear = clicked.relative(facing.opposite, state.getValue(CANNON_PART).ordinal)
        val magazine = level.getBlockEntity(rear) as? CannonBlockEntity
            ?: return Component.translatable("info.vs_eureka.cannon_broken")

        // Anything beyond a full cooldown means the world clock moved rather than the gun being genuinely
        // hot -- a restored backup, most likely -- so let it fire instead of locking the gun out for a week.
        val remaining = magazine.readyAt - level.gameTime
        if (remaining in 1..COOLDOWN_TICKS) {
            return Component.translatable("info.vs_eureka.cannon_cooling", (remaining / 20.0 + 0.5).toInt().coerceAtLeast(1))
        }

        if (magazine.powder.isEmpty) return Component.translatable("info.vs_eureka.cannon_no_powder")
        val ball = CannonShot.ballOf(magazine.shot) ?: return Component.translatable("info.vs_eureka.cannon_no_shot")

        // Bore geometry, in whatever space the block lives in.
        val shipyardMuzzle = Vector3d(
            rear.x + 0.5 + facing.stepX * MUZZLE_FORWARD,
            rear.y + 0.5 + MUZZLE_HEIGHT,
            rear.z + 0.5 + facing.stepZ * MUZZLE_FORWARD
        )
        val shipyardAhead = Vector3d(shipyardMuzzle).add(
            facing.stepX.toDouble(), 0.0, facing.stepZ.toDouble()
        )

        // ...and the same two points in world space. On solid ground the transform is the identity, so this
        // costs nothing and needs no special case.
        val ship = level.getLoadedShipManagingPos(rear)
        val muzzle = ship?.shipToWorld?.transformPosition(Vector3d(shipyardMuzzle)) ?: shipyardMuzzle
        val ahead = ship?.shipToWorld?.transformPosition(Vector3d(shipyardAhead)) ?: shipyardAhead

        val from = Vec3(muzzle.x, muzzle.y, muzzle.z)
        val direction = Vec3(ahead.x - muzzle.x, ahead.y - muzzle.y, ahead.z - muzzle.z)
        if (direction.lengthSqr() < 1.0e-6) return Component.translatable("info.vs_eureka.cannon_broken")

        val shown = magazine.shot.copy()
        magazine.powder.shrink(1)
        magazine.shot.shrink(1)
        magazine.readyAt = level.gameTime + COOLDOWN_TICKS
        magazine.setChanged()

        CannonShot.spawn(level, from, direction, ball, shown, player)

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
     * What lights a gun: flint and steel, and nothing else.
     *
     * The spark, not a flame -- which is also why the gesture must claim the click rather than let the item
     * run. A cannon is iron and oak and does not burn, so a fire block appearing on the barrel would be wrong
     * twice over: wrong about the material, and sitting in the way of the next shot.
     */
    fun isIgniter(item: net.minecraft.world.item.Item): Boolean = item == Items.FLINT_AND_STEEL
}
