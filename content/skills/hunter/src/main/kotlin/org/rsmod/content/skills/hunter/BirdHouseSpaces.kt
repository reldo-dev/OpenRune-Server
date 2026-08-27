package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.varp.VarpServerType

/**
 * One of the four Fossil Island spaces. A bird house is not a world object: the crab trap's shape
 * one step on, writing a *varp* instead of a varbit, with the same structural invariants - no loc
 * repository, private per player. [varp] is read off the loc's own `multiVarp`, so the server
 * writes exactly what the client renders from. [index] is never persisted. See docs/hunter.md.
 */
data class BirdHouseSpace(val index: Int, val loc: String, val locId: Int, val varp: VarpServerType)

/**
 * The four spaces, in varp order - which is *not* the wiki's touring order; the packed map and
 * RuneLite's enum agree on which space is which (docs/hunter.md).
 */
object BirdHouseSpaces {
    /** Zero is the bare child, so an unwritten varp reads the correct state. */
    const val BARE: Int = 0

    val all: List<BirdHouseSpace> by lazy {
        LOCS.mapIndexed { index, loc ->
            val locId = loc.asRSCM(RSCMType.LOC)
            val type = checkNotNull(ServerCacheManager.getObject(locId)) { "Missing loc: $loc" }
            require(type.multiVarp > 0) { "$loc is not a varp multiloc." }
            val varp =
                checkNotNull(ServerCacheManager.getVarp(type.multiVarp)) {
                    "$loc reads varp ${type.multiVarp}, which has no packed definition."
                }
            BirdHouseSpace(index = index, loc = loc, locId = locId, varp = varp)
        }
    }

    private val byLocId: Map<Int, BirdHouseSpace> by lazy { all.associateBy(BirdHouseSpace::locId) }

    /** The space an op landed on, or null if the loc is not one of the four. */
    fun byLocId(locId: Int): BirdHouseSpace? = byLocId[locId]

    // Written out, never assembled by string surgery.
    private val LOCS: List<String> =
        listOf("loc.birdhouse_1", "loc.birdhouse_2", "loc.birdhouse_3", "loc.birdhouse_4")
}
