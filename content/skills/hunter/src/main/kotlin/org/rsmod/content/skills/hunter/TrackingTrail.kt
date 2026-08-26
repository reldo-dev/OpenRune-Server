package org.rsmod.content.skills.hunter

import org.rsmod.map.CoordGrid

/**
 * One trail segment: a run of 2-27 footprint tiles the client draws from [varbit] alone.
 * The server holds no per-tile coordinates and **never computes a bit position** - nine of
 * the packed state varbits are not 3 bits at `3i` (`hunting_trail_state2_9` is 5 bits), so
 * the varbit gameval is written by name and the cache places it.
 *
 * [endA]/[endB] are the junctions the run joins - graph nodes, not loc placements. A trail
 * traverses the segment in either direction ([TrailStep.reversed]), which flips the rendered
 * orientation. [clue] is the searchable loc ("Plant", "Cactus", "Jungle plant", ...) that
 * reveals this segment, at [clueCoords]; the trigger sits beside the run, not at a junction.
 */
data class TrailSegment(
    val varbit: String,
    val clue: String,
    val clueCoords: CoordGrid,
    val endA: CoordGrid,
    val endB: CoordGrid,
)

data class TrailStep(val segment: TrailSegment, val reversed: Boolean) {
    val from: CoordGrid
        get() = if (reversed) segment.endB else segment.endA

    val to: CoordGrid
        get() = if (reversed) segment.endA else segment.endB
}

/** A start burrow: the clickable loc at [coords], and the trail-graph node it feeds, [origin]. */
data class TrailBurrow(val loc: String, val coords: CoordGrid, val origin: CoordGrid)

/** A catch loc placement (snow drift etc.). A trail is catchable at exactly one of these. */
data class TrailCatchSpot(val loc: String, val coords: CoordGrid)

data class TrackingNetwork(
    /** Short area identifier, e.g. "rellekka_polar". Networks are per AREA: a creature may
     *  span two or three varp blocks, and varps 922/924 are each split between two creatures,
     *  so the varp is not an identity here and is never named in this file. */
    val area: String,
    val creature: TrackingCreature,
    val segments: List<TrailSegment>,
    val burrows: List<TrailBurrow>,
    val catchSpots: List<TrailCatchSpot>,
) {
    /**
     * Every valid trail from every burrow origin, enumerated once. Networks are ~10 segments,
     * so exhaustive enumeration is trivially cheap and buys determinism: generation is a
     * uniform pick from this list, there is no retry loop to tune, and an authoring mistake
     * that strands a burrow (no valid trail) is a boot-time check in [TrackingEvents] instead
     * of a runtime lottery.
     */
    val trailsByOrigin: Map<CoordGrid, List<List<TrailStep>>> by lazy {
        burrows.map(TrailBurrow::origin).distinct().associateWith { TrailLogic.enumerate(this, it) }
    }
}

/**
 * A player's active trail. [revealed] counts rendered steps, starting at 1 from the burrow
 * inspect; the searchable clue is always the *next* unrevealed step's, matching the live
 * flow where each find uncovers the tracks beyond it.
 */
class TrailState(val network: TrackingNetwork, val steps: List<TrailStep>, var revealed: Int) {
    val complete: Boolean
        get() = revealed >= steps.size

    val nextClue: TrailSegment?
        get() = if (complete) null else steps[revealed].segment

    val catchCoords: CoordGrid
        get() = steps.last().to
}

object TrailLogic {
    /** Wiki: "usually about three segments"; the exact distribution is unpublished. */
    const val MIN_SEGMENTS = 2
    const val MAX_SEGMENTS = 3

    /**
     * The values the footprint multilocs key on: 3 and 4 are the two visible variants of a
     * run, 5 and 6 their fading twins - which nothing here writes, so they have no constant -
     * and 0 hidden. Both visible values draw the same footprints; they differ by a
     * 180-degree flip.
     *
     * **Which of the two points toward the catch is not a constant, and swapping these will
     * not fix it.** Measured off the packed map, 2026-08-25. Two findings:
     * - The exemplar this was first read off, `hunting_trail3_0l`, is the mirrored variant.
     *   Every run is tiled from a straight loc, a right corner (`..r`) and a left corner
     *   (`..l`), and the `l` type maps 3/4 to the *opposite* pair of the other two - the
     *   swap exists so one varbit value renders a whole mixed run flowing one way. So "4 is
     *   the forward one" holds for `l` tiles and is backwards on the other two.
     * - Each run has a drawn direction baked into its tiles' placement angles (angle is the
     *   travel direction turned 90 degrees, consistently across every run sampled), and that
     *   direction has no relation to [TrailSegment.endA]/[TrailSegment.endB] ordering, which
     *   was derived from the sweep's diameter endpoints. `hunting_trail_state8_1` is drawn
     *   endA to endB; `hunting_trail_state6_5` and `hunting_trail_state8_2` are drawn endB to
     *   endA. So [revealValue] - keyed on [TrailStep.reversed], which is relative to that
     *   ordering - points the right way on roughly half of them whichever value it picks.
     *
     * Fixing it properly means recording each segment's drawn direction alongside its
     * endpoints and choosing the value against that. Cosmetic either way: footprints point
     * the wrong way down some runs; nothing about following a trail or catching depends on
     * it. Left alone deliberately rather than swapped on a guess.
     */
    const val VISIBLE_FORWARD = 4
    const val VISIBLE_REVERSED = 3

    /**
     * All simple paths from [origin] to any catch spot with length in
     * [MIN_SEGMENTS]..[MAX_SEGMENTS], never reusing a segment. Segments are undirected:
     * a path may enter one at either endpoint, recorded as [TrailStep.reversed].
     */
    fun enumerate(network: TrackingNetwork, origin: CoordGrid): List<List<TrailStep>> {
        val results = mutableListOf<List<TrailStep>>()
        val ends = network.catchSpots.map(TrailCatchSpot::coords).toSet()

        fun walk(at: CoordGrid, path: List<TrailStep>) {
            if (path.size >= MIN_SEGMENTS && at in ends) {
                results += path
            }
            if (path.size >= MAX_SEGMENTS) {
                return
            }
            for (segment in network.segments) {
                if (path.any { it.segment.varbit == segment.varbit }) {
                    continue
                }
                if (segment.endA == at) {
                    walk(segment.endB, path + TrailStep(segment, reversed = false))
                }
                if (segment.endB == at) {
                    walk(segment.endA, path + TrailStep(segment, reversed = true))
                }
            }
        }

        walk(origin, emptyList())
        return results
    }

    fun revealValue(step: TrailStep): Int =
        if (step.reversed) VISIBLE_REVERSED else VISIBLE_FORWARD

    /**
     * The writes that render a trail with [revealed] steps showing: one per segment, keyed by
     * varbit gameval. Never a varp value - see the class amendment note.
     */
    fun revealWrites(steps: List<TrailStep>, revealed: Int): List<Pair<String, Int>> =
        steps.take(revealed).map { it.segment.varbit to revealValue(it) }
}
