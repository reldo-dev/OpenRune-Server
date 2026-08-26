package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.varp.VarpServerType

/**
 * One of the four Fossil Island bird house spaces, and everything the packed cache already knows
 * about it.
 *
 * **A bird house is not a world object either.** The map places one `birdhouse_<n>` loc carrying no
 * ops and a `multivar`; the client draws whichever of that loc's 28 `multiloc` children the
 * *viewing player's* own varp selects. So this is the crab trap's shape one step further along -
 * [HunterCrabTrap] writes a varbit, this writes a **varp** - and it inherits the same invariant:
 * nothing here ever touches a loc repository, so the "never delete a map-placed loc" rule the
 * deadfall and net trap enforce with a hard `check` is satisfied structurally.
 *
 * The varp is what makes the technique cheap. Varps default to `VarpLifetime.Perm` and the account
 * repository saves Perm varps, so which tier is built and which of its three states it is showing
 * survive a logout with **nothing authored server-side**. Compare the crab trap, whose sites are
 * varbit multilocs.
 *
 * [varp] is read off the loc's own `multiVarp` rather than named in source, for the reason
 * [CrabTrapSite] gives about its varbit: the server then writes the exact var the client renders
 * from, and the two cannot drift.
 *
 * [index] is the space's position in [BirdHouseSpaces.all]. It is what the fill queue carries as its
 * argument, and unlike a trap's creature index it is never persisted - the queue lives only as long
 * as the session, and [HunterBirdHouse.rearmBirdHouseFills] rebuilds every pending fill from the
 * varps at login.
 */
data class BirdHouseSpace(val index: Int, val loc: String, val locId: Int, val varp: VarpServerType)

/**
 * The four spaces, in varp order.
 *
 * **Varp order is not the wiki's order.** The *Bird house trapping* page's Locations section
 * describes the spaces in the order an efficient run visits them, which is neither the loc order nor
 * the varp order, and reading it as an ordering puts two houses in the wrong valley. The packed map
 * settles it, and RuneLite's own `BirdHouseSpace` enum agrees with the packed map exactly:
 *
 * | loc | varp | coords | spot |
 * |---|---|---|---|
 * | `loc.birdhouse_1` | 1626 | (3677, 3882) | Mushroom Meadow, north |
 * | `loc.birdhouse_2` | 1627 | (3679, 3815) | Mushroom Meadow, south |
 * | `loc.birdhouse_3` | 1628 | (3768, 3761) | Verdant Valley, north-east |
 * | `loc.birdhouse_4` | 1629 | (3763, 3755) | Verdant Valley, south-west |
 *
 * Nothing in this module keys off those coordinates - a space is found by the loc id the op hands
 * us - but they are recorded because they are the one thing a player-visible bug would be reported
 * against, and because all four were confirmed placed exactly once on our packed map.
 */
object BirdHouseSpaces {
    /**
     * The value a space's varp holds when nothing is built on it.
     *
     * Zero is the bare `birdhouse_not_built` child, so an account that has never been to Fossil
     * Island reads the correct state without anything ever having been written - which is the same
     * property [HunterTrapVars]' sentinels are chosen for.
     */
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

    /**
     * The four space locs, written out rather than assembled from a prefix and an index.
     *
     * Four literals against the class of bug this module has been bitten by three times: a name
     * built by string surgery resolves to nothing and throws at whichever click happens to reach it
     * first, rather than at boot.
     */
    private val LOCS: List<String> =
        listOf("loc.birdhouse_1", "loc.birdhouse_2", "loc.birdhouse_3", "loc.birdhouse_4")
}
