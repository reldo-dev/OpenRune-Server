package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.rsmod.game.loc.LocAngle
import org.rsmod.map.CoordGrid

/**
 * The net trap's two-tile geometry, tested without a cache: the offset is a pure function of a
 * [LocAngle], so no packed table, gameval or game world has to exist for these to run.
 *
 * This is the pair the whole family hangs off. A net trap is two locs - the young tree the player
 * clicks and the "Net trap" that appears beside it - and every path that starts from one has to
 * find the other: the set spawns the net from the tree, the tick reads the net's tile from the
 * tree's, and the three ops that land on the net loc (Dismantle, Investigate, Check) have to walk
 * back to the tree, which is where the controller is anchored. Getting the inverse wrong desyncs
 * the pair silently - the trap looks armed and simply never resolves - so the round trip is
 * asserted directly rather than left implied by the world tests.
 */
class NetTrapLogicTest {
    private val tree = CoordGrid(3204, 3204, 0)

    /**
     * The offset is derived from the tree loc's own angle - one step counter-clockwise of its
     * compass name, the side the bent trunk leans over; see the KDoc on `netTrapOffset` for the
     * in-game measurement. That the four angles give four *distinct*, orthogonally adjacent tiles
     * is what makes the inverse below well defined.
     */
    @Test
    fun `each tree angle puts the net on a different adjacent tile`() {
        val placed = LocAngle.entries.map { netTrapCoords(tree, it) }

        assertEquals(placed.size, placed.toSet().size, "Two angles resolved to the same tile.")
        for (coords in placed) {
            assertEquals(1, coords.chebyshevDistance(tree), "The net must be adjacent to the tree.")
            assertEquals(
                1,
                Math.abs(coords.x - tree.x) + Math.abs(coords.z - tree.z),
                "The net must be orthogonally adjacent, never diagonal.",
            )
            assertEquals(tree.level, coords.level, "The net stays on the tree's level.")
        }
    }

    /**
     * The invariant that matters: walking tree -> net -> tree returns the tile you started on, for
     * every angle. The net loc is spawned carrying the tree's own angle, which is what makes this
     * recoverable from the net loc alone - nothing else records where its tree was.
     */
    @Test
    fun `walking from the tree to the net and back is the identity`() {
        for (angle in LocAngle.entries) {
            val net = netTrapCoords(tree, angle)
            assertEquals(tree, netTrapTreeCoords(net, angle), "Angle $angle does not round-trip.")
        }
    }

    /** The other direction of the same round trip, started from a net tile. */
    @Test
    fun `walking from the net to the tree and back is the identity`() {
        val net = CoordGrid(3210, 3210, 0)
        for (angle in LocAngle.entries) {
            val treeCoords = netTrapTreeCoords(net, angle)
            assertEquals(net, netTrapCoords(treeCoords, angle), "Angle $angle does not round-trip.")
        }
    }
}
