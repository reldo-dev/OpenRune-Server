package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import kotlin.math.abs

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

    // Authored data, never derived from the npc symbol (docs/hunter.md).
    private fun locKey(creature: HunterCreature): String =
        requireNotNull(creature.locKey) {
            "Creature is missing its loc key: ${creature.npc}"
        }

    fun setLoc(family: TrapFamily): String? =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap"
            TrapFamily.BOX -> "loc.hunting_boxtrap_empty"
            TrapFamily.DEADFALL -> null
        }

    /** The mid-catch state, given where the creature stands relative to the trap ([dx], [dz]). */
    fun trappingLoc(creature: HunterCreature, dx: Int, dz: Int): String =
        when (creature.family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_trapping_${locKey(creature)}"
            TrapFamily.BOX -> "loc.hunting_boxtrap_trapping_${locKey(creature)}_${compass(dx, dz)}"
            TrapFamily.DEADFALL -> deadfallApproachLoc(creature, dx, dz)
        }

    fun fullLoc(creature: HunterCreature): String =
        when (creature.family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_full_${locKey(creature)}"
            TrapFamily.BOX -> "loc.hunting_boxtrap_full_${locKey(creature)}"
            TrapFamily.DEADFALL ->
                requireNotNull(creature.fullLoc) {
                    "Deadfall creature is missing its full loc: ${creature.npc}"
                }
        }

    fun failingLoc(family: TrapFamily, creature: HunterCreature? = null): String =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_failing"
            TrapFamily.BOX -> "loc.hunting_boxtrap_failing"
            TrapFamily.DEADFALL -> DEADFALL_FAILING
        }

    fun failedLoc(family: TrapFamily, creature: HunterCreature? = null): String =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_broken"
            TrapFamily.BOX -> "loc.hunting_boxtrap_failed"
            TrapFamily.DEADFALL -> DEADFALL_BOULDER
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
