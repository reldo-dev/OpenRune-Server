package org.rsmod.content.skills.hunter

import org.rsmod.map.CoordGrid

/**
 * One of the twenty-five permanent pits, with everything about it the packed cache already fixes.
 *
 * **A pitfall is not a world object**, for the same reason a crab trap is not: the map places a
 * `hunting_pitfall_<n>` loc that carries no ops of its own and a `multivar` naming a
 * `hunt_pitfall_state<n>` varbit, and the client picks which of that loc's `multiloc` children to
 * draw from the value of that varbit *for the viewing player*. So every player sees their own pit
 * at every site, two players never contend for one, and nothing in this feature ever touches
 * `locRepo`. See [CrabTrapSite], which is the same shape.
 *
 * Every column here is a **literal**. Nothing is assembled from a prefix and an index, derived by
 * arithmetic, or zipped out of two ordered lists, and the four reasons are worth stating because
 * each one costs a different silent failure:
 * - [coords] is at **level 0**, and the cache authors all 134 pitfall placements at level **1**.
 *   Each sits on a `LINK_BELOW` tile, so `GameMapDecoder.bridgeLevel` resolves it a plane down
 *   before the loc reaches its zone - the same "authored a plane up, collides a plane down" deck
 *   pattern that puts a player on a bridge instead of in the river. A site authored at level 1
 *   would exist, would resolve, and would never fire an op, with nothing in any log to say why.
 * - [animalLoc] is null for sites 1-16 and is not an oversight. Only the nine antelope sites carry
 *   a `_animal` companion loc; for the sixteen cat sites the base loc's own children carry the
 *   caught creature and there is no second loc to name.
 * - the companion **ids are not in site order** - 53036 is site 19, 53037 is site 22, 53038 is site
 *   24 - so a companion may never be paired to a site by sorting, by arithmetic, or by position.
 * - [varbit] is a **name**, never a computed bit offset. The twenty-five state fields are three
 *   bits each, but the layout has a hole: site 18 ends at bit 23 of
 *   `varp.hunt_pitfall_states_basevar2` and site 19 starts at bit **25**. Bit 24 of that varp is
 *   unused, so `3 * field` is right for twenty-three of the twenty-five and reads into a
 *   neighbour's state for the other two. Let `ServerCacheManager.getVarbit` supply the bits,
 *   exactly as [CrabTrapSites] does.
 *
 * [index] is the site's number in the cache's own naming, 1 through 25, and is what a pending catch
 * carries as its queue argument. Like the crab trap's it is never persisted: the whole of a site's
 * state is the cache varbit, so a login rebuilds any pending catch by reading them back.
 *
 * Coordinates were surveyed against the packed map, not read off the wiki; the survey, and the
 * direct tile decode behind the level-0 column, are in
 * `.data/cache/wiki-hunter/pitfall-site-table.md`.
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
 * The twenty-five pit sites, in the cache's own numbering: six kyatt (map square 42,59), five
 * larupia (39,45 and 40,45), five graahk (43,46 and 43,47), five sunlight antelope (27,46 and
 * 27,47) and four moonlight antelope (24,147, which is underground).
 *
 * Written out one row at a time rather than generated. Twenty-five literal rows are cheaper than
 * the class of bug this module has already been bitten by - a name assembled by string surgery
 * resolves to something that does not exist and throws at first use rather than at boot - and every
 * row has to resolve in **two** namespaces anyway, since a site's loc and its varbit do not share a
 * symbol.
 *
 * `CoordGrid` has two `Int` constructors that both compile and mean different things, so every
 * coordinate below names its arguments. A positional slip would place the site somewhere else
 * entirely and compile cleanly.
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

    /** The site behind a state varbit, for the login pass that rebuilds pending catches. */
    val byVarbit: Map<String, PitfallSite> = all.associateBy { it.varbit }
}
