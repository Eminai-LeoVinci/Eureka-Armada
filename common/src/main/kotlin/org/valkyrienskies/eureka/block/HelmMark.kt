package org.valkyrienskies.eureka.block

import net.minecraft.util.StringRepresentable

/**
 * Who a ship's wheel answers to, shown by the colour of its hub.
 *
 * [NORMAL] is every helm a player has ever placed -- the blue Heart-of-the-Sea hub, and the only value
 * obtainable in survival or creative. The other two exist for generated pirate ships and are written solely
 * by pirate machinery (and the DEV-ONLY `/vs pirate set-mark`):
 *
 * - [PIRATE] -- black hub, pillagers alive. Every interaction is refused and the block is inviolable:
 *   it cannot be mined, blown up, shot off, or burned, and it never drops as an item.
 * - [TAKEN] -- white hub, all pillagers dead. An ordinary breakable block again; breaking it starts the
 *   conquest window.
 *
 * A blockstate property rather than block-entity data so that both sides can read it without a lookup --
 * the break gate, the villager POI exclusion and the wheel renderer all key off the state alone -- and so
 * that it bakes into the template palettes pirate hulls ship as.
 */
enum class HelmMark : StringRepresentable {
    NORMAL,
    PIRATE,
    TAKEN;

    override fun getSerializedName(): String = name.lowercase()
}
