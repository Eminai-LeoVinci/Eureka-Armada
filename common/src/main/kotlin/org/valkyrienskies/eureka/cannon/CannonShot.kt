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
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.EurekaEntities
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
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

    /**
     * Where the bore's mouth was, in world space, and every ship of the vessel the gun stood on -- the
     * firing hull plus its welded armada. Together they decide what a round refuses to strike: any block
     * belonging to its own vessel, and anyone ABOARD its own vessel, at any range.
     *
     * The gun-block exemption above was not enough once the elevation went to five-degree steps, and a
     * distance grace was not enough either -- an instrumented build showed why. A gun laid fifteen degrees
     * down on a ranked broadside sends its bore line through the seated gunners of the rank AHEAD, about
     * four blocks out; at twenty-five it rakes two tiers. No radius fits every deck plan, so the rule is
     * identity instead: a ship's own guns cannot hole her and cannot kill her crew, wherever aboard her
     * they stand. An enemy alongside stays fair game at any range -- her hull and her crew are another
     * ship's -- which is what a radius could never get right in both directions at once.
     *
     * [ARMING_DISTANCE] remains as the muzzle-adjacent grace for what identity cannot cover: the ground
     * a shore battery stands on, and anything hugging the muzzle itself.
     *
     * None of this persists, like [firedBy]: a shot that outlives a chunk reload comes back fully armed,
     * which errs on the side of the ball behaving like a ball.
     */
    private var muzzle: Vec3? = null
    private var ownVessel: Set<Long> = emptySet()

    private var age = 0

    /**
     * Strikes this round has left: four for armor-piercing, one for everything else -- see [Load.impacts].
     * A round with strikes remaining flies on through whatever it just hit instead of being spent on it.
     */
    private var impactsLeft = 1

    /**
     * What the opening block strike rolled, which every follow-through is a share of. The chain hangs off
     * the FIRST hit alone -- 75%, 50%, 25% of it, never of each other -- so a lucky opening carries the
     * whole run and a poor one dooms it. Zero until that first hit lands.
     */
    private var baseRoll = 0

    /** How many block strikes have landed, indexing [Load.followThrough]'s shares. */
    private var blockImpacts = 0

    /**
     * Entities this round has already gone through, so a target as wide as the step never soaks two of an
     * armor-piercing round's strikes in consecutive ticks. Not persisted, same as [firedBy] and for the same
     * reason: nothing here outlives a flight measured in seconds.
     */
    private val pierced = HashSet<Int>()

    /**
     * Break effects this round has left to spend, across all of its impacts.
     *
     * A per-impact ration alone is not enough for an armor-piercing round: four impacts at four effects
     * each is sixteen, arriving in four bursts a tick or two apart, which is the stutter this exists to
     * stop. So the round carries one budget for its whole flight and the last impacts of a heavy one land
     * quietly -- by which point the ball is deep inside a hull and its holes are behind three walls that
     * nobody is looking through anyway.
     */
    private var effectBudget = EFFECT_BUDGET

    /** A cannonball is not a thing you can attack. */
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean = false

    /**
     * How far away a shot in flight still draws. Vanilla sizes this off the bounding box -- about 77
     * blocks for a shot -- which made long shots vanish mid-arc while the guns that threw them were still
     * plainly visible. Config-driven (client side, cosmetic); the tracking range at registration is what
     * actually caps it, see [EurekaEntities.CANNON_SHOT].
     */
    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        val range = EurekaConfig.CLIENT.cannonShotRenderDistance.toDouble().coerceAtLeast(0.0)
        return distance < range * range
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(SHOWN, ItemStack.EMPTY)
        builder.define(POWDER, PowderCharge.SINGLE.ordinal)
        builder.define(GRAVITY, PowderCharge.SINGLE.gravity.toFloat())
        builder.define(DRAG, PowderCharge.SINGLE.drag.toFloat())
    }

    override fun getItem(): ItemStack = entityData.get(SHOWN)

    /**
     * How much powder threw this, which is what decides its whole arc.
     *
     * **Synced, and it has to be.** The client integrates the flight itself rather than being told where the
     * ball is (see [tick]). Muzzle velocity does not need syncing -- it is spent once and rides along inside
     * the spawn packet's velocity -- and gravity and drag now ride their own [GRAVITY]/[DRAG] values, frozen
     * at launch, so a live /armada retune or a guest's stale local file cannot bend an arc mid-flight. The
     * ordinal still syncs for everything else that reads the measure off the shot.
     */
    val charge: PowderCharge get() = PowderCharge.of(entityData.get(POWDER))

    /**
     * [charge] is handed in rather than read off the gun, because by the time the ball is in the air the gun
     * is out of the picture -- and a shot's arc has to keep answering to the measure that actually threw it,
     * not to whatever the breech happens to be set to by the time it lands.
     */
    fun launch(
        from: Vec3,
        direction: Vec3,
        load: Load,
        shownAs: ItemStack,
        charge: PowderCharge,
        speedOverride: Double? = null
    ) {
        this.load = load
        this.impactsLeft = load.impacts
        entityData.set(SHOWN, shownAs.copyWithCount(1))
        entityData.set(POWDER, charge.ordinal)
        // Frozen at launch: an /armada gravity change mid-flight must not bend an arc already flying, and a
        // guest client's local config must not disagree with the number the server integrates. See GRAVITY.
        entityData.set(GRAVITY, charge.gravity.toFloat())
        entityData.set(DRAG, charge.drag.toFloat())
        setPos(from.x, from.y, from.z)
        // The override is the AI's solved muzzle speed -- spent once, right here, so it needs no syncing
        // and no persistence: the client reads the resulting velocity off the spawn packet like any other,
        // and gravity/drag still come from the synced charge.
        deltaMovement = direction.normalize().scale(speedOverride ?: charge.speed)
    }

    /**
     * Vanilla's fluid push is the ONE outside hand that could touch this flight -- a shot skimming a wave
     * would pick up a flow nudge the client (which re-integrates the arc itself, see [tick]) never applies,
     * and the solver (which promises the arc is pure arithmetic) never planned for. Refused on all three
     * counts.
     */
    override fun isPushedByFluid(): Boolean = false

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
            deltaMovement = deltaMovement.scale(entityData.get(DRAG).toDouble())
                .subtract(0.0, entityData.get(GRAVITY).toDouble(), 0.0)
            smoke()
            return
        }

        if (age++ > flightCapTicks()) {
            discard()
            return
        }

        // Entities first: a round that punches through a crew to hit the hull behind them is not a
        // cannonball, it is a rumour. Except an armor-piercing one, whose whole purchase is that it does not
        // stop -- flesh costs it a strike like a wall does, and the ball flies on to whatever stands behind.
        // Anyone aboard the firing vessel is spared, at any range: a depressed gun on a ranked broadside
        // sends its bore line straight through the seated gunners of the rank ahead. See [muzzle].
        val struck = level().getEntities(this, AABB(from, to).inflate(0.5)).firstOrNull {
            it.isPickable && it !== firedBy && it.id !in pierced &&
                armedAt(it.position()) && !aboardOwnVessel(it.position())
        }
        if (struck != null) {
            struck.hurt(damageSources().explosion(this, firedBy), load.maxBlocks.toFloat())
            pierced.add(struck.id)
            strike(struck.position(), null)
            if (isRemoved) return
            // Spent a strike, kept flying: the rest of this tick's flight continues below, so a wall a
            // hand's breadth behind the target is still hit this tick rather than slipped past.
        }

        val hit = level().clip(
            ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)
        )
        // The gun's own blocks are compared in the same space the hit reports -- shipyard for a hull, world
        // for the ground -- because CannonFire records them from the same block positions the clip walks.
        // A hit on the firing ship inside the arming distance is not a hit at all -- the ball flies on
        // through its own gunport sill exactly as it flies through its own barrel.
        if (hit.type != HitResult.Type.MISS && !gun.contains(hit.blockPos) && !friendlyBlockHit(hit)) {
            // location: world space, for the flash. blockPos: shipyard space, for the damage.
            strike(hit.location, hit.blockPos)
            if (!isRemoved) {
                // Punched through. Pick up a step past the point of impact and let the next tick carry on:
                // the blocks this strike took are already air -- punch is synchronous -- so the ball can
                // never collide with something its own previous impact should have removed. Whatever the
                // hole's edge left standing in the path is a legitimate next wall.
                val step = deltaMovement.normalize().scale(0.1)
                setPos(hit.location.x + step.x, hit.location.y + step.y, hit.location.z + step.z)
            }
            return
        }

        setPos(to.x, to.y, to.z)
        deltaMovement = deltaMovement.scale(entityData.get(DRAG).toDouble())
            .subtract(0.0, entityData.get(GRAVITY).toDouble(), 0.0)
        smoke()
    }

    /** Whether the round has travelled far enough from the muzzle to strike things at [where]. */
    private fun armedAt(where: Vec3): Boolean {
        val from = muzzle ?: return true
        return from.distanceToSqr(where) >= ARMING_DISTANCE_SQ
    }

    /**
     * Whether [hit] is a block the round refuses on principle: its own vessel's hull at any range, or --
     * for a gun with no vessel at all -- the plain ground inside the arming distance, which is a shore
     * battery's emplacement. Ship identity, not proximity: an ENEMY hull two blocks from the muzzle is the
     * most deliberate shot in the game and must land.
     */
    private fun friendlyBlockHit(hit: BlockHitResult): Boolean {
        val hitShipId = level().getShipManagingPos(hit.blockPos)?.id
        if (hitShipId != null) return hitShipId in ownVessel
        return ownVessel.isEmpty() && !armedAt(hit.location)
    }

    /**
     * Whether whatever stands at [pos] is aboard the firing vessel: its world position, carried back into
     * each member ship's own space, lands inside that hull's box (with a block of margin for a crewman
     * leaning over a rail). This is what stops a ranked broadside raking its own forward gunners -- see
     * [muzzle] for the trace that found them dying four blocks downrange of the tier behind.
     */
    private fun aboardOwnVessel(pos: Vec3): Boolean {
        if (ownVessel.isEmpty()) return false
        val level = level() as? ServerLevel ?: return false
        val world = level.shipObjectWorld
        val local = org.joml.Vector3d()
        for (shipId in ownVessel) {
            val ship = world.loadedShips.getById(shipId) ?: continue
            val box = ship.shipAABB ?: continue
            ship.worldToShip.transformPosition(local.set(pos.x, pos.y, pos.z))
            if (local.x >= box.minX() - 1.0 && local.x <= box.maxX() + 1.0 &&
                local.y >= box.minY() - 1.0 && local.y <= box.maxY() + 1.0 &&
                local.z >= box.minZ() - 1.0 && local.z <= box.maxZ() + 1.0
            ) return true
        }
        return false
    }

    /** A thin powder trail, so a shot can be followed back to the gun that fired it. */
    private fun smoke() {
        if (tickCount % 2 != 0) return
        level().addParticle(ParticleTypes.SMOKE, x, y, z, 0.0, 0.0, 0.0)
    }

    /**
     * Land one strike: noise and flash in world space, blocks taken in shipyard space.
     *
     * Deliberately not a vanilla explosion. A real one would pick its own blocks by blast resistance and
     * ignore the damage ladder entirely, and it would light fires and hurt everything nearby -- a cannon is
     * meant to punch a hole where it was aimed, not to level the deck around it.
     *
     * Most rounds get exactly one of these and are spent by it. An armor-piercing round gets four: the
     * opening block strike rolls the metal's ladder and every later one takes its dwindling share of that
     * roll -- see [Load.followThrough] -- until the strikes run out and the ball is spent like any other.
     */
    private fun strike(where: Vec3, blockHit: BlockPos?) {
        val level = level() as ServerLevel

        level.playSound(null, where.x, where.y, where.z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 3.0f, 0.9f)

        // Built out of several bursts rather than one, because a single EXPLOSION particle is a fixed size
        // and there is no scale on it. EXPLOSION_EMITTER is the big TNT-style bloom; the ring of plain
        // EXPLOSION puffs around it is what makes the whole thing read as wide. Heavier shot throws more of
        // them, so a netherite hit looks like the hole it is about to make.
        //
        // The full bloom belongs to the FIRST strike alone. It is the most expensive thing a round does --
        // EXPLOSION_EMITTER spawns a cluster client-side, and an armor-piercing ball was firing four of them
        // a tick or two apart -- and it is also the least true after the first: the shot has already spent
        // itself getting through the first wall, and the strikes behind it are a ball grinding on through a
        // hull, not four fresh detonations. So the follow-throughs keep the shape and lose the scale.
        val firstStrike = impactsLeft == load.impacts
        // A follow-through is a ball grinding on through a hull, not a fresh detonation: one puff and a
        // wisp of smoke marks where it passed, and the bloom stays the first strike's alone.
        val bursts = if (firstStrike) 2 + load.ball.ordinal else 1
        if (firstStrike) {
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, where.x, where.y, where.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
        for (i in 0 until bursts) {
            level.sendParticles(
                ParticleTypes.EXPLOSION,
                where.x + (random.nextDouble() - 0.5) * BURST_SPREAD,
                where.y + (random.nextDouble() - 0.5) * BURST_SPREAD,
                where.z + (random.nextDouble() - 0.5) * BURST_SPREAD,
                1, 0.0, 0.0, 0.0, 0.0
            )
        }
        val smoke = if (firstStrike) 24 else 5
        level.sendParticles(ParticleTypes.LARGE_SMOKE, where.x, where.y, where.z, smoke, 1.0, 1.0, 1.0, 0.03)

        if (blockHit != null) {
            val bite = if (blockImpacts == 0) {
                load.roll(level.random).also { baseRoll = it }
            } else {
                load.followThrough(baseRoll, blockImpacts)
            }
            blockImpacts++
            // Destruction first, then fire. The order is the rule: an incendiary round lights what is left
            // standing, so burning can never be an extra helping of damage. See CannonDamage.kindle.
            //
            // The hole is always the full size the round rolled; only how loudly it is made is rationed --
            // this impact may show a few break effects, and never more than the round has left. See
            // CannonDamage.punch and [effectBudget].
            val allowed = minOf(EFFECTS_PER_IMPACT, effectBudget)
            effectBudget -= CannonDamage.punch(level, blockHit, bite, allowed)
            CannonDamage.kindle(level, blockHit, load.incendiaryBlocks)
        }

        impactsLeft--
        if (impactsLeft <= 0) discard()
    }

    // "Charge" is the SHELL type (plain/explosive/incendiary) and predates this; the powder measure gets its
    // own key rather than overloading it.
    override fun readAdditionalSaveData(input: ValueInput) {
        age = input.getIntOr("Age", 0)
        load = Load(
            Cannonball.entries.getOrNull(input.getIntOr("Ball", Cannonball.IRON.ordinal)) ?: Cannonball.IRON,
            CannonCharge.entries.getOrNull(input.getIntOr("Charge", 0)) ?: CannonCharge.PLAIN
        )
        entityData.set(POWDER, input.getIntOr("PowderCharge", PowderCharge.SINGLE.ordinal))
        // Not saved: a reloaded shot re-arms against the charge's CURRENT config numbers, matching how the
        // rest of this method re-reads the world it wakes into.
        entityData.set(GRAVITY, charge.gravity.toFloat())
        entityData.set(DRAG, charge.drag.toFloat())
        entityData.set(SHOWN, input.read("Shown", ItemStack.CODEC).orElse(ItemStack.EMPTY))
        // After load, which sets what "all of them" means for this round.
        impactsLeft = input.getIntOr("ImpactsLeft", load.impacts)
        baseRoll = input.getIntOr("BaseRoll", 0)
        blockImpacts = input.getIntOr("BlockImpacts", 0)
        effectBudget = input.getIntOr("EffectBudget", EFFECT_BUDGET)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putInt("Age", age)
        output.putInt("Ball", load.ball.ordinal)
        output.putInt("Charge", load.charge.ordinal)
        output.putInt("PowderCharge", entityData.get(POWDER))
        output.putInt("ImpactsLeft", impactsLeft)
        output.putInt("BaseRoll", baseRoll)
        output.putInt("BlockImpacts", blockImpacts)
        output.putInt("EffectBudget", effectBudget)
        if (!item.isEmpty) output.store("Shown", ItemStack.CODEC, item)
    }

    companion object {
        private val SHOWN: EntityDataAccessor<ItemStack> =
            SynchedEntityData.defineId(CannonShot::class.java, EntityDataSerializers.ITEM_STACK)

        /** The powder measure that threw this, as a [PowderCharge] ordinal. See [charge] for why it syncs. */
        private val POWDER: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(CannonShot::class.java, EntityDataSerializers.INT)

        /**
         * The gravity and drag this shot flies under, in blocks/tick^2 and fraction-kept-per-tick, FROZEN at
         * launch. Synced as their own values rather than derived from [POWDER], because the config numbers
         * behind a powder charge can change mid-flight now (/armada cannons gravity ...) and can differ
         * between a guest client's local file and the server's -- either would bend the client's
         * self-integrated arc off the path the server actually flies.
         */
        private val GRAVITY: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(CannonShot::class.java, EntityDataSerializers.FLOAT)
        private val DRAG: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(CannonShot::class.java, EntityDataSerializers.FLOAT)

        // Speed, gravity and drag live on EurekaConfig.SERVER, cut three ways by powder charge, so an arc can
        // be dialled in against a real ship with a /reload rather than a rebuild. The lifetime cap used to be
        // a hard 200 ticks on the old "not worth exposing" reasoning -- reversed once /armada cannons could
        // retune gravity live: a slow low-gravity lob needs more sky than ten seconds, and stranded shots are
        // now the operator's own rope. CannonSolver clamps its sweep to min(200, this) so the AI never plans
        // past what a ball lives -- or burns a frame sweeping an hour-long arc.
        /** The flight-time cap in ticks, from config; floored at one tick. Shared with [CannonSolver]. */
        internal fun flightCapTicks(): Long =
            (EurekaConfig.SERVER.cannonShotMaxFlightSeconds * 20.0).toLong().coerceAtLeast(1L)

        /** How far the extra explosion puffs scatter from the point of impact, in blocks. */
        private const val BURST_SPREAD = 2.0

        /**
         * How far from the muzzle a round arms, in blocks. Sized to the worst self-strike the elevation
         * range can produce: at fifteen degrees down the bore line reaches its own deck about two and a
         * half blocks out, and everything steeper strikes closer. See [muzzle].
         */
        private const val ARMING_DISTANCE = 3.0
        private const val ARMING_DISTANCE_SQ = ARMING_DISTANCE * ARMING_DISTANCE

        /**
         * Break effects one impact may show, and one whole round may show across all of its impacts.
         *
         * Four is enough to read as "that block just came apart" -- past it the puffs pile up inside a
         * single hole and add nothing but packets. Eight for the round means an armor-piercing ball's
         * last two impacts land silently -- by which point it is deep inside a hull, its holes behind
         * walls nobody is looking through. Was twelve; the trailing bursts still read as clutter.
         */
        private const val EFFECTS_PER_IMPACT = 4
        private const val EFFECT_BUDGET = 8

        fun spawn(
            level: ServerLevel,
            from: Vec3,
            direction: Vec3,
            load: Load,
            shownAs: ItemStack,
            charge: PowderCharge,
            firedBy: Entity? = null,
            gun: Array<BlockPos> = emptyArray(),
            ownVessel: Set<Long> = emptySet(),
            speedOverride: Double? = null
        ): CannonShot {
            val shot = CannonShot(EurekaEntities.CANNON_SHOT.get(), level)
            shot.firedBy = firedBy
            shot.gun = gun
            shot.muzzle = from
            shot.ownVessel = ownVessel
            shot.launch(from, direction, load, shownAs, charge, speedOverride)
            level.addFreshEntity(shot)
            return shot
        }

        /** What a stack in the shot slot actually is, or null if it is not shot at all. */
        fun loadOf(stack: ItemStack): Load? = (stack.item as? CannonballItem)?.load
    }
}
