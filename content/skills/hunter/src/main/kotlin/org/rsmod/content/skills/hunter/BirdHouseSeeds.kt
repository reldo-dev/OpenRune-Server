package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType

/**
 * Every seed a bird house accepts. The per-seed 5-or-10 column is published; the *unit* model
 * (capacity 10, high-value = 2) is inferred and flagged (docs/hunter.md). 41 literals rather than
 * anything name-derived: the hop/bush symbols and display names disagree, and `marigold_seed_2..5`
 * are unholdable sprites. Seeds are cosmetic to the loot, so none of this is persisted.
 */
object BirdHouseSeeds {
    /** The inferred half of the capacity model - the one constant that moves if live disagrees. */
    const val BIRDHOUSE_SEED_UNITS: Int = 10

    /** What one high-value seed is worth, so that five of them fill a house. */
    const val HIGH_VALUE_UNITS: Int = 2

    /** What one low-value seed is worth. */
    const val LOW_VALUE_UNITS: Int = 1

    // Wildblood is the only non-herb entry - why this is a set, not "herbs above harralander".
    val highValue: List<String> =
        listOf(
            "obj.wildblood_hop_seed",
            "obj.ranarr_seed",
            "obj.toadflax_seed",
            "obj.irit_seed",
            "obj.avantoe_seed",
            "obj.kwuarm_seed",
            "obj.snapdragon_seed",
            "obj.cadantine_seed",
            "obj.lantadyme_seed",
            "obj.dwarf_weed_seed",
            "obj.torstol_seed",
        )

    /**
     * The thirty seeds a house takes ten of: the rest of the hops, herbs, flowers, allotments and
     * bushes.
     */
    val lowValue: List<String> =
        listOf(
            "obj.barley_seed",
            "obj.hammerstone_hop_seed",
            "obj.asgarnian_hop_seed",
            "obj.jute_seed",
            "obj.yanillian_hop_seed",
            "obj.krandorian_hop_seed",
            "obj.guam_seed",
            "obj.marrentill_seed",
            "obj.tarromin_seed",
            "obj.harralander_seed",
            "obj.marigold_seed",
            "obj.rosemary_seed",
            "obj.nasturtium_seed",
            "obj.woad_seed",
            "obj.limpwurt_seed",
            "obj.white_lily_seed",
            "obj.potato_seed",
            "obj.onion_seed",
            "obj.cabbage_seed",
            "obj.tomato_seed",
            "obj.sweetcorn_seed",
            "obj.strawberry_seed",
            "obj.watermelon_seed",
            "obj.snape_grass_seed",
            "obj.redberry_bush_seed",
            "obj.poisonivy_bush_seed",
            "obj.cadavaberry_bush_seed",
            "obj.dwellberry_bush_seed",
            "obj.jangerberry_bush_seed",
            "obj.whiteberry_bush_seed",
        )

    /** Every accepted seed, high-value first, which is also the order a greedy insert prefers. */
    val all: List<String> = highValue + lowValue

    /**
     * Unit cost by packed obj id, resolved once so a seed symbol that does not exist fails at class
     * load rather than at whichever seeding happens to reach it.
     */
    private val unitsById: Map<Int, Int> by lazy {
        buildMap {
            for (seed in highValue) {
                put(seed.asRSCM(RSCMType.OBJ), HIGH_VALUE_UNITS)
            }
            for (seed in lowValue) {
                put(seed.asRSCM(RSCMType.OBJ), LOW_VALUE_UNITS)
            }
        }
    }

    /**
     * What one of [obj] is worth towards filling a house, or null if it is not an accepted seed.
     */
    fun unitsOf(obj: String): Int? = unitsById[obj.asRSCM(RSCMType.OBJ)]
}
