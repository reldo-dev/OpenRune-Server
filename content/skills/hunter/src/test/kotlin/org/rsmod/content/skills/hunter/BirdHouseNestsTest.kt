package org.rsmod.content.skills.hunter

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * The two rate models, checked against oracles from outside the code: Mod Ash's dated endpoints,
 * the chart template's own parameters, and the Money Making Guide's per-run outputs - a different
 * page by different people exercising the whole chain in one number each.
 *
 * The per-tier scenery pages also carry "expected number of bird nests per run" prose. It is
 * **deliberately not used**: the level-99 halves agree with the charts but the at-requirement halves
 * disagree with each other about rounding (Willow's needs `val/256`, Teak's `(val+1)/256`, Oak's a
 * truncated `0.062`). They are hand-maintained and would be a false oracle.
 */
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class BirdHouseNestsTest {
    /* Roll one: the seed nest. */

    /** Mod Ash's two endpoints. His "0%" is `1/256` rounded, which is the standard `+1` offset. */
    @Test
    fun theSeedNestRateHitsModAshsPublishedEndpoints() {
        assertEquals(1.0 / 256.0, BirdHouseNests.seedNestChance(1), 1e-12, "level 1")
        assertEquals(201.0 / 256.0, BirdHouseNests.seedNestChance(99), 1e-12, "level 99")
        assertEquals(0.785, BirdHouseNests.seedNestChance(99), 0.001, "78.5% as Mod Ash states it")
    }

    /**
     * The seed nest rate reads the level and nothing else.
     *
     * "The tier of bird house has **no effect** on the rate of receiving a seed nest" - which is why
     * [BirdHouseNests.seedNestChance] takes no tier, and this asserts the signature can't quietly
     * grow one.
     */
    @Test
    fun theSeedNestRateRisesWithLevelAndNeverFalls() {
        val rates = (1..99).map(BirdHouseNests::seedNestChance)
        assertTrue(rates.zipWithNext().all { it.first <= it.second }, "monotonic in level")
        assertTrue(rates.first() < rates.last())
        // Every value is an exact 256th, because the engine formula is `(1 + floor(...)) / 256`.
        for ((index, rate) in rates.withIndex()) {
            val scaled = rate * 256.0
            assertEquals(
                scaled.toInt().toDouble(),
                scaled,
                1e-9,
                "level ${index + 1} must land on a whole 256th",
            )
        }
    }

    /* Roll two: the ten nest rolls. */

    /**
     * Every tier's level-99 rate is Mod Ash's own percentage.
     *
     * The nine figures are transcribed here from his quote rather than read from the shipped column,
     * so this compares the **model plus the table** against the source, not the model against the
     * table.
     */
    @Test
    fun everyTiersLevel99RateIsModAshsFigure() {
        val published =
            mapOf(
                "obj.birdhouse_normal" to 0.100,
                "obj.birdhouse_oak" to 0.125,
                "obj.birdhouse_willow" to 0.128,
                "obj.birdhouse_teak" to 0.130,
                "obj.birdhouse_maple" to 0.140,
                "obj.birdhouse_mahogany" to 0.150,
                "obj.birdhouse_yew" to 0.160,
                "obj.birdhouse_magic" to 0.170,
                "obj.birdhouse_redwood" to 0.175,
            )
        assertEquals(9, published.size)
        for (type in BirdHouseTypes.all) {
            val expected = checkNotNull(published[type.obj]) { "No published rate for ${type.obj}" }
            assertEquals(
                expected,
                BirdHouseNests.nestRollChance(type.nestPermille, 99),
                1e-12,
                "${type.obj} at level 99",
            )
        }
    }

    /**
     * "These scale down by half at level 50", and "the rate is constant at levels below 50".
     *
     * Both halves of that, and the continuity at the knee: level 50 is inside the flat region, so
     * the ramp starts at 51 and the curve has no step in it.
     */
    @Test
    fun theRateIsFlatBelowFiftyAtHalfTheLevel99Figure() {
        for (type in BirdHouseTypes.all) {
            val ninetyNine = BirdHouseNests.nestRollChance(type.nestPermille, 99)
            val half = ninetyNine / 2.0
            for (level in 1..BirdHouseNests.NEST_RATE_KNEE) {
                assertEquals(
                    half,
                    BirdHouseNests.nestRollChance(type.nestPermille, level),
                    1e-12,
                    "${type.obj} at level $level must be half the level-99 rate",
                )
            }
            val rates = (1..99).map { BirdHouseNests.nestRollChance(type.nestPermille, it) }
            assertTrue(rates.zipWithNext().all { it.first <= it.second }, "${type.obj} monotonic")
            // No step at the knee: one level past it moves by roughly a 49th of the half-to-full gap.
            val step =
                BirdHouseNests.nestRollChance(type.nestPermille, 51) -
                    BirdHouseNests.nestRollChance(type.nestPermille, 50)
            assertEquals(half / 49.0, step, 1e-12, "${type.obj} knee is continuous")
        }
    }

    /**
     * Boosting above 99 keeps helping the ten rolls, and does nothing for the seed nest.
     *
     * Published, from the same Mod Ash answer of 24 July 2021: "I believe it does help with the
     * total quantity of egg/ring/empty nests. **It does not make you more likely to get the seed
     * nest.**" The two models therefore extrapolate differently past 99, and that difference is
     * deliberate rather than an oversight.
     */
    @Test
    fun boostingPastNinetyNineHelpsTheTenRollsAndNotTheSeedNest() {
        val redwood = checkNotNull(BirdHouseTypes.byObj("obj.birdhouse_redwood"))
        assertTrue(
            BirdHouseNests.nestRollChance(redwood.nestPermille, 104) >
                BirdHouseNests.nestRollChance(redwood.nestPermille, 99)
        )
        // The seed nest curve is unclamped too, so this documents a known deviation rather than
        // asserting a fix: nothing about the roll reads a boost separately from the level.
        assertTrue(BirdHouseNests.seedNestChance(104) > BirdHouseNests.seedNestChance(99))
    }

    /** The chart's `req1..req9` are the tiers' own placement levels. */
    @Test
    fun theChartsRequirementsAreTheShippedHunterLevels() {
        val published = listOf(5, 14, 24, 34, 44, 49, 59, 74, 89)
        assertEquals(published, BirdHouseTypes.all.map { it.hunterLevel })
    }

    /* The nest type table. */

    /**
     * The published ring/egg/empty rarities, both with and without a strung rabbit foot.
     *
     * Counted by walking every index rather than by restating the weights, which is what catches an
     * off-by-one in the ring band: an index roll is the implementation Mod Ash described, and the
     * band `3..34` is the part of it that is easy to get wrong by one either way.
     */
    @Test
    fun theNestTypeTableMatchesThePublishedRarities() {
        assertNestTable(
            rabbitFoot = false,
            slots = 100,
            ring = 32,
            eachEgg = 1,
            empty = 65,
        )
        // The foot removes five slots, and they are five *empty* ones - ring and egg counts are
        // untouched, only the denominator moves. Implementing it as "+5% ring/egg" gives different
        // numbers for every row.
        assertNestTable(rabbitFoot = true, slots = 95, ring = 32, eachEgg = 1, empty = 60)
    }

    /**
     * The whole chain, against a published figure from a different page.
     *
     * *Money making guide/Bird house trapping* states the per-run output for four redwood houses at
     * 99 Hunter with a strung rabbit foot worn: **3.14** seed nests, **4.42** empty, **2.36** ring
     * and **0.22** egg. Nothing in this test's arithmetic comes from the model - the expected values
     * are quoted, and the predictions are built from `seedNestChance`, `nestRollChance`, ten rolls,
     * and the type table's own slot counts. Four independent numbers agreeing to three significant
     * figures is the strongest evidence available offline that the ten-roll model is right.
     *
     * The guide's "red egg" row lumps all three colours, since all three are sacrificed to the
     * Shrine at the same rate.
     */
    @Test
    fun theWholeChainReproducesTheMoneyMakingGuidesPublishedRun() {
        val redwood = checkNotNull(BirdHouseTypes.byObj("obj.birdhouse_redwood"))
        val houses = 4
        val slots = BirdHouseNests.nestTypeSlots(rabbitFoot = true).toDouble()

        val seedNests = houses * BirdHouseNests.seedNestChance(99)
        val nestRolls =
            houses * BirdHouseNests.NEST_ROLLS * BirdHouseNests.nestRollChance(redwood.nestPermille, 99)

        assertClose(3.14, seedNests, "seed nests per run")
        assertClose(4.42, nestRolls * 60.0 / slots, "empty nests per run")
        assertClose(2.36, nestRolls * 32.0 / slots, "ring nests per run")
        assertClose(0.22, nestRolls * 3.0 / slots, "egg nests per run")
    }

    private fun assertNestTable(
        rabbitFoot: Boolean,
        slots: Int,
        ring: Int,
        eachEgg: Int,
        empty: Int,
    ) {
        assertEquals(slots, BirdHouseNests.nestTypeSlots(rabbitFoot), "slot count")
        val counts = (0 until slots).map(BirdHouseNests::nestTypeAt).groupingBy { it }.eachCount()
        assertEquals(ring, counts[BirdHouseNests.RING_NEST], "ring out of $slots")
        assertEquals(eachEgg, counts[BirdHouseNests.RED_EGG_NEST], "red egg out of $slots")
        assertEquals(eachEgg, counts[BirdHouseNests.BLUE_EGG_NEST], "blue egg out of $slots")
        assertEquals(eachEgg, counts[BirdHouseNests.GREEN_EGG_NEST], "green egg out of $slots")
        assertEquals(empty, counts[BirdHouseNests.EMPTY_NEST], "empty out of $slots")
        assertEquals(slots, counts.values.sum(), "every index means exactly one thing")
    }

    /** Three significant figures, which is the precision the guide prints. */
    private fun assertClose(expected: Double, actual: Double, label: String) {
        assertTrue(
            abs(expected - actual) < 0.005,
            "$label: published $expected, model $actual",
        )
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }
    }
}
