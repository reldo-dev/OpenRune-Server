package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * The spawn tier tables, checked against the rates the wiki publishes.
 *
 * *Impling* (oldid=15303398), *Types of spawn*, prints each tier as explicit numerators over an
 * explicit denominator. Those numbers are transcribed below independently of [ImplingSpawns] - if
 * the two ever disagree the table is wrong, not this file - which is the only thing that makes a
 * weighted table with four different denominators trustworthy.
 *
 * Mostly pure logic; only [everyRollableCreatureIsAShippedRow] reads the packed table, which is
 * why the cache is loaded and the shared lock taken.
 */
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class ImplingSpawnsTest {
    @Test
    fun everyTierMatchesThePublishedWeights() {
        for ((tier, published) in PUBLISHED) {
            val shipped = ImplingSpawns.table(tier).map { it.npc to it.weight }
            assertEquals(published, shipped, "$tier does not match the published weights")
        }
    }

    /**
     * Each tier's weights account for its whole denominator.
     *
     * The denominators genuinely differ - 100, 100, 101, 301 - so this is not a normalisation
     * check. A table summing short would leave [ImplingSpawns.roll] with rolls that fall past every
     * entry, and the fallback would quietly over-produce whichever creature happens to be last.
     */
    @Test
    fun everyTierSumsToItsPublishedTotal() {
        for ((tier, published) in PUBLISHED) {
            assertEquals(
                ImplingSpawns.totals.getValue(tier),
                published.sumOf { it.second },
                "$tier weights do not sum to its published total",
            )
            assertEquals(
                ImplingSpawns.totals.getValue(tier),
                ImplingSpawns.table(tier).sumOf { it.weight },
                "$tier shipped weights do not sum to its published total",
            )
        }
    }

    /** The two high tiers are the same five creatures weighted differently, not different sets. */
    @Test
    fun theTwoHighTiersHoldTheSameCreatures() {
        assertEquals(
            ImplingSpawns.table(ImplingTier.High).map { it.npc },
            ImplingSpawns.table(ImplingTier.HighPuroPuro).map { it.npc },
        )
        assertTrue(
            ImplingSpawns.table(ImplingTier.High) != ImplingSpawns.table(ImplingTier.HighPuroPuro),
            "The Puro-Puro high tier is weighted differently and should not be a copy.",
        )
    }

    /** Prifddinas is not a weighted choice: its one spawn point always produces a crystal. */
    @Test
    fun thePrifddinasTierAlwaysProducesTheCrystalImpling() {
        val table = ImplingSpawns.table(ImplingTier.Crystal)
        assertEquals(1, table.size)
        assertEquals("npc.ii_impling_type_12_johnny", table.single().npc)
    }

    /** Every creature a tier can roll has to be a shipped row, or the spawner produces nothing. */
    @Test
    fun everyRollableCreatureIsAShippedRow() {
        val overworldIds = ImplingCreatures.all.map { it.npcOverworld }.toSet()
        for (tier in ImplingTier.entries) {
            for (entry in ImplingSpawns.table(tier)) {
                assertTrue(
                    entry.npc in overworldIds,
                    "$tier can roll ${entry.npc}, which no creature row claims",
                )
            }
        }
    }

    /** A roll never falls through: every value in a tier's range maps to one of its entries. */
    @Test
    fun everyRollValueSelectsAnEntry() {
        for (tier in ImplingTier.entries) {
            val total = ImplingSpawns.totals.getValue(tier)
            val selected = (0 until total).map { ImplingSpawns.roll(tier, ScriptedRandom(nextInt = it)) }
            assertEquals(
                ImplingSpawns.table(tier).map { it.npc }.toSet(),
                selected.toSet(),
                "$tier does not reach every entry across its range",
            )
            for (entry in ImplingSpawns.table(tier)) {
                assertEquals(
                    entry.weight,
                    selected.count { it == entry.npc },
                    "${entry.npc} should occupy ${entry.weight} of $tier's $total",
                )
            }
        }
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }

        private val PUBLISHED =
            mapOf(
                ImplingTier.Low to
                    listOf(
                        "npc.ii_impling_type_1" to 20,
                        "npc.ii_impling_type_2" to 20,
                        "npc.ii_impling_type_3" to 20,
                        "npc.ii_impling_type_4" to 20,
                        "npc.ii_impling_type_5" to 10,
                        "npc.ii_impling_type_6" to 10,
                    ),
                ImplingTier.Mid to
                    listOf(
                        "npc.ii_impling_type_3" to 10,
                        "npc.ii_impling_type_4" to 10,
                        "npc.ii_impling_type_5" to 20,
                        "npc.ii_impling_type_6" to 37,
                        "npc.ii_impling_type_7" to 20,
                        "npc.ii_impling_type_8" to 2,
                        "npc.ii_impling_type_9" to 1,
                    ),
                ImplingTier.High to
                    listOf(
                        "npc.ii_impling_type_7" to 10,
                        "npc.ii_impling_type_8" to 50,
                        "npc.ii_impling_type_9" to 30,
                        "npc.ii_impling_type_10" to 10,
                        "npc.ii_impling_type_11" to 1,
                    ),
                ImplingTier.HighPuroPuro to
                    listOf(
                        "npc.ii_impling_type_7" to 150,
                        "npc.ii_impling_type_8" to 114,
                        "npc.ii_impling_type_9" to 27,
                        "npc.ii_impling_type_10" to 9,
                        "npc.ii_impling_type_11" to 1,
                    ),
                ImplingTier.Crystal to listOf("npc.ii_impling_type_12_johnny" to 1),
            )
    }
}
