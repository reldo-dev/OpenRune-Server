package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * The 41-seed accept list, against the wiki's own table and against the packed cache.
 *
 * Two independent sources, neither of which is the shipped list:
 * - `wiki-charts/birdhouse-seeds.tsv` carries the wiki's *Bird house trapping > Seeds* table - every
 *   seed's display name and how many of it one house takes.
 * - the **packed obj** supplies each symbol's real `name`, which is what pins the symbol column. The
 *   symbols are the half most likely to be wrong, because several of them do not look like their
 *   names: `obj.hammerstone_hop_seed` is `"Hammerstone seed"`, `obj.redberry_bush_seed` is
 *   `"Redberry seed"`, and `obj.barley_seed` is a hop with no infix at all.
 *
 * Together those catch the failure this module has hit three times - a symbol assembled from a name,
 * resolving to nothing or to the wrong item - without ever comparing the table to itself.
 */
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class BirdHouseSeedsTest {
    @Test
    fun everyWikiSeedShipsWithTheRightUnitCost() {
        assertEquals(41, WIKI.size, "the wiki lists 41 seeds")
        assertEquals(WIKI.size, BirdHouseSeeds.all.size, "and all 41 ship")
        assertEquals(WIKI.map { it.symbol }.toSet(), BirdHouseSeeds.all.toSet())

        for (seed in WIKI) {
            val units = BirdHouseSeeds.unitsOf(seed.symbol)
            assertNotNull(units, "${seed.symbol} is not accepted")
            // Ten units to a house, so a seed the wiki says you need five of is worth two.
            val expected = BirdHouseSeeds.BIRDHOUSE_SEED_UNITS / seed.perHouse
            assertEquals(expected, units, "${seed.symbol} (${seed.name}) unit cost")
        }
    }

    /**
     * Eleven seeds at five per house, and Wildblood is the only one that is not a herb.
     *
     * Pinned because "herbs from ranarr up" is true of ten of the eleven, and is exactly the rule
     * someone would reach for if the set were ever rebuilt - silently mispricing the one hop seed.
     */
    @Test
    fun thereAreElevenHighValueSeedsAndOneOfThemIsAHop() {
        assertEquals(11, BirdHouseSeeds.highValue.size)
        assertEquals(30, BirdHouseSeeds.lowValue.size)
        assertEquals(11, WIKI.count { it.perHouse == 5 }, "the wiki agrees on the split")
        assertTrue("obj.wildblood_hop_seed" in BirdHouseSeeds.highValue)
        assertEquals(
            5,
            checkNotNull(WIKI.firstOrNull { it.symbol == "obj.wildblood_hop_seed" }).perHouse,
        )
    }

    /** Every symbol resolves to a packed obj whose own name is the one the wiki prints. */
    @Test
    fun everySeedSymbolResolvesToTheItemTheWikiNames() {
        for (seed in WIKI) {
            val obj =
                checkNotNull(ServerCacheManager.getItem(seed.symbol.asRSCM(RSCMType.OBJ))) {
                    "Missing packed obj: ${seed.symbol}"
                }
            assertEquals(seed.name, obj.name, "${seed.symbol} packed name")
            // Every accepted seed is stackable, which is what makes a ten-seed insert one slot.
            assertTrue(obj.isStackable, "${seed.symbol} must be stackable")
        }
    }

    /** High-value first, which is the order the greedy insert relies on. */
    @Test
    fun theCombinedListPutsHighValueSeedsFirst() {
        assertEquals(BirdHouseSeeds.highValue + BirdHouseSeeds.lowValue, BirdHouseSeeds.all)
        val firstLow = BirdHouseSeeds.all.indexOfFirst { BirdHouseSeeds.unitsOf(it) == 1 }
        assertEquals(BirdHouseSeeds.highValue.size, firstLow)
    }

    /**
     * The seeds the wiki does **not** list are refused.
     *
     * A negative control, and the reason it is worth having: the accepted set is "hop, herb, flower,
     * bush and allotment", and every one of these is a seed a player would plausibly try. Tree and
     * fruit-tree seeds, the special seeds and the spores are all absent from the wiki's table.
     */
    @Test
    fun seedsOutsideTheWikisTableAreRefused() {
        val refused =
            listOf(
                "obj.acorn",
                "obj.magic_tree_seed",
                "obj.papaya_tree_seed",
                "obj.belladonna_seed",
                "obj.mushroom_seed",
                "obj.seaweed_seed",
                "obj.grape_seed",
                "obj.celastrus_tree_seed",
                "obj.redwood_tree_seed",
            )
        for (seed in refused) {
            assertNull(BirdHouseSeeds.unitsOf(seed), "$seed must not be accepted")
        }
    }

    private data class WikiSeed(val symbol: String, val name: String, val perHouse: Int)

    companion object {
        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }

        /** The wiki's own seed table, checked in beside the catch-rate charts. */
        private val WIKI: List<WikiSeed> by lazy {
            val stream =
                checkNotNull(
                    BirdHouseSeedsTest::class.java.getResourceAsStream(
                        "/wiki-charts/birdhouse-seeds.tsv"
                    )
                ) {
                    "Missing the checked-in bird house seed extract."
                }
            stream.bufferedReader().useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("symbol\t") }
                    .map { line ->
                        val (symbol, name, amount) = line.split('\t')
                        WikiSeed(symbol, name, amount.toInt())
                    }
                    .toList()
            }
        }
    }
}
