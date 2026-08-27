package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrackingCreaturesTest {
    /* Literals read off each creature's **own** page - the "Hunter info" box, which
     * carries the level and the xp - and NOT back off the constants under test: a test
     * that reads the constant moves with a bad edit and asserts nothing.
     *
     * Per-creature pages rather than a summary table, because the summary tables round.
     * That is precisely how the razor-backed kebbit shipped at 348 instead of 348.5: the
     * constant and the literal checking it were both taken from the same rounded source,
     * so the test moved with the bug. Polar (oldid 15196366), common (15161155), feldip
     * weasel (15196280), desert devil (15196431), razor-backed (15196430). xp is stored
     * x10, the repo convention. */
    @Test
    fun `five creatures pin their wiki levels and xp`() {
        assertEquals(5, TrackingCreatures.all.size)
        assertEquals(1, TrackingCreatures.polar.level)
        assertEquals(300, TrackingCreatures.polar.xp)
        assertEquals(3, TrackingCreatures.common.level)
        assertEquals(360, TrackingCreatures.common.xp)
        assertEquals(7, TrackingCreatures.feldipWeasel.level)
        assertEquals(480, TrackingCreatures.feldipWeasel.xp)
        assertEquals(13, TrackingCreatures.desertDevil.level)
        assertEquals(660, TrackingCreatures.desertDevil.xp)
        assertEquals(49, TrackingCreatures.razorBacked.level)
        // 348.5, the only fractional award of the five and the one the summary tables round.
        assertEquals(3485, TrackingCreatures.razorBacked.xp)
    }

    @Test
    fun `loot and anim gamevals are the biome-suffixed cache symbols`() {
        assertEquals("obj.huntingbeast_polar_fur", TrackingCreatures.polar.fur)
        assertEquals("seq.hunting_noose_polar", TrackingCreatures.polar.catchSeq)
        assertEquals("obj.huntingbeast_woodland_fur", TrackingCreatures.common.fur)
        assertEquals("seq.hunting_noose_wood", TrackingCreatures.common.catchSeq)
        assertEquals("obj.huntingbeast_jungle_fur", TrackingCreatures.feldipWeasel.fur)
        assertEquals("seq.hunting_noose_jungle", TrackingCreatures.feldipWeasel.catchSeq)
        assertEquals("obj.huntingbeast_desert_fur", TrackingCreatures.desertDevil.fur)
        assertEquals("seq.hunting_noose_desert", TrackingCreatures.desertDevil.catchSeq)
        assertEquals("obj.huntingbeast_bigspike", TrackingCreatures.razorBacked.fur)
        assertEquals("seq.hunting_noose_razorback", TrackingCreatures.razorBacked.catchSeq)
    }
}
