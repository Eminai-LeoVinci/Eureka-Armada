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

    /** One material line: what is wanted in total, and what has actually been handed over. */
    class Material(val item: Item, val needed: Int, val given: Int) {
        val outstanding: Int get() = maxOf(0, needed - given)
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
        val materials: List<Material>
    ) {
        val given: Int get() = materials.sumOf { minOf(it.needed, it.given) }
        val ready: Boolean get() = materials.all { it.outstanding <= 0 }

        /** 0..1 by total item count rather than by kind -- one plank short is not "half done". */
        val progress: Float get() = if (items <= 0) 1.0f else given.toFloat() / items.toFloat()
    }

    /**
     * A captain's whole shelf as the screen sees it.
     *
     * [hasFreeBottle] is why the Bottle button can be greyed rather than merely refusing when pressed: the
     * shipwright will not hand over a bottled ship without an unassigned Ship Bottle to put it in, and a button
     * that looks available and then says no is worse than one that says why up front.
     */
    class Shelf(
        val villager: Int,
        val slots: Int,
        val hasFreeBottle: Boolean,
        val rows: List<Row>
    )

    /** What the screen asks the server to do. Ordinal-encoded on the wire, so do not reorder casually. */
    enum class Action { PAY, BUILD, BOTTLE, DELETE }

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
                    Material(item, needed, plans.delivered[item] ?: 0)
                }
            )
        }
        return Shelf(villager, library.slots, hasFreeBottle, rows)
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
