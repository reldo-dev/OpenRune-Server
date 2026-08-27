package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * The bird house table, checked against sources outside itself.
 *
 * Three independent ones are available here, which is unusually good: the wiki's own figures, the
 * **client's skill guide** (the `skill_features` dbrows the client draws its Hunter and Crafting
 * guides from), and the **packed obj**, which carries each bird house's Crafting requirement as
 * `param_436`. Where a number can be checked against the cache it is, because that is a different
 * source from the wiki rather than the same one twice.
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
        assertEquals(4, BirdHouseSpaces.all.size)
        val varps = BirdHouseSpaces.all.map { it.varp.id }
        assertEquals(varps.toSet().size, varps.size, "each space must read a different varp")
        for (varp in varps) {
            assertTrue(varp > 0, "a bird house space must be a varp multiloc, not a varbit one")
        }
        // Perm is the default lifetime and is what makes a filling bird house survive a logout with
        // nothing authored server-side. If these ever became Temp the technique would silently
        // reset on every logout.
        for (space in BirdHouseSpaces.all) {
            assertEquals(
                dev.openrune.types.varp.VarpLifetime.Perm,
                space.varp.scope,
                "${space.loc} reads varp ${space.varp.id}, whose lifetime",
            )
        }
    }

    /**
     * All four spaces carry the same multiloc chain.
     *
     * [BirdHouseTypes] reads the chain from the first space and applies it to all four, which is
     * only sound if they agree. They do - the packed lists are byte-identical - but that is a fact
     * about the cache and not a guarantee, so it is asserted rather than assumed. A cache update
     * that reordered one space's chain would otherwise show the wrong tier at that space alone.
     */
    @Test
    fun everySpaceCarriesTheSameMultilocChain() {
        val chains =
            BirdHouseSpaces.all.map { space ->
                val type =
                    checkNotNull(ServerCacheManager.getObject(space.locId)) {
                        "Missing loc: ${space.loc}"
                    }
                type.multiLoc.map { it and 0xFFFF }
            }
        for ((index, chain) in chains.withIndex()) {
            assertEquals(chains.first(), chain, "${BirdHouseSpaces.all[index].loc} multiloc chain")
        }
        // 29, not 28: indices 0..27 are the real children and index 28 is the 65535 "no loc"
        // sentinel. Pinned because a length check written against 28 would fail for the wrong
        // reason, and because `stateOf` searching for a child by id is what makes the tail harmless.
        assertEquals(29, chains.first().size, "the chain is 28 children plus a sentinel")
        assertEquals(65535, chains.first().last(), "the last entry is the no-loc sentinel")
    }

    /**
     * Every one of the 28 children carries the bird house content group.
     *
     * The other half of the two-declaration rule, and the exact gap that once left a collapsed box
     * trap unclearable. A group missing from one state makes the bird house unclickable in that
     * state alone, which no test of the handler bodies could see.
     */
    @Test
    fun everyChildLocCarriesTheContentGroup() {
        val children = birdHouseChain().dropLast(1)
        assertEquals(28, children.size)
        for (child in children) {
            val type = checkNotNull(ServerCacheManager.getObject(child)) { "Missing loc $child" }
            assertTrue(
                type.isContentType(BIRD_HOUSE_GROUP),
                "loc $child (${type.name}) must carry $BIRD_HOUSE_GROUP, " +
                    "has contentGroup=${type.contentGroup}",
            )
        }
        // The spaces themselves must NOT carry it. They have no ops at all, and `opTrigger` resolves
        // the child before it looks for a handler, so a group on the parent would be dead weight
        // that reads as if it were doing the routing.
        for (parent in BirdHouseSpaces.all) {
            val type = checkNotNull(ServerCacheManager.getObject(parent.locId))
            assertFalse(type.isContentType(BIRD_HOUSE_GROUP), "${parent.loc} must not carry it")
        }
    }

    /**
     * The op grid the four handlers are registered against, read off the packed children.
     *
     * This is the assertion that justifies **one** content group: the op indices line up across the
     * states even though the labels do not. If a cache update ever moved `Seeds` off op2 or `Reset`
     * off op4, the handlers would keep registering and the feature would go quiet.
     */
    @Test
    fun theOpGridIsTheOneTheHandlersAssume() {
        val chain = birdHouseChain()

        assertOps(chain[0], listOf("Build", null, null, null), "the bare space")
        for (type in BirdHouseTypes.all) {
            assertOps(chain[type.builtState], listOf(null, "Seeds", null, null), "${type.obj} built")
            assertOps(
                chain[type.fullState],
                listOf(null, "Seeds", "Dismantle", null),
                "${type.obj} filling",
            )
            assertOps(
                chain[type.birdState],
                listOf("Interact", "Seeds", "Empty", "Reset"),
                "${type.obj} full",
            )
        }
    }

    private fun assertOps(locId: Int, expected: List<String?>, label: String) {
        val type = checkNotNull(ServerCacheManager.getObject(locId)) { "Missing loc $locId" }
        // Absent is normalised the way `LocTypeExt.hasOp` normalises it - blank and `hidden` both
        // mean "no op" - so this asserts exactly what the dispatcher would see, not the raw slot.
        val actual =
            expected.indices.map { slot ->
                type.actions.getOpOrNull(slot)?.takeUnless {
                    it.isBlank() || it.equals("hidden", ignoreCase = true)
                }
            }
        assertEquals(expected, actual, "$label (loc $locId) ops")
    }

    /** The 29-entry multiloc chain every space carries, masked the way `LocInteractions` masks it. */
    private fun birdHouseChain(): List<Int> {
        val space = BirdHouseSpaces.all.first()
        val type =
            checkNotNull(ServerCacheManager.getObject(space.locId)) { "Missing loc: ${space.loc}" }
        return type.multiLoc.map { it and 0xFFFF }
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

        private const val BIRD_HOUSE_GROUP = "content.hunter_bird_house"

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
