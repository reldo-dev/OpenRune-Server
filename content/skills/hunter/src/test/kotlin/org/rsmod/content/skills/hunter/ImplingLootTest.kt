package org.rsmod.content.skills.hunter

import dtx.core.Single
import dtx.rs.RSDropTable
import dtx.rs.RSPreRollTable
import dtx.rs.RSWeightedTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.droptable.DropRollItem
import org.rsmod.game.entity.Player

/**
 * The impling loot tables, checked against the published rates rather than against themselves.
 *
 * `ImplingLoot` is a 121-row transcription of what the wiki publishes under Jagex's own
 * "the following drop rates are provided by Jagex" banner. A transcription that size is only
 * trustworthy if something compares it to its source, so every row below is matched against
 * `wiki-charts/impling-loot.tsv` - item, quantity range and rarity - and the match is required to
 * be exact in both directions, so neither a dropped row nor an invented one survives.
 *
 * Nothing here needs a world, a cache or a player, so unlike the rest of the module's tests it takes
 * no lock and loads nothing.
 */
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class ImplingLootTest {
    @Test
    fun everyMainTableRowMatchesThePublishedRate() {
        var checked = 0
        for ((impling, jar) in JARS) {
            val published =
                readLoot()
                    .filter { it.impling == impling && it.section == "main" && it.obj.isNotEmpty() }
                    .map { Triple(it.obj, it.min..it.max, it.weightOutOf100()) }
            val shipped = mainRows(jar)
            // Sorted as whole rows, not by obj: essence carries two noted `Pure essence` rows
            // (20 and 35) that share one obj, so an obj-only sort is not a total order and the
            // two lists can hold the same rows in different orders.
            assertEquals(
                published.map(Triple<String, IntRange, Int>::toString).sorted(),
                shipped.map(Triple<String, IntRange, Int>::toString).sorted(),
                "$impling main table does not match the published rows",
            )
            checked += shipped.size
        }
        // 113 published main rows less the baby impling's objless `Nothing`, which
        // [onlyTheBabyImplingCanPayOutNothing] covers instead.
        assertEquals(112, checked, "Main-table row count changed; confirm the extract is intact.")
    }

    /** Each table is written out of 100, so the weights must account for the whole roll. */
    @Test
    fun everyMainTableWeightSumsToTheWholeRoll() {
        for ((impling, jar) in JARS) {
            // Every entry, the nothing slot included - it is a tenth of the baby table, so
            // excluding it would make that table look like it summed to 90 and pass anyway.
            assertEquals(
                100,
                weightedEntries(jar).sumOf { it.first },
                "$impling weights do not sum to 100",
            )
        }
    }

    /**
     * Only the baby impling carries a nothing slot, and it is a real 1/10 row.
     *
     * *Baby impling* trivia (oldid=15297388): "This is the only impling which has a chance to give
     * nothing when successfully caught." Asserted in both directions, because the failure that
     * matters is a nothing slot silently appearing in one of the other five and eating a tenth of
     * their rewards.
     */
    @Test
    fun onlyTheBabyImplingCanPayOutNothing() {
        for ((impling, jar) in JARS) {
            val nothings = weightedEntries(jar).filter { it.second.isNothing }
            if (impling == "baby") {
                assertEquals(1, nothings.size, "baby should carry exactly one nothing slot")
                assertEquals(10, nothings.single().first, "baby's nothing slot is published as 1/10")
            } else {
                assertTrue(nothings.isEmpty(), "$impling must not pay out nothing")
            }
        }
    }

    /** The tertiary rolls - clue scrolls - are independent of the main roll and rated separately. */
    @Test
    fun everyTertiaryMatchesThePublishedRate() {
        var checked = 0
        for ((impling, jar) in JARS) {
            val published =
                readLoot().filter { it.impling == impling && it.section == "tertiary" }
            val shipped = chanceRows(jar, TERTIARY_INDEX)
            assertEquals(
                published.size,
                shipped.size,
                "$impling has ${shipped.size} tertiary rows, published has ${published.size}",
            )
            for (row in published) {
                val match =
                    shipped.singleOrNull { objSuffix(row.item) in it.second.obj }
                        ?: error("$impling has no tertiary for ${row.item}")
                assertEquals(
                    row.num * 100.0 / row.den,
                    match.first,
                    1e-9,
                    "$impling ${row.item} is published at ${row.num}/${row.den}",
                )
            }
            checked += shipped.size
        }
        assertEquals(8, checked, "Tertiary row count changed; confirm the extract is intact.")
    }

    /**
     * The gourmet impling's Grubby key is a pre-roll, and only the gourmet has one.
     *
     * It has its own section on both the creature and the jar page, above the standard table. Were
     * it folded into the main table its 1/500 would push that table's rarity sum to 1.002, and were
     * it a tertiary it would be rolled in addition to the main item rather than instead of it.
     */
    @Test
    fun onlyTheGourmetImplingHasAPreRoll() {
        for ((impling, jar) in JARS) {
            val preRoll = chanceRows(jar, PRE_ROLL_INDEX)
            if (impling == "gourmet") {
                assertEquals(1, preRoll.size, "gourmet should carry exactly one pre-roll")
            } else {
                assertTrue(preRoll.isEmpty(), "$impling must not carry a pre-roll")
            }
        }
        assertEquals(
            1,
            readLoot().count { it.section == "preroll" },
            "The extract should carry exactly one pre-roll row.",
        )
    }

    /** Every jar this slice ships has a table, and every table belongs to a shipped jar. */
    @Test
    fun everyShippedJarHasATable() {
        assertEquals(JARS.map { it.second }.toSet(), ImplingLoot.jars)
        val caught = ImplingCreatures.all.map { it.caught.single().obj }.toSet()
        assertEquals(caught, ImplingLoot.jars, "A caught jar with no table would be unopenable.")
    }

    private fun table(jar: String): RSDropTable<Player, DropRollItem> =
        checkNotNull(ImplingLoot.forJar(jar)) { "No loot table for $jar" }

    private fun weightedEntries(jar: String): List<Pair<Int, DropRollItem>> {
        val main =
            table(jar).tableEntries.filterIsInstance<RSWeightedTable<Player, DropRollItem>>().single()
        return main.tableEntries.map { entry ->
            val drop = (entry.rollable as Single<Player, DropRollItem>).result
            entry.weight.toInt() to drop
        }
    }

    private fun mainRows(jar: String): List<Triple<String, IntRange, Int>> =
        weightedEntries(jar).filterNot { it.second.isNothing }.map { (weight, drop) ->
            Triple(drop.obj, drop.count, weight)
        }

    /**
     * The pre-roll or tertiary slot as (chance-percent, drop) pairs.
     *
     * Both slots are `RSPreRollTable`s of `ChanceRollable`, which carries the rate as a percentage
     * rather than the `1 outOf n` the table is written with, so the expected value is converted the
     * same way at the call site.
     */
    private fun chanceRows(jar: String, index: Int): List<Pair<Double, DropRollItem>> {
        val slot = table(jar).tableEntries.toList()[index]
        if (slot !is RSPreRollTable<*, *>) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val entries = (slot as RSPreRollTable<Player, DropRollItem>).tableEntries
        return entries.mapNotNull { entry ->
            val drop = (entry.rollable as? Single<Player, DropRollItem>)?.result ?: return@mapNotNull null
            entry.chance to drop
        }
    }

    private data class LootRow(
        val impling: String,
        val item: String,
        val min: Int,
        val max: Int,
        val num: Int,
        val den: Int,
        val section: String,
        val obj: String,
    ) {
        /** The published rarity as a weight out of 100, which is how the tables are written. */
        fun weightOutOf100(): Int {
            val scaled = num * 100
            require(scaled % den == 0) { "$item's rarity $num/$den is not a whole weight out of 100" }
            return scaled / den
        }
    }

    private fun readLoot(): List<LootRow> =
        checkNotNull(javaClass.getResourceAsStream("/wiki-charts/impling-loot.tsv")) {
                "Missing /wiki-charts/impling-loot.tsv"
            }
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val f = line.split("\t")
                require(f.size == 8) { "Malformed loot row: $line" }
                LootRow(
                    f[0],
                    f[1],
                    f[2].toInt(),
                    f[3].toInt(),
                    f[4].toInt(),
                    f[5].toInt(),
                    f[6],
                    f[7],
                )
            }

    /** Clue rows name one specific step obj, so match on the tier rather than the exact step. */
    private fun objSuffix(item: String): String =
        when {
            "beginner" in item.lowercase() -> "beginner"
            "easy" in item.lowercase() -> "easy"
            "medium" in item.lowercase() -> "medium"
            else -> item
        }

    companion object {
        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }

        /** `RSDropTable` builds its entries as guaranteed, preRoll, separateRolls, main, tertiary. */
        private const val PRE_ROLL_INDEX = 1
        private const val TERTIARY_INDEX = 4

        private val JARS =
            listOf(
                "baby" to "obj.ii_captured_impling_1",
                "young" to "obj.ii_captured_impling_2",
                "gourmet" to "obj.ii_captured_impling_3",
                "earth" to "obj.ii_captured_impling_4",
                "essence" to "obj.ii_captured_impling_5",
                "eclectic" to "obj.ii_captured_impling_6",
            )
    }
}
