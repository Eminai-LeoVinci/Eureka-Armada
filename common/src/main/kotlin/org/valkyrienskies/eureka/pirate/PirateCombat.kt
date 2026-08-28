package org.valkyrienskies.eureka.pirate

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.entity.ai.targeting.TargetingConditions
import net.minecraft.world.entity.monster.CrossbowAttackMob
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.monster.RangedAttackMob
import net.minecraft.world.entity.player.Player
import org.joml.Vector3d
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.follow.ShipCrew
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * What a pirate crew hand is like in a fight, beyond what vanilla gives a shore-bound monster.
 *
 * Three changes, all keyed on the crew tag ([PirateShips.CREW_TAG]) and re-applied by the once-a-second
 * crew watch -- goals do not persist across a save, the tag does, so the watch's re-outfit IS the
 * persistence:
 *
 * 1. **Ranged hands shoot like ship's marksmen.** Vanilla's crossbow goal opens fire at 8 blocks and a
 *    bow at 15 -- rowboat distances. The vanilla goal class is kept (its approach/strafe/reload logic is
 *    exactly right) but rebuilt with [EurekaConfig.Server.pirateCrewShootRange], wrapped only to keep
 *    its pathfinding aboard. The projectile itself is hurried along by a mixin on `Projectile.shoot`
 *    (crew-owned shots fly [EurekaConfig.Server.pirateCrewProjectileSpeedMultiplier] faster and
 *    straighter), which also flattens the arc -- so the range extension is real, not just permission
 *    to miss from further away.
 *
 * 2. **Melee hands keep the deck.** A vindicator that can see a player on a passing ship must not hurl
 *    itself into the sea after them. The player-target goal is gated at the TARGETING level -- no
 *    target means no pathing at all, which beats fighting the navigator afterwards: aboard a ship, a
 *    melee hand only takes players standing on HER deck; ashore (a beached wreck, a dormant hull's
 *    statue come loose) it is any other monster. Ranged hands pass the gate untouched -- shooting
 *    across the water is their whole job.
 *
 * 3. **A boarder is everyone's first problem.** The crew watch calls [swarm] for every hand the moment
 *    a player stands on the deck -- the user's rule verbatim: step aboard and the crew make you their
 *    number one priority.
 *
 * Retaliation (HurtByTargetGoal) is left alone everywhere: a hand that is hit fights back, aboard or
 * ashore, gated or not.
 */
object PirateCombat {

    /**
     * Bring one crew hand up to the ship's standard. Idempotent and cheap when nothing changed -- the
     * crew watch calls this every pass for every living hand, because that cadence is what survives
     * relogs, template placements and /reload (a range edit re-outfits within the second).
     */
    fun outfit(level: ServerLevel, mob: Mob) {
        if (mob.isNoAi) return // a mounted gunner's brain is off; the ship is their eyes
        widenRangedGoals(mob)
        gateTargeting(mob)
    }

    /** The boarding rule: [boarder] stood on the deck, so [mob] drops whatever it was doing about it. */
    fun swarm(mob: Mob, boarder: Player) {
        val current = mob.target
        // Already fighting a boarder on the own deck: no reason to swap victims mid-swing.
        if (current is Player && current.isAlive &&
            ShipCrew.standingOn(current) != null && ShipCrew.standingOn(current) == ShipCrew.standingOn(mob)
        ) {
            return
        }
        mob.setTarget(boarder)
    }

    // region ranged goals

    private fun widenRangedGoals(mob: Mob) {
        if (mob !is Monster || mob !is RangedAttackMob) return
        val range = EurekaConfig.SERVER.pirateCrewShootRange.toFloat()
        val selector = mob.goalSelector

        for (wrapped in selector.availableGoals.toList()) {
            when (val goal = wrapped.goal) {
                // Ours already. Rebuilt only when the config moved under it.
                is ShipCrossbowGoal<*> -> {
                    if (goal.rangeUsed == range) return
                    selector.removeGoal(goal)
                    if (mob is CrossbowAttackMob) selector.addGoal(wrapped.priority, newCrossbow<Nothing>(mob, range))
                    return
                }
                is ShipBowGoal<*> -> {
                    if (goal.rangeUsed == range) return
                    selector.removeGoal(goal)
                    selector.addGoal(wrapped.priority, newBow<Nothing>(mob, range))
                    return
                }
                // Vanilla's, at rowboat range: same class, our numbers, plus the stay-aboard wrap.
                is RangedCrossbowAttackGoal<*> -> {
                    if (mob !is CrossbowAttackMob) continue
                    selector.removeGoal(goal)
                    selector.addGoal(wrapped.priority, newCrossbow<Nothing>(mob, range))
                    return
                }
                is RangedBowAttackGoal<*> -> {
                    selector.removeGoal(goal)
                    selector.addGoal(wrapped.priority, newBow<Nothing>(mob, range))
                    return
                }
            }
        }
    }

    /**
     * The generic gymnastics: the vanilla goal's T is an intersection (Monster & RangedAttackMob [&
     * CrossbowAttackMob]) no plain Mob reference can name, and Kotlin cannot cast to an intersection.
     * A type parameter erases to its FIRST bound, so `mob as T` compiles to a checkcast against Monster
     * alone -- safe for every mob the `is` guards at the call sites admitted. The explicit `<Nothing>`
     * at those sites is noise the compiler demands, not information.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <X> newCrossbow(mob: Mob, range: Float): Goal
        where X : Monster, X : RangedAttackMob, X : CrossbowAttackMob =
        ShipCrossbowGoal(mob as X, range)

    @Suppress("UNCHECKED_CAST")
    private fun <X> newBow(mob: Mob, range: Float): Goal
        where X : Monster, X : RangedAttackMob =
        ShipBowGoal(mob as X, range)

    private class ShipCrossbowGoal<T>(
        private val crew: T,
        val rangeUsed: Float
    ) : RangedCrossbowAttackGoal<T>(crew, CROSSBOW_SPEED, rangeUsed)
        where T : Monster, T : RangedAttackMob, T : CrossbowAttackMob {
        override fun tick() {
            super.tick()
            confineToShip(crew)
        }
    }

    private class ShipBowGoal<T>(
        private val crew: T,
        val rangeUsed: Float
    ) : RangedBowAttackGoal<T>(crew, BOW_SPEED, BOW_INTERVAL, rangeUsed)
        where T : Monster, T : RangedAttackMob {
        override fun tick() {
            super.tick()
            confineToShip(crew)
        }
    }

    /**
     * The positive stay-aboard constraint: if the goal's pathfinding has picked a point off the hull,
     * stop the navigator now rather than letting the tether teleport the hand out of the sea later.
     * VS2 bakes ship blocks into the nav grid in world space, so the check transforms the nav target
     * into the ship's own frame and asks whether it is inside the hull box.
     */
    private fun confineToShip(mob: Mob) {
        val navTarget = mob.navigation.targetPos ?: return
        val level = mob.level() as? ServerLevel ?: return
        val shipId = ShipCrew.standingOn(mob) ?: return
        val ship = level.shipObjectWorld.loadedShips.getById(shipId) ?: return
        val box = ship.shipAABB ?: return
        val local = Vector3d(navTarget.x + 0.5, navTarget.y + 0.5, navTarget.z + 0.5)
        ship.worldToShip.transformPosition(local)
        val inside =
            local.x >= box.minX() - DECK_MARGIN && local.x <= box.maxX() + 1 + DECK_MARGIN &&
                local.y >= box.minY() - DECK_MARGIN && local.y <= box.maxY() + 1 + DECK_MARGIN &&
                local.z >= box.minZ() - DECK_MARGIN && local.z <= box.maxZ() + 1 + DECK_MARGIN
        if (!inside) mob.navigation.stop()
    }

    // endregion

    // region targeting gate

    private fun gateTargeting(mob: Mob) {
        val selector = mob.targetSelector
        for (wrapped in selector.availableGoals.toList()) {
            val goal = wrapped.goal
            if (goal is ShipTargetGoal) return // done
            if (goal !is NearestAttackableTargetGoal<*>) continue
            if (goal.targetType != Player::class.java) continue
            selector.removeGoal(goal)
            selector.addGoal(wrapped.priority, ShipTargetGoal(mob))
            return
        }
    }

    private class ShipTargetGoal(mob: Mob) : NearestAttackableTargetGoal<Player>(
        mob, Player::class.java, true,
        { target: net.minecraft.world.entity.LivingEntity -> eligible(mob, target) }
    )

    private fun eligible(mob: Mob, target: LivingEntity): Boolean {
        if (target !is Player) return false
        // Ranged hands pass untouched: shooting across the water is the job.
        if (mob is RangedAttackMob) return true
        val ownShip = ShipCrew.standingOn(mob) ?: return true // ashore: any other monster
        return ShipCrew.standingOn(target) == ownShip // aboard: only people on HER deck
    }

    // endregion

    /** Vanilla's own approach-speed modifiers for the two goals; only the radii were wrong for a ship. */
    private const val CROSSBOW_SPEED = 1.0
    private const val BOW_SPEED = 1.0
    private const val BOW_INTERVAL = 20

    /** Same margin every crew box-test uses. */
    private const val DECK_MARGIN = 2.0
}
