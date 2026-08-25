package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * The bird house table, checked against sources outside itself.
 *
 * Three independent ones are available here, which is unusually good for this branch: the wiki's own
 * figures, the **client's skill guide** (the `skill_features` dbrows the client draws its Hunter and
 * Crafting guides from), and the **packed obj**, which carries each bird house's Crafting
 * requirement as `param_436`. Where a number can be checked against the cache it is, because that is
 * a different source from the wiki rather than the same one twice.
 */
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class BirdHouseTablesTest {
    @Test
    fun everyTierCarriesTheWikisLevelsAndExperience() {
        assertEquals(WIKI.size, BirdHouseTypes.all.size, "nine tiers ship")
        assertEquals(WIKI.map { it.obj }, BirdHouseTypes.all.map { it.obj }, "in tier order")

        for (entry in WIKI) {
            val type = checkNotNull(BirdHouseTypes.byObj(entry.obj)) { "No row for ${entry.obj}" }
            assertEquals(entry.hunterLevel, type.hunterLevel, "${entry.obj} Hunter level")
            assertEquals(entry.craftingLevel, type.craftingLevel, "${entry.obj} Crafting level")
            // Both stored x10, as every experience column in this module is.
            assertEquals(entry.hunterXp * 10, type.hunterXp, "${entry.obj} Hunter xp")
            assertEquals(entry.craftingXp * 10, type.craftingXp, "${entry.obj} Crafting xp")
            assertEquals(entry.logs, type.logs, "${entry.obj} logs")
        }
    }

    /**
     * The Crafting requirement agrees with the packed obj's own `param_436`.
     *
     * The strongest assertion here, because it compares the shipped table against the **cache**
     * rather than against the wiki - a different source, not a second reading of the same one. If
     * the two ever disagree the cache is what the client enforces.
     */
    @Test
    fun everyCraftingLevelAgreesWithThePackedObjParam() {
        for (type in BirdHouseTypes.all) {
            val obj =
                checkNotNull(ServerCacheManager.getItem(type.obj.asRSCM(RSCMType.OBJ))) {
                    "Missing packed obj: ${type.obj}"
                }
            val param = obj.paramsRaw?.get(CRAFTING_LEVEL_PARAM)
            assertEquals(
                type.craftingLevel,
                (param as? Int),
                "${type.obj} param_$CRAFTING_LEVEL_PARAM disagrees with the shipped Crafting level",
            )
        }
    }

    /**
     * The multiloc states are the chain's own, and they land where `(varp - 1) / 3` says.
     *
     * The table derives its three state indices from the packed chain rather than computing them.
     * This asserts the arithmetic anyway, so that the day the chain is reordered the failure lands
     * here - on a documented assumption - instead of on a player seeing an oak bird house where they
     * built a redwood one.
     */
    @Test
    fun everyTiersStatesAreThreeConsecutiveMultilocIndices() {
        BirdHouseTypes.all.forEachIndexed { tier, type ->
            assertEquals(tier * 3 + 1, type.builtState, "${type.obj} built state")
            assertEquals(tier * 3 + 2, type.fullState, "${type.obj} filling state")
            assertEquals(tier * 3 + 3, type.birdState, "${type.obj} full state")
        }
        assertEquals(27, BirdHouseTypes.all.last().birdState, "the chain ends at 27")
    }

    /** All four spaces are varp multilocs, and each reads its own varp. */
    @Test
    fun theFourSpacesEachReadTheirOwnVarp() {
        assertEquals(4, BirdHouseTypes.spaces.size)
        val varps = BirdHouseTypes.varps
        assertEquals(varps.toSet().size, varps.size, "each space must read a different varp")
        for (varp in varps) {
            assertTrue(varp > 0, "a bird house space must be a varp multiloc, not a varbit one")
        }
        // Perm is the default lifetime and is what makes a filling bird house survive a logout with
        // nothing authored server-side. If these ever became Temp the technique would silently
        // reset on every logout.
        for (varp in varps) {
            val type = checkNotNull(ServerCacheManager.getVarp(varp)) { "Missing varp $varp" }
            assertEquals(dev.openrune.types.varp.VarpLifetime.Perm, type.scope, "varp $varp lifetime")
        }
    }

    /** Every varp value from 0 to 27 means exactly one thing, and 0 means "bare space". */
    @Test
    fun everyVarpValueDecodesToOneState() {
        assertNull(BirdHouseTypes.byVarpValue(0), "0 is the bare space")
        assertNull(BirdHouseTypes.stateOf(0))

        val states = BirdHouseState.entries
        for (value in 1..27) {
            val type = checkNotNull(BirdHouseTypes.byVarpValue(value)) { "$value decodes to nothing" }
            val state = checkNotNull(BirdHouseTypes.stateOf(value))
            assertTrue(state in states)
            assertEquals(value, listOf(type.builtState, type.fullState, type.birdState)[state.ordinal])
        }
    }

    /**
     * The ten-roll nest rates are Mod Ash's published level-99 endpoints.
     *
     * The one column here that is not an ordinary published figure. Flagged rather than hidden: the
     * endpoints are sourced, the shape below 99 is not.
     */
    @Test
    fun theNestRatesAreThePublishedLevel99Endpoints() {
        for (entry in WIKI) {
            val type = checkNotNull(BirdHouseTypes.byObj(entry.obj))
            assertEquals(entry.nestPermille, type.nestPermille, "${entry.obj} nest rate at level 99")
        }
        assertTrue(
            BirdHouseTypes.all.map { it.nestPermille }.zipWithNext().all { it.first < it.second },
            "the nest rate should rise with tier",
        )
    }

    private data class WikiTier(
        val obj: String,
        val craftingLevel: Int,
        val craftingXp: Int,
        val hunterLevel: Int,
        val hunterXp: Int,
        val logs: String,
        val nestPermille: Int,
    )

    companion object {
        /** `param_436` on a bird house obj is its Crafting requirement. */
        private const val CRAFTING_LEVEL_PARAM = 436

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }

        /**
         * Transcribed from `Bird house trapping` and the nine scenery pages, independently of the
         * pack module. The level-99 nest rates are Mod Ash's, 5 June 2019, quoted on that page.
         */
        private val WIKI =
            listOf(
                WikiTier("obj.birdhouse_normal", 5, 15, 5, 280, "obj.logs", 100),
                WikiTier("obj.birdhouse_oak", 15, 20, 14, 420, "obj.oak_logs", 125),
                WikiTier("obj.birdhouse_willow", 25, 25, 24, 560, "obj.willow_logs", 128),
                WikiTier("obj.birdhouse_teak", 35, 30, 34, 700, "obj.teak_logs", 130),
                WikiTier("obj.birdhouse_maple", 45, 35, 44, 820, "obj.maple_logs", 140),
                WikiTier("obj.birdhouse_mahogany", 50, 40, 49, 960, "obj.mahogany_logs", 150),
                WikiTier("obj.birdhouse_yew", 60, 45, 59, 1020, "obj.yew_logs", 160),
                WikiTier("obj.birdhouse_magic", 75, 50, 74, 1140, "obj.magic_logs", 170),
                WikiTier("obj.birdhouse_redwood", 90, 55, 89, 1200, "obj.redwood_logs", 175),
            )
    }
}
