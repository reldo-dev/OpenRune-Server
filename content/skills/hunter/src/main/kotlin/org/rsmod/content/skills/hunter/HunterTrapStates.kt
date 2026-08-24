package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import kotlin.math.abs
import org.rsmod.game.loc.LocAngle
import org.rsmod.map.CoordGrid

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

/**
 * How far the "Net trap" half of a net trap sits from its young tree, as an (x, z) step.
 *
 * **Partly unsourced.** That the offset comes from the *tree loc's own angle* rather than from
 * where the player stood is structural, and is how the one available reference implementation
 * threads it. Which tile each of the four angles picks is not recoverable offline: the placement is
 * server-side, so no cs2 script or loc field states it, and the wiki describes the pair only in
 * prose ("the tree will snap back", "not standing on the net"). Reading [LocAngle]'s own names as
 * compass directions is the convention here, and it is not a source.
 *
 * There is one piece of corroboration worth recording: every `hunting_sapling_up_*` carries
 * `forceapproach=north`, i.e. one side of the tree cannot be walked up to - which is what a net
 * strung out from it would do, and which rotates with the loc exactly as this does.
 *
 * What the exact mapping cannot get wrong is the pairing itself: [netTrapTreeCoords] is defined as
 * the negation of this, so tree -> net -> tree is the identity whichever four tiles are picked.
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
 * The inverse of [netTrapCoords]: the young tree's tile, given the net's.
 *
 * [netAngle] is the *net* loc's angle, which is the tree's - the net is spawned carrying it for
 * exactly this reason. Nothing else records where a net's tree was, so an op that lands on the net
 * has no other way home.
 */
fun netTrapTreeCoords(netCoords: CoordGrid, netAngle: LocAngle): CoordGrid {
    val (dx, dz) = netTrapOffset(netAngle)
    return netCoords.translate(-dx, -dz)
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

    /**
     * The magic box's four states. Constants rather than table columns for the same reason the
     * deadfall's boulder is one: with a single creature in the family, every state is shared by
     * construction and there is nothing per-creature left to store.
     */
    const val MAGIC_BOX_EMPTY: String = "loc.hunting_imptrap_empty"

    /** Mid-catch. Op-less, and direction-independent - there is one, not one per compass side. */
    const val MAGIC_BOX_TRAPPING: String = "loc.hunting_imptrap_trapping"

    /** Holds the catch; carries `Retrieve`. */
    const val MAGIC_BOX_FULL: String = "loc.hunting_imptrap_full"

    /** The wreck; carries `Deactivate`. There is no separate mid-failure frame - see [failingLoc]. */
    const val MAGIC_BOX_FAILED: String = "loc.hunting_imptrap_failed"

    /**
     * The three `name=Young tree` states, which are the ones that sit on a **permanent map loc**.
     *
     * This is the net trap's half of the invariant [HunterTrap] is built around: a young tree is
     * the map's, not ours, so it is only ever reached through `locRepo.change`. The set is what
     * finds a tree on a tile without knowing which of the three states it is wearing.
     */
    val netTrapTreeLocIds: Set<Int> by lazy {
        HunterCreatures.netTrap
            .flatMap { listOfNotNull(it.upLoc, it.settingLoc, it.setLoc) }
            .mapTo(HashSet()) { it.asRSCM(RSCMType.LOC) }
    }

    /**
     * The five `name=Net trap` states, which are spawned beside the tree and may be deleted.
     *
     * These are the only sapling ids a delete is ever legitimate for, which is why the teardown
     * path checks membership of *this* set rather than merely non-membership of
     * [netTrapTreeLocIds].
     */
    val netTrapNetLocIds: Set<Int> by lazy {
        HunterCreatures.netTrap
            .flatMap {
                listOfNotNull(it.netSetLoc, it.trappingLoc, it.fullLoc, it.failingLoc, it.failedLoc)
            }
            .mapTo(HashSet()) { it.asRSCM(RSCMType.LOC) }
    }

    /** All 40 sapling states, both halves. Read only by [HunterTrap]'s never-delete guard. */
    val netTrapLocIds: Set<Int> by lazy { netTrapTreeLocIds + netTrapNetLocIds }

    /** The unset trees, which carry `Set-trap`. */
    val netTrapUpLocIds: Set<Int> by lazy {
        HunterCreatures.netTrap.mapNotNullTo(HashSet()) { it.upLoc?.asRSCM(RSCMType.LOC) }
    }

    /**
     * The two states a tree may be in when a set-trap finishes: still showing the setting frame, or
     * already reverted by that frame's safety timer. The deadfall's `SETTABLE_DEADFALL_LOCS`
     * equivalent.
     */
    val netTrapSettableLocIds: Set<Int> by lazy {
        HunterCreatures.netTrap
            .flatMap { listOfNotNull(it.upLoc, it.settingLoc) }
            .mapTo(HashSet()) { it.asRSCM(RSCMType.LOC) }
    }

    /**
     * The armed states, which carry `Dismantle` / `Investigate` - **one on each tile**. A net trap
     * can be taken down by clicking either the tree or the net, and the cache gives both halves the
     * same two ops precisely so it can be.
     */
    val netTrapArmedLocIds: Set<Int> by lazy {
        HunterCreatures.netTrap
            .flatMap { listOfNotNull(it.setLoc, it.netSetLoc) }
            .mapTo(HashSet()) { it.asRSCM(RSCMType.LOC) }
    }

    /** The nets holding a catch, which carry `Check`. */
    val netTrapFullLocIds: Set<Int> by lazy {
        HunterCreatures.netTrap.mapNotNullTo(HashSet()) { it.fullLoc?.asRSCM(RSCMType.LOC) }
    }

    /** The sprung-and-empty nets, which carry `Dismantle`. */
    val netTrapFailedLocIds: Set<Int> by lazy {
        HunterCreatures.netTrap.mapNotNullTo(HashSet()) { it.failedLoc?.asRSCM(RSCMType.LOC) }
    }

    /** The unset tree a net trap starts and ends at. */
    fun upLoc(creature: HunterCreature): String =
        requireNotNull(creature.upLoc) { "Net trap creature is missing its up loc: ${creature.npc}" }

    /** Shown while the player is stringing the net; carries no ops, hence no content group. */
    fun settingLoc(creature: HunterCreature): String =
        requireNotNull(creature.settingLoc) {
            "Net trap creature is missing its setting loc: ${creature.npc}"
        }

    /** The bent-over tree of an armed net trap. */
    fun armedTreeLoc(creature: HunterCreature): String =
        requireNotNull(creature.setLoc) {
            "Net trap creature is missing its set loc: ${creature.npc}"
        }

    /** The net of an armed net trap, on the tile beside the tree. */
    fun netSetLoc(creature: HunterCreature): String =
        requireNotNull(creature.netSetLoc) {
            "Net trap creature is missing its net_set loc: ${creature.npc}"
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

    /**
     * The state a *portable* trap is laid in, or null for the two families that have none.
     *
     * Null rather than a placeholder: a deadfall is armed in place on its boulder and a net trap on
     * its tree, and neither has a laid state to spawn. Both of those families' armed states are
     * reached from somewhere else - [DEADFALL_ARMED] and [armedTreeLoc] - so returning one here
     * would only give [HunterTrap.layTrap] something to spawn on an empty tile.
     */
    fun setLoc(family: TrapFamily): String? =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap"
            TrapFamily.BOX -> "loc.hunting_boxtrap_empty"
            TrapFamily.MAGICBOX -> MAGIC_BOX_EMPTY
            TrapFamily.DEADFALL,
            TrapFamily.NETTRAP -> null
        }

    /**
     * The mid-catch state, given where the creature stands relative to the trap ([dx] on the x
     * axis, [dz] on the z axis).
     *
     * Every family keys this off the same two offsets and none of them agree on how: the bird
     * snare, the magic box and the net trap ignore the direction entirely, the box trap has one loc
     * per compass side, and the deadfall has a base loc and a mirror of it.
     */
    fun trappingLoc(creature: HunterCreature, dx: Int, dz: Int): String =
        when (creature.family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_trapping_${snareKey(creature)}"
            TrapFamily.BOX -> "loc.hunting_boxtrap_trapping_${boxKey(creature)}_${compass(dx, dz)}"
            TrapFamily.MAGICBOX -> MAGIC_BOX_TRAPPING
            TrapFamily.DEADFALL -> deadfallApproachLoc(creature, dx, dz)
            TrapFamily.NETTRAP ->
                requireNotNull(creature.trappingLoc) {
                    "Net trap creature is missing its catching loc: ${creature.npc}"
                }
        }

    fun fullLoc(creature: HunterCreature): String =
        when (creature.family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_full_${snareKey(creature)}"
            TrapFamily.BOX -> "loc.hunting_boxtrap_full_${boxKey(creature)}"
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

    /**
     * The frame shown between a catch failing and the trap settling into [failedLoc].
     *
     * [creature] is read on the net trap's branch alone, whose net is recoloured per salamander;
     * every other family's failure frame is one loc for its whole table. The magic box has no
     * separate frame at all - the cache holds `empty`, `trapping`, `full` and `failed` and nothing
     * between the last two - so it settles into its wreck immediately and the settle step is a
     * no-op rather than a state change.
     */
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

    /**
     * The state a trap ends in when it caught nothing, or when its lifetime ran out.
     *
     * The three portable families leave a wreck on the ground that still owes the player their trap
     * item. A deadfall leaves nothing: it is a boulder again, which is why this returns
     * [DEADFALL_BOULDER] rather than a state of its own - there is no `hunting_deadfall_failed` in
     * the cache, and inventing one would mean a permanent map loc stuck wearing it. A net trap is
     * in between: its *tree* goes back to [upLoc], and this is what its *net* is left wearing.
     */
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
