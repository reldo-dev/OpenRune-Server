package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.map.CoordGrid

/**
 * One of the twenty-five permanent pits, with everything the packed cache fixes: the varbit is
 * the loc's own `multiVarBit`, the state ordinals are positions in the child chain looked up by
 * id, and the creature is matched by which collapsed locs appear among the children - the crab
 * trap's derivation discipline throughout (docs/hunter.md).
 */
data class PitfallSite(
    val index: Int,
    val baseLoc: String,
    val animalLoc: String?,
    val varbit: String,
    val creature: PitfallCreature,
    val coords: CoordGrid,
)

/**
 * The twenty-five pit sites in the cache's own numbering, written out rather than assembled - and
 * each proven placed on the packed map by `PitfallSitesTest`.
 */
object PitfallSites {
    val all: List<PitfallSite> =
        listOf(
            PitfallSite(
                index = 1,
                baseLoc = "loc.hunting_pitfall_1",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state1",
                creature = PitfallCreatures.kyatt,
                coords = CoordGrid(x = 2700, z = 3795, level = 0),
            ),
            PitfallSite(
                index = 2,
                baseLoc = "loc.hunting_pitfall_2",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state2",
                creature = PitfallCreatures.kyatt,
                coords = CoordGrid(x = 2700, z = 3785, level = 0),
            ),
            PitfallSite(
                index = 3,
                baseLoc = "loc.hunting_pitfall_3",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state3",
                creature = PitfallCreatures.kyatt,
                coords = CoordGrid(x = 2706, z = 3789, level = 0),
            ),
            PitfallSite(
                index = 4,
                baseLoc = "loc.hunting_pitfall_4",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state4",
                creature = PitfallCreatures.kyatt,
                coords = CoordGrid(x = 2730, z = 3791, level = 0),
            ),
            PitfallSite(
                index = 5,
                baseLoc = "loc.hunting_pitfall_5",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state5",
                creature = PitfallCreatures.kyatt,
                coords = CoordGrid(x = 2737, z = 3784, level = 0),
            ),
            PitfallSite(
                index = 6,
                baseLoc = "loc.hunting_pitfall_6",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state6",
                creature = PitfallCreatures.kyatt,
                coords = CoordGrid(x = 2730, z = 3780, level = 0),
            ),
            PitfallSite(
                index = 7,
                baseLoc = "loc.hunting_pitfall_7",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state7",
                creature = PitfallCreatures.larupia,
                coords = CoordGrid(x = 2556, z = 2893, level = 0),
            ),
            PitfallSite(
                index = 8,
                baseLoc = "loc.hunting_pitfall_8",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state8",
                creature = PitfallCreatures.larupia,
                coords = CoordGrid(x = 2543, z = 2908, level = 0),
            ),
            PitfallSite(
                index = 9,
                baseLoc = "loc.hunting_pitfall_9",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state9",
                creature = PitfallCreatures.larupia,
                coords = CoordGrid(x = 2552, z = 2904, level = 0),
            ),
            PitfallSite(
                index = 10,
                baseLoc = "loc.hunting_pitfall_10",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state10",
                creature = PitfallCreatures.larupia,
                coords = CoordGrid(x = 2565, z = 2888, level = 0),
            ),
            PitfallSite(
                index = 11,
                baseLoc = "loc.hunting_pitfall_11",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state11",
                creature = PitfallCreatures.larupia,
                coords = CoordGrid(x = 2573, z = 2885, level = 0),
            ),
            PitfallSite(
                index = 12,
                baseLoc = "loc.hunting_pitfall_12",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state12",
                creature = PitfallCreatures.graahk,
                coords = CoordGrid(x = 2766, z = 3010, level = 0),
            ),
            PitfallSite(
                index = 13,
                baseLoc = "loc.hunting_pitfall_13",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state13",
                creature = PitfallCreatures.graahk,
                coords = CoordGrid(x = 2762, z = 3005, level = 0),
            ),
            PitfallSite(
                index = 14,
                baseLoc = "loc.hunting_pitfall_14",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state14",
                creature = PitfallCreatures.graahk,
                coords = CoordGrid(x = 2771, z = 3004, level = 0),
            ),
            PitfallSite(
                index = 15,
                baseLoc = "loc.hunting_pitfall_15",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state15",
                creature = PitfallCreatures.graahk,
                coords = CoordGrid(x = 2777, z = 3001, level = 0),
            ),
            PitfallSite(
                index = 16,
                baseLoc = "loc.hunting_pitfall_16",
                animalLoc = null,
                varbit = "varbit.hunt_pitfall_state16",
                creature = PitfallCreatures.graahk,
                coords = CoordGrid(x = 2784, z = 3001, level = 0),
            ),
            PitfallSite(
                index = 17,
                baseLoc = "loc.hunting_pitfall_17",
                animalLoc = "loc.hunting_pitfall_17_animal",
                varbit = "varbit.hunt_pitfall_state17",
                creature = PitfallCreatures.sunlight,
                coords = CoordGrid(x = 1749, z = 3014, level = 0),
            ),
            PitfallSite(
                index = 18,
                baseLoc = "loc.hunting_pitfall_18",
                animalLoc = "loc.hunting_pitfall_18_animal",
                varbit = "varbit.hunt_pitfall_state18",
                creature = PitfallCreatures.sunlight,
                coords = CoordGrid(x = 1744, z = 3010, level = 0),
            ),
            // Site 19's varbit starts at bit 25, not 24: see the hole documented on [PitfallSite].
            PitfallSite(
                index = 19,
                baseLoc = "loc.hunting_pitfall_19",
                animalLoc = "loc.hunting_pitfall_19_animal",
                varbit = "varbit.hunt_pitfall_state19",
                creature = PitfallCreatures.sunlight,
                coords = CoordGrid(x = 1751, z = 3009, level = 0),
            ),
            PitfallSite(
                index = 20,
                baseLoc = "loc.hunting_pitfall_20",
                animalLoc = "loc.hunting_pitfall_20_animal",
                varbit = "varbit.hunt_pitfall_state20",
                creature = PitfallCreatures.sunlight,
                coords = CoordGrid(x = 1738, z = 3000, level = 0),
            ),
            PitfallSite(
                index = 21,
                baseLoc = "loc.hunting_pitfall_21",
                animalLoc = "loc.hunting_pitfall_21_animal",
                varbit = "varbit.hunt_pitfall_state21",
                creature = PitfallCreatures.sunlight,
                coords = CoordGrid(x = 1749, z = 2999, level = 0),
            ),
            PitfallSite(
                index = 22,
                baseLoc = "loc.hunting_pitfall_22",
                animalLoc = "loc.hunting_pitfall_22_animal",
                varbit = "varbit.hunt_pitfall_state22",
                creature = PitfallCreatures.moonlight,
                coords = CoordGrid(x = 1563, z = 9424, level = 0),
            ),
            PitfallSite(
                index = 23,
                baseLoc = "loc.hunting_pitfall_23",
                animalLoc = "loc.hunting_pitfall_23_animal",
                varbit = "varbit.hunt_pitfall_state23",
                creature = PitfallCreatures.moonlight,
                coords = CoordGrid(x = 1555, z = 9419, level = 0),
            ),
            PitfallSite(
                index = 24,
                baseLoc = "loc.hunting_pitfall_24",
                animalLoc = "loc.hunting_pitfall_24_animal",
                varbit = "varbit.hunt_pitfall_state24",
                creature = PitfallCreatures.moonlight,
                coords = CoordGrid(x = 1560, z = 9415, level = 0),
            ),
            PitfallSite(
                index = 25,
                baseLoc = "loc.hunting_pitfall_25",
                animalLoc = "loc.hunting_pitfall_25_animal",
                varbit = "varbit.hunt_pitfall_state25",
                creature = PitfallCreatures.moonlight,
                coords = CoordGrid(x = 1564, z = 9417, level = 0),
            ),
        )

    /**
     * The site a click landed on, keyed by the **base** loc - the map-placed parent, never the
     * child the player's varbit resolved to.
     *
     * `associateBy` drops a duplicate key silently, which for a hand-transcribed table would mean
     * losing a site rather than failing, so `PitfallSitesTest` asserts this map's size.
     */
    val byBaseLoc: Map<String, PitfallSite> = all.associateBy { it.baseLoc }

    /** The site behind a state varbit name, keyed and size-checked exactly as [byBaseLoc] is. */
    val byVarbit: Map<String, PitfallSite> = all.associateBy { it.varbit }

    /**
     * The `multiloc` child a pit renders as at [PitState.Empty], and the only loc in the whole
     * family that carries `op3=Trap`.
     *
     * All twenty-five sites share it - every base loc declares `multiloc=0,` this - so `Trap` is
     * one registration rather than twenty-five, and it is registered here rather than on the base
     * loc because `LocInteractions.opTrigger` resolves the child *before* it looks for a handler.
     */
    const val EMPTY_LOC: String = "loc.hunting_pitfall_invis_empty"

    /**
     * The child a pit renders as at [PitState.Set], carrying `op1=Jump` **and** `op2=Dismantle`.
     *
     * Shared by all twenty-five sites, like [EMPTY_LOC].
     */
    const val SET_LOC: String = "loc.hunting_pitfall_invis_set"

    /**
     * The seven collapsed-state children, matched to their creature by id, never by name surgery.
     */
    val fullLocs: List<String> =
        listOf(
            "loc.hunter_pitfall_full",
            "loc.hunter_pitfall_full_kyatt",
            "loc.hunter_pitfall_full_kyatt_180",
            "loc.hunter_pitfall_full_larupia",
            "loc.hunter_pitfall_full_larupia_180",
            "loc.hunter_pitfall_full_graahk",
            "loc.hunter_pitfall_full_graahk_180",
        )

    /**
     * Every child a `Dismantle` click can arrive on: the spiked pit and all seven full renderings.
     *
     * One list because one handler covers them - [HunterPitfall.dismantlePit] branches on the
     * site's varbit, not on which child it was handed.
     */
    val dismantleLocs: List<String> = listOf(SET_LOC) + fullLocs

    /**
     * The pitfall locs with no reachable op, which must never be registered: a handler on one
     * would be dead code that looks live in the wiring test.
     */
    val opLessLocs: List<String> =
        listOf(
            "loc.hunting_pitfall_invis_catching",
            "loc.hunting_pitfall_invis_collpased",
            "loc.hunter_pitfall_full_antelope_sunlight",
            "loc.hunter_pitfall_full_antelope_sunlight_180",
            "loc.hunter_pitfall_full_antelope_moonlight",
            "loc.hunter_pitfall_full_antelope_moonlight_180",
        )

    /**
     * Every site's base loc id, in table order.
     *
     * Touching it resolves all twenty-five `hunting_pitfall_<n>` names through `RSCM`, which is
     * why `PitfallEvents.startup` reads it: a mistyped site name then throws at boot rather than
     * at whichever click happens to reach it first. `HunterWiringTest` reads it for the other
     * reason - to assert that none of the twenty-five is subscribed to by id.
     */
    val baseLocIds: List<Int> by lazy { all.map { it.baseLoc.asRSCM(RSCMType.LOC) } }

    private val sitesByLocId: Map<Int, PitfallSite> by lazy { baseLocIds.zip(all).toMap() }

    /**
     * The site a click landed on, keyed by the **base** loc's id.
     *
     * `LocEvents.OpN` hands over both halves of a multiloc - `loc` is the map-placed site, `vis` is
     * the child the player's varbit resolved to - and this reads the site off `loc`. The state is
     * then read from the varbit rather than from `vis`, for the reason `CrabTrapSites.byLocId`
     * gives: the varbit is what the client rendered from and is the value the server is about to
     * overwrite, so the two cannot disagree.
     *
     * Lazy because it is the first thing in this file to touch `RSCM`, and the twenty-five literal
     * rows above deliberately do not.
     */
    fun byLocId(locId: Int): PitfallSite? = sitesByLocId[locId]
}
