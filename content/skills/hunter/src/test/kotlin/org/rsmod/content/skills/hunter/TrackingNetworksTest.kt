package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * The authored trail geometry, checked against the cache and against itself.
 *
 * Serialised like the rest of the suite: `ServerCacheManager` is a singleton and `RSCM` memoises
 * into a plain `HashMap`, so a cache-touching class run in parallel with another races on shared
 * mutable state.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class TrackingNetworksTest {
    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
    }

    @Test
    fun `every gameval in every network resolves`() {
        for (network in TrackingNetworks.all) {
            for (segment in network.segments) {
                segment.varbit.asRSCM(RSCMType.VARBIT)
                segment.clue.asRSCM(RSCMType.LOC)
            }
            for (burrow in network.burrows) burrow.loc.asRSCM(RSCMType.LOC)
            for (spot in network.catchSpots) spot.loc.asRSCM(RSCMType.LOC)
        }
    }

    @Test
    fun `every burrow origin has at least one valid trail`() {
        for (network in TrackingNetworks.all) {
            for ((origin, trails) in network.trailsByOrigin) {
                assertTrue(trails.isNotEmpty()) { "${network.area}: origin $origin is stranded" }
            }
        }
    }

    @Test
    fun `segment varbits are unique within a network`() {
        for (network in TrackingNetworks.all) {
            assertEquals(network.segments.size, network.segments.map { it.varbit }.toSet().size)
        }
    }

    @Test
    fun `all five creatures have a network and each area is distinct`() {
        assertEquals(5, TrackingNetworks.all.size)
        assertEquals(5, TrackingNetworks.all.map { it.area }.toSet().size)
        assertEquals(
            TrackingCreatures.all.toSet(),
            TrackingNetworks.all.map { it.creature }.toSet(),
        )
    }

    @Test
    fun `the razor-backed network owns the basevar1 segments, not the common kebbit`() {
        /* The sweep's correction, pinned so it cannot silently regress to the spec's
         * original (wrong) anchor: 2009scape's CommonKebbitEast is the razor-backed
         * graph mislabelled. Wiki burrow coords put common kebbit in square 36,55 and
         * every razor-backed RuneLite tile marker in 36,56 - the basevar1/2 network. */
        val razor = TrackingNetworks.all.single { it.creature == TrackingCreatures.razorBacked }
        val common = TrackingNetworks.all.single { it.creature == TrackingCreatures.common }
        assertTrue(razor.segments.any { it.varbit.startsWith("varbit.hunting_trail_state1_") })
        assertTrue(common.segments.none { it.varbit.startsWith("varbit.hunting_trail_state1_") })
    }

    @Test
    fun `razor-backed and common kebbit geometry sits in the map square that settles the anchor`() {
        /* The varbit-prefix assertion above works, but a varbit rename would leave it green for
         * the wrong reason - it is not the actual basis of the correction. The wiki's burrow pins
         * and RuneLite's tile markers place common kebbit in map square 36,55 and razor-backed in
         * 36,56; that placement, not the varbit name, is what settles which creature owns which
         * graph. Map square is x/64, z/64 (CoordGrid.mx / CoordGrid.mz). */
        val razor = TrackingNetworks.all.single { it.creature == TrackingCreatures.razorBacked }
        val common = TrackingNetworks.all.single { it.creature == TrackingCreatures.common }

        val razorCoords =
            razor.segments.flatMap { listOf(it.endA, it.endB, it.clueCoords) } +
                razor.burrows.flatMap { listOf(it.coords, it.origin) } +
                razor.catchSpots.map { it.coords }
        val commonCoords =
            common.segments.flatMap { listOf(it.endA, it.endB, it.clueCoords) } +
                common.burrows.flatMap { listOf(it.coords, it.origin) } +
                common.catchSpots.map { it.coords }

        assertTrue(
            razorCoords.all { it.mx == 36 && it.mz == 56 },
            "razor-backed geometry is not entirely in map square 36,56",
        )
        assertTrue(
            commonCoords.all { it.mx == 36 && it.mz == 55 },
            "common kebbit geometry is not entirely in map square 36,55",
        )
    }

    @Test
    fun `no loc gameval serves two roles or two networks`() {
        val burrows = TrackingNetworks.burrowLocs.keys
        val clues = TrackingNetworks.clueLocs.keys
        val ends = TrackingNetworks.catchLocs.keys
        assertTrue((burrows intersect clues).isEmpty())
        assertTrue((burrows intersect ends).isEmpty())
        assertTrue((clues intersect ends).isEmpty())
        // toMap() drops duplicate keys silently, so assert the maps kept every entry -
        // varps 922 and 924 are each split between two creatures, and a clue gameval
        // landing in two networks would otherwise vanish without a symptom.
        assertEquals(TrackingNetworks.all.sumOf { it.segments.size }, clues.size)
        assertEquals(TrackingNetworks.all.sumOf { it.burrows.size }, burrows.size)
        // Catch spots are counted per network rather than per placement: one gameval covers
        // several tiles in an area - `hunting_trail_end_polar` is placed four times in Rellekka -
        // which is exactly why a catch spot is matched on coordinate. What must not collide is a
        // gameval across two *areas*: `toMap()` would bind both to whichever came last, and every
        // trail in the other would end at a loc no handler routes to that network. Silent.
        val endsPerNetwork = TrackingNetworks.all.map { it.catchSpots.map(TrailCatchSpot::loc) }
        assertTrue(
            endsPerNetwork.all { it.toSet().size == 1 },
            "each network's catch spots share exactly one gameval",
        )
        assertEquals(TrackingNetworks.all.size, ends.size)
    }

    @Test
    fun `the graph each network's geometry describes is the one that shipped`() {
        /* Two fingerprints of the whole authored graph, both computed off the sweep
         * before it was transcribed. A mistyped digit in a junction splits one node in
         * two, which changes the junction count; if the split node was load-bearing it
         * also drops trails. Counting shared junctions alone does not catch it - a split
         * leaves the surviving node shared and adds a singleton. */
        val junctions =
            TrackingNetworks.all.associate { network ->
                network.area to network.segments.flatMap { listOf(it.endA, it.endB) }.toSet().size
            }
        assertEquals(
            mapOf(
                "rellekka_polar" to 9,
                "piscatoris_ne_razorbacked" to 13,
                "piscatoris_sw_common" to 12,
                "uzer_desert_devil" to 12,
                "feldip_weasel" to 12,
            ),
            junctions,
        )

        val trails =
            TrackingNetworks.all.associate { network ->
                network.area to network.trailsByOrigin.values.sumOf { it.size }
            }
        assertEquals(
            mapOf(
                "rellekka_polar" to 4,
                "piscatoris_ne_razorbacked" to 37,
                "piscatoris_sw_common" to 35,
                "uzer_desert_devil" to 17,
                "feldip_weasel" to 25,
            ),
            trails,
        )
    }

    @Test
    fun `every network is on the level its area sits on`() {
        // Rellekka's Hunter area is entirely on level 1; every other in-scope area is level 0.
        // A default-level slip authors a trail nobody can reach, and nothing else would notice.
        val polar = TrackingNetworks.all.single { it.creature == TrackingCreatures.polar }
        val polarCoords =
            polar.segments.flatMap { listOf(it.clueCoords, it.endA, it.endB) } +
                polar.burrows.flatMap { listOf(it.coords, it.origin) } +
                polar.catchSpots.map { it.coords }
        assertTrue(polarCoords.all { it.level == 1 }, "polar geometry is level 1")

        val others = TrackingNetworks.all.filter { it.creature != TrackingCreatures.polar }
        val otherCoords =
            others.flatMap { network ->
                network.segments.flatMap { listOf(it.clueCoords, it.endA, it.endB) } +
                    network.burrows.flatMap { listOf(it.coords, it.origin) } +
                    network.catchSpots.map { it.coords }
            }
        assertTrue(otherCoords.all { it.level == 0 }, "every other area is level 0")
    }

    @Test
    fun `segment counts match the placements the sweep found`() {
        // 75 placed segments in scope, split per the sweep's section 2 table. Author only
        // placed segments: state2_9 is declared and unplaced, so network 2 ships nine.
        assertEquals(
            mapOf(
                "rellekka_polar" to 9,
                "piscatoris_ne_razorbacked" to 19,
                "piscatoris_sw_common" to 17,
                "uzer_desert_devil" to 15,
                "feldip_weasel" to 15,
            ),
            TrackingNetworks.all.associate { it.area to it.segments.size },
        )
        assertEquals(75, TrackingNetworks.all.sumOf { it.segments.size })
        assertTrue(
            TrackingNetworks.all.none { n ->
                n.segments.any { it.varbit == "varbit.hunting_trail_state2_9" }
            },
            "state2_9 is declared but never placed",
        )
    }
}
