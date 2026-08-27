package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.type.hasOp

/**
 * The packed crab-trapping data, checked against sources that are not the table: the cache's own
 * `skill_features` rows, the `multiLoc` arrays, the recolour pairings. Serialised like the rest of
 * the suite.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class CrabTrapTablesTest {
    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
    }

    @Test
    fun `three crabs ship, in dbrow order`() {
        val all = CrabCreatures.all
        assertEquals(3, all.size, "red, blue and rainbow")
        assertEquals(listOf(21, 48, 77), all.map { it.level }, "levels ascend with the dbrow ids")
    }

    /**
     * The levels are in the cache, not only on the wiki.
     *
     * `skill_feature_hunter_red_crab` (dbrow 11798) carries `data=skill,23,21,9` - skill 23 is
     * Hunter in the client's own skill enum, 21 is the level, 9 is the crab-trapping feature group -
     * and its two siblings carry 48 and 77. Reading them back out of the packed dbrow is a source
     * the table author could not have influenced.
     */
    @Test
    fun `each level matches the cache skill_features row for that crab`() {
        val expected =
            mapOf(
                "dbrow.skill_feature_hunter_red_crab" to 21,
                "dbrow.skill_feature_hunter_blue_crab" to 48,
                "dbrow.skill_feature_hunter_rainbow_crab" to 77,
            )
        val fromCache = expected.keys.map { hunterLevelOf(it) }
        assertEquals(expected.values.toList(), fromCache, "cache skill_features levels")
        assertEquals(fromCache, CrabCreatures.all.map { it.level }, "shipped levels")
    }

    /**
     * All three crabs carry the same Construction requirement, and it is the one the build gate
     * uses.
     *
     * Each hunter crab row carries a second requirement, `data=skill,22,10,-1` - skill 22 is
     * Construction, 10 is the level, and the `-1` means it belongs to no feature group of its own.
     * That is why it is a single build-time constant rather than a per-creature column.
     */
    @Test
    fun `the construction gate matches the cache skill_features rows`() {
        val rows =
            listOf(
                "dbrow.skill_feature_hunter_red_crab",
                "dbrow.skill_feature_hunter_blue_crab",
                "dbrow.skill_feature_hunter_rainbow_crab",
            )
        for (row in rows) {
            assertEquals(
                HunterCrabTrap.CRAB_TRAP_CONSTRUCTION_LEVEL,
                constructionLevelOf(row),
                "$row must require Construction ${HunterCrabTrap.CRAB_TRAP_CONSTRUCTION_LEVEL}",
            )
        }
    }

    /**
     * The crab a row hands over is the crab the cache illustrates that feature with.
     *
     * Column 0 of each `skill_features` row is an obj id, and for these three it is the crab itself.
     * Comparing it to the shipped reward catches a row that named the wrong colour's obj - or, for
     * the rainbow crab, one whose variant list starts at `b`.
     */
    @Test
    fun `each reward obj is the one the cache uses as that feature's icon`() {
        val expected =
            listOf(
                "dbrow.skill_feature_hunter_red_crab",
                "dbrow.skill_feature_hunter_blue_crab",
                "dbrow.skill_feature_hunter_rainbow_crab",
            )
        for ((dbrow, creature) in expected.zip(CrabCreatures.all)) {
            assertEquals(
                iconOf(dbrow),
                creature.variants.first().caught.asRSCM(RSCMType.OBJ),
                "$dbrow icon must be the crab that row yields",
            )
        }
    }

    /** Xp is stored x10, so the packed values are ten times the wiki's 64 / 136 / 216. */
    @Test
    fun `xp is stored x10 and matches the wiki overview table`() {
        assertEquals(listOf(640, 1360, 2160), CrabCreatures.all.map { it.xp })
    }

    /**
     * The bait a row names is the bait the site's own baited loc depicts.
     *
     * The pandemonium and great-conch sites resolve their baited state to `crab_trap_active` and the
     * crown-jewel sites to `crab_trap_active_fine_offcuts`. That split is in the cache's `multiLoc`
     * arrays, so it is an independent check on which crab takes which offcuts.
     */
    @Test
    fun `regular offcuts bait the plain baited loc and fine offcuts the fine one`() {
        val fineBaitedLoc = "loc.crab_trap_active_fine_offcuts".asRSCM(RSCMType.LOC)
        val plainBaitedLoc = "loc.crab_trap_active".asRSCM(RSCMType.LOC)

        for (site in CrabTrapSites.all) {
            val baitedLoc = site.childLocIds[site.baitedState]
            val expectedBait =
                if (baitedLoc == fineBaitedLoc) FINE_FISH_OFFCUTS else FISH_OFFCUTS
            assertEquals(
                expectedBait,
                site.creature.bait,
                "${site.loc} shows ${nameOf(baitedLoc)} so it must take $expectedBait",
            )
            assertTrue(
                baitedLoc == fineBaitedLoc || baitedLoc == plainBaitedLoc,
                "${site.loc} baited state must be one of the two baited locs",
            )
        }
    }

    /** Both bait items are stackable, which is what lets a full inventory keep baiting. */
    @Test
    fun `both baits are stackable`() {
        for (bait in listOf(FISH_OFFCUTS, FINE_FISH_OFFCUTS)) {
            val type = ServerCacheManager.getItem(bait.asRSCM(RSCMType.OBJ))
            assertNotNull(type, "no packed obj for $bait")
            assertTrue(type!!.isStackable, "$bait must be stackable")
        }
    }

    /**
     * The rainbow crab's two parallel columns list its colourways in the same order. The a/b/c
     * pairing itself is a recolour-table identity checkable only against the dump (the server pack
     * drops recolours); what is checkable here is index alignment in ascending id order - a
     * misordered column would hand the player a crab whose colour does not match the trap.
     */
    @Test
    fun `the rainbow crab is one creature in three colourways, listed in the same order`() {
        val rainbow = CrabCreatures.all.single { it.variants.size > 1 }
        assertEquals(3, rainbow.variants.size, "three colourways")

        val objs =
            rainbow.variants.map {
                ServerCacheManager.getItem(it.caught.asRSCM(RSCMType.OBJ))
                    ?: error("No packed obj for ${it.caught}")
            }
        assertEquals(listOf("Rainbow crab"), objs.map { it.name }.distinct(), "one crab, one name")
        assertEquals(1, objs.map { it.examine }.distinct().size, "one crab, one examine")
        assertEquals(1, objs.map { it.cost }.distinct().size, "one crab, one value")
        assertEquals(
            1,
            objs.map { it.paramsRaw }.distinct().size,
            "one crab, one raw-meat and paste pairing",
        )
        assertEquals(3, objs.map { it.id }.toSet().size, "three distinct objs all the same")

        val locs =
            rainbow.variants.map {
                ServerCacheManager.getObject(it.fullLoc.asRSCM(RSCMType.LOC))
                    ?: error("No packed loc for ${it.fullLoc}")
            }
        assertEquals(listOf("Crab trap (full)"), locs.map { it.name }.distinct())
        assertEquals(objs.map { it.id }.sorted(), objs.map { it.id }, "objs listed in id order")
        assertEquals(locs.map { it.id }.sorted(), locs.map { it.id }, "locs listed in id order")
    }

    /** Red and blue have exactly one colourway, so a variant roll never draws for them. */
    @Test
    fun `red and blue crabs have a single variant each`() {
        val single = CrabCreatures.all.filter { it.variants.size == 1 }
        assertEquals(2, single.size)
        assertEquals(
            listOf("obj.red_crab", "obj.blue_crab"),
            single.map { it.variants.single().caught },
        )
    }

    /** Sourced exactly: 15 ticks for red and blue, 25 for rainbow. */
    @Test
    fun `catch delays are the published tick counts`() {
        assertEquals(listOf(15, 15, 25), CrabCreatures.all.map { it.catchDelay })
    }

    /* Sites. */

    @Test
    fun `twenty sites resolve, five per location`() {
        val sites = CrabTrapSites.all
        assertEquals(20, sites.size, "four locations of five holes each")
        assertEquals(sites.indices.toList(), sites.map { it.index }, "index is list position")
        assertEquals(20, sites.map { it.locId }.toSet().size, "every site loc is distinct")
        assertEquals(20, sites.map { it.varbit.id }.toSet().size, "every site varbit is distinct")
    }

    /**
     * Which crab a site yields is read off the cache, never off the site's name.
     *
     * A site's `multiLoc` array *contains* the full-trap locs it can show, so the creature is
     * recovered by matching those against the table rather than by parsing `pandemonium` out of a
     * symbol. This asserts the recovery landed where the wiki says it should: red crabs on The
     * Pandemonium, blue on The Great Conch, rainbow on The Crown Jewel.
     */
    @Test
    fun `each site yields the crab its location is documented for`() {
        val byPrefix = CrabTrapSites.all.groupBy { it.loc.substringBeforeLast('_') }
        assertEquals(
            setOf(
                "loc.crab_trap_pandemonium",
                "loc.crab_trap_great_conch_north",
                "loc.crab_trap_great_conch_east",
                "loc.crab_trap_crown_jewel",
            ),
            byPrefix.keys,
        )
        for ((prefix, sites) in byPrefix) {
            assertEquals(5, sites.size, "$prefix has five holes")
            val expectedLevel =
                when (prefix) {
                    "loc.crab_trap_pandemonium" -> 21
                    "loc.crab_trap_crown_jewel" -> 77
                    else -> 48
                }
            for (site in sites) {
                assertEquals(expectedLevel, site.creature.level, "${site.loc} crab")
            }
        }
    }

    /**
     * A site's four (or six) states are the varbit values the client renders from.
     *
     * Every ordinal is looked up in the packed `multiLoc` rather than written down as 0/1/2/3,
     * because writing them down is exactly the kind of assumption that survives a cache update and
     * then shows the wrong model. The `fullStates` list is asserted parallel to the creature's
     * variants, since the two are indexed together at catch time.
     */
    @Test
    fun `every site's states index its own multiLoc, and full states track the variants`() {
        for (site in CrabTrapSites.all) {
            val children = site.childLocIds
            assertEquals(
                "loc.crab_trap_unbuilt",
                nameOf(children[site.unbuiltState]),
                "${site.loc} unbuilt",
            )
            assertEquals(
                "loc.crab_trap_built",
                nameOf(children[site.builtState]),
                "${site.loc} built",
            )
            assertEquals(
                site.creature.variants.size,
                site.fullStates.size,
                "${site.loc} needs one full state per colourway",
            )
            for ((i, state) in site.fullStates.withIndex()) {
                assertEquals(
                    site.creature.variants[i].fullLoc,
                    nameOf(children[state]),
                    "${site.loc} full state $i",
                )
            }
        }
    }

    /**
     * No state ordinal can address a padding slot.
     *
     * `multiLoc` arrays are padded with `65535`, and `LocInteractions.multiLoc` silently falls back
     * to the base loc when the resolved child is not a real loc type - which would leave the player
     * looking at an op-less site. Writing a value the varbit cannot hold is the other half of the
     * same failure, so the bit width is checked too.
     */
    @Test
    fun `every state ordinal resolves to a real loc and fits the site varbit`() {
        for (site in CrabTrapSites.all) {
            val states =
                listOf(site.unbuiltState, site.builtState, site.baitedState) + site.fullStates
            val maxValue = (1 shl (site.varbit.endBit - site.varbit.startBit + 1)) - 1
            for (state in states) {
                assertTrue(state in site.childLocIds.indices, "${site.loc} state $state in range")
                assertNotNull(
                    ServerCacheManager.getObject(site.childLocIds[state]),
                    "${site.loc} state $state must be a real loc, not padding",
                )
                assertTrue(state <= maxValue, "${site.loc} state $state must fit its varbit")
            }
        }
    }

    /**
     * The nine lifecycle locs carry the group the script registers on, and the op it dispatches.
     *
     * The two-declaration rule: `content.hunter_crab_trap` resolving to an id and each loc carrying
     * that id are independent, and a state missing its group is unclickable in exactly that state.
     * The twenty **site** locs must not carry it - they have no ops of their own, and giving the
     * parent a group would route a click that the multiloc child is meant to answer.
     */
    @Test
    fun `every crab lifecycle loc carries the content group and its Empty-or-Build op`() {
        for (loc in CrabTrapSites.lifecycleLocs) {
            val type =
                ServerCacheManager.getObject(loc.asRSCM(RSCMType.LOC))
                    ?: error("No packed loc definition for $loc")
            assertTrue(
                type.isContentType(CRAB_GROUP),
                "$loc must carry $CRAB_GROUP, has contentGroup=${type.contentGroup}",
            )
            assertTrue(type.hasOp(InteractionOp.Op1), "$loc must carry op1 (${type.actions})")
            assertFalse(type.hasOp(InteractionOp.Op2), "$loc draws no op2")
        }
        assertEquals(9, CrabTrapSites.lifecycleLocs.size, "four shared states plus five full ones")

        for (site in CrabTrapSites.all) {
            val type = checkNotNull(ServerCacheManager.getObject(site.locId))
            assertFalse(type.isContentType(CRAB_GROUP), "${site.loc} is a parent, not a state")
            assertFalse(type.hasOp(InteractionOp.Op1), "${site.loc} draws no op of its own")
        }
    }

    /** Every symbol the feature names really exists in the packed cache, under the right name. */
    @Test
    fun `every build material and reward resolves to the packed obj it is named for`() {
        val expected =
            mapOf(
                HunterCrabTrap.CRAB_TRAP_PLANK to "Plank",
                HunterCrabTrap.CRAB_TRAP_BUCKET to "Bucket",
                "obj.hammer" to "Hammer",
                "obj.poh_saw" to "Saw",
                FISH_OFFCUTS to "Fish offcuts",
                FINE_FISH_OFFCUTS to "Fine fish offcuts",
                "obj.red_crab" to "Red crab",
                "obj.blue_crab" to "Blue crab",
            )
        for ((obj, name) in expected) {
            val type = ServerCacheManager.getItem(obj.asRSCM(RSCMType.OBJ))
            assertNotNull(type, "no packed obj for $obj")
            assertEquals(name, type!!.name, "$obj name")
        }
    }

    /**
     * "two nails of any type" is eight real, distinct, stackable nail objs.
     *
     * `obj.any_nails` (32923) looks like the obvious answer and is **not** one: it is a
     * display-only duplicate of steel nails with no cost, no cert and no placeholder link, so a
     * player can never hold it. It is asserted absent from the accepted set so it cannot drift in.
     */
    @Test
    fun `every accepted nail is a real stackable nail obj and any_nails is not one`() {
        val nails = HunterCrabTrap.CRAB_TRAP_NAILS
        assertEquals(8, nails.size, "bronze through dragon, plus plain steel")
        assertEquals(nails.size, nails.toSet().size, "no duplicates")
        assertFalse("obj.any_nails" in nails, "any_nails is a display-only duplicate")

        for (obj in nails) {
            val type =
                ServerCacheManager.getItem(obj.asRSCM(RSCMType.OBJ))
                    ?: error("No packed obj for $obj")
            assertTrue(type.isStackable, "$obj must be stackable")
            assertTrue(type.name.endsWith("nails"), "$obj is named ${type.name}")
        }
    }

    private fun hunterLevelOf(dbrow: String): Int = skillRequirement(dbrow, HUNTER_SKILL)

    private fun constructionLevelOf(dbrow: String): Int =
        skillRequirement(dbrow, CONSTRUCTION_SKILL)

    /**
     * The level a `skill_features` row demands of [skill].
     *
     * Column 3 of `dbtable.skill_features` is a flat run of `(skill, level, group)` triples - a row
     * with two requirements stores six values, which is exactly what the three crab rows do - so the
     * skill is found by striding, not by index. Read straight off the packed dbrow, which is a
     * source this repo does not author.
     */
    private fun skillRequirement(dbrow: String, skill: Int): Int {
        val row =
            ServerCacheManager.getDbrow(dbrow.asRSCM(RSCMType.DBROW))
                ?: error("No packed dbrow for $dbrow")
        val column =
            row.definedColumns()[SKILL_COLUMN] ?: error("$dbrow has no skill column")
        val values =
            column.values?.filterIsInstance<Int>() ?: error("No column values on $dbrow")
        val triples = values.chunked(3).filter { it.size == 3 }
        val match =
            triples.firstOrNull { it[0] == skill }
                ?: error("$dbrow carries no requirement for skill $skill (values=$values)")
        return match[1]
    }

    /**
     * The obj a `skill_features` row is illustrated with, which for these three is the crab itself.
     *
     * Column 0 of the same row. It is a second, independent statement of which item a crab yields -
     * the icons are 31671, 31674 and 31677, i.e. `red_crab`, `blue_crab` and `rainbow_crab_a` - and
     * so it can be checked against the reward column without going near the table that set it.
     */
    private fun iconOf(dbrow: String): Int {
        val row =
            ServerCacheManager.getDbrow(dbrow.asRSCM(RSCMType.DBROW))
                ?: error("No packed dbrow for $dbrow")
        val column = row.definedColumns()[ICON_COLUMN] ?: error("$dbrow has no icon column")
        return column.values?.filterIsInstance<Int>()?.single() ?: error("No icon on $dbrow")
    }

    private fun nameOf(locId: Int): String? =
        dev.openrune.rscm.RSCM.getReverseMapping(RSCMType.LOC, locId)

    private companion object {
        private const val CRAB_GROUP = "content.hunter_crab_trap"
        private const val FISH_OFFCUTS = "obj.brut_fish_cuts"
        private const val FINE_FISH_OFFCUTS = "obj.sailing_fine_fish_offcuts"

        /** The client skill enum's ids, as the cache `skill_features` rows use them. */
        private const val HUNTER_SKILL = 23
        private const val CONSTRUCTION_SKILL = 22

        /** `dbtable.skill_features` column ids: 0 is the icon obj, 3 the requirement triples. */
        private const val ICON_COLUMN = 0
        private const val SKILL_COLUMN = 3
    }
}
