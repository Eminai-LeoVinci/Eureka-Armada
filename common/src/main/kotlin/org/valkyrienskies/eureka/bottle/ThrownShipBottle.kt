package org.valkyrienskies.eureka.bottle

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.InterpolationHandler
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.projectile.ItemSupplier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.primitives.AABBdc
import org.valkyrienskies.eureka.EurekaItems
import org.valkyrienskies.eureka.path.PathMessages
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * A Ship Bottle in flight -- thrown to take a ship, or thrown to let one out.
 *
 * One entity covers both because they are the same object in the air, and which one it is is simply which stack
 * it is carrying. A bottle with a wheel marked on it goes and *fetches*; a bottle with a ship in it goes and
 * *spills*. Everything up to the moment it lands is identical.
 *
 * ## The arc
 * It leaves the hand like an ender pearl at half strength and falls under gravity -- about twenty blocks on a
 * good throw. It is not fragile and not flammable: it will ride a lava lake as happily as a pond, because a
 * bottle that burned up with a ship inside it would be an unrecoverable loss on a bad throw.
 *
 * On water or lava it comes to rest **at the surface** rather than on top of it -- the waterline runs through
 * the bottle, which is both what a floating bottle looks like and where the ship's keel wants to be.
 *
 * ## After it lands
 * A **bottled ship** lets its ship out where it stopped: afloat if that was water, keel on the ground if it was
 * not.
 *
 * A **marked bottle** floats up off wherever it landed and sets out for its wheel, closing at a speed that
 * winds up over distance so that a ship under sail cannot outrun it. Inside the ship's influence border it eases
 * off and is carried along by the hull, exactly as a player standing on the deck is -- which is what makes the
 * capture certain rather than a chase. It stations [RISE_ABOVE] blocks over the wheel, takes the ship, holds
 * for [HOVER_TICKS] so the moment reads, and then comes home.
 *
 * ## Coming home
 * It never drops what it is holding. Whatever the outcome -- a ship caught, a ship refused, a wheel that went
 * missing mid-flight -- the bottle flies back to whoever threw it and puts itself in their inventory, passing
 * through anything in the way. A ship is too much to lose to a bad landing.
 *
 * ## Where the ship actually goes
 * Nothing about the flight owns the ship. Capture is [ShipBottle.take] and release is [ShipBottle.releaseOnWater]
 * or [ShipBottle.releaseOnGround] -- the flight is presentation over transactions that already worked.
 */
class ThrownShipBottle(type: EntityType<out ThrownShipBottle>, level: Level) : Entity(type, level), ItemSupplier {

    private enum class Phase { ARC, HOMING, HOVER, RETURN }

    private var phase = Phase.ARC

    /** The wheel this bottle is going for, in the ship's own coordinates. Null on a bottle carrying a ship. */
    private var helm: BlockPos? = null
    private var thrower: UUID? = null

    /** What the bottle is holding. Starts as the thrown stack and becomes the bottled ship on a capture. */
    private var carried: ItemStack = ItemStack.EMPTY

    private var hover = 0
    private var chase = APPROACH_SPEED
    private var deadline = TIMEOUT_TICKS

    /**
     * What makes the flight look like a flight.
     *
     * This bottle's path is decided entirely on the server -- it is steered by ship transforms the client
     * cannot reproduce -- so unlike an arrow or an eye of ender there is no physics for the client to run
     * alongside. Without a handler the client simply snaps to each position packet and the renderer has a
     * single tick to catch up, which reads as roughly ten frames a second no matter how well the game is
     * running. Vanilla's answer to exactly this is [InterpolationHandler]: positions arrive as targets and get
     * eased into over [INTERPOLATION_STEPS] ticks. It is what boats use.
     */
    private val smoothing by lazy { InterpolationHandler(this, INTERPOLATION_STEPS) }

    override fun getInterpolation(): InterpolationHandler = smoothing

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(SHOWN, ItemStack.EMPTY)
    }

    /** Not a target, and not fuel. Shooting one down would strand the ship inside it nowhere at all. */
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean = false

    /** What the client draws. The swap from empty bottle to full one is the whole visual payoff of a capture. */
    override fun getItem(): ItemStack = entityData.get(SHOWN)

    private fun carry(stack: ItemStack) {
        carried = stack
        entityData.set(SHOWN, stack.copy())
    }

    /**
     * Throw this bottle. [helm] is the marked wheel for a capture, or null for a bottle carrying a ship out.
     */
    fun launch(from: ServerPlayer, stack: ItemStack, helm: BlockPos?) {
        this.thrower = from.uuid
        this.helm = helm
        carry(stack.copyWithCount(1))
        setPos(from.x, from.eyeY - 0.15, from.z)
        deltaMovement = from.lookAngle.scale(THROW_POWER)
    }

    override fun tick() {
        super.tick()

        val level = level() as? ServerLevel ?: run {
            smoothing.interpolate()
            return
        }

        trail(level)

        when (phase) {
            Phase.ARC -> arc(level)
            Phase.HOMING -> home(level)
            Phase.HOVER -> {
                deltaMovement = Vec3.ZERO
                if (--hover <= 0) beginReturn()
            }
            Phase.RETURN -> comeHome(level)
        }

        // A bottle that lost its ship -- the wheel broken mid-flight, the chunk gone -- must not orbit forever.
        // Running out of patience sends it home rather than ending it; only a bottle that cannot even manage
        // that gets put down where it stands.
        if (tickCount > deadline) {
            if (phase == Phase.RETURN) putDown(level) else beginReturn()
        }
    }

    // ---- Act one: the throw -------------------------------------------------------------------------------

    private fun arc(level: ServerLevel) {
        deltaMovement = deltaMovement.add(0.0, -GRAVITY, 0.0).scale(DRAG)

        val from = position()
        val to = from.add(deltaMovement)
        // Fluid.ANY is what makes the sea a surface to land on rather than something to fall through. The hit
        // lands on the top face of the topmost fluid block, which is the waterline exactly.
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this))

        if (hit.type == HitResult.Type.BLOCK) {
            setPos(hit.location.x, hit.location.y, hit.location.z)
            deltaMovement = Vec3.ZERO
            land(level, hit)
            return
        }

        setPos(to.x, to.y, to.z)
        // Thrown off the edge of the world. Nothing has been spent yet, so it can still be handed back.
        if (y < level.minY - 8) beginReturn()
    }

    private fun land(level: ServerLevel, hit: BlockHitResult) {
        val marked = helm
        if (marked != null) {
            // An empty bottle floats where it fell -- on the waterline, on the ground, on lava -- and sets off.
            phase = Phase.HOMING
            chase = APPROACH_SPEED
            return
        }

        val captain = captain(level)
        if (captain == null) {
            putDown(level)
            return
        }

        val inFluid = !level.getFluidState(hit.blockPos).isEmpty
        val let = if (inFluid) {
            ShipBottle.releaseOnWater(level, captain, carried, hit.blockPos)
        } else {
            ShipBottle.releaseOnGround(level, captain, carried, hit.blockPos.relative(hit.direction))
        }

        // A refusal has already said why in chat. The ship is still in the bottle and the bottle goes back --
        // a hull that will not fit must never cost the player the ship.
        if (let) discard() else beginReturn()
    }

    // ---- Act two: the chase -------------------------------------------------------------------------------

    private fun home(level: ServerLevel) {
        val marked = helm ?: run { beginReturn(); return }
        val ship = level.getLoadedShipManagingPos(marked) ?: run {
            captain(level)?.let {
                PathMessages.send(it, "The bottle lost its ship.", PathMessages.Kind.WARN)
            }
            beginReturn()
            return
        }

        // Asked again every tick, because the wheel is on a ship: it moves, and its own coordinates say nothing
        // about where the hull is drawn.
        val wheel = ship.shipToWorld.transformPosition(
            Vector3d(marked.x + 0.5, marked.y + 0.5, marked.z + 0.5)
        )
        val to = Vec3(wheel.x, wheel.y + RISE_ABOVE, wheel.z)
        val gap = to.subtract(position())
        val distance = gap.length()

        if (distance < ARRIVED_WITHIN) {
            takeTheShip(level, marked)
            return
        }

        // Inside the border the bottle eases off and rides with the hull; outside it winds up. The wind-up is
        // what makes the capture certain: a bottle held to one steady pace would trail a ship under sail
        // forever, and one that never slowed would arrive at the wheel as a blur.
        val aboard = withinInfluence(ship.worldAABB, position())
        chase = if (aboard) {
            (chase - BRAKE).coerceAtLeast(APPROACH_SPEED)
        } else {
            (chase + ACCELERATION).coerceAtMost(MAX_CHASE)
        }

        var step = gap.normalize().scale(minOf(chase, distance))
        if (aboard) {
            // Carried, exactly as a player standing on the deck is. VS2's own carry is a client-side affair
            // decided by the local player's influence border, so this is the server-side equivalent: take the
            // hull's motion as our own, and the ship cannot sail out from under us.
            val v = ship.velocity
            step = step.add(v.x() / 20.0, v.y() / 20.0, v.z() / 20.0)
        }

        deltaMovement = step
        setPos(x + step.x, y + step.y, z + step.z)
    }

    private fun takeTheShip(level: ServerLevel, marked: BlockPos) {
        helm = null
        deltaMovement = Vec3.ZERO

        val captain = captain(level)
        val taken = if (captain == null) null else ShipBottle.take(level, captain, marked)

        if (taken != null) {
            carry(taken)
            level.playSound(null, x, y, z, SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 1.0f, 1.0f)
        }
        // On a refusal the bottle keeps the empty it set out with, and still goes home with it.

        phase = Phase.HOVER
        hover = HOVER_TICKS
    }

    // ---- Act three: the way back --------------------------------------------------------------------------

    private fun beginReturn() {
        phase = Phase.RETURN
        chase = APPROACH_SPEED
        deadline = tickCount + RETURN_TICKS
    }

    private fun comeHome(level: ServerLevel) {
        val captain = captain(level) ?: run { putDown(level); return }

        val to = captain.position().add(0.0, captain.bbHeight * 0.5, 0.0)
        val gap = to.subtract(position())
        val distance = gap.length()

        if (distance < CAUGHT_WITHIN) {
            if (!captain.inventory.add(carried)) captain.drop(carried, false)
            level.playSound(
                null, captain.x, captain.y, captain.z,
                SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4f, 1.6f
            )
            discard()
            return
        }

        // Straight through whatever is in the way. The bottle is guaranteed to arrive, so terrain is not
        // allowed a say in it.
        chase = (chase + ACCELERATION).coerceAtMost(MAX_CHASE)
        val step = gap.normalize().scale(minOf(chase, distance))
        deltaMovement = step
        setPos(x + step.x, y + step.y, z + step.z)
    }

    /** Last resort: whoever threw this is gone. Leave it where it is rather than deleting a ship. */
    private fun putDown(level: ServerLevel) {
        if (isRemoved) return
        if (!carried.isEmpty) level.addFreshEntity(ItemEntity(level, x, y, z, carried))
        discard()
    }

    /**
     * A thin thread of light behind the bottle, so the eye has something to follow it by.
     *
     * Every other tick and one particle at a time: the bottle spends most of its flight small and far away
     * against sky or sea, and a denser trail would read as a firework rather than as a thing being carried.
     * End rod drifts almost not at all, which is what keeps the line looking like a path rather than a cloud.
     */
    private fun trail(level: ServerLevel) {
        if (isRemoved || tickCount % 2 != 0) return
        level.sendParticles(ParticleTypes.END_ROD, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
    }

    private fun captain(level: ServerLevel): ServerPlayer? =
        thrower?.let { level.getPlayerByUUID(it) } as? ServerPlayer

    private fun withinInfluence(box: AABBdc, at: Vec3): Boolean =
        at.x >= box.minX() - INFLUENCE_MARGIN && at.x <= box.maxX() + INFLUENCE_MARGIN &&
            at.y >= box.minY() - INFLUENCE_MARGIN && at.y <= box.maxY() + INFLUENCE_MARGIN &&
            at.z >= box.minZ() - INFLUENCE_MARGIN && at.z <= box.maxZ() + INFLUENCE_MARGIN

    // ---- Persistence --------------------------------------------------------------------------------------

    override fun readAdditionalSaveData(input: ValueInput) {
        helm = input.read(HELM_KEY, BlockPos.CODEC).orElse(null)
        carry(input.read(CARRIED_KEY, ItemStack.CODEC).orElse(ItemStack.EMPTY))
        hover = input.getIntOr(HOVER_KEY, 0)
        deadline = input.getIntOr(DEADLINE_KEY, TIMEOUT_TICKS)
        phase = Phase.entries.getOrElse(input.getIntOr(PHASE_KEY, 0)) { Phase.ARC }
        thrower = runCatching { UUID.fromString(input.getStringOr(THROWER_KEY, "")) }.getOrNull()
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        helm?.let { output.store(HELM_KEY, BlockPos.CODEC, it) }
        if (!carried.isEmpty) output.store(CARRIED_KEY, ItemStack.CODEC, carried)
        output.putInt(HOVER_KEY, hover)
        output.putInt(DEADLINE_KEY, deadline)
        output.putInt(PHASE_KEY, phase.ordinal)
        thrower?.let { output.putString(THROWER_KEY, it.toString()) }
    }

    companion object {
        private val SHOWN: EntityDataAccessor<ItemStack> =
            SynchedEntityData.defineId(ThrownShipBottle::class.java, EntityDataSerializers.ITEM_STACK)

        private const val HELM_KEY = "vs_eureka:helm"
        private const val CARRIED_KEY = "vs_eureka:carried"
        private const val HOVER_KEY = "vs_eureka:hover"
        private const val PHASE_KEY = "vs_eureka:phase"
        private const val DEADLINE_KEY = "vs_eureka:deadline"
        private const val THROWER_KEY = "vs_eureka:thrower"

        /** Half an ender pearl's, which lands it around twenty blocks out on a level throw. */
        const val THROW_POWER = 0.75
        private const val GRAVITY = 0.03
        private const val DRAG = 0.99

        /** Station height over the wheel. High enough to see the whole ship go, low enough to stay in frame. */
        const val RISE_ABOVE = 4.0

        /** The unhurried pace of the last few blocks, and the floor the chase eases back down to. */
        const val APPROACH_SPEED = 0.35
        const val MAX_CHASE = 2.5
        private const val ACCELERATION = 0.08
        private const val BRAKE = 0.25
        const val ARRIVED_WITHIN = 0.7
        const val CAUGHT_WITHIN = 1.0

        /** How far outside the hull the bottle counts as aboard. Matches VS2's default per-face extension. */
        private const val INFLUENCE_MARGIN = 2.0

        /**
         * How many ticks the client eases each position update over. Three is vanilla's own default: enough to
         * cover ordinary network jitter, short enough that the bottle is never visibly behind where it is.
         */
        private const val INTERPOLATION_STEPS = 3

        /** One second of holding the ship before it sets off home -- the beat that reads as "it is in there". */
        const val HOVER_TICKS = 20
        /** Roughly a minute to reach a ship, then ten seconds to get back. */
        private const val TIMEOUT_TICKS = 1200
        private const val RETURN_TICKS = 200
    }
}
