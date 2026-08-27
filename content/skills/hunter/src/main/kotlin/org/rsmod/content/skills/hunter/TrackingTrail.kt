package org.rsmod.content.skills.hunter

import org.rsmod.map.CoordGrid

/**
 * One trail segment: a run of 2-27 footprint tiles the client draws from [varbit] alone - written
 * by name, never a computed bit position (nine state varbits are not 3 bits at `3i`). [endA]/
 * [endB] are graph nodes walked by `CoordGrid` equality; [clue] is the searchable loc that
 * reveals the segment, at its *first* placement's coordinates.
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
     * The footprint multiloc values: 3 and 4 are the two visible variants (a 180-degree flip),
     * 0 hidden. Which of the two points toward the catch is not a constant, and swapping these
     * will not fix it - left alone deliberately rather than swapped on a guess; cosmetic either
     * way. Full derivation: docs/hunter.md.
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
     * varbit gameval. Never a varp value - see [HunterTracking.clearTrail] for why.
     */
    fun revealWrites(steps: List<TrailStep>, revealed: Int): List<Pair<String, Int>> =
        steps.take(revealed).map { it.segment.varbit to revealValue(it) }
}
