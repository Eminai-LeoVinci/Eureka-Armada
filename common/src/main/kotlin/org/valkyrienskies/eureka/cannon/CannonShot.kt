package org.valkyrienskies.eureka.cannon

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.ItemSupplier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaEntities
import org.valkyrienskies.eureka.item.CannonCharge
import org.valkyrienskies.eureka.item.Cannonball
import org.valkyrienskies.eureka.item.Load
import org.valkyrienskies.eureka.item.CannonballItem

/**
 * A round in flight.
 *
 * ## It finds ship blocks for free
 * `level.clip` is not vanilla's here: VS2 wraps `BlockGetter.clip` so every raycast walks the ships too. The
 * hit it returns is in **two different coordinate spaces at once**, and both are the ones we want:
 *
 *  - [net.minecraft.world.phys.BlockHitResult.getBlockPos] is a **shipyard** position, so it can be read and
 *    written directly and its neighbours are the real neighbours on that hull.
 *  - `location` has been transformed back to **world** space, so it is where the flash and the noise belong.
 *
 * Mixing those two up is the classic way to write a cannon that explodes in the ocean a thousand blocks from
 * the ship it hit.
 *
 * ## Why it steps its own raycast rather than extending Projectile
 * The same reason [org.valkyrienskies.eureka.bottle.ThrownShipBottle] does: a plain [Entity] that clips its
 * own path each tick is simpler to reason about than the projectile hierarchy's interaction with ship
 * transforms, and the clip mixin means it loses nothing by it.
 */
class CannonShot(type: EntityType<out CannonShot>, level: Level) : Entity(type, level), ItemSupplier {

    /**
     * What this round is and what is packed behind it. Only meaningful on the server; the client just needs
     * the sprite, and the sprite it gets is the **plain** ball of that metal -- a charge is a thing inside
     * the shell, so a round in the air looks like the metal it is made of whatever it is carrying.
     */
    var load: Load = Load(Cannonball.IRON, CannonCharge.PLAIN)

    /**
     * Who fired it, so a shot does not immediately hit the gunner leaning over the barrel.
     *
     * Not persisted: a round is airborne for at most a couple of seconds, so a shot that survives a reload has
     * already outlived anything this would protect.
     */
    var firedBy: Entity? = null

    /**
     * The blocks of the gun that fired this, which the shot passes straight through.
     *
     * A muzzle is not a point outside the gun -- it is a hole *in* it -- so at some elevations the shot
     * begins its first step inside its own barrel's collision box and detonates against the cannon that
     * fired it. Depressed 22.5 degrees was the case that showed it: the bore sits four thousandths of a
     * block above the front half's hitbox, close enough that the first step clips it. At full depression the
     * muzzle drops below that box and the same shot leaves cleanly, which is exactly the kind of margin that
     * should not be deciding whether a gun destroys itself.
     *
     * Only *this* gun is exempt, not cannons in general. Silencing an enemy's guns by shooting them is worth
     * having, so the exemption has to be about the shot's own origin rather than about the block type.
     */
    private var gun: Array<BlockPos> = emptyArray()

    private var age = 0

    /** A cannonball is not a thing you can attack. */
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean = false

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(SHOWN, ItemStack.EMPTY)
    }

    override fun getItem(): ItemStack = entityData.get(SHOWN)

    /**
     * [muzzleSpeed] is handed in rather than read here because it belongs to the GUN, and a gun belongs to a
     * ship category -- an airship throws flatter than a hull. By the time the ball is in the air it has left
     * the ship and has no category to ask, so the number is spent at the muzzle and lives on in the velocity.
     *
     * That also means the client needs nothing extra: it already receives this velocity in the spawn packet
     * and integrates the flight itself (see [tick]), so per-category muzzle velocity costs no synced data.
     * Gravity and drag stay global for exactly the reason this one cannot be -- they are read every tick, on
     * both sides, long after the gun is out of the picture, and they are properties of the ball anyway.
     */
    fun launch(from: Vec3, direction: Vec3, load: Load, shownAs: ItemStack, muzzleSpeed: Double) {
        this.load = load
        entityData.set(SHOWN, shownAs.copyWithCount(1))
        setPos(from.x, from.y, from.z)
        deltaMovement = direction.normalize().scale(muzzleSpeed)
    }

    override fun tick() {
        super.tick()

        val from = position()
        val to = from.add(deltaMovement)

        // The client flies it too, rather than waiting to be told where it is.
        //
        // A thrown bottle cannot do this -- its path homes on ship transforms the client has no way to
        // reproduce -- so it interpolates between server updates instead. A cannonball is pure ballistics: the
        // client already has the launch velocity from the spawn packet and can integrate the identical
        // arithmetic. Interpolating instead cost three ticks of lag, which at 2.5 blocks a tick is seven
        // blocks behind; a shot at a nearby wall was over before the render caught up, which is exactly the
        // "visible at the muzzle then gone" it produced. Any drift is irrelevant because the server discards
        // the shot on impact and the client simply stops seeing it.
        if (level().isClientSide) {
            setPos(to.x, to.y, to.z)
            deltaMovement = deltaMovement.scale(EurekaConfig.SERVER.cannonShotDrag)
            .subtract(0.0, EurekaConfig.SERVER.cannonShotGravity, 0.0)
            smoke()
            return
        }

        if (age++ > MAX_TICKS) {
            discard()
            return
        }

        // Entities first: a round that punches through a crew to hit the hull behind them is not a
        // cannonball, it is a rumour.
        val struck = level().getEntities(this, AABB(from, to).inflate(0.5))
            .firstOrNull { it.isPickable && it !== firedBy }
        if (struck != null) {
            struck.hurt(damageSources().explosion(this, firedBy), load.maxBlocks.toFloat())
            burst(struck.position(), null)
            return
        }

        val hit = level().clip(
            ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)
        )
        // The gun's own blocks are compared in the same space the hit reports -- shipyard for a hull, world
        // for the ground -- because CannonFire records them from the same block positions the clip walks.
        if (hit.type != HitResult.Type.MISS && !gun.contains(hit.blockPos)) {
            // location: world space, for the flash. blockPos: shipyard space, for the damage.
            burst(hit.location, hit.blockPos)
            return
        }

        setPos(to.x, to.y, to.z)
        deltaMovement = deltaMovement.scale(EurekaConfig.SERVER.cannonShotDrag)
            .subtract(0.0, EurekaConfig.SERVER.cannonShotGravity, 0.0)
        smoke()
    }

    /** A thin powder trail, so a shot can be followed back to the gun that fired it. */
    private fun smoke() {
        if (tickCount % 2 != 0) return
        level().addParticle(ParticleTypes.SMOKE, x, y, z, 0.0, 0.0, 0.0)
    }

    /**
     * Land the shot: noise and flash in world space, blocks taken in shipyard space.
     *
     * Deliberately not a vanilla explosion. A real one would pick its own blocks by blast resistance and
     * ignore the damage ladder entirely, and it would light fires and hurt everything nearby -- a cannon is
     * meant to punch a hole where it was aimed, not to level the deck around it.
     */
    private fun burst(where: Vec3, blockHit: BlockPos?) {
        val level = level() as ServerLevel

        level.playSound(null, where.x, where.y, where.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 3.0f, 0.9f)

        // Built out of several bursts rather than one, because a single EXPLOSION particle is a fixed size
        // and there is no scale on it. EXPLOSION_EMITTER is the big TNT-style bloom; the ring of plain
        // EXPLOSION puffs around it is what makes the whole thing read as wide. Heavier shot throws more of
        // them, so a netherite hit looks like the hole it is about to make.
        val bursts = 2 + load.ball.ordinal
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, where.x, where.y, where.z, 1, 0.0, 0.0, 0.0, 0.0)
        for (i in 0 until bursts) {
            level.sendParticles(
                ParticleTypes.EXPLOSION,
                where.x + (random.nextDouble() - 0.5) * BURST_SPREAD,
                where.y + (random.nextDouble() - 0.5) * BURST_SPREAD,
                where.z + (random.nextDouble() - 0.5) * BURST_SPREAD,
                1, 0.0, 0.0, 0.0, 0.0
            )
        }
        level.sendParticles(ParticleTypes.LARGE_SMOKE, where.x, where.y, where.z, 24, 1.0, 1.0, 1.0, 0.03)

        if (blockHit != null) {
            // Destruction first, then fire. The order is the rule: an incendiary round lights what is left
            // standing, so burning can never be an extra helping of damage. See CannonDamage.kindle.
            CannonDamage.punch(level, blockHit, load.roll(level.random))
            CannonDamage.kindle(level, blockHit, load.incendiaryBlocks)
        }
        discard()
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        age = input.getIntOr("Age", 0)
        load = Load(
            Cannonball.entries.getOrNull(input.getIntOr("Ball", Cannonball.IRON.ordinal)) ?: Cannonball.IRON,
            CannonCharge.entries.getOrNull(input.getIntOr("Charge", 0)) ?: CannonCharge.PLAIN
        )
        entityData.set(SHOWN, input.read("Shown", ItemStack.CODEC).orElse(ItemStack.EMPTY))
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putInt("Age", age)
        output.putInt("Ball", load.ball.ordinal)
        output.putInt("Charge", load.charge.ordinal)
        if (!item.isEmpty) output.store("Shown", ItemStack.CODEC, item)
    }

    companion object {
        private val SHOWN: EntityDataAccessor<ItemStack> =
            SynchedEntityData.defineId(CannonShot::class.java, EntityDataSerializers.ITEM_STACK)

        // Speed, gravity and drag live on EurekaConfig.SERVER so an arc can be dialled in against a real ship
        // with a /reload rather than a rebuild. Only the lifetime cap stays here -- it is a safety net rather
        // than a tuning knob, and a config value that could strand shots in the sky forever is not one worth
        // exposing.
        private const val MAX_TICKS = 200

        /** How far the extra explosion puffs scatter from the point of impact, in blocks. */
        private const val BURST_SPREAD = 2.0

        fun spawn(
            level: ServerLevel,
            from: Vec3,
            direction: Vec3,
            load: Load,
            shownAs: ItemStack,
            muzzleSpeed: Double,
            firedBy: Entity? = null,
            gun: Array<BlockPos> = emptyArray()
        ): CannonShot {
            val shot = CannonShot(EurekaEntities.CANNON_SHOT.get(), level)
            shot.firedBy = firedBy
            shot.gun = gun
            shot.launch(from, direction, load, shownAs, muzzleSpeed)
            level.addFreshEntity(shot)
            return shot
        }

        /** What a stack in the shot slot actually is, or null if it is not shot at all. */
        fun loadOf(stack: ItemStack): Load? = (stack.item as? CannonballItem)?.load
    }
}
