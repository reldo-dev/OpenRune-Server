package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import kotlin.math.abs
import org.rsmod.game.loc.LocAngle
import org.rsmod.map.CoordGrid

/**
 * Which of the two "falling boulder" locs to show. West-or-south-picks-the-mirror is our
 * convention, not a source (docs/hunter.md); it only changes which model shows for two cycles.
 */
fun deadfallApproachLoc(creature: HunterCreature, dx: Int, dy: Int): String {
    val base =
        requireNotNull(creature.trappingLoc) {
            "Deadfall creature is missing its trapping loc: ${creature.npc}"
        }
    val mirrored = creature.trappingLocM ?: base
    return if (dx < 0 || dy < 0) mirrored else base
}

/**
 * How far the net sits from its young tree. Reading [LocAngle] names as compass directions is a
 * convention, corroborated three ways but not sourced - see docs/hunter.md. [netTrapTreeCoords] is
 * the negation of this, so tree -> net -> tree is the identity whichever tiles are picked.
 */
private fun netTrapOffset(angle: LocAngle): Pair<Int, Int> =
    when (angle) {
        LocAngle.West -> -1 to 0
        LocAngle.North -> 0 to 1
        LocAngle.East -> 1 to 0
        LocAngle.South -> 0 to -1
    }

/** The tile the net half of a net trap occupies, given the young tree's tile and angle. */
fun netTrapCoords(treeCoords: CoordGrid, treeAngle: LocAngle): CoordGrid {
    val (dx, dz) = netTrapOffset(treeAngle)
    return treeCoords.translate(dx, dz)
}

/**
 * The inverse of [netTrapCoords]. [netAngle] is the net loc's angle, which is the tree's - the net
 * is spawned carrying it because nothing else records where its tree was.
 */
fun netTrapTreeCoords(netCoords: CoordGrid, netAngle: LocAngle): CoordGrid {
    val (dx, dz) = netTrapOffset(netAngle)
    return netCoords.translate(-dx, -dz)
}

object HunterTrapStates {
    /** A *map* loc: only ever reached through `locRepo.change`, never spawned. */
    const val DEADFALL_BOULDER: String = "loc.hunting_deadfall_boulder"

    /** Shown while the player is fitting the log; carries no ops, hence no content group. */
    const val DEADFALL_SETTING: String = "loc.hunting_deadfall_setting"

    /** The armed trap: the only deadfall state with `Dismantle` and `Investigate`. */
    const val DEADFALL_ARMED: String = "loc.hunting_deadfall_trap"

    /** The boulder mid-fall on a catch that failed. Also op-less. */
    const val DEADFALL_FAILING: String = "loc.hunting_deadfall_failing"

    /** Every state a boulder can wear; read by [HunterTrap]'s never-delete guard. */
    val deadfallLocIds: Set<Int> by lazy {
        val shared = listOf(DEADFALL_BOULDER, DEADFALL_SETTING, DEADFALL_ARMED, DEADFALL_FAILING)
        val perCreature =
            HunterCreatures.deadfall.flatMap {
                listOfNotNull(it.trappingLoc, it.trappingLocM, it.fullLoc)
            }
        (shared + perCreature).mapTo(HashSet()) { it.asRSCM(RSCMType.LOC) }
    }

    /** The deadfall states that carry the `Check` op, i.e. the ones holding a catch. */
    val deadfallFullLocIds: Set<Int> by lazy {
        HunterCreatures.deadfall.mapNotNullTo(HashSet()) { it.fullLoc?.asRSCM(RSCMType.LOC) }
    }

    /** One creature in the family, so every state is shared by construction: constants. */
    const val MAGIC_BOX_EMPTY: String = "loc.hunting_imptrap_empty"

    const val MAGIC_BOX_TRAPPING: String = "loc.hunting_imptrap_trapping"

    const val MAGIC_BOX_FULL: String = "loc.hunting_imptrap_full"

    /** Doubles as the failing frame: the cache has nothing between `full` and `failed`. */
    const val MAGIC_BOX_FAILED: String = "loc.hunting_imptrap_failed"

    private fun netTrapLocIds(vararg states: (HunterCreature) -> String?): Set<Int> =
        HunterCreatures.netTrap
            .flatMap { creature -> states.mapNotNull { state -> state(creature) } }
            .mapTo(HashSet()) { it.asRSCM(RSCMType.LOC) }

    // Where a caller needs one specific state, absence is a data fault and must fail by name.
    private fun HunterCreature.requireLoc(state: String, value: String?): String =
        requireNotNull(value) { "Net trap creature is missing its $state loc: $npc" }

    /** The three `name=Young tree` states - a permanent map loc, change-only. */
    val netTrapTreeLocIds: Set<Int> by lazy {
        netTrapLocIds(HunterCreature::upLoc, HunterCreature::settingLoc, HunterCreature::setLoc)
    }

    /** The five `name=Net trap` states - the only sapling ids a delete is ever legitimate for. */
    val netTrapNetLocIds: Set<Int> by lazy {
        netTrapLocIds(
            HunterCreature::netSetLoc,
            HunterCreature::trappingLoc,
            HunterCreature::fullLoc,
            HunterCreature::failingLoc,
            HunterCreature::failedLoc,
        )
    }

    /** All 40 sapling states; read by [HunterTrap]'s never-delete guard. */
    val netTrapLocIds: Set<Int> by lazy { netTrapTreeLocIds + netTrapNetLocIds }

    val netTrapUpLocIds: Set<Int> by lazy { netTrapLocIds(HunterCreature::upLoc) }

    // Still showing the setting frame, or already reverted by its safety timer.
    val netTrapSettableLocIds: Set<Int> by lazy {
        netTrapLocIds(HunterCreature::upLoc, HunterCreature::settingLoc)
    }

    // One armed state on each tile: the cache gives both halves the same two ops, so a net trap
    // can be taken down by clicking either.
    val netTrapArmedLocIds: Set<Int> by lazy {
        netTrapLocIds(HunterCreature::setLoc, HunterCreature::netSetLoc)
    }

    val netTrapFullLocIds: Set<Int> by lazy { netTrapLocIds(HunterCreature::fullLoc) }

    val netTrapFailedLocIds: Set<Int> by lazy { netTrapLocIds(HunterCreature::failedLoc) }

    fun upLoc(creature: HunterCreature): String = creature.requireLoc("up", creature.upLoc)

    fun settingLoc(creature: HunterCreature): String =
        creature.requireLoc("setting", creature.settingLoc)

    fun armedTreeLoc(creature: HunterCreature): String =
        creature.requireLoc("set", creature.setLoc)

    fun netSetLoc(creature: HunterCreature): String =
        creature.requireLoc("net_set", creature.netSetLoc)

    // Authored data, never derived from the npc symbol (docs/hunter.md).
    private fun locKey(creature: HunterCreature): String =
        requireNotNull(creature.locKey) {
            "Creature is missing its loc key: ${creature.npc}"
        }

    fun setLoc(family: TrapFamily): String? =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap"
            TrapFamily.BOX -> "loc.hunting_boxtrap_empty"
            TrapFamily.MAGICBOX -> MAGIC_BOX_EMPTY
            TrapFamily.DEADFALL,
            TrapFamily.NETTRAP -> null
        }

    /** The mid-catch state, given where the creature stands relative to the trap ([dx], [dz]). */
    fun trappingLoc(creature: HunterCreature, dx: Int, dz: Int): String =
        when (creature.family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_trapping_${locKey(creature)}"
            TrapFamily.BOX -> "loc.hunting_boxtrap_trapping_${locKey(creature)}_${compass(dx, dz)}"
            TrapFamily.MAGICBOX -> MAGIC_BOX_TRAPPING
            TrapFamily.DEADFALL -> deadfallApproachLoc(creature, dx, dz)
            TrapFamily.NETTRAP ->
                requireNotNull(creature.trappingLoc) {
                    "Net trap creature is missing its catching loc: ${creature.npc}"
                }
        }

    fun fullLoc(creature: HunterCreature): String =
        when (creature.family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_full_${locKey(creature)}"
            TrapFamily.BOX -> "loc.hunting_boxtrap_full_${locKey(creature)}"
            TrapFamily.MAGICBOX -> MAGIC_BOX_FULL
            TrapFamily.DEADFALL ->
                requireNotNull(creature.fullLoc) {
                    "Deadfall creature is missing its full loc: ${creature.npc}"
                }
            TrapFamily.NETTRAP ->
                requireNotNull(creature.fullLoc) {
                    "Net trap creature is missing its full loc: ${creature.npc}"
                }
        }

    fun failingLoc(family: TrapFamily, creature: HunterCreature? = null): String =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_failing"
            TrapFamily.BOX -> "loc.hunting_boxtrap_failing"
            TrapFamily.MAGICBOX -> MAGIC_BOX_FAILED
            TrapFamily.DEADFALL -> DEADFALL_FAILING
            TrapFamily.NETTRAP ->
                requireNotNull(creature?.failingLoc) {
                    "Net trap creature is missing its failing loc: ${creature?.npc}"
                }
        }

    fun failedLoc(family: TrapFamily, creature: HunterCreature? = null): String =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_broken"
            TrapFamily.BOX -> "loc.hunting_boxtrap_failed"
            TrapFamily.MAGICBOX -> MAGIC_BOX_FAILED
            TrapFamily.DEADFALL -> DEADFALL_BOULDER
            TrapFamily.NETTRAP ->
                requireNotNull(creature?.failedLoc) {
                    "Net trap creature is missing its failed loc: ${creature?.npc}"
                }
        }

    // Ties and a same-tile creature fall to `n`; whether live picks this way is unverified.
    private fun compass(dx: Int, dz: Int): Char =
        when {
            abs(dz) >= abs(dx) && dz >= 0 -> 'n'
            abs(dz) >= abs(dx) -> 's'
            dx >= 0 -> 'e'
            else -> 'w'
        }
}
