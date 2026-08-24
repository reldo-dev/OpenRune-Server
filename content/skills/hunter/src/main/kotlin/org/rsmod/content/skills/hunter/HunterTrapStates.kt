package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import kotlin.math.abs

/**
 * Which of a deadfall creature's two "falling boulder" locs to show, given where the creature is
 * standing relative to the boulder ([dx] on the x axis, [dy] on the z axis - map north).
 *
 * Unverified. Every deadfall creature has a mirrored `_m` variant of its `_trapping_` loc in the
 * cache, and the boulder visibly falls towards the side the animal walked in from, so the pair
 * plainly encodes an approach side. What live keys the choice on is not recoverable offline: the
 * catch is server-side, so no cs2 script or loc field states it, and the two emulator references
 * that implement deadfall each pick a side from the creature's approach direction without agreeing
 * on the exact axis test. West or south picking the mirror is the convention here; it is not a
 * source. It only ever changes which of two boulder models a player sees for a couple of cycles,
 * never whether the catch lands.
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
    /**
     * The unset boulder, and the state every deadfall transition eventually returns to. This is a
     * *map* loc: see [HunterTrap] for why it is only ever reached through `locRepo.change`.
     */
    const val DEADFALL_BOULDER: String = "loc.hunting_deadfall_boulder"

    /** Shown while the player is fitting the log; carries no ops, hence no content group. */
    const val DEADFALL_SETTING: String = "loc.hunting_deadfall_setting"

    /** The armed trap: the only deadfall state with `Dismantle` and `Investigate`. */
    const val DEADFALL_ARMED: String = "loc.hunting_deadfall_trap"

    /** The boulder mid-fall on a catch that failed. Also op-less. */
    const val DEADFALL_FAILING: String = "loc.hunting_deadfall_failing"

    /**
     * Every loc id a deadfall boulder can be wearing, whichever creature's table row it came from.
     *
     * Used to find the boulder on a tile without knowing which state it is in, and by
     * [HunterTrap]'s guard against the portable teardown path ever reaching one.
     */
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

    // "npc.hunting_bird_jungle" -> "jungle". Split per family rather than one `key(creature)` with
    // a family `when`: the deadfall builds no loc name from its npc's - its states are columns in
    // the packed table - so there is no third suffix rule to write, only an unreachable branch to
    // invent.
    private fun snareKey(creature: HunterCreature): String =
        creature.npc.substringAfterLast("hunting_bird_")

    // "npc.hunting_chinchompa_big" -> "chinchompa_big"
    private fun boxKey(creature: HunterCreature): String =
        creature.npc.substringAfter("npc.hunting_")

    fun setLoc(family: TrapFamily): String =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap"
            TrapFamily.BOX -> "loc.hunting_boxtrap_empty"
            TrapFamily.DEADFALL -> DEADFALL_ARMED
        }

    /**
     * The mid-catch state, given where the creature stands relative to the trap ([dx] on the x
     * axis, [dz] on the z axis).
     *
     * All three families key this off the same two offsets and none of them agree on how: the bird
     * snare ignores the direction entirely, the box trap has one loc per compass side, and the
     * deadfall has a base loc and a mirror of it.
     */
    fun trappingLoc(creature: HunterCreature, dx: Int, dz: Int): String =
        when (creature.family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_trapping_${snareKey(creature)}"
            TrapFamily.BOX -> "loc.hunting_boxtrap_trapping_${boxKey(creature)}_${compass(dx, dz)}"
            TrapFamily.DEADFALL -> deadfallApproachLoc(creature, dx, dz)
        }

    fun fullLoc(creature: HunterCreature): String =
        when (creature.family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_full_${snareKey(creature)}"
            TrapFamily.BOX -> "loc.hunting_boxtrap_full_${boxKey(creature)}"
            TrapFamily.DEADFALL ->
                requireNotNull(creature.fullLoc) {
                    "Deadfall creature is missing its full loc: ${creature.npc}"
                }
        }

    fun failingLoc(family: TrapFamily): String =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_failing"
            TrapFamily.BOX -> "loc.hunting_boxtrap_failing"
            TrapFamily.DEADFALL -> DEADFALL_FAILING
        }

    /**
     * The state a trap ends in when it caught nothing, or when its lifetime ran out.
     *
     * The two portable families leave a wreck on the ground that still owes the player their trap
     * item. A deadfall leaves nothing: it is a boulder again, which is why this returns
     * [DEADFALL_BOULDER] rather than a state of its own - there is no `hunting_deadfall_failed` in
     * the cache, and inventing one would mean a permanent map loc stuck wearing it.
     */
    fun failedLoc(family: TrapFamily): String =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_broken"
            TrapFamily.BOX -> "loc.hunting_boxtrap_failed"
            TrapFamily.DEADFALL -> DEADFALL_BOULDER
        }

    /**
     * Which compass side of the trap a creature at ([dx], [dz]) is standing on, as the `n`/`e`/`s`/
     * `w` suffix the box trap's `_trapping_` locs are keyed by. Ties and a same-tile creature fall
     * to `n`. Whether live picks the direction this way is unverified.
     */
    private fun compass(dx: Int, dz: Int): Char =
        when {
            abs(dz) >= abs(dx) && dz >= 0 -> 'n'
            abs(dz) >= abs(dx) -> 's'
            dx >= 0 -> 'e'
            else -> 'w'
        }
}
