package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [PitState] and [PitfallLogic.maxTraps], tested without a cache: both are plain arithmetic over an
 * `Int`, so nothing here needs a packed table, gameval or game world to run.
 *
 * Every state and every ladder boundary is asserted against a **literal**, never against the
 * constant or property under test read back as its own expected value - this module has already
 * shipped two vacuous tests shaped exactly like that, against bird house `NEST_ROLLS` and against
 * `Controller.duration`, where mutating the real value left the test green because it moved with
 * the mutation.
 */
class PitfallLogicTest {
    /* PitState <-> varbitValue, both directions, off the cache's own multiloc children. */

    @Test
    fun `varbit 0 is the empty pit`() {
        assertEquals(PitState.Empty, PitState.of(0))
        assertEquals(0, PitState.Empty.varbitValue)
    }

    @Test
    fun `varbit 1 is the armed, spiked pit`() {
        assertEquals(PitState.Set, PitState.of(1))
        assertEquals(1, PitState.Set.varbitValue)
    }

    @Test
    fun `varbit 2 is the transient collapsing trap`() {
        assertEquals(PitState.Catching, PitState.of(2))
        assertEquals(2, PitState.Catching.varbitValue)
    }

    @Test
    fun `varbit 3 is a full pit`() {
        assertEquals(PitState.Full, PitState.of(3))
        assertEquals(3, PitState.Full.varbitValue)
    }

    @Test
    fun `varbit 4 is a full pit rotated 180 degrees`() {
        assertEquals(PitState.FullRotated, PitState.of(4))
        assertEquals(4, PitState.FullRotated.varbitValue)
    }

    /**
     * The varbit is three bits, so 5, 6 and 7 are representable even though the cache defines no
     * multiloc child for any of them. [PitState.of] throws rather than defaulting to
     * [PitState.Empty] - see the KDoc on [PitState.of] for why a silent fallback here is wrong.
     */
    @Test
    fun `values with no multiloc child throw rather than mapping silently`() {
        assertThrows(IllegalArgumentException::class.java) { PitState.of(5) }
        assertThrows(IllegalArgumentException::class.java) { PitState.of(6) }
        assertThrows(IllegalArgumentException::class.java) { PitState.of(7) }
    }

    /* The trap-count ladder: 1 below 20, 2 at 20, 3 at 40, 4 at 60, 5 at 80 (wiki, Pitfall,
     * oldid=15201220). Every boundary is pinned along with the value immediately either side of
     * it. */

    @Test
    fun `one trap below level 20`() {
        assertEquals(1, PitfallLogic.maxTraps(1))
        assertEquals(1, PitfallLogic.maxTraps(19))
    }

    @Test
    fun `two traps from level 20`() {
        assertEquals(2, PitfallLogic.maxTraps(20))
        assertEquals(2, PitfallLogic.maxTraps(39))
    }

    @Test
    fun `three traps from level 40`() {
        assertEquals(3, PitfallLogic.maxTraps(40))
        assertEquals(3, PitfallLogic.maxTraps(59))
    }

    @Test
    fun `four traps from level 60`() {
        assertEquals(4, PitfallLogic.maxTraps(60))
        assertEquals(4, PitfallLogic.maxTraps(79))
    }

    @Test
    fun `five traps from level 80, including level 99`() {
        assertEquals(5, PitfallLogic.maxTraps(80))
        assertEquals(5, PitfallLogic.maxTraps(99))
    }
}
