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

    /**
     * The obj keys below are transcribed from the packed firemaking logs table's own `input(...)`
     * rows (`or-cache/.../skills/Firemaking.kt`), which is the independent source here: every one of
     * them is a real row, so the deadfall's "any type of log" would otherwise accept it and the
     * first-by-slot-order pick would destroy a clue step's log. Note the inconsistent naming in the
     * table - three are `<colour>_logs` and two are `trail_logs_<colour>` - which is exactly the
     * sort of thing a hand-written list gets wrong.
     */
    @Test
    fun `treasure trails logs are rejected`() {
        assertFalse(isUsableDeadfallLog("obj.blue_logs"))
        assertFalse(isUsableDeadfallLog("obj.green_logs"))
        assertFalse(isUsableDeadfallLog("obj.red_logs"))
        assertFalse(isUsableDeadfallLog("obj.trail_logs_purple"))
        assertFalse(isUsableDeadfallLog("obj.trail_logs_white"))
    }

    /**
     * The near-misses that keep the exclusion from being a substring match: `obj.redwood_logs` is
     * out but `obj.rosewood_logs` is a perfectly ordinary 92-firemaking log, and `obj.red_logs` is a
     * clue log while `obj.redwood_logs` is not one.
     */
    @Test
    fun `logs with similar names to the excluded ones are still accepted`() {
        assertTrue(isUsableDeadfallLog("obj.rosewood_logs"))
        assertTrue(isUsableDeadfallLog("obj.blisterwood_logs"))
        assertTrue(isUsableDeadfallLog("obj.magic_logs"))
    }
}
