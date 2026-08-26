package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.rsmod.map.CoordGrid

class TrackingTrailTest {
    /* A synthetic diamond network. Coordinates are arbitrary; only the graph matters.
     *
     *   origin O(0,0) -- s0 -- A(0,1) -- s1 -- END(0,2)
     *                \-- s2 -- B(1,1) -- s3 -- END(0,2)
     *   plus s4: A -- B (a rung, allows a 3-step path O-A-B-END via s0,s4,s3)
     */
    private val o = CoordGrid(0, 0, 0, 0, 0)
    private val a = CoordGrid(0, 0, 0, 0, 1)
    private val b = CoordGrid(0, 0, 0, 1, 1)
    private val end = CoordGrid(0, 0, 0, 0, 2)

    private fun seg(i: Int, from: CoordGrid, to: CoordGrid) =
        TrailSegment("varbit.s$i", "loc.c$i", from, from, to)

    private val network =
        TrackingNetwork(
            area = "test_area",
            creature = TrackingCreatures.polar,
            segments = listOf(seg(0, o, a), seg(1, a, end), seg(2, o, b), seg(3, b, end), seg(4, a, b)),
            burrows = listOf(TrailBurrow("loc.burrow", o, o)),
            catchSpots = listOf(TrailCatchSpot("loc.end", end)),
        )

    @Test
    fun `enumeration finds exactly the four valid trails`() {
        val trails = TrailLogic.enumerate(network, o)
        // 2-step: s0+s1, s2+s3. 3-step: s0+s4+s3, s2+s4(reversed)+s1.
        assertEquals(4, trails.size)
        assertTrue(trails.all { it.size in 2..3 })
        assertTrue(trails.all { it.last().to == end })
        // No trail reuses a segment.
        assertTrue(trails.all { t -> t.map { it.segment.varbit }.toSet().size == t.size })
        // Steps chain: each step starts where the previous ended.
        assertTrue(trails.all { t -> t.zipWithNext().all { (x, y) -> x.to == y.from } })
    }

    @Test
    fun `reversed steps swap from and to`() {
        val s = seg(0, a, b)
        assertEquals(a, TrailStep(s, reversed = false).from)
        assertEquals(b, TrailStep(s, reversed = false).to)
        assertEquals(b, TrailStep(s, reversed = true).from)
        assertEquals(a, TrailStep(s, reversed = true).to)
    }

    @Test
    fun `a one-segment path is rejected even when it reaches a catch spot`() {
        val short = network.copy(segments = listOf(seg(0, o, end)))
        assertTrue(TrailLogic.enumerate(short, o).isEmpty())
    }

    @Test
    fun `trail state reveals in order and completes at the last step`() {
        val trail = TrailLogic.enumerate(network, o).first { it.size == 3 }
        val state = TrailState(network, trail, revealed = 1)
        assertEquals(trail[1].segment, state.nextClue)
        assertTrue(!state.complete)
        state.revealed = 3
        assertEquals(null, state.nextClue)
        assertTrue(state.complete)
        assertEquals(end, state.catchCoords)
    }

    @Test
    fun `a forward step renders 4 and a reversed step renders 3`() {
        // Multiloc table measured on hunting_trail3_0l: 3 = visible-180, 4 = visible-forward.
        assertEquals(4, TrailLogic.revealValue(TrailStep(seg(0, o, a), reversed = false)))
        assertEquals(3, TrailLogic.revealValue(TrailStep(seg(0, o, a), reversed = true)))
    }

    @Test
    fun `reveal writes name one varbit per revealed step, in order`() {
        val steps =
            listOf(
                TrailStep(seg(0, o, a), reversed = false),
                TrailStep(seg(4, a, b), reversed = true),
                TrailStep(seg(3, b, end), reversed = false),
            )
        assertEquals(listOf("varbit.s0" to 4), TrailLogic.revealWrites(steps, revealed = 1))
        assertEquals(
            listOf("varbit.s0" to 4, "varbit.s4" to 3),
            TrailLogic.revealWrites(steps, revealed = 2),
        )
        assertEquals(3, TrailLogic.revealWrites(steps, revealed = 3).size)
    }

    @Test
    fun `no render value is ever a bare varp write`() {
        // Every write this type produces is keyed by a varbit gameval, because zeroing a trail
        // varp would also reset lumbridge_alchemy_high (varp 925).
        val steps = listOf(TrailStep(seg(0, o, a), reversed = false))
        assertTrue(TrailLogic.revealWrites(steps, revealed = 1).all { it.first.startsWith("varbit.") })
    }
}
