package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.definition.type.VarBitType
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType

/** The hole a trap is built over, and the state every site starts and is torn back down to. */
const val CRAB_TRAP_UNBUILT: String = "loc.crab_trap_unbuilt"

/** A built, unbaited trap. Carries `Bait`, and is the only state that does. */
const val CRAB_TRAP_BUILT: String = "loc.crab_trap_built"

/** A trap baited with `obj.brut_fish_cuts`; the red and blue sites' baited state. */
const val CRAB_TRAP_ACTIVE: String = "loc.crab_trap_active"

/**
 * A trap baited with `obj.sailing_fine_fish_offcuts`; the crown-jewel sites' baited state.
 *
 * That there are two baited locs at all is the cache stating, on its own, that bait type is part of
 * the trap's visible state rather than an unmodelled bonus - which is why this is the first hunter
 * technique where bait is a lifecycle step and not a `+%` nobody implemented.
 */
const val CRAB_TRAP_ACTIVE_FINE: String = "loc.crab_trap_active_fine_offcuts"

/**
 * One of the twenty holes a crab trap can be built over, with everything about it that the packed
 * cache already knows.
 *
 * **A crab trap is not a world object.** The map places a `crab_trap_<site>_<n>` loc that carries no
 * ops of its own and a `multivar` naming a varbit; the client picks which of that loc's `multiloc`
 * children to draw from the value of that varbit, *for the viewing player*. So every player has
 * their own five traps per island, two players never contend for a hole, and - the invariant the
 * whole technique inherits - **nothing here ever calls `locRepo` at all**, let alone `locRepo.del`.
 * The whole feature is a varbit write. See [HunterCrabTrap].
 *
 * Everything but [loc] is derived from the packed cache rather than retyped:
 * - [varbit] is the loc's own `multiVarBit`, so the server writes the exact var the client renders
 *   from and the two cannot drift.
 * - [unbuiltState], [builtState], [baitedState] and [fullStates] are **positions in
 *   [childLocIds]**, looked up by matching the child ids. Hard-coding them as 0/1/2/3 would work
 *   today and would silently show the wrong model if a cache update reordered a `multiloc`.
 * - [creature] is the crab whose full-trap locs appear among this site's children, which is how a
 *   site is matched to a crab without parsing `pandemonium` out of a symbol. That kind of name
 *   surgery is the failure `HunterCreature.locKey` exists to undo.
 *
 * [index] is the site's position in [CrabTrapSites.all] and is what a pending catch carries as its
 * queue argument. It is a runtime list position, never persisted: a queue lives only as long as the
 * session, and the login re-arm rebuilds every pending catch from the varbits.
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
    /** True while the trap counts against the level cap: "active (baited or full)" on the wiki. */
    fun isActive(state: Int): Boolean = state == baitedState || state in fullStates

    /**
     * Which of [CrabCreature.variants] a trap in [state] is holding, or null if it holds nothing.
     */
    fun variantAt(state: Int): CrabVariant? =
        fullStates.indexOf(state).takeIf { it >= 0 }?.let(creature.variants::get)
}

/**
 * The twenty crab-trap sites: five holes each on The Pandemonium, two shores of The Great Conch, and
 * The Crown Jewel.
 *
 * The names are written out rather than built from a prefix and an index. Twenty literals are
 * cheaper than the class of bug this module has already been bitten by three times - a name assembled
 * by string surgery resolves to something that does not exist and throws at first use rather than at
 * boot - and each one has to resolve in **two** namespaces anyway, since the site's loc and its
 * varbit share a symbol.
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

    /**
     * The nine locs a crab trap is ever *seen* as, which are the ones that carry ops and therefore
     * the ones that need `content.hunter_crab_trap`.
     *
     * Built from the sites rather than listed, so a cache update that gave a site a new state would
     * surface here instead of leaving that state silently unclickable.
     */
    val lifecycleLocs: Set<String> by lazy {
        all.flatMapTo(LinkedHashSet()) { site ->
            listOf(CRAB_TRAP_UNBUILT, CRAB_TRAP_BUILT) +
                checkNotNull(reverseLoc(site.childLocIds[site.baitedState])) +
                site.creature.variants.map { it.fullLoc }
        }
    }

    val all: List<CrabTrapSite> by lazy { SITE_LOCS.mapIndexed(::site) }

    private val byLocId: Map<Int, CrabTrapSite> by lazy { all.associateBy { it.locId } }

    /**
     * The site a click landed on, keyed by the **base** loc.
     *
     * `LocContentEvents.Op1` hands over both halves of a multiloc - `loc` is the map-placed site,
     * `vis` is the child the player's varbit resolved to - and this reads the site. The state is then
     * read from the varbit rather than from `vis`, because the varbit is what the client rendered
     * from and is the value the server is about to write.
     */
    fun byLocId(locId: Int): CrabTrapSite? = byLocId[locId]

    private fun site(index: Int, loc: String): CrabTrapSite {
        val locId = loc.asRSCM(RSCMType.LOC)
        val type =
            ServerCacheManager.getObject(locId) ?: error("Missing crab trap site loc type: $loc")

        val varbitId = type.multiVarBit
        require(varbitId > 0) { "$loc has no multiVarBit; it is not a crab trap site." }
        val varbit =
            ServerCacheManager.getVarbit(varbitId) ?: error("Missing varbit $varbitId for $loc")

        // Masked to 16 bits the way `LocInteractions.multiLoc` masks it. Padding slots read back as
        // 65535 and resolve to no loc type at all, which is why every state below is found by
        // matching a known id rather than by index arithmetic over this array.
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

    /**
     * Which of the two baited locs this site shows, and the reason the bait column can be trusted:
     * the cache decides per site which offcuts a trap is filled with, so a site that shows the fine
     * variant belongs to a crab whose row must name the fine offcuts.
     */
    private fun List<Int>.baitedStateOf(site: String): Int {
        val plain = indexOf(CRAB_TRAP_ACTIVE.asRSCM(RSCMType.LOC))
        val fine = indexOf(CRAB_TRAP_ACTIVE_FINE.asRSCM(RSCMType.LOC))
        check(plain >= 0 || fine >= 0) { "$site has neither baited loc among its children." }
        check(plain < 0 || fine < 0) { "$site shows both baited locs; the bait is ambiguous." }
        return if (plain >= 0) plain else fine
    }

    private fun reverseLoc(locId: Int): String? = RSCM.getReverseMapping(RSCMType.LOC, locId)
}
