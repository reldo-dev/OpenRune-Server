package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pure deadfall rules, tested without a cache. The log keys below are asserted against the
 * same predicate the set-trap path calls; `obj.arctic_pine_log` is deliberately the cache's
 * singular spelling.
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
     * The obj keys are transcribed from the packed firemaking table's own `input(...)` rows - the
     * independent source: each is a real row "any type of log" would otherwise sweep in.
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
