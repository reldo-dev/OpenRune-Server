package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType

/**
 * Every seed a bird house accepts, and what each one is worth towards filling it.
 *
 * ## The capacity model, and which half of it is published
 *
 * **Published:** "To arm the trap, players need **10 low level or 5 high level** hop seeds, herb
 * seeds, flower seeds, bush seeds, or allotment seeds. By using less seeds than the required amount,
 * different types of seeds can be used to set up the same trap (for example, it's possible to use a
 * stack of 7 Barley seeds and fill the remaining 3 with Hammerstone)." (*Bird house trapping >
 * Seeds*.) The per-seed 5-or-10 column of that same table is published, and it is transcribed in
 * `src/test/resources/wiki-charts/birdhouse-seeds.tsv`, which [BirdHouseSeedsTest] asserts this
 * table against row for row.
 *
 * **Inferred, and flagged as such:** that a house has a capacity of [BIRDHOUSE_SEED_UNITS] units and
 * a high-value seed costs [HIGH_VALUE_UNITS] of them. It is the only single model consistent with
 * both "10 barley" and "5 ranarr" *and* with mixing, but the wiki never states a unit weighting and
 * never gives an example that mixes across the 5/10 boundary. If a real 5-seed-plus-3-ranarr case is
 * ever measured and disagrees, this constant is where it changes and nothing else moves.
 *
 * ## Two naming traps the symbols carry and the names do not
 *
 * The hop seeds and the bush seeds carry `_hop_` and `_bush_` in their symbols but not in their
 * display names - `obj.hammerstone_hop_seed` is `"Hammerstone seed"` - while `obj.barley_seed` and
 * `obj.jute_seed` are hops with no infix at all. And `obj.marigold_seed` (5096) is the flower seed;
 * `marigold_seed_2..5` are grow-stage sprite variants that no player can hold. Both are why this
 * list is 41 literals rather than anything derived from a name.
 *
 * Seeds are **cosmetic to the loot** - "All seeds give the exact same rewards" (Mod Ash, 17 July
 * 2018) - so nothing downstream ever asks which seed went in, and none of this is persisted.
 */
object BirdHouseSeeds {
    /**
     * How many seed units one bird house holds.
     *
     * Ten, because the published rule is stated as ten of a low-value seed. See the class doc for
     * which part of the unit model is sourced.
     */
    const val BIRDHOUSE_SEED_UNITS: Int = 10

    /** What one high-value seed is worth, so that five of them fill a house. */
    const val HIGH_VALUE_UNITS: Int = 2

    /** What one low-value seed is worth. */
    const val LOW_VALUE_UNITS: Int = 1

    /**
     * The eleven seeds a house takes only five of: Wildblood, and every herb from ranarr up.
     *
     * Wildblood is the only non-herb entry, which is exactly why the split is stored as a set rather
     * than expressed as "herbs above harralander" - that rule is true of ten of the eleven and would
     * silently misprice the one hop seed.
     */
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

    /** The thirty seeds a house takes ten of: the rest of the hops, herbs, flowers, allotments and bushes. */
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
     * Unit cost keyed by packed obj id, resolved once so a seed symbol that does not exist fails at
     * class load rather than at whichever seeding happens to reach it.
     *
     * Keyed by id rather than by symbol because an [org.rsmod.game.inv.InvObj] carries only an id -
     * the same reason [HunterCrabTrap] inverts its nail resolution.
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

    /** What one of [objId] is worth towards filling a house, or null if it is not an accepted seed. */
    fun unitsOf(objId: Int): Int? = unitsById[objId]

    /** What one of [obj] is worth towards filling a house, or null if it is not an accepted seed. */
    fun unitsOf(obj: String): Int? = unitsById[obj.asRSCM(RSCMType.OBJ)]
}
