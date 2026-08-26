package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * The five pitfall creatures, checked against sources this table did not produce itself.
 *
 * Levels are pinned twice over: once as an independent literal, and once against the cache's own
 * `skill_features` dbrows - the client's skill-guide data, not the wiki - which is exactly the kind
 * of second source `CLAUDE.md` asks for after a xp constant once shipped wrong because its test
 * literal was copied from the same rounded table the constant was.
 *
 * Serialised like the rest of the cache-touching suite: `ServerCacheManager` is a singleton and
 * `RSCM` memoises into a plain `HashMap`.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class PitfallCreaturesTest {
    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
    }

    @Test
    fun `five creatures ship, larupia through moonlight`() {
        assertEquals(
            listOf(
                PitfallCreatures.larupia,
                PitfallCreatures.graahk,
                PitfallCreatures.kyatt,
                PitfallCreatures.sunlight,
                PitfallCreatures.moonlight,
            ),
            PitfallCreatures.all,
        )
    }

    /**
     * Three of the five npc symbols share no substring with the creature's name - the same trap
     * that hid the pyre fox (`varlamore_fennecfox`) and the tropical wagtail
     * (`multicoloured_bird`). A table built by string surgery over the creature name resolves to a
     * name that does not exist and throws at first use rather than at boot.
     */
    @Test
    fun `every npc symbol is the cache's, not a guess from the creature's name`() {
        assertEquals("npc.hunting_jaguar", PitfallCreatures.larupia.npc)
        assertEquals("npc.hunting_leopard", PitfallCreatures.graahk.npc)
        assertEquals("npc.hunting_snow_tiger", PitfallCreatures.kyatt.npc)
        assertEquals("npc.sunlight_antelope", PitfallCreatures.sunlight.npc)
        assertEquals("npc.moonlight_antelope", PitfallCreatures.moonlight.npc)

        for (creature in PitfallCreatures.all) {
            assertNotNull(
                ServerCacheManager.getNpc(creature.npc.asRSCM(RSCMType.NPC)),
                "no packed npc for ${creature.npc}",
            )
        }
    }

    @Test
    fun `levels are 31, 41, 55, 72, 91`() {
        assertEquals(listOf(31, 41, 55, 72, 91), PitfallCreatures.all.map { it.level })
    }

    /**
     * The levels agree with the cache-native `skill_features` dbrows the client's own skill guide
     * renders from - `data=skill,23,<level>,8`, 23 being Hunter - which is a source the wiki did
     * not produce and could not have influenced.
     */
    @Test
    fun `each level matches the cache skill_features row for that creature`() {
        val expected =
            mapOf(
                "dbrow.skill_feature_hunter_jaguar" to 31,
                "dbrow.skill_feature_hunter_leopard" to 41,
                "dbrow.skill_feature_hunter_tiger" to 55,
                "dbrow.skill_feature_hunter_sun_antlers" to 72,
                "dbrow.skill_feature_hunter_moon_antlers" to 91,
            )
        val fromCache = expected.keys.map { hunterLevelOf(it) }
        assertEquals(expected.values.toList(), fromCache, "cache skill_features levels")
        assertEquals(fromCache, PitfallCreatures.all.map { it.level }, "shipped levels")
    }

    /** Xp is stored x10, so the packed values are ten times the wiki's 180/240/300/380/450. */
    @Test
    fun `xp is stored x10 and matches the wiki`() {
        assertEquals(listOf(1800, 2400, 3000, 3800, 4500), PitfallCreatures.all.map { it.xp })
    }

    /**
     * Both antelopes are a documented 100% catch and carry `null`, not `(256, 256)` or any other
     * always-true pair. A null is the honest encoding, and it is what lets the engine (a later
     * task) refuse to roll for them at all rather than roll a rate that always wins.
     */
    @Test
    fun `both antelopes carry a null success pair`() {
        assertNull(PitfallCreatures.sunlight.successLow)
        assertNull(PitfallCreatures.sunlight.successHigh)
        assertNull(PitfallCreatures.moonlight.successLow)
        assertNull(PitfallCreatures.moonlight.successHigh)
    }

    /**
     * The three cats carry the derivation file's literals verbatim - a derived guess, not a
     * published or fitted-from-chart pair.
     * See `.data/cache/wiki-hunter/pitfall-rate-derivation.md`.
     */
    @Test
    fun `the three cats carry the derivation file's pairs, and only the cats`() {
        assertEquals(53, PitfallCreatures.larupia.successLow)
        assertEquals(325, PitfallCreatures.larupia.successHigh)
        assertEquals(41, PitfallCreatures.graahk.successLow)
        assertEquals(289, PitfallCreatures.graahk.successHigh)
        assertEquals(24, PitfallCreatures.kyatt.successLow)
        assertEquals(237, PitfallCreatures.kyatt.successHigh)

        val nonNullPairs = PitfallCreatures.all.count { it.successLow != null }
        assertEquals(3, nonNullPairs, "only the three cats carry a pair")
    }

    /** Every loot obj this table names really exists in the packed cache, under that name. */
    @Test
    fun `every loot obj resolves to a packed item`() {
        for (creature in PitfallCreatures.all) {
            assertTrue(creature.loot.isNotEmpty(), "${creature.npc} must award at least one item")
            for (catch in creature.loot) {
                assertNotNull(
                    ServerCacheManager.getItem(catch.obj.asRSCM(RSCMType.OBJ)),
                    "no packed obj for ${catch.obj}",
                )
            }
        }
    }

    /** Every creature's leap sequence resolves; a bad symbol throws here rather than at boot. */
    @Test
    fun `every leap sequence resolves`() {
        for (creature in PitfallCreatures.all) {
            creature.leapSeq.asRSCM(RSCMType.SEQ)
        }
    }

    private fun hunterLevelOf(dbrow: String): Int {
        val row =
            ServerCacheManager.getDbrow(dbrow.asRSCM(RSCMType.DBROW))
                ?: error("No packed dbrow for $dbrow")
        val column = row.definedColumns()[SKILL_COLUMN] ?: error("$dbrow has no skill column")
        val values = column.values?.filterIsInstance<Int>() ?: error("No column values on $dbrow")
        val triples = values.chunked(3).filter { it.size == 3 }
        val match =
            triples.firstOrNull { it[0] == HUNTER_SKILL }
                ?: error("$dbrow carries no requirement for skill $HUNTER_SKILL (values=$values)")
        return match[1]
    }

    private companion object {
        /** The client skill enum's id for Hunter, as the cache `skill_features` rows use it. */
        private const val HUNTER_SKILL = 23

        /** `dbtable.skill_features` column ids: 3 is the requirement triples. */
        private const val SKILL_COLUMN = 3
    }
}
