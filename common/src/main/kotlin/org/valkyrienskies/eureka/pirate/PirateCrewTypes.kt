package org.valkyrienskies.eureka.pirate

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import org.valkyrienskies.eureka.EurekaMod

/**
 * Who is allowed to crew a raider.
 *
 * Pirates were pillagers because pillagers were what existed when the machinery was written -- the type was
 * pinned to `Raider` in six places, and every one of them was reading a class where it meant "somebody who
 * can stand at a gun". That is not the same question, and the difference is the whole of what stopped a
 * ghost ship crewed by skeletons or a nether hull crewed by piglins from being authorable.
 *
 * ## Two gates, because there are two different reasons to say no
 * The TAG says what KIND of thing may sign on: undead, illagers, piglins -- the things a pirate ship should
 * be crewed by, and nothing else. It lives in `data/vs_eureka/tags/entity_type/pirate_crew.json` so a pack
 * can widen or narrow it without touching code, which is how every other list in this mod works.
 *
 * The HEIGHT says what will actually FIT. A gun crew stands at a piece under a deck, and a mob taller than
 * the deck is above it cannot do that however appropriate it looks on paper -- an Enderman is humanoid and
 * is nearly three blocks of it. Measured rather than listed, because a name list is a promise about
 * vanilla that no modded mob is bound by, and because it makes the rule say what it means: two blocks is
 * the clearance a gun deck has.
 *
 * [MAX_HEIGHT] is set so that everything a player would reach for passes -- zombies, husks, drowned,
 * zombie villagers, piglins and brutes at 1.95, skeletons and strays at 1.99, every illager at 1.95 -- and
 * the things that cannot serve a gun fall out on their own: an Enderman at 2.9, a giant at twelve.
 *
 * ## Two things the tag deliberately does not say
 * It does not say `#minecraft:raiders`, tempting as that is, because that tag carries the RAVAGER -- a
 * quadruped, and one that was technically eligible for the whole time the crew were typed as `Raider`. The
 * illagers are listed by name instead, which costs a pack the free inheritance and buys an exact answer.
 *
 * And it does not carry the WITHER SKELETON, which is the one omission likely to be missed: at 2.4 blocks
 * it is over the ceiling anyway, so listing it would only produce an entry that silently never matches. If
 * a nether hull ever wants one, both the tag and [MAX_HEIGHT] have to move together.
 */
object PirateCrewTypes {

    /**
     * The tallest a crew member may be, in blocks.
     *
     * Two, because that is what a gun deck gives you. See the class note for what this admits and refuses.
     */
    const val MAX_HEIGHT = 2.0

    val CREW: TagKey<EntityType<*>> = TagKey.create(
        Registries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath(EurekaMod.MOD_ID, "pirate_crew")
    )

    /**
     * Whether [entity] may serve aboard a raider -- asked at every door: authoring a hull, restoring a
     * complement, adopting a site, counting who still stands.
     *
     * A `Mob` and nothing looser. The complement is stood down, re-seated, tethered and given a sight
     * range, all of which are mob behaviours; a dropped item or an armour stand inside the hull's box is
     * not a smaller crew member, it is not a crew member.
     */
    fun eligible(entity: Entity?): Boolean {
        val mob = entity as? Mob ?: return false
        if (!mob.type.`is`(CREW)) return false
        return mob.type.height <= MAX_HEIGHT
    }
}
