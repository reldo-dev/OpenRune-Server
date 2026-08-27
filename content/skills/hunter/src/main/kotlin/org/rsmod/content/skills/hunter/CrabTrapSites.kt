package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.definition.type.VarBitType
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType

const val CRAB_TRAP_UNBUILT: String = "loc.crab_trap_unbuilt"

const val CRAB_TRAP_BUILT: String = "loc.crab_trap_built"

const val CRAB_TRAP_ACTIVE: String = "loc.crab_trap_active"

/** Two baited locs is the cache stating that bait type is visible state (docs/hunter.md). */
const val CRAB_TRAP_ACTIVE_FINE: String = "loc.crab_trap_active_fine_offcuts"

/**
 * One of the twenty holes a crab trap can be built over. Everything but [loc] is derived from the
 * packed cache rather than retyped: the varbit is the loc's own `multiVarBit`, the state ordinals
 * are positions in [childLocIds] looked up by id, and [creature] is matched by which full-trap
 * locs appear among the children - never by parsing a symbol (docs/hunter.md). [index] is a
 * runtime list position, never persisted.
 */
data class CrabTrapSite(
    val index: Int,
    val loc: String,
    val locId: Int,
    val varbit: VarBitType,
    val creature: CrabCreature,
    val childLocIds: List<Int>,
    val unbuiltState: Int,
    val builtState: Int,
    val baitedState: Int,
    val fullStates: List<Int>,
) {
    /** "active (baited or full)" is what the cap counts. */
    fun isActive(state: Int): Boolean = state == baitedState || state in fullStates

    fun variantAt(state: Int): CrabVariant? =
        fullStates.indexOf(state).takeIf { it >= 0 }?.let(creature.variants::get)
}

/**
 * The twenty crab-trap sites. The names are written out, never assembled by string surgery - the
 * failure mode `HunterCreature.locKey` exists to undo.
 */
object CrabTrapSites {
    private val SITE_LOCS: List<String> =
        listOf(
            "loc.crab_trap_pandemonium_1",
            "loc.crab_trap_pandemonium_2",
            "loc.crab_trap_pandemonium_3",
            "loc.crab_trap_pandemonium_4",
            "loc.crab_trap_pandemonium_5",
            "loc.crab_trap_great_conch_north_1",
            "loc.crab_trap_great_conch_north_2",
            "loc.crab_trap_great_conch_north_3",
            "loc.crab_trap_great_conch_north_4",
            "loc.crab_trap_great_conch_north_5",
            "loc.crab_trap_great_conch_east_1",
            "loc.crab_trap_great_conch_east_2",
            "loc.crab_trap_great_conch_east_3",
            "loc.crab_trap_great_conch_east_4",
            "loc.crab_trap_great_conch_east_5",
            "loc.crab_trap_crown_jewel_1",
            "loc.crab_trap_crown_jewel_2",
            "loc.crab_trap_crown_jewel_3",
            "loc.crab_trap_crown_jewel_4",
            "loc.crab_trap_crown_jewel_5",
        )

    // Built from the sites rather than listed, so a new state surfaces here instead of being
    // silently unclickable.
    val lifecycleLocs: Set<String> by lazy {
        all.flatMapTo(LinkedHashSet()) { site ->
            listOf(CRAB_TRAP_UNBUILT, CRAB_TRAP_BUILT) +
                checkNotNull(reverseLoc(site.childLocIds[site.baitedState])) +
                site.creature.variants.map { it.fullLoc }
        }
    }

    val all: List<CrabTrapSite> by lazy { SITE_LOCS.mapIndexed(::site) }

    private val byLocId: Map<Int, CrabTrapSite> by lazy { all.associateBy { it.locId } }

    fun byLocId(locId: Int): CrabTrapSite? = byLocId[locId]

    private fun site(index: Int, loc: String): CrabTrapSite {
        val locId = loc.asRSCM(RSCMType.LOC)
        val type =
            ServerCacheManager.getObject(locId) ?: error("Missing crab trap site loc type: $loc")

        val varbitId = type.multiVarBit
        require(varbitId > 0) { "$loc has no multiVarBit; it is not a crab trap site." }
        val varbit =
            ServerCacheManager.getVarbit(varbitId) ?: error("Missing varbit $varbitId for $loc")

        // Masked the way `LocInteractions.multiLoc` masks it; padding slots read back 65535.
        val children = type.multiLoc.map { it and 0xFFFF }

        val creature =
            CrabCreatures.all.firstOrNull { candidate ->
                candidate.variants.all { it.fullLoc.asRSCM(RSCMType.LOC) in children }
            } ?: error("No crab creature matches the full-trap locs of $loc")

        return CrabTrapSite(
            index = index,
            loc = loc,
            locId = locId,
            varbit = varbit,
            creature = creature,
            childLocIds = children,
            unbuiltState = children.stateOf(CRAB_TRAP_UNBUILT, loc),
            builtState = children.stateOf(CRAB_TRAP_BUILT, loc),
            baitedState = children.baitedStateOf(loc),
            fullStates = creature.variants.map { children.stateOf(it.fullLoc, loc) },
        )
    }

    private fun List<Int>.stateOf(child: String, site: String): Int {
        val state = indexOf(child.asRSCM(RSCMType.LOC))
        check(state >= 0) { "$site has no $child among its multiloc children." }
        return state
    }

    // The cache decides per site which offcuts fill a trap - the bait column's cross-check.
    private fun List<Int>.baitedStateOf(site: String): Int {
        val plain = indexOf(CRAB_TRAP_ACTIVE.asRSCM(RSCMType.LOC))
        val fine = indexOf(CRAB_TRAP_ACTIVE_FINE.asRSCM(RSCMType.LOC))
        check(plain >= 0 || fine >= 0) { "$site has neither baited loc among its children." }
        check(plain < 0 || fine < 0) { "$site shows both baited locs; the bait is ambiguous." }
        return if (plain >= 0) plain else fine
    }

    private fun reverseLoc(locId: Int): String? = RSCM.getReverseMapping(RSCMType.LOC, locId)
}
