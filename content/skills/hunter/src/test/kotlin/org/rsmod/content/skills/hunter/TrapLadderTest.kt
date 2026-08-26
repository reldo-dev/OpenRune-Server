package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The shared trap-count ladder, pinned at every rung and at both sides of every boundary.
 *
 * No cache and no world: this is plain arithmetic over an Int, so it needs neither, and it is not
 * serialised against the rest of the suite for the same reason.
 *
 * The literals are the wiki's, not [TrapLadder]'s - nothing here reads the function back as its own
 * expected value. The callers keep their own cap tests (`PitfallLogicTest`, and
 * `HunterTrapOpsTest.a level-1 hunter can only lay one trap`), so a delegation wired to the wrong
 * ladder fails there even while this file stays green.
 */
class TrapLadderTest {
    @Test
    fun `one trap below level 20`() {
        assertEquals(1, TrapLadder.cap(1))
        assertEquals(1, TrapLadder.cap(19))
    }

    @Test
    fun `two traps from level 20`() {
        assertEquals(2, TrapLadder.cap(20))
        assertEquals(2, TrapLadder.cap(39))
    }

    @Test
    fun `three traps from level 40`() {
        assertEquals(3, TrapLadder.cap(40))
        assertEquals(3, TrapLadder.cap(59))
    }

    @Test
    fun `four traps from level 60`() {
        assertEquals(4, TrapLadder.cap(60))
        assertEquals(4, TrapLadder.cap(79))
    }

    @Test
    fun `five traps from level 80, including level 99`() {
        assertEquals(5, TrapLadder.cap(80))
        assertEquals(5, TrapLadder.cap(99))
    }

    /**
     * The crab trap's ladder is a different table and must stay one.
     *
     * It starts at 2 with no below-20 rung, because its lowest site is level 21. Folding it into
     * [TrapLadder] would give crab trapping a `1` its published table does not have, and the two
     * are close enough to look like a duplicate to the next reader.
     */
    @Test
    fun `the crab trap ladder is deliberately not this one`() {
        assertEquals(1, TrapLadder.cap(19))
        assertEquals(2, HunterCrabTrap.crabTrapCap(19))
    }
}
