package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two pure deadfall rules, tested without a cache: everything either helper touches is a plain
 * string, so no packed table, gameval or game world has to exist for these to run.
 *
 * [isUsableDeadfallLog] is the filter the game applies to the packed firemaking logs table, not a
 * hardcoded log list - the log keys below are asserted against the same predicate the set-trap path
 * calls, and `obj.arctic_pine_log` is deliberately the cache's singular spelling.
 */
class DeadfallLogicTest {
    private val creature =
        HunterCreature(
            family = TrapFamily.DEADFALL,
            npc = "npc.huntingbeast_claws",
            level = 23,
            xp = 1280,
            caught = listOf(HunterCatch("obj.bones")),
            successLow = 0,
            successHigh = 0,
            trappingLoc = "loc.hunting_deadfall_trapping_claw",
            trappingLocM = "loc.hunting_deadfall_trapping_claw_m",
            fullLoc = "loc.hunting_deadfall_full_claw",
        )

    @Test
    fun `approach from west or south picks the mirrored loc`() {
        assertEquals(creature.trappingLocM, deadfallApproachLoc(creature, dx = -1, dy = 0))
        assertEquals(creature.trappingLocM, deadfallApproachLoc(creature, dx = 0, dy = -1))
    }

    @Test
    fun `approach from east, north or same tile picks the base loc`() {
        assertEquals(creature.trappingLoc, deadfallApproachLoc(creature, dx = 1, dy = 0))
        assertEquals(creature.trappingLoc, deadfallApproachLoc(creature, dx = 0, dy = 1))
        assertEquals(creature.trappingLoc, deadfallApproachLoc(creature, dx = 0, dy = 0))
    }

    @Test
    fun `redwood and arctic pine logs are rejected, ordinary and yew accepted`() {
        assertTrue(isUsableDeadfallLog("obj.logs"))
        assertTrue(isUsableDeadfallLog("obj.yew_logs"))
        assertFalse(isUsableDeadfallLog("obj.redwood_logs"))
        assertFalse(isUsableDeadfallLog("obj.arctic_pine_log"))
    }
}
