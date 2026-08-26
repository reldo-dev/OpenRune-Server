package org.rsmod.content.skills.hunter

import org.rsmod.map.CoordGrid

/**
 * The authored trail geometry, one entry per AREA, transcribed from the packed-map sweep
 * (`.data/cache/wiki-hunter/tracking-network-sweep.md`). This file is the only place that knows a
 * coordinate, and a wrong one is silent where it is used: a burrow that inspects into nothing, or a
 * kebbit that can never be caught, with no error anywhere. So no coordinate here stands on its own
 * authority - all 119 are checked against sources outside this file, by the local-only
 * `hunterVerify` task against the packed map and by
 * `TrackingLoopTest.theAuthoredTrailLocsArePlacedInTheBootedWorld` against a booted world's
 * `LocRegistry`, which is where a click actually lands. Neither runs in `assemble`.
 *
 * **Not one entry per varp.** Four of the five creatures span two or three varp blocks, and varps
 * 922 and 924 are each split between two creatures - grouping by varp would merge the common kebbit
 * with the desert devil. Segments name their varbit and nothing else; the varp behind it is the
 * cache's business.
 *
 * **Only placed segments are here.** `hunting_trail_state2_9` is declared and never placed, so
 * network 2 ships nine segments rather than ten; the `state11_*`/`state12_*` runs are unplaced too.
 * Fossil networks (herbiboar) are deliberately absent - own slice.
 *
 * ## How the junctions were derived
 *
 * The sweep records each segment as a run of footprint tiles with its two diameter endpoints, not
 * as a pair of graph nodes, so the adjacency below is derived from those endpoints by one rule,
 * applied uniformly:
 * 1. each end takes the **nearest bush or burrow within Chebyshev 4**, the sweep's own
 *    endpoint-to-node matching radius (a bush beats a burrow at equal distance);
 * 2. if both ends land on the same node, `endB` - never `endA` - recomputes to the next node out
 *    beyond that shared one: the next-nearest bush or burrow, or, when neither is closer, a clue
 *    placement (`state6_8` in feldip lands this way, on `clue6_6` rather than a bush);
 * 3. if an end has no bush or burrow inside that radius, it takes the nearest **clue** placement -
 *    which is how two runs that meet away from a bush share a junction.
 *
 * That is not a guess dressed up as a rule. Run against network 1 it reproduces the sweep's
 * independent 2009scape comparison table (section 6.2) on nine of ten segments; the tenth,
 * `state1_5`'s west end, is a d3 tie the packed map does not resolve and that table does, so its
 * value is taken from there and marked below.
 *
 * Polar is the other network with prior art, and there the two sources part company: `state8_2`,
 * `state8_4`, `state8_5`, `state8_6` and `state8_7` land on different nodes from section 6.1's.
 * Those five are 2009scape's own numbers, not measurements, and the sweep names the packed map as
 * the authority wherever the two disagree - so the footprint runs win. The visible effect is that
 * both polar burrows anchor on the burrow tile itself rather than the snow drift beside it.
 *
 * A junction is a graph node, not a placement to look up: `TrailLogic.enumerate` walks it by
 * `CoordGrid` equality, so two segments meet only when their coordinates match exactly.
 */
object TrackingNetworks {
    private val rellekkaPolar =
        TrackingNetwork(
            area = "rellekka_polar",
            creature = TrackingCreatures.polar,
            segments =
                listOf(
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state8_0",
                        clue = "loc.hunting_trail_clue8_0",
                        clueCoords = CoordGrid(x = 2712, z = 3815, level = 1),
                        endA = CoordGrid(x = 2718, z = 3820, level = 1),
                        endB = CoordGrid(x = 2708, z = 3819, level = 1),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state8_1",
                        clue = "loc.hunting_trail_clue8_1",
                        clueCoords = CoordGrid(x = 2711, z = 3819, level = 1),
                        endA = CoordGrid(x = 2708, z = 3819, level = 1),
                        endB = CoordGrid(x = 2711, z = 3819, level = 1),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state8_2",
                        clue = "loc.hunting_trail_clue8_2",
                        clueCoords = CoordGrid(x = 2715, z = 3820, level = 1),
                        endA = CoordGrid(x = 2717, z = 3819, level = 1),
                        endB = CoordGrid(x = 2718, z = 3820, level = 1),
                    ),
                    // endB has no bush or burrow within 4 tiles; the junction is the nearest clue.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state8_3",
                        clue = "loc.hunting_trail_clue8_3",
                        clueCoords = CoordGrid(x = 2721, z = 3827, level = 1),
                        endA = CoordGrid(x = 2718, z = 3820, level = 1),
                        endB = CoordGrid(x = 2721, z = 3827, level = 1),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state8_4",
                        clue = "loc.hunting_trail_clue8_4",
                        clueCoords = CoordGrid(x = 2708, z = 3825, level = 1),
                        endA = CoordGrid(x = 2708, z = 3819, level = 1),
                        endB = CoordGrid(x = 2711, z = 3830, level = 1),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state8_5",
                        clue = "loc.hunting_trail_clue8_5",
                        clueCoords = CoordGrid(x = 2714, z = 3821, level = 1),
                        endA = CoordGrid(x = 2717, z = 3819, level = 1),
                        endB = CoordGrid(x = 2716, z = 3827, level = 1),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state8_6",
                        clue = "loc.hunting_trail_clue8_6",
                        clueCoords = CoordGrid(x = 2718, z = 3829, level = 1),
                        endA = CoordGrid(x = 2716, z = 3827, level = 1),
                        endB = CoordGrid(x = 2712, z = 3831, level = 1),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state8_7",
                        clue = "loc.hunting_trail_clue8_7",
                        clueCoords = CoordGrid(x = 2713, z = 3827, level = 1),
                        endA = CoordGrid(x = 2711, z = 3830, level = 1),
                        endB = CoordGrid(x = 2712, z = 3831, level = 1),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state8_8",
                        clue = "loc.hunting_trail_clue8_8",
                        clueCoords = CoordGrid(x = 2718, z = 3832, level = 1),
                        endA = CoordGrid(x = 2712, z = 3831, level = 1),
                        endB = CoordGrid(x = 2718, z = 3832, level = 1),
                    ),
                ),
            burrows =
                listOf(
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn_polar1",
                        coords = CoordGrid(x = 2711, z = 3830, level = 1),
                        origin = CoordGrid(x = 2711, z = 3830, level = 1),
                    ),
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn_polar2",
                        coords = CoordGrid(x = 2717, z = 3819, level = 1),
                        origin = CoordGrid(x = 2717, z = 3819, level = 1),
                    ),
                ),
            catchSpots =
                listOf(
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_polar",
                        coords = CoordGrid(x = 2708, z = 3819, level = 1),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_polar",
                        coords = CoordGrid(x = 2712, z = 3831, level = 1),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_polar",
                        coords = CoordGrid(x = 2716, z = 3827, level = 1),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_polar",
                        coords = CoordGrid(x = 2718, z = 3820, level = 1),
                    ),
                ),
        )

    private val piscatorisNeRazorBacked =
        TrackingNetwork(
            area = "piscatoris_ne_razorbacked",
            creature = TrackingCreatures.razorBacked,
            segments =
                listOf(
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state1_0",
                        clue = "loc.hunting_trail_clue1_0",
                        clueCoords = CoordGrid(x = 2362, z = 3598, level = 0),
                        endA = CoordGrid(x = 2353, z = 3595, level = 0),
                        endB = CoordGrid(x = 2360, z = 3602, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state1_1",
                        clue = "loc.hunting_trail_clue1_1",
                        clueCoords = CoordGrid(x = 2355, z = 3598, level = 0),
                        endA = CoordGrid(x = 2353, z = 3595, level = 0),
                        endB = CoordGrid(x = 2355, z = 3601, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state1_2",
                        clue = "loc.hunting_trail_clue1_2",
                        clueCoords = CoordGrid(x = 2347, z = 3603, level = 0),
                        endA = CoordGrid(x = 2349, z = 3604, level = 0),
                        endB = CoordGrid(x = 2353, z = 3595, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state1_3",
                        clue = "loc.hunting_trail_clue1_3",
                        clueCoords = CoordGrid(x = 2358, z = 3599, level = 0),
                        endA = CoordGrid(x = 2355, z = 3601, level = 0),
                        endB = CoordGrid(x = 2360, z = 3602, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state1_4",
                        clue = "loc.hunting_trail_clue1_4",
                        clueCoords = CoordGrid(x = 2352, z = 3603, level = 0),
                        endA = CoordGrid(x = 2349, z = 3604, level = 0),
                        endB = CoordGrid(x = 2355, z = 3601, level = 0),
                    ),
                    // endA is a d3 tie in the packed map; section 6.2's table resolves it.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state1_5",
                        clue = "loc.hunting_trail_clue1_5",
                        clueCoords = CoordGrid(x = 2358, z = 3603, level = 0),
                        endA = CoordGrid(x = 2357, z = 3607, level = 0),
                        endB = CoordGrid(x = 2360, z = 3602, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2363,3602).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state1_6",
                        clue = "loc.hunting_trail_clue1_6",
                        clueCoords = CoordGrid(x = 2362, z = 3610, level = 0),
                        endA = CoordGrid(x = 2360, z = 3602, level = 0),
                        endB = CoordGrid(x = 2360, z = 3611, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2359,3613).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state1_7",
                        clue = "loc.hunting_trail_clue1_7",
                        clueCoords = CoordGrid(x = 2358, z = 3607, level = 0),
                        endA = CoordGrid(x = 2357, z = 3607, level = 0),
                        endB = CoordGrid(x = 2360, z = 3611, level = 0),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state1_8",
                        clue = "loc.hunting_trail_clue1_8",
                        clueCoords = CoordGrid(x = 2355, z = 3608, level = 0),
                        endA = CoordGrid(x = 2354, z = 3609, level = 0),
                        endB = CoordGrid(x = 2357, z = 3607, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state1_9",
                        clue = "loc.hunting_trail_clue1_9",
                        clueCoords = CoordGrid(x = 2351, z = 3608, level = 0),
                        endA = CoordGrid(x = 2349, z = 3604, level = 0),
                        endB = CoordGrid(x = 2354, z = 3609, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state2_0",
                        clue = "loc.hunting_trail_clue2_0",
                        clueCoords = CoordGrid(x = 2363, z = 3617, level = 0),
                        endA = CoordGrid(x = 2357, z = 3624, level = 0),
                        endB = CoordGrid(x = 2362, z = 3615, level = 0),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state2_1",
                        clue = "loc.hunting_trail_clue2_1",
                        clueCoords = CoordGrid(x = 2349, z = 3620, level = 0),
                        endA = CoordGrid(x = 2351, z = 3619, level = 0),
                        endB = CoordGrid(x = 2357, z = 3624, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state2_2",
                        clue = "loc.hunting_trail_clue2_2",
                        clueCoords = CoordGrid(x = 2356, z = 3620, level = 0),
                        endA = CoordGrid(x = 2357, z = 3624, level = 0),
                        endB = CoordGrid(x = 2358, z = 3620, level = 0),
                    ),
                    // endA has no bush or burrow within 4 tiles; the junction is the nearest clue.
                    // The clue is placed twice, one per run end; also at (2347,3607).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state2_3",
                        clue = "loc.hunting_trail_clue2_3",
                        clueCoords = CoordGrid(x = 2344, z = 3612, level = 0),
                        endA = CoordGrid(x = 2344, z = 3612, level = 0),
                        endB = CoordGrid(x = 2349, z = 3604, level = 0),
                    ),
                    // endA has no bush or burrow within 4 tiles; the junction is the nearest clue.
                    // The clue is placed twice, one per run end; also at (2352,3612).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state2_4",
                        clue = "loc.hunting_trail_clue2_4",
                        clueCoords = CoordGrid(x = 2348, z = 3612, level = 0),
                        endA = CoordGrid(x = 2344, z = 3612, level = 0),
                        endB = CoordGrid(x = 2354, z = 3609, level = 0),
                    ),
                    // endA has no bush or burrow within 4 tiles; the junction is the nearest clue.
                    // The clue is placed twice, one per run end; also at (2349,3617).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state2_5",
                        clue = "loc.hunting_trail_clue2_5",
                        clueCoords = CoordGrid(x = 2345, z = 3614, level = 0),
                        endA = CoordGrid(x = 2345, z = 3614, level = 0),
                        endB = CoordGrid(x = 2351, z = 3619, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2356,3618).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state2_6",
                        clue = "loc.hunting_trail_clue2_6",
                        clueCoords = CoordGrid(x = 2352, z = 3618, level = 0),
                        endA = CoordGrid(x = 2351, z = 3619, level = 0),
                        endB = CoordGrid(x = 2358, z = 3620, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state2_7",
                        clue = "loc.hunting_trail_clue2_7",
                        clueCoords = CoordGrid(x = 2362, z = 3614, level = 0),
                        endA = CoordGrid(x = 2360, z = 3611, level = 0),
                        endB = CoordGrid(x = 2362, z = 3615, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state2_8",
                        clue = "loc.hunting_trail_clue2_8",
                        clueCoords = CoordGrid(x = 2360, z = 3618, level = 0),
                        endA = CoordGrid(x = 2358, z = 3620, level = 0),
                        endB = CoordGrid(x = 2362, z = 3615, level = 0),
                    ),
                ),
            burrows =
                listOf(
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn1",
                        coords = CoordGrid(x = 2357, z = 3624, level = 0),
                        origin = CoordGrid(x = 2357, z = 3624, level = 0),
                    ),
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn2",
                        coords = CoordGrid(x = 2353, z = 3595, level = 0),
                        origin = CoordGrid(x = 2353, z = 3595, level = 0),
                    ),
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn3",
                        coords = CoordGrid(x = 2360, z = 3611, level = 0),
                        origin = CoordGrid(x = 2360, z = 3611, level = 0),
                    ),
                ),
            catchSpots =
                listOf(
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush29",
                        coords = CoordGrid(x = 2349, z = 3604, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush29",
                        coords = CoordGrid(x = 2351, z = 3619, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush29",
                        coords = CoordGrid(x = 2354, z = 3609, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush29",
                        coords = CoordGrid(x = 2355, z = 3601, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush29",
                        coords = CoordGrid(x = 2357, z = 3607, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush29",
                        coords = CoordGrid(x = 2358, z = 3620, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush29",
                        coords = CoordGrid(x = 2360, z = 3602, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush29",
                        coords = CoordGrid(x = 2362, z = 3615, level = 0),
                    ),
                ),
        )

    private val piscatorisSwCommon =
        TrackingNetwork(
            area = "piscatoris_sw_common",
            creature = TrackingCreatures.common,
            segments =
                listOf(
                    // The clue is placed twice, one per run end; also at (2330,3562).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state3_0",
                        clue = "loc.hunting_trail_clue3_0",
                        clueCoords = CoordGrid(x = 2323, z = 3562, level = 0),
                        endA = CoordGrid(x = 2323, z = 3563, level = 0),
                        endB = CoordGrid(x = 2331, z = 3562, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state3_1",
                        clue = "loc.hunting_trail_clue3_1",
                        clueCoords = CoordGrid(x = 2333, z = 3565, level = 0),
                        endA = CoordGrid(x = 2331, z = 3562, level = 0),
                        endB = CoordGrid(x = 2332, z = 3568, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state3_2",
                        clue = "loc.hunting_trail_clue3_2",
                        clueCoords = CoordGrid(x = 2335, z = 3561, level = 0),
                        endA = CoordGrid(x = 2331, z = 3562, level = 0),
                        endB = CoordGrid(x = 2337, z = 3565, level = 0),
                    ),
                    // endB has no bush or burrow within 4 tiles; the junction is the nearest clue.
                    // The clue is placed twice, one per run end; also at (2346,3566).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state3_3",
                        clue = "loc.hunting_trail_clue3_3",
                        clueCoords = CoordGrid(x = 2338, z = 3563, level = 0),
                        endA = CoordGrid(x = 2337, z = 3565, level = 0),
                        endB = CoordGrid(x = 2343, z = 3566, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state3_4",
                        clue = "loc.hunting_trail_clue3_4",
                        clueCoords = CoordGrid(x = 2321, z = 3565, level = 0),
                        endA = CoordGrid(x = 2322, z = 3570, level = 0),
                        endB = CoordGrid(x = 2323, z = 3563, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2330,3568).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state3_5",
                        clue = "loc.hunting_trail_clue3_5",
                        clueCoords = CoordGrid(x = 2325, z = 3565, level = 0),
                        endA = CoordGrid(x = 2323, z = 3563, level = 0),
                        endB = CoordGrid(x = 2332, z = 3568, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2337,3573).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state3_6",
                        clue = "loc.hunting_trail_clue3_6",
                        clueCoords = CoordGrid(x = 2335, z = 3564, level = 0),
                        endA = CoordGrid(x = 2336, z = 3571, level = 0),
                        endB = CoordGrid(x = 2337, z = 3565, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2343,3566).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state3_7",
                        clue = "loc.hunting_trail_clue3_7",
                        clueCoords = CoordGrid(x = 2338, z = 3570, level = 0),
                        endA = CoordGrid(x = 2336, z = 3571, level = 0),
                        endB = CoordGrid(x = 2343, z = 3568, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2345,3567).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state3_8",
                        clue = "loc.hunting_trail_clue3_8",
                        clueCoords = CoordGrid(x = 2341, z = 3579, level = 0),
                        endA = CoordGrid(x = 2341, z = 3577, level = 0),
                        endB = CoordGrid(x = 2343, z = 3568, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state3_9",
                        clue = "loc.hunting_trail_clue3_9",
                        clueCoords = CoordGrid(x = 2320, z = 3573, level = 0),
                        endA = CoordGrid(x = 2322, z = 3570, level = 0),
                        endB = CoordGrid(x = 2322, z = 3576, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state4_0",
                        clue = "loc.hunting_trail_clue4_0",
                        clueCoords = CoordGrid(x = 2324, z = 3571, level = 0),
                        endA = CoordGrid(x = 2322, z = 3570, level = 0),
                        endB = CoordGrid(x = 2327, z = 3573, level = 0),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state4_1",
                        clue = "loc.hunting_trail_clue4_1",
                        clueCoords = CoordGrid(x = 2331, z = 3572, level = 0),
                        endA = CoordGrid(x = 2327, z = 3573, level = 0),
                        endB = CoordGrid(x = 2332, z = 3568, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state4_2",
                        clue = "loc.hunting_trail_clue4_2",
                        clueCoords = CoordGrid(x = 2326, z = 3575, level = 0),
                        endA = CoordGrid(x = 2322, z = 3576, level = 0),
                        endB = CoordGrid(x = 2327, z = 3573, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2331,3576).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state4_3",
                        clue = "loc.hunting_trail_clue4_3",
                        clueCoords = CoordGrid(x = 2323, z = 3577, level = 0),
                        endA = CoordGrid(x = 2322, z = 3576, level = 0),
                        endB = CoordGrid(x = 2332, z = 3578, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state4_4",
                        clue = "loc.hunting_trail_clue4_4",
                        clueCoords = CoordGrid(x = 2335, z = 3574, level = 0),
                        endA = CoordGrid(x = 2332, z = 3578, level = 0),
                        endB = CoordGrid(x = 2336, z = 3571, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state4_5",
                        clue = "loc.hunting_trail_clue4_5",
                        clueCoords = CoordGrid(x = 2340, z = 3574, level = 0),
                        endA = CoordGrid(x = 2336, z = 3571, level = 0),
                        endB = CoordGrid(x = 2341, z = 3577, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state4_6",
                        clue = "loc.hunting_trail_clue4_6",
                        clueCoords = CoordGrid(x = 2336, z = 3579, level = 0),
                        endA = CoordGrid(x = 2332, z = 3578, level = 0),
                        endB = CoordGrid(x = 2341, z = 3577, level = 0),
                    ),
                ),
            burrows =
                listOf(
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn4",
                        coords = CoordGrid(x = 2331, z = 3562, level = 0),
                        origin = CoordGrid(x = 2331, z = 3562, level = 0),
                    ),
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn5",
                        coords = CoordGrid(x = 2322, z = 3576, level = 0),
                        origin = CoordGrid(x = 2322, z = 3576, level = 0),
                    ),
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn6",
                        coords = CoordGrid(x = 2341, z = 3577, level = 0),
                        origin = CoordGrid(x = 2341, z = 3577, level = 0),
                    ),
                ),
            catchSpots =
                listOf(
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush35_55",
                        coords = CoordGrid(x = 2322, z = 3570, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush35_55",
                        coords = CoordGrid(x = 2323, z = 3563, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush35_55",
                        coords = CoordGrid(x = 2327, z = 3573, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush35_55",
                        coords = CoordGrid(x = 2332, z = 3568, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush35_55",
                        coords = CoordGrid(x = 2332, z = 3578, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush35_55",
                        coords = CoordGrid(x = 2336, z = 3571, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush35_55",
                        coords = CoordGrid(x = 2337, z = 3565, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_bush35_55",
                        coords = CoordGrid(x = 2343, z = 3568, level = 0),
                    ),
                ),
        )

    private val uzerDesertDevil =
        TrackingNetwork(
            area = "uzer_desert_devil",
            creature = TrackingCreatures.desertDevil,
            segments =
                listOf(
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state4_7",
                        clue = "loc.hunting_trail_clue4_7",
                        clueCoords = CoordGrid(x = 3393, z = 3129, level = 0),
                        endA = CoordGrid(x = 3393, z = 3122, level = 0),
                        endB = CoordGrid(x = 3402, z = 3131, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state4_8",
                        clue = "loc.hunting_trail_clue4_8",
                        clueCoords = CoordGrid(x = 3403, z = 3126, level = 0),
                        endA = CoordGrid(x = 3405, z = 3124, level = 0),
                        endB = CoordGrid(x = 3402, z = 3131, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state5_0",
                        clue = "loc.hunting_trail_clue5_0",
                        clueCoords = CoordGrid(x = 3408, z = 3128, level = 0),
                        endA = CoordGrid(x = 3402, z = 3131, level = 0),
                        endB = CoordGrid(x = 3414, z = 3121, level = 0),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state5_1",
                        clue = "loc.hunting_trail_clue5_1",
                        clueCoords = CoordGrid(x = 3396, z = 3121, level = 0),
                        endA = CoordGrid(x = 3393, z = 3122, level = 0),
                        endB = CoordGrid(x = 3396, z = 3121, level = 0),
                    ),
                    // endA has no bush or burrow within 4 tiles; the junction is the nearest clue.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state5_2",
                        clue = "loc.hunting_trail_clue5_2",
                        clueCoords = CoordGrid(x = 3401, z = 3123, level = 0),
                        endA = CoordGrid(x = 3396, z = 3121, level = 0),
                        endB = CoordGrid(x = 3405, z = 3124, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state5_3",
                        clue = "loc.hunting_trail_clue5_3",
                        clueCoords = CoordGrid(x = 3405, z = 3122, level = 0),
                        endA = CoordGrid(x = 3405, z = 3124, level = 0),
                        endB = CoordGrid(x = 3407, z = 3121, level = 0),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state5_4",
                        clue = "loc.hunting_trail_clue5_4",
                        clueCoords = CoordGrid(x = 3409, z = 3121, level = 0),
                        endA = CoordGrid(x = 3407, z = 3121, level = 0),
                        endB = CoordGrid(x = 3414, z = 3121, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state5_5",
                        clue = "loc.hunting_trail_clue5_5",
                        clueCoords = CoordGrid(x = 3392, z = 3112, level = 0),
                        endA = CoordGrid(x = 3393, z = 3122, level = 0),
                        endB = CoordGrid(x = 3396, z = 3106, level = 0),
                    ),
                    // endA has no bush or burrow within 4 tiles; the junction is the nearest clue.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state5_6",
                        clue = "loc.hunting_trail_clue5_6",
                        clueCoords = CoordGrid(x = 3402, z = 3116, level = 0),
                        endA = CoordGrid(x = 3396, z = 3121, level = 0),
                        endB = CoordGrid(x = 3400, z = 3114, level = 0),
                    ),
                    // endA has no bush or burrow within 4 tiles; the junction is the nearest clue.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state5_7",
                        clue = "loc.hunting_trail_clue5_7",
                        clueCoords = CoordGrid(x = 3406, z = 3118, level = 0),
                        endA = CoordGrid(x = 3406, z = 3111, level = 0),
                        endB = CoordGrid(x = 3407, z = 3121, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state5_8",
                        clue = "loc.hunting_trail_clue5_8",
                        clueCoords = CoordGrid(x = 3411, z = 3114, level = 0),
                        endA = CoordGrid(x = 3411, z = 3108, level = 0),
                        endB = CoordGrid(x = 3414, z = 3121, level = 0),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state5_9",
                        clue = "loc.hunting_trail_clue5_9",
                        clueCoords = CoordGrid(x = 3399, z = 3111, level = 0),
                        endA = CoordGrid(x = 3396, z = 3106, level = 0),
                        endB = CoordGrid(x = 3399, z = 3111, level = 0),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state6_0",
                        clue = "loc.hunting_trail_clue6_0",
                        clueCoords = CoordGrid(x = 3403, z = 3110, level = 0),
                        endA = CoordGrid(x = 3400, z = 3114, level = 0),
                        endB = CoordGrid(x = 3403, z = 3110, level = 0),
                    ),
                    // endB has no bush or burrow within 4 tiles; the junction is the nearest clue.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state6_1",
                        clue = "loc.hunting_trail_clue6_1",
                        clueCoords = CoordGrid(x = 3406, z = 3111, level = 0),
                        endA = CoordGrid(x = 3411, z = 3108, level = 0),
                        endB = CoordGrid(x = 3406, z = 3111, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state6_2",
                        clue = "loc.hunting_trail_clue6_2",
                        clueCoords = CoordGrid(x = 3402, z = 3101, level = 0),
                        endA = CoordGrid(x = 3396, z = 3106, level = 0),
                        endB = CoordGrid(x = 3411, z = 3108, level = 0),
                    ),
                ),
            burrows =
                listOf(
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn_desert1",
                        coords = CoordGrid(x = 3396, z = 3106, level = 0),
                        origin = CoordGrid(x = 3396, z = 3106, level = 0),
                    ),
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn_desert2",
                        coords = CoordGrid(x = 3402, z = 3131, level = 0),
                        origin = CoordGrid(x = 3402, z = 3131, level = 0),
                    ),
                ),
            catchSpots =
                listOf(
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_desert",
                        coords = CoordGrid(x = 3393, z = 3122, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_desert",
                        coords = CoordGrid(x = 3400, z = 3114, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_desert",
                        coords = CoordGrid(x = 3405, z = 3124, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_desert",
                        coords = CoordGrid(x = 3407, z = 3121, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_desert",
                        coords = CoordGrid(x = 3411, z = 3108, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_desert",
                        coords = CoordGrid(x = 3414, z = 3121, level = 0),
                    ),
                ),
        )

    private val feldipWeasel =
        TrackingNetwork(
            area = "feldip_weasel",
            creature = TrackingCreatures.feldipWeasel,
            segments =
                listOf(
                    // The clue is placed twice, one per run end; also at (2524,2891).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state6_3",
                        clue = "loc.hunting_trail_clue6_3",
                        clueCoords = CoordGrid(x = 2522, z = 2881, level = 0),
                        endA = CoordGrid(x = 2525, z = 2882, level = 0),
                        endB = CoordGrid(x = 2525, z = 2889, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state6_4",
                        clue = "loc.hunting_trail_clue6_4",
                        clueCoords = CoordGrid(x = 2524, z = 2886, level = 0),
                        endA = CoordGrid(x = 2525, z = 2882, level = 0),
                        endB = CoordGrid(x = 2525, z = 2889, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state6_5",
                        clue = "loc.hunting_trail_clue6_5",
                        clueCoords = CoordGrid(x = 2527, z = 2890, level = 0),
                        endA = CoordGrid(x = 2525, z = 2889, level = 0),
                        endB = CoordGrid(x = 2531, z = 2890, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state6_6",
                        clue = "loc.hunting_trail_clue6_6",
                        clueCoords = CoordGrid(x = 2530, z = 2887, level = 0),
                        endA = CoordGrid(x = 2525, z = 2882, level = 0),
                        endB = CoordGrid(x = 2531, z = 2890, level = 0),
                    ),
                    // endB has no bush or burrow within 4 tiles; the junction is the nearest clue.
                    // The clue is placed twice, one per run end; also at (2531,2881).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state6_7",
                        clue = "loc.hunting_trail_clue6_7",
                        clueCoords = CoordGrid(x = 2526, z = 2882, level = 0),
                        endA = CoordGrid(x = 2525, z = 2882, level = 0),
                        endB = CoordGrid(x = 2531, z = 2881, level = 0),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state6_8",
                        clue = "loc.hunting_trail_clue6_8",
                        clueCoords = CoordGrid(x = 2533, z = 2882, level = 0),
                        endA = CoordGrid(x = 2533, z = 2885, level = 0),
                        endB = CoordGrid(x = 2530, z = 2887, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2539,2890).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state7_0",
                        clue = "loc.hunting_trail_clue7_0",
                        clueCoords = CoordGrid(x = 2533, z = 2889, level = 0),
                        endA = CoordGrid(x = 2531, z = 2890, level = 0),
                        endB = CoordGrid(x = 2540, z = 2886, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2540,2884).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state7_1",
                        clue = "loc.hunting_trail_clue7_1",
                        clueCoords = CoordGrid(x = 2534, z = 2886, level = 0),
                        endA = CoordGrid(x = 2533, z = 2885, level = 0),
                        endB = CoordGrid(x = 2540, z = 2886, level = 0),
                    ),
                    // endA has no bush or burrow within 4 tiles; the junction is the nearest clue.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state7_2",
                        clue = "loc.hunting_trail_clue7_2",
                        clueCoords = CoordGrid(x = 2537, z = 2880, level = 0),
                        endA = CoordGrid(x = 2533, z = 2882, level = 0),
                        endB = CoordGrid(x = 2542, z = 2881, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state7_3",
                        clue = "loc.hunting_trail_clue7_3",
                        clueCoords = CoordGrid(x = 2541, z = 2883, level = 0),
                        endA = CoordGrid(x = 2540, z = 2886, level = 0),
                        endB = CoordGrid(x = 2542, z = 2881, level = 0),
                    ),
                    // both run ends sit at the same bush; endB takes the next node out.
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state7_4",
                        clue = "loc.hunting_trail_clue7_4",
                        clueCoords = CoordGrid(x = 2540, z = 2887, level = 0),
                        endA = CoordGrid(x = 2540, z = 2886, level = 0),
                        endB = CoordGrid(x = 2540, z = 2887, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2551,2888).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state7_5",
                        clue = "loc.hunting_trail_clue7_5",
                        clueCoords = CoordGrid(x = 2543, z = 2890, level = 0),
                        endA = CoordGrid(x = 2540, z = 2886, level = 0),
                        endB = CoordGrid(x = 2553, z = 2888, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2551,2881).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state7_6",
                        clue = "loc.hunting_trail_clue7_6",
                        clueCoords = CoordGrid(x = 2544, z = 2881, level = 0),
                        endA = CoordGrid(x = 2542, z = 2881, level = 0),
                        endB = CoordGrid(x = 2554, z = 2882, level = 0),
                    ),
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state7_7",
                        clue = "loc.hunting_trail_clue7_7",
                        clueCoords = CoordGrid(x = 2552, z = 2885, level = 0),
                        endA = CoordGrid(x = 2553, z = 2888, level = 0),
                        endB = CoordGrid(x = 2554, z = 2882, level = 0),
                    ),
                    // The clue is placed twice, one per run end; also at (2555,2881).
                    TrailSegment(
                        varbit = "varbit.hunting_trail_state7_8",
                        clue = "loc.hunting_trail_clue7_8",
                        clueCoords = CoordGrid(x = 2554, z = 2888, level = 0),
                        endA = CoordGrid(x = 2554, z = 2882, level = 0),
                        endB = CoordGrid(x = 2553, z = 2888, level = 0),
                    ),
                ),
            burrows =
                listOf(
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn_jungle1",
                        coords = CoordGrid(x = 2554, z = 2882, level = 0),
                        origin = CoordGrid(x = 2554, z = 2882, level = 0),
                    ),
                    TrailBurrow(
                        loc = "loc.hunting_trail_spawn_jungle2",
                        coords = CoordGrid(x = 2525, z = 2889, level = 0),
                        origin = CoordGrid(x = 2525, z = 2889, level = 0),
                    ),
                ),
            catchSpots =
                listOf(
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_jungle",
                        coords = CoordGrid(x = 2525, z = 2882, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_jungle",
                        coords = CoordGrid(x = 2531, z = 2890, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_jungle",
                        coords = CoordGrid(x = 2533, z = 2885, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_jungle",
                        coords = CoordGrid(x = 2540, z = 2886, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_jungle",
                        coords = CoordGrid(x = 2542, z = 2881, level = 0),
                    ),
                    TrailCatchSpot(
                        loc = "loc.hunting_trail_end_jungle",
                        coords = CoordGrid(x = 2553, z = 2888, level = 0),
                    ),
                ),
        )

    val all: List<TrackingNetwork> =
        listOf(
            rellekkaPolar,
            piscatorisNeRazorBacked,
            piscatorisSwCommon,
            uzerDesertDevil,
            feldipWeasel,
        )

    val burrowLocs: Map<String, TrackingNetwork> =
        all.flatMap { n -> n.burrows.map { it.loc to n } }.toMap()

    val clueLocs: Map<String, TrackingNetwork> =
        all.flatMap { n -> n.segments.map { it.clue to n } }.toMap()

    val catchLocs: Map<String, TrackingNetwork> =
        all.flatMap { n -> n.catchSpots.map { it.loc to n } }.toMap()
}
