package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.utils.skills.SkillingSuccessRate

/**
 * Every shipped catch-rate pair, checked against the published chart it was fit against.
 *
 * These are the assertions in this module with real power. Every expected number below is read off
 * a wiki chart checked in under `src/test/resources/wiki-charts`, not copied out of `HunterTables`,
 * so a transposed or mistyped pair fails here rather than shipping a wrong curve. The charts are
 * test resources rather than reads of the gitignored `.data` scratch dir on purpose: a chart the
 * test cannot find is a chart the test does not check, and an `assumeTrue` guard would skip
 * silently on every machine but the author's.
 *
 * Rates are read back through [HunterCreatures], [FalconryCreatures] and [ButterflyCreatures], i.e.
 * out of the *packed* dbtable rather than the pack module's Kotlin source, so a column-id gap that
 * shifts a table sideways fails here too.
 */
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class HunterRateTablesTest {
    /** Every charted point is reproduced exactly by the pair the server ships for that creature. */
    @Test
    fun everyChartedPointReproducesItsShippedPair() {
        val charts = readCharts()
        var checked = 0
        for (series in CHARTED) {
            val rate = shippedRate(series.npc)
            val points = charts.getValue(series.series)
            for ((level, expected) in points) {
                assertEquals(
                    expected,
                    charted(rate.low + series.netBonus, rate.high + series.netBonus, level),
                    "${series.series} (${series.npc}) at level $level: chart says $expected/256",
                )
                checked++
            }
        }
        assertEquals(1682, checked, "Chart point count changed; confirm the resources are intact.")
    }

    /**
     * Each chart begins at its creature's level requirement.
     *
     * The cross-check that catches a transposition. Swapping two creatures' pairs is caught by
     * [everyChartedPointReproducesItsShippedPair]; swapping their level requirements is not, because
     * the curves are defined over all levels and both would still reproduce. The wiki plots each
     * curve from the requirement, so the first charted level pins it.
     */
    @Test
    fun everyChartStartsAtItsCreaturesLevelRequirement() {
        val charts = readCharts()
        for (series in CHARTED) {
            val first = charts.getValue(series.series).minOf { it.level }
            assertEquals(
                shippedRate(series.npc).level,
                first,
                "${series.series}: chart starts at L$first, so ${series.npc}'s requirement should too",
            )
        }
    }

    /** No chart sits in the resources unaccounted for, mapped to nothing and asserted by nothing. */
    @Test
    fun everyChartedSeriesIsEitherMappedToARowOrDeclaredUnshipped() {
        val mapped = CHARTED.map { it.series }.toSet()
        for (series in readCharts().keys.sorted()) {
            assertTrue(
                series in mapped || series in UNSHIPPED_SERIES,
                "Chart series '$series' is mapped to no creature and not declared unshipped.",
            )
        }
    }

    /**
     * Every shipped creature is either charted or on the guessed list, and the guessed list is
     * exactly the five rows whose pair `HunterTables` annotates as a guess.
     *
     * The structural guard: without it a creature added with an invented pair and no source simply
     * would not be asserted anywhere, and the suite would stay green. Adding one now fails here
     * until it is either charted or explicitly declared a guess.
     */
    @Test
    fun everyShippedRowIsEitherChartedOrDeclaredAGuess() {
        val charted = CHARTED.map { it.npc }.toSet()
        val accounted = charted + GUESSED_NPCS
        val shipped = allShippedRates().map { it.npc }.toSet()
        assertEquals(
            emptySet<String>(),
            shipped - accounted,
            "Shipped creatures with no chart and no guess declaration.",
        )
        assertEquals(
            emptySet<String>(),
            accounted - shipped,
            "Declared creatures that no longer ship a rate row.",
        )
        assertEquals(emptySet<String>(), charted intersect GUESSED_NPCS)
    }

    /**
     * The two guessed box-trap pairs solve the anchors `HunterTables` says they were derived from:
     * the regular chinchompa's curve translated to each creature's own requirement, i.e. 146/256 at
     * the requirement and certainty exactly 41 levels later.
     */
    @Test
    fun theTwoGuessedBoxTrapPairsSolveTheChinchompaAnchors() {
        val chinchompa = shippedRate("npc.hunting_chinchompa")
        assertEquals(146, charted(chinchompa.low, chinchompa.high, chinchompa.level))
        assertEquals(chinchompa.level + 41, firstCertainLevel(chinchompa))

        for (npc in listOf("npc.hunting_ferret", "npc.varlamore_hunterjerboa01")) {
            val rate = shippedRate(npc)
            assertEquals(
                146,
                charted(rate.low, rate.high, rate.level),
                "$npc should read 146/256 at its own requirement, as the chinchompa does",
            )
            assertEquals(
                rate.level + 41,
                firstCertainLevel(rate),
                "$npc should reach certainty 41 levels above its requirement, as the chinchompa does",
            )
        }
    }

    /** The three guessed butterfly pairs are the published pair they were taken from, unchanged. */
    @Test
    fun theThreeGuessedButterflyPairsAreThePublishedPair() {
        val published = shippedRate("npc.butterfly_warlock")
        assertEquals(published.low to published.high, shippedRate("npc.moth_sunlight").let { it.low to it.high })
        for (npc in listOf("npc.butterfly_ruby", "npc.butterfly_glacialis", "npc.butterfly_snowy")) {
            val rate = shippedRate(npc)
            assertEquals(
                published.low to published.high,
                rate.low to rate.high,
                "$npc is guessed as the published butterfly pair; it should match it exactly",
            )
        }
    }

    /**
     * The moonlight moth is charted and does **not** sit on the other butterflies' curve.
     *
     * It is not shipped - zero spawns - but it is the third published member of the family, and it
     * bounds what [theThreeGuessedButterflyPairsAreThePublishedPair] may be read as meaning. Two
     * published members agreeing licenses a guess; it does not establish that butterfly catch chance
     * is a function of level alone, because this one is a counterexample. Asserted so that the
     * weaker claim cannot quietly be promoted to the stronger one later.
     */
    @Test
    fun theMoonlightMothIsNotOnTheOtherButterfliesCurve() {
        val family = shippedRate("npc.butterfly_warlock")
        val points = readCharts().getValue("moonlight_moth")
        val disagreements =
            points.count { charted(family.low, family.high, it.level) != it.chance256 }
        assertNotEquals(
            0,
            disagreements,
            "The moonlight moth now matches the family curve; the guessed rows' rationale changed.",
        )
    }

    /**
     * Every shipped pair is the chart template's **own published parameter**, exactly.
     *
     * Strictly stronger than [everyChartedPointReproducesItsShippedPair], and it exists because
     * reproducing a chart does not pin a pair. The barb-tailed kebbit charts only six levels and 25
     * different pairs reproduce all six; the sabre-toothed charts five and 19 pairs reproduce them.
     * A fit picks one arbitrarily. The wiki stores the real parameters in its Parsoid transclusion
     * metadata, so they can be read rather than inferred - see `published-params.tsv`.
     */
    @Test
    fun everyShippedPairIsThePublishedParameter() {
        val params = readParams()
        for (entry in PUBLISHED) {
            val key = "${entry.page}|${entry.series}"
            val published = checkNotNull(params[key]) { "No published parameters for $key" }
            val rate = shippedRate(entry.npc)
            assertEquals(
                (published.low - entry.netBonus) to (published.high - entry.netBonus),
                rate.low to rate.high,
                "${entry.npc}: $key publishes (${published.low}, ${published.high})" +
                    if (entry.netBonus != 0) " less the ${entry.netBonus} net bonus" else "",
            )
        }
    }

    /** The template also publishes each creature's level requirement; it must be the shipped one. */
    @Test
    fun everyShippedLevelIsThePublishedRequirement() {
        val params = readParams()
        for (entry in PUBLISHED) {
            val published = params.getValue("${entry.page}|${entry.series}")
            assertEquals(
                published.req,
                shippedRate(entry.npc).level,
                "${entry.npc}: ${entry.page} publishes req=${published.req}",
            )
        }
    }

    /** No charted creature may rely on a fit when its parameters are published. */
    @Test
    fun everyChartedCreatureAlsoHasItsPublishedParameterAsserted() {
        assertEquals(
            emptySet<String>(),
            CHARTED.map { it.npc }.toSet() - PUBLISHED.map { it.npc }.toSet(),
            "Charted creatures whose published parameters are not asserted.",
        )
    }

    private fun readParams(): Map<String, PublishedParams> =
        checkNotNull(javaClass.getResourceAsStream("/wiki-charts/published-params.tsv")) {
                "Missing /wiki-charts/published-params.tsv"
            }
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val f = line.split("\t")
                require(f.size == 6) { "Malformed params row: $line" }
                "${f[0]}|${f[2]}" to PublishedParams(f[3].toInt(), f[4].toInt(), f[5].toInt())
            }

    private data class PublishedParams(val low: Int, val high: Int, val req: Int)

    /** A published chart series, and the shipped row whose pair it is the source for. */
    private data class Published(
        val page: String,
        val series: String,
        val npc: String,
        val netBonus: Int = 0,
    )

    private fun shippedRate(npc: String): ShippedRate =
        checkNotNull(allShippedRates().firstOrNull { it.npc == npc }) {
            "No shipped rate row for $npc"
        }

    private fun allShippedRates(): List<ShippedRate> =
        HunterCreatures.all.map { ShippedRate(it.npc, it.level, it.successLow, it.successHigh) } +
            FalconryCreatures.all.map {
                ShippedRate(it.npc, it.level, it.successLow, it.successHigh)
            } +
            ButterflyCreatures.all.map {
                ShippedRate(it.npc, it.level, it.successLow, it.successHigh)
            } +
            ImplingCreatures.all.map {
                ShippedRate(it.npc, it.level, it.successLow, it.successHigh)
            }

    private fun firstCertainLevel(rate: ShippedRate): Int =
        (1..99).first { charted(rate.low, rate.high, it) == 256 }

    /** What the engine rolls against, out of 256, uncapped. */
    private fun chance256(low: Int, high: Int, level: Int): Int =
        Math.round(SkillingSuccessRate.successRate(low, high, level, 99) * 256).toInt()

    /** What the wiki *charts*, which is [chance256] capped at certainty. */
    private fun charted(low: Int, high: Int, level: Int): Int =
        minOf(256, chance256(low, high, level))

    private fun readCharts(): Map<String, List<ChartPoint>> =
        CHART_FILES
            .flatMap { file ->
                val resource = "/wiki-charts/$file"
                val text =
                    checkNotNull(javaClass.getResourceAsStream(resource)) {
                            "Missing chart resource $resource"
                        }
                        .bufferedReader()
                        .readText()
                text.lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { line ->
                        val fields = line.trim().split(Regex("\\s+"))
                        require(fields.size == 3) { "Malformed row in $file: $line" }
                        fields[0] to ChartPoint(fields[1].toInt(), fields[2].toInt())
                    }
                    .toList()
            }
            .groupBy({ it.first }, { it.second })

    private data class ChartPoint(val level: Int, val chance256: Int)

    private data class ShippedRate(val npc: String, val level: Int, val low: Int, val high: Int)

    /** A charted series and the row it is the source for. */
    private data class Charted(val series: String, val npc: String, val netBonus: Int = 0)

    companion object {
        private val CHART_FILES =
            listOf(
                "birdsnare-chance.tsv",
                "boxtrap-chance.tsv",
                "butterfly-chance.tsv",
                "deadfall-chance.tsv",
                "falconry-chance.tsv",
                "impling-chance.tsv",
                "magicbox-chance.tsv",
                "nettrap-chance.tsv",
            )

        private val CHARTED =
            listOf(
                Charted("crimson_swift", "npc.hunting_bird_jungle"),
                Charted("golden_warbler", "npc.hunting_bird_desert"),
                Charted("copper_longtail", "npc.hunting_bird_woodland"),
                Charted("cerulean_twitch", "npc.hunting_bird_polar"),
                Charted("tropical_wagtail", "npc.multicoloured_bird"),
                Charted("chinchompa", "npc.hunting_chinchompa"),
                Charted("carnivorous_chinchompa", "npc.hunting_chinchompa_big"),
                Charted("black_chinchompa", "npc.hunting_chinchompa_black"),
                Charted("wild_kebbit", "npc.huntingbeast_claws"),
                Charted("barbtailed_kebbit", "npc.huntingbeast_barbedtail"),
                Charted("prickly_kebbit", "npc.huntingbeast_spiky"),
                Charted("sabretoothed_kebbit", "npc.huntingbeast_sabreteeth"),
                Charted("pyre_fox", "npc.varlamore_fennecfox"),
                Charted("swamp_lizard", "npc.salamander_green"),
                Charted("orange_salamander", "npc.salamander_orange"),
                Charted("red_salamander", "npc.salamander_red"),
                Charted("black_salamander", "npc.salamander_black"),
                Charted("tecu_salamander", "npc.salamander_mountain"),
                Charted("imp", "npc.imp"),
                Charted("spotted_kebbit", "npc.huntingbeast_speedy"),
                Charted("dark_kebbit", "npc.huntingbeast_silent"),
                Charted("dashing_kebbit", "npc.huntingbeast_speedy2"),
                Charted("baby_impling", "npc.ii_impling_type_1_maze"),
                Charted(
                    "baby_impling_magicnet",
                    "npc.ii_impling_type_1_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("young_impling", "npc.ii_impling_type_2_maze"),
                Charted(
                    "young_impling_magicnet",
                    "npc.ii_impling_type_2_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("gourmet_impling", "npc.ii_impling_type_3_maze"),
                Charted(
                    "gourmet_impling_magicnet",
                    "npc.ii_impling_type_3_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("earth_impling", "npc.ii_impling_type_4_maze"),
                Charted(
                    "earth_impling_magicnet",
                    "npc.ii_impling_type_4_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("essence_impling", "npc.ii_impling_type_5_maze"),
                Charted(
                    "essence_impling_magicnet",
                    "npc.ii_impling_type_5_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("eclectic_impling", "npc.ii_impling_type_6_maze"),
                Charted(
                    "eclectic_impling_magicnet",
                    "npc.ii_impling_type_6_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("nature_impling", "npc.ii_impling_type_7_maze"),
                Charted(
                    "nature_impling_magicnet",
                    "npc.ii_impling_type_7_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("magpie_impling", "npc.ii_impling_type_8_maze"),
                Charted(
                    "magpie_impling_magicnet",
                    "npc.ii_impling_type_8_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("ninja_impling", "npc.ii_impling_type_9_maze"),
                Charted(
                    "ninja_impling_magicnet",
                    "npc.ii_impling_type_9_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("crystal_impling", "npc.ii_impling_type_12_johnny"),
                Charted(
                    "crystal_impling_magicnet",
                    "npc.ii_impling_type_12_johnny",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("dragon_impling", "npc.ii_impling_type_10_maze"),
                Charted(
                    "dragon_impling_magicnet",
                    "npc.ii_impling_type_10_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("lucky_impling", "npc.ii_impling_type_11_maze"),
                Charted(
                    "lucky_impling_magicnet",
                    "npc.ii_impling_type_11_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("black_warlock", "npc.butterfly_warlock"),
                Charted("sunlight_moth", "npc.moth_sunlight"),
                // The magic net's separate, faster curve, modelled as a flat bonus on both
                // coefficients rather than a second column pair. See `HunterButterfly.NET_BONUS`.
                Charted(
                    "black_warlock_magicnet",
                    "npc.butterfly_warlock",
                    HunterButterfly.NET_BONUS,
                ),
                Charted("sunlight_moth_magicnet", "npc.moth_sunlight", HunterButterfly.NET_BONUS),
            )

        /**
         * The published `{{Skilling success chart}}` parameters, keyed by page and series label.
         *
         * Series labels are the wiki's own. Note the inconsistency they carry: the black warlock and
         * moonlight moth pages group barehanded with the *magic* net, while the sunlight moth page
         * groups it with the *plain* net. Every impling page, and two of the three butterfly pages,
         * say barehanded matches the magic net, which is what `HunterButterfly.usesFasterCurve`
         * implements. The bait and smoke series on the deadfall pages are published too and are
         * deliberately unmapped - neither is modelled.
         */
        private val PUBLISHED =
            listOf(
                Published("Crimson swift", "Crimson swift", "npc.hunting_bird_jungle"),
                Published("Golden warbler", "Golden warbler", "npc.hunting_bird_desert"),
                Published("Copper longtail", "Copper longtail", "npc.hunting_bird_woodland"),
                Published("Cerulean twitch", "Cerulean twitch", "npc.hunting_bird_polar"),
                Published("Tropical wagtail", "Tropical wagtail", "npc.multicoloured_bird"),
                Published("Chinchompa (Hunter)", "Grey", "npc.hunting_chinchompa"),
                Published("Chinchompa (Hunter)", "Red", "npc.hunting_chinchompa_big"),
                Published("Chinchompa (Hunter)", "Black", "npc.hunting_chinchompa_black"),
                Published("Wild kebbit", "Wild kebbit", "npc.huntingbeast_claws"),
                Published("Barb-tailed kebbit", "Barb-tailed kebbit", "npc.huntingbeast_barbedtail"),
                Published("Prickly kebbit", "Prickly kebbit", "npc.huntingbeast_spiky"),
                Published(
                    "Sabre-toothed kebbit",
                    "Sabre-toothed kebbit",
                    "npc.huntingbeast_sabreteeth",
                ),
                Published("Pyre fox", "Pyre fox", "npc.varlamore_fennecfox"),
                Published("Net trap", "Swamp lizard", "npc.salamander_green"),
                Published("Net trap", "Orange salamander", "npc.salamander_orange"),
                Published("Net trap", "Red salamander", "npc.salamander_red"),
                Published("Net trap", "Black salamander", "npc.salamander_black"),
                Published("Net trap", "Tecu salamander", "npc.salamander_mountain"),
                Published("Imp", "Imp", "npc.imp"),
                Published("Spotted kebbit", "Spotted kebbit", "npc.huntingbeast_speedy"),
                Published("Dark kebbit", "Dark kebbit", "npc.huntingbeast_silent"),
                Published("Dashing kebbit", "Dashing kebbit", "npc.huntingbeast_speedy2"),
                Published("Baby impling", "Butterfly net", "npc.ii_impling_type_1_maze"),
                Published(
                    "Baby impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_1_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Young impling", "Butterfly net", "npc.ii_impling_type_2_maze"),
                Published(
                    "Young impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_2_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Gourmet impling", "Butterfly net", "npc.ii_impling_type_3_maze"),
                Published(
                    "Gourmet impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_3_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Earth impling", "Butterfly net", "npc.ii_impling_type_4_maze"),
                Published(
                    "Earth impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_4_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Essence impling", "Butterfly net", "npc.ii_impling_type_5_maze"),
                Published(
                    "Essence impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_5_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Eclectic impling", "Butterfly net", "npc.ii_impling_type_6_maze"),
                Published(
                    "Eclectic impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_6_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Nature impling", "Butterfly net", "npc.ii_impling_type_7_maze"),
                Published(
                    "Nature impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_7_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Magpie impling", "Butterfly net", "npc.ii_impling_type_8_maze"),
                Published(
                    "Magpie impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_8_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Ninja impling", "Butterfly net", "npc.ii_impling_type_9_maze"),
                Published(
                    "Ninja impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_9_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Crystal impling", "Butterfly net", "npc.ii_impling_type_12_johnny"),
                Published(
                    "Crystal impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_12_johnny",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Dragon impling", "Butterfly net", "npc.ii_impling_type_10_maze"),
                Published(
                    "Dragon impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_10_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Lucky impling", "Butterfly net", "npc.ii_impling_type_11_maze"),
                Published(
                    "Lucky impling",
                    "Barehanded or magic butterfly net",
                    "npc.ii_impling_type_11_maze",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Black warlock", "Butterfly net", "npc.butterfly_warlock"),
                Published(
                    "Black warlock",
                    "Barehanded or Magic butterfly net",
                    "npc.butterfly_warlock",
                    HunterButterfly.NET_BONUS,
                ),
                Published("Sunlight Moth", "Barehanded or butterfly net", "npc.moth_sunlight"),
                Published(
                    "Sunlight Moth",
                    "Magic butterfly net",
                    "npc.moth_sunlight",
                    HunterButterfly.NET_BONUS,
                ),
            )

        /**
         * Charted but deliberately not shipped; see
         * [theMoonlightMothIsNotOnTheOtherButterfliesCurve].
         */
        private val UNSHIPPED_SERIES = setOf("moonlight_moth", "moonlight_moth_magicnet")

        /** The five rows whose pair `HunterTables` annotates as a guess rather than a fit. */
        private val GUESSED_NPCS =
            setOf(
                "npc.hunting_ferret",
                "npc.varlamore_hunterjerboa01",
                "npc.butterfly_ruby",
                "npc.butterfly_glacialis",
                "npc.butterfly_snowy",
            )

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }
    }
}
