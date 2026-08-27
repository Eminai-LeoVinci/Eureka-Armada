package org.valkyrienskies.eureka.shipwright

import net.minecraft.world.item.Item

/**
 * What the shipwright's screen shows, and how it gets there.
 *
 * ## A snapshot, deliberately
 * The same arrangement the crew manifest uses: the server sends everything the screen draws when it opens, and
 * the screen does not poll. Nothing can change a shelf while one is open except the player standing at it, and
 * every action they can take asks the server -- which answers with a fresh snapshot. So reopening is never the
 * refresh; the server's reply is.
 *
 * ## Why the whole bill travels
 * A material list is a few dozen rows and it is the thing players came to read. Sending it up front means the
 * detail card opens instantly rather than asking the server for what it already knew, and it means the screen
 * has no states where a row is half-populated.
 */
object ShipwrightMenu {

    /**
     * One material line: what is wanted in total, and what has actually been handed over.
     *
     * The last three fields are what the Alter card draws its markers and its dropdown from. Note what is
     * NOT here: the list of items that could replace this one. Item tags are synced to every client at
     * login and the player's own pack is client-side, so the client can build that list and grey out what
     * the player has none of without a word from the server -- which matters, because "every whole block"
     * is a few hundred entries and would otherwise ride on every row of every refresh. The server still
     * validates whatever comes back, as it must regardless.
     */
    class Material(
        val item: Item,
        val needed: Int,
        val given: Int,
        /** The material this row was originally, when a swap has moved it. Null when nothing was swapped. */
        val swappedFrom: Item? = null,
        /** The family this row may be swapped within, or null when it may not be swapped at all. */
        val family: String? = null,
        /** FOUNDATIONAL / FURNITURE / DECOR -- whether this row may be struck off, and under which heading. */
        val category: String = "FOUNDATIONAL",
        /**
         * Whether this row is an ANY row -- "anything of the kind will do" -- rather than a fixed swap.
         *
         * It has to ride separately, because an Any row's representative IS its original item: the bill is
         * keyed by the representative so that a delivery of any family member pays into it, and the tidiest
         * representative to pick is the material the plans already called for. That makes an Any row and an
         * unswapped row identical in every other field, which is exactly how choosing "Anything of the kind"
         * came to look like it had silently reverted to the top of the list.
         */
        val anyOfKind: Boolean = false
    ) {
        val outstanding: Int get() = maxOf(0, needed - given)
        /**
         * Whether this row has been altered at all -- fixed swap or ANY alike. It is what the materials list
         * draws its asterisk and its orange from, so excluding Any rows here (an earlier cut did) makes a
         * row that IS altered look untouched everywhere except the card you altered it on.
         *
         * Telling the two apart is [anyOfKind]'s job, and every place that needs to asks that FIRST.
         */
        val swapped: Boolean get() = swappedFrom != null
        val excludable: Boolean get() = category != "FOUNDATIONAL"
    }

    /** One set of plans on the shelf, with everything the blueprint page showed plus what has been paid. */
    class Row(
        val shipName: String,
        val width: Int,
        val height: Int,
        val length: Int,
        val blocks: Int,
        val items: Int,
        val mass: Double,
        val topSpeed: Double,
        /** BOAT / AIRSHIP / SUBMARINE, as the hull will classify once built. */
        val profile: String,
        val materials: List<Material>,
        /**
         * The rows this alteration struck off, carried SEPARATELY from [materials].
         *
         * They are not part of the bill any more -- that is the whole point of striking them -- so they must
         * not count toward [items], [given] or [progress]. But the card still has to show them, greyed and
         * marked, or a captain has no way to put one back.
         */
        val struck: List<Material> = emptyList(),
        /** Whether anything about these plans has been changed from the page as filed. */
        val altered: Boolean = false,
        /**
         * What the yard charges to build this hull, quoted off [blocks] -- the plans as DRAWN, so striking
         * the decor off lowers the materials and not the fee. Empty when the world charges nothing or she
         * is under one whole unit. [Material.needed] is the count owed and [Material.given] is always zero;
         * the row is reused so the wire codec is too.
         */
        val fee: List<Material> = emptyList(),
        /**
         * Whether that fee has already been settled. The build fee is paid UP FRONT, before the first
         * plank, so a commission part-way through its materials has paid it -- the card dims the line
         * rather than dropping it, because what the ship cost is still worth reading.
         */
        val feePaid: Boolean = false
    ) {
        val given: Int get() = materials.sumOf { minOf(it.needed, it.given) }
        val ready: Boolean get() = materials.all { it.outstanding <= 0 }

        /** 0..1 by total item count rather than by kind -- one plank short is not "half done". */
        val progress: Float get() = if (items <= 0) 1.0f else given.toFloat() / items.toFloat()
    }

    /**
     * One assembled ship the shipwright can see, and where its repair stands.
     *
     * [plansName] is the dropdown's current answer -- pre-filled by the shipwright's own guess, and
     * overridable. Everything from [match] down is null or empty until plans are chosen, because until then
     * there is nothing to compare against.
     */
    class Vessel(
        val slug: String,
        val width: Int,
        val height: Int,
        val length: Int,
        val blocks: Int,
        val mass: Double,
        /** 0..1 across every engine aboard, or -1 when the hull has no engines to average. */
        val fuel: Float,
        /** True when this hull is a child in an armada whose parent is the one in range. */
        val child: Boolean,
        val plansName: String?,
        /** 0..1 of the chosen plans already in place. Meaningless without [plansName]. */
        val match: Float,
        /** Why a repair is refused, or null. */
        val refusal: String?,
        val repairs: List<Material>,
        /**
         * What the yard charges to break this hull up, quoted off [blocks] -- her LIVE count, not her plans.
         * Empty when the world charges nothing or she is under one whole unit. [Material.needed] is the
         * count owed and [Material.given] is always zero; the row is reused so the wire codec is too.
         */
        val fee: List<Material> = emptyList()
    ) {
        val given: Int get() = repairs.sumOf { minOf(it.needed, it.given) }
        val needed: Int get() = repairs.sumOf { it.needed }
        val sound: Boolean get() = plansName != null && refusal == null && repairs.isEmpty()
        val paid: Boolean get() = repairs.isNotEmpty() && repairs.all { it.outstanding <= 0 }
        val progress: Float get() = if (needed <= 0) 1.0f else given.toFloat() / needed.toFloat()
    }

    /**
     * A ship that has been broken up, as the claim list sees it.
     *
     * Hull and cargo ride as [Material] rows -- the same shape the bill uses -- with [Material.needed] as the
     * count waiting and [Material.given] left at zero. Reusing the row means the claim list draws through the
     * painter that already exists rather than a second one that would drift away from it.
     */
    class Pile(
        val shipName: String,
        val hull: List<Material>,
        val cargo: List<Material>,
        /** Kept whole rather than counted: enchanted, damaged, named, or a shulker box with something in it. */
        val keepsakes: List<Keepsake>
    ) {
        val items: Int get() = hull.sumOf { it.needed } + cargo.sumOf { it.needed }
    }

    /**
     * One stack a count could not describe, as the Kept tab lists it.
     *
     * Addressed by [index] rather than by item, because two shulker boxes are the same item and different
     * property -- and the whole reason these are kept whole is that what is inside them differs.
     *
     * [label] is written on the server, where the components can actually be read: a client is sent the
     * box's item and a line saying what is in it, not the contents themselves.
     */
    class Keepsake(val index: Int, val item: Item, val count: Int, val label: String)

    /**
     * A captain's whole shelf as the screen sees it, plus the ships in front of the bench.
     *
     * [hasFreeBottle] is why the Bottle button can be greyed rather than merely refusing when pressed: the
     * shipwright will not hand over a bottled ship without an unassigned Ship Bottle to put it in, and a button
     * that looks available and then says no is worse than one that says why up front.
     */
    class Shelf(
        val villager: Int,
        val slots: Int,
        val hasFreeBottle: Boolean,
        val rows: List<Row>,
        val vessels: List<Vessel> = emptyList(),
        /** Whether this world's shipwrights take repair work at all -- off, the book has no Yard page. */
        val repairEnabled: Boolean = true,
        /** Whether Repair works from a part-paid bill. The client cannot read server config, so it rides here. */
        val partialRepair: Boolean = true,
        /** Whether the captain is carrying a blank page, so Take Blueprint can grey itself rather than refuse. */
        val hasBlankBlueprint: Boolean = false,
        /** Config, riding along for the same reason [partialRepair] does. */
        val excludeEnabled: Boolean = true,
        val swapEnabled: Boolean = true,
        /** Whether structure -- as opposed to decor and furniture -- may be swapped at all. */
        val swapFoundational: Boolean = false,
        /** Ships this captain has broken up and not yet carried away. Listed in the Yard beside the water. */
        val salvage: List<Pile> = emptyList(),
        /** Whether this world lets a shipwright break a ship up at all. */
        val dismantleEnabled: Boolean = true
    )

    /**
     * What the screen asks the server to do. Ordinal-encoded on the wire, so do not reorder casually.
     *
     * The first four act on a set of plans and carry its ship name; the last three act on a hull in the water
     * and carry its slug, with [SELECT] carrying the chosen plans as well.
     */
    enum class Action {
        // 0..6 -- ordinal IS the wire format, so these seven never move and nothing is ever inserted.
        PAY, BUILD, BOTTLE, DELETE, SELECT, PAY_REPAIR, REPAIR,

        /** argument = "DECOR" or "FURNITURE"; toggles that whole heading off the plans. */
        EXCLUDE_CATEGORY,

        /** argument = item id; toggles that one row off the plans. */
        EXCLUDE_ITEM,

        /** argument = the row's item id, argument2 = the replacement's id, "*<family>" for Any, "" to clear. */
        SWAP,

        /** Put the plans back the way the page had them. */
        RESET_ALTERATION,

        /** File the alteration as a second set of plans. argument = a name, or blank to pick one. */
        SAVE_AS_NEW,

        /** Bake the alteration onto a page, spending a blank blueprint. */
        TAKE_BLUEPRINT,

        /** Break a hull up into a claim list. shipName = the hull's slug. */
        DISMANTLE,

        /**
         * Claim a whole tab. shipName = the pile's ship name, argument = "hull", "cargo" or "keep",
         * with "+box" appended when the captain wants their shulkers filled.
         */
        CLAIM_ALL,

        /**
         * Claim ONE row. Same arguments, plus argument2 = the item id.
         *
         * Deliberately its own action rather than [CLAIM_ALL] with an optional item: an argument that goes
         * missing on the wire would otherwise turn "give me the ladders" into "give me the entire ship",
         * and a mistake that big must not be one field away from a mistake that small.
         */
        CLAIM_ONE,

        /** Throw one row away. Same arguments as [CLAIM_ONE]; the screen asks twice before sending it. */
        SALVAGE_DISMISS,

        /**
         * Throw a WHOLE TAB away. shipName = the pile, argument = the tab.
         *
         * Its own action rather than [SALVAGE_DISMISS] with the item left blank, for the reason the
         * CLAIM_ALL / CLAIM_ONE split exists: an argument that goes missing must never be the difference
         * between discarding one row and discarding a ship.
         */
        SALVAGE_DISMISS_ALL,

        /**
         * The captain shut the book. Names nothing; releases the shipwright's attention.
         *
         * Sent on close rather than inferred from silence, because there is no other moment the server
         * could learn it -- a screen that is simply not sending looks exactly like one being read.
         */
        CLOSED
    }

    /**
     * Installed by the loader's client entrypoint, for the same reason [org.valkyrienskies.eureka.blueprint
     * .BlueprintPages] is: :common cannot name a Screen, and a dedicated server must never load one.
     */
    @Volatile
    @JvmStatic
    var opener: ((Shelf) -> Unit)? = null

    fun open(shelf: Shelf) {
        opener?.invoke(shelf)
    }

    /** Build a shelf snapshot for [owner] standing at [villager]. Server side only. */
    fun snapshot(
        ledger: ShipwrightLedger,
        owner: java.util.UUID,
        villager: Int,
        hasFreeBottle: Boolean,
        detail: (String) -> Detail?
    ): Shelf {
        val library = ledger.libraryOf(owner)
        val rows = library.plans.values.mapNotNull { plans ->
            val info = detail(plans.template) ?: return@mapNotNull null
            Row(
                shipName = plans.shipName,
                width = info.width,
                height = info.height,
                length = info.length,
                blocks = info.blocks,
                items = plans.cost.values.sum(),
                mass = info.mass,
                topSpeed = info.topSpeed,
                profile = info.profile,
                materials = plans.cost.map { (item, needed) ->
                    materialOf(plans, item, needed)
                },
                struck = plans.alteration.struckOut(plans.baseCost).map { (item, needed) ->
                    materialOf(plans, item, needed, given = 0)
                },
                altered = !plans.alteration.isEmpty,
                // Quoted off the same count printed beside it on the card, so the two can never disagree.
                fee = YardFee.quoteBuild(info.blocks).map { Material(it.item, it.count, 0) },
                feePaid = plans.feePaid
            )
        }
        return Shelf(villager, library.slots, hasFreeBottle, rows)
    }

    /**
     * One row, dressed with everything the Alter card needs to know about it.
     *
     * [swappedFrom] is found by looking BACKWARDS through the swaps, because the bill is keyed by what the
     * row became rather than what it was -- which is the right way round for paying, and the wrong way round
     * for explaining. A captain wants to be told "this was birch".
     */
    private fun materialOf(
        plans: ShipwrightLedger.Plans,
        item: Item,
        needed: Int,
        given: Int = plans.delivered[item] ?: 0
    ): Material {
        val match = plans.alteration.swaps.entries.firstOrNull { (_, swap) ->
            when (swap) {
                is Alteration.Fixed -> swap.item == item
                is Alteration.Any -> swap.representative == item
            }
        }
        val from = match?.key
        val family = MaterialFamilies.familyOf(from ?: item)?.location()?.toString()
        return Material(
            item = item,
            needed = needed,
            given = given,
            swappedFrom = from,
            family = family,
            category = MaterialFamilies.categoryOf(from ?: item).name,
            anyOfKind = match?.value is Alteration.Any
        )
    }

    /**
     * The parts of a ship's description that are not in the ledger.
     *
     * The ledger stores the bill, because the bill is what changes. Everything else -- how big, how heavy, how
     * fast -- is a property of the template and is recomputed from it, exactly as [org.valkyrienskies.eureka
     * .template.ShipManifest] intends. Two records of one ship can disagree; one cannot.
     */
    class Detail(
        val width: Int,
        val height: Int,
        val length: Int,
        val blocks: Int,
        val mass: Double,
        val topSpeed: Double,
        val profile: String
    )
}
