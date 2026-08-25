package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.utils.skills.SkillingSuccessRate

/**
 * The butterfly and closer *tables*, checked against the wiki rather than against the code.
 *
 * Split from [ButterflyTest] on purpose: nothing here needs a world, and these are the assertions
 * with real power - every expected number below is read off a published chart or infobox, not copied
 * out of `HunterTables`, so a transposed coefficient fails here instead of shipping a wrong curve.
 */
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class ButterflyTablesTest {
    /**
     * The two published butterfly curves reproduce their charts exactly.
     *
     * Black warlock oldid=15288148 and Sunlight Moth oldid=15197088, both under
     * *Hunter info > Hunting chance*; the full 153-point extract is in
     * `.data/cache/wiki-hunter/butterfly-chance.tsv`. Points are exact 256ths read off the
     * `{{Skilling success chart}}` "Butterfly net" series.
     */
    @Test
    fun publishedButterflyCurvesReproduceTheirCharts() {
        val samples =
            mapOf(
                "npc.butterfly_warlock" to
                    listOf(45 to 145, 46 to 148, 60 to 187, 65 to 201, 84 to 255, 85 to 256),
                "npc.moth_sunlight" to
                    listOf(65 to 201, 66 to 204, 75 to 229, 84 to 255, 85 to 256),
            )

        for ((npc, points) in samples) {
            val creature = checkNotNull(ButterflyCreatures.byNpc(npc)) { "No butterfly row for $npc" }
            for ((level, expected) in points) {
                assertEquals(
                    expected,
                    charted(creature.successLow, creature.successHigh, level),
                    "$npc at level $level: chart says $expected/256",
                )
            }
        }
    }

    /**
     * The load-bearing observation behind the three guessed rows: the two published members of this
     * family are pointwise identical everywhere they overlap.
     *
     * The sunlight moth requires 65 and its chart starts at 201/256 - which is what the black
     * warlock's curve reads at 65, not the 145/256 the warlock itself starts at. Those two therefore
     * share one curve rather than each anchoring their own, which is what licenses giving the three
     * uncharted creatures the same pair.
     *
     * It does **not** establish that butterfly catch chance is a function of level alone - the
     * moonlight moth is a third published member and disagrees. See
     * `HunterRateTablesTest.theMoonlightMothIsNotOnTheOtherButterfliesCurve`.
     */
    @Test
    fun theTwoPublishedCurvesAreIdenticalWhereTheyOverlap() {
        val warlock = checkNotNull(ButterflyCreatures.byNpc("npc.butterfly_warlock"))
        val moth = checkNotNull(ButterflyCreatures.byNpc("npc.moth_sunlight"))

        for (level in 65..99) {
            assertEquals(
                chance256(warlock.successLow, warlock.successHigh, level),
                chance256(moth.successLow, moth.successHigh, level),
                "the two charted butterfly curves must agree at level $level",
            )
        }
    }

    /** Every butterfly shares one curve, which is what the class doc claims and code must honour. */
    @Test
    fun everyButterflyUsesTheOneSharedPair() {
        assertEquals(5, ButterflyCreatures.all.size)
        for (creature in ButterflyCreatures.all) {
            assertEquals(20, creature.successLow, "${creature.npc} low")
            assertEquals(296, creature.successHigh, "${creature.npc} high")
        }
    }

    /**
     * A guessed-rate creature really is on the packed pair, not on a default.
     *
     * Asserting the level-15 chance rather than the coefficients: if a guessed row ever failed to
     * pack, `successLow`/`successHigh` would read 0 and this would drop from 60/256 to 1/256.
     */
    @Test
    fun guessedRowsAreOnTheirPackedPairNotADefault() {
        val ruby = checkNotNull(ButterflyCreatures.byNpc("npc.butterfly_ruby"))
        assertEquals(60, chance256(ruby.successLow, ruby.successHigh, 15))
        val snowy = checkNotNull(ButterflyCreatures.byNpc("npc.butterfly_snowy"))
        assertEquals(117, chance256(snowy.successLow, snowy.successHigh, 35))
    }

    /**
     * The magic net and barehanded bonus is `+20` on both coefficients, twice sourced.
     *
     * Both published charts carry a second series that fits `(40, 316)`, and void reaches the same
     * `+20` independently. Checked against the black warlock's charted magic-net points.
     */
    @Test
    fun theNetBonusReproducesTheMagicNetChart() {
        val warlock = checkNotNull(ButterflyCreatures.byNpc("npc.butterfly_warlock"))
        val points = listOf(45 to 165, 46 to 168, 65 to 221, 77 to 255, 78 to 256)
        for ((level, expected) in points) {
            assertEquals(
                expected,
                charted(
                    warlock.successLow + HunterButterfly.NET_BONUS,
                    warlock.successHigh + HunterButterfly.NET_BONUS,
                    level,
                ),
                "magic net at level $level: chart says $expected/256",
            )
        }
    }

    /** Levels, xp (x10) and the jar each catch fills, against the wiki's Butterflies table. */
    @Test
    fun tableCarriesTheChartedLevelsXpAndJars() {
        val expected =
            listOf(
                Triple("npc.butterfly_ruby", 15, 240) to "obj.butterfly_jar_ruby",
                Triple("npc.butterfly_glacialis", 25, 340) to "obj.butterfly_jar_glacialis",
                Triple("npc.butterfly_snowy", 35, 440) to "obj.butterfly_jar_snowy",
                Triple("npc.butterfly_warlock", 45, 540) to "obj.butterfly_jar_warlock",
                Triple("npc.moth_sunlight", 65, 740) to "obj.butterfly_jar_sunmoth",
            )
        assertEquals(expected.size, ButterflyCreatures.all.size)

        for ((triple, jar) in expected) {
            val (npc, level, xp) = triple
            val creature = checkNotNull(ButterflyCreatures.byNpc(npc))
            assertEquals(level, creature.level, "$npc level")
            assertEquals(xp, creature.xp, "$npc xp (stored x10)")
            assertEquals(listOf(jar), creature.caught.map { it.obj }, "$npc jar")
            assertEquals(1..1, creature.caught.single().quantity, "$npc jar quantity")
        }
    }

    /**
     * The moonlight moth must not ship.
     *
     * Its npc, its jar and its published chart all exist, so it looks buildable from every angle
     * except the one that matters: zero spawns in `.data`. This is the guard on someone adding the
     * obvious sixth row.
     */
    @Test
    fun theMoonlightMothIsNotShipped() {
        assertFalse(
            ButterflyCreatures.all.any { it.npc == "npc.moth_moonlight" },
            "the moonlight moth has zero spawns and must stay out",
        )
    }

    /**
     * Butterflies must never appear in the trap engine's world.
     *
     * The same regression guard falconry carries, for the same reason: `HunterTrap` persists a
     * creature as an index into `HunterCreatures.all` and a family as a `TrapFamily` ordinal.
     */
    @Test
    fun butterfliesAreNotPartOfTheTrapTables() {
        val butterflyNpcs = ButterflyCreatures.all.map { it.npc }.toSet()
        val trapNpcs = HunterCreatures.all.map { it.npc }.toSet()
        assertTrue(
            (butterflyNpcs intersect trapNpcs).isEmpty(),
            "butterflies must not appear in HunterCreatures.all",
        )
        assertEquals(5, TrapFamily.entries.size, "butterfly netting must not add a TrapFamily entry")
    }

    /* The three closers */

    /**
     * The tropical wagtail's fitted pair reproduces its chart, and its prose endpoints.
     *
     * oldid=15259195, 43 points over L19-61. The page also states "The catch rate is 29% at lvl 1
     * and 144% at lvl 99", which is an independent check on `(low, high)` that the chart's own
     * range never reaches.
     */
    @Test
    fun wagtailRateReproducesItsChartAndProse() {
        val wagtail = checkNotNull(HunterCreatures.byNpc("npc.multicoloured_bird"))
        val points = listOf(19 to 130, 20 to 133, 40 to 193, 50 to 224, 60 to 254, 61 to 256)
        for ((level, expected) in points) {
            assertEquals(
                expected,
                charted(wagtail.successLow, wagtail.successHigh, level),
                "wagtail at level $level: chart says $expected/256",
            )
        }
        // Prose, truncated to whole percent the way the wiki states it.
        assertEquals(29, chance256(wagtail.successLow, wagtail.successHigh, 1) * 100 / 256)
        assertEquals(144, chance256(wagtail.successLow, wagtail.successHigh, 99) * 100 / 256)
    }

    /** The wagtail joins the snare family with the trap state the old exclusion said was orphaned. */
    @Test
    fun wagtailIsASnareCreatureOnTheColouredTrapState() {
        val wagtail = checkNotNull(HunterCreatures.byNpc("npc.multicoloured_bird"))
        assertEquals(TrapFamily.SNARE, wagtail.family)
        assertEquals(19, wagtail.level)
        assertEquals(952, wagtail.xp)
        assertEquals(
            "loc.hunting_ojibway_trap_full_coloured",
            HunterTrapStates.fullLoc(wagtail),
            "the loc name must come from the packed key, not from the npc symbol",
        )
        assertEquals(
            listOf("obj.bones", "obj.spit_raw_bird_meat", "obj.hunting_stripy_bird_feather"),
            wagtail.caught.map { it.obj },
        )
        assertEquals(5..10, wagtail.caught.last().quantity, "only the feather is rolled")
    }

    /** Ferret and jerboa join the box family, on locs their npc symbols cannot spell. */
    @Test
    fun ferretAndJerboaAreBoxCreaturesOnTheirOwnLocs() {
        val ferret = checkNotNull(HunterCreatures.byNpc("npc.hunting_ferret"))
        assertEquals(TrapFamily.BOX, ferret.family)
        assertEquals(27, ferret.level)
        assertEquals(1152, ferret.xp)
        assertEquals("loc.hunting_boxtrap_full_ferret", HunterTrapStates.fullLoc(ferret))
        assertEquals(listOf("obj.hunting_ferret"), ferret.caught.map { it.obj })

        val jerboa = checkNotNull(HunterCreatures.byNpc("npc.varlamore_hunterjerboa01"))
        assertEquals(TrapFamily.BOX, jerboa.family)
        assertEquals(39, jerboa.level)
        assertEquals(1370, jerboa.xp)
        assertEquals("loc.hunting_boxtrap_full_jerboa", HunterTrapStates.fullLoc(jerboa))
        assertEquals(listOf("obj.hunting_jerboa_tail"), jerboa.caught.map { it.obj })
        // The ambient "Jerboa" (12982) is not huntable and must not have been picked up.
        assertTrue(HunterCreatures.byNpc("npc.varlamore_jerboa") == null)
    }

    /**
     * The guessed box pairs sit on the shape they were derived from.
     *
     * The derivation is "the regular chinchompa's curve translated to this creature's own
     * requirement": 146/256 at the requirement, certainty 41 levels later. Asserting the anchors
     * rather than the coefficients means a re-derivation that keeps the rule but rounds differently
     * still passes, while a row that failed to pack does not.
     */
    @Test
    fun guessedBoxPairsMatchTheirStatedDerivation() {
        val chinchompa = checkNotNull(HunterCreatures.byNpc("npc.hunting_chinchompa"))
        assertEquals(146, chance256(chinchompa.successLow, chinchompa.successHigh, 53))

        val ferret = checkNotNull(HunterCreatures.byNpc("npc.hunting_ferret"))
        assertEquals(146, chance256(ferret.successLow, ferret.successHigh, 27))
        assertEquals(256, chance256(ferret.successLow, ferret.successHigh, 27 + 41))

        val jerboa = checkNotNull(HunterCreatures.byNpc("npc.varlamore_hunterjerboa01"))
        assertEquals(146, chance256(jerboa.successLow, jerboa.successHigh, 39))
        assertEquals(256, chance256(jerboa.successLow, jerboa.successHigh, 39 + 41))
    }

    /**
     * Adding the three closers must not have moved any creature already in the list.
     *
     * This is the save-compatibility guard. `HunterTrap` persists a creature as its index into
     * `HunterCreatures.all`, so the eighteen rows that shipped before this slice have to still be at
     * indices 0-17, in order, with the three new ones appended after them - even though two of them
     * joined tables whose other rows sit at indices 0-6.
     */
    @Test
    fun theClosersAppendWithoutShiftingAnyExistingIndex() {
        val expected =
            listOf(
                "npc.hunting_bird_jungle",
                "npc.hunting_bird_desert",
                "npc.hunting_bird_woodland",
                "npc.hunting_bird_polar",
                "npc.hunting_chinchompa",
                "npc.hunting_chinchompa_big",
                "npc.hunting_chinchompa_black",
                "npc.huntingbeast_claws",
                "npc.huntingbeast_barbedtail",
                "npc.huntingbeast_spiky",
                "npc.huntingbeast_sabreteeth",
                "npc.varlamore_fennecfox",
                "npc.salamander_green",
                "npc.salamander_orange",
                "npc.salamander_red",
                "npc.salamander_black",
                "npc.salamander_mountain",
                "npc.imp",
                // Appended by this slice.
                "npc.multicoloured_bird",
                "npc.hunting_ferret",
                "npc.varlamore_hunterjerboa01",
            )
        assertEquals(expected, HunterCreatures.all.map { it.npc })
    }

    /** What the engine actually rolls against, out of 256, and deliberately **not** capped. */
    private fun chance256(low: Int, high: Int, level: Int): Int =
        Math.round(SkillingSuccessRate.successRate(low, high, level, 99) * 256).toInt()

    /**
     * What the wiki *charts*, which is [chance256] capped at 1.0.
     *
     * The two differ above the certainty level and the difference is not cosmetic: the black
     * warlock's curve reads 258/256 at level 85 where the chart plots 1.0, so comparing a charted
     * point against the raw rate fails on every capped level. The prose endpoints are the other way
     * round - "144% at lvl 99" is a raw value - so both forms are needed.
     */
    private fun charted(low: Int, high: Int, level: Int): Int =
        minOf(256, chance256(low, high, level))

    companion object {
        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }
    }
}
