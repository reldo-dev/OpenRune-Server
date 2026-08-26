package org.rsmod.content.skills.hunter

/**
 * Every tuned number the five trap families run on, and where each one came from - which wiki page,
 * which revision, which sentence, and where a figure is ours because no source states it.
 */

/** The controller type every laid trap is anchored to, whichever family it belongs to. */
const val TRAP_CONTROLLER: String = "controller.hunter_trap"

/**
 * The trap's lifetime in map cycles, i.e. how long an untouched trap has before it collapses.
 *
 * 100 cycles is ~1 minute at 0.6s/cycle, seeded from RuneLite's client-side `HunterTrap.TRAP_TIME`
 * overlay figure. That is not server truth - it is a starting value to confirm in-game.
 */
const val TRAP_LIFETIME_CYCLES: Int = 100

/**
 * How long the `_trapping_` / `_failing_` loc is shown before it settles into `_full_` /
 * `_failed_`.
 *
 * The design records that live's real duration for these intermediate states is not answerable
 * offline; a fixed short step is the honest model, and it is the only reason both states exist as
 * separate locs at all.
 */
const val TRAP_SPRING_CYCLES: Int = 2

/**
 * How long a collapsed trap is left on the ground after its controller is gone. Finite so the loc
 * cleans itself up rather than being stranded by an owner who never comes back for it.
 */
const val TRAP_COLLAPSE_LINGER_CYCLES: Int = 100

/**
 * How close a creature has to be to a box trap to be lured into it, in tiles.
 *
 * "Any ferret or chinchompa within a 2-tile radius of the box trap (forming a 5x5 square centred on
 * the trap) can be attracted." (wiki, *Box trap > Mechanics*).
 */
const val BOX_TRAP_TRIGGER_DISTANCE: Int = 2

/**
 * The bird snare's equivalent of [BOX_TRAP_TRIGGER_DISTANCE].
 *
 * Unsourced. Neither the *Bird snare* page nor *Hunter > Hunting techniques > Bird snaring* states
 * a radius - the latter says only that snares "have a chance to attract and catch birds as they fly
 * by" - and no cache record carries one, because the catch is entirely server-side. Adjacency is
 * the conservative reading and is what has been in place since the snare landed; do not promote it
 * to the box trap's 2 without a source.
 */
const val SNARE_TRIGGER_DISTANCE: Int = 1

/**
 * How often a laid box trap rolls for a catch, in cycles.
 *
 * "Once a box trap has been set, it will make an attempt every 3 ticks (1.8 seconds) to lure in an
 * animal that is currently in range." (wiki, *Box trap > Mechanics*). Rolling every cycle instead
 * would triple the effective catch rate at the same per-attempt chance.
 */
const val BOX_TRAP_ATTEMPT_CYCLES: Int = 3

/**
 * The bird snare's equivalent of [BOX_TRAP_ATTEMPT_CYCLES].
 *
 * Unsourced, like [SNARE_TRIGGER_DISTANCE]: the wiki gives the 3-tick cadence for the box trap
 * only. One attempt per cycle is what the snare has always done here, kept as the accepted
 * approximation rather than borrowed from the other family.
 */
const val SNARE_ATTEMPT_CYCLES: Int = 1

/**
 * The deadfall's equivalent of [BOX_TRAP_TRIGGER_DISTANCE].
 *
 * Unsourced, exactly like [SNARE_TRIGGER_DISTANCE]: the *Deadfall* page describes what the trap is
 * and what cannot set it, never a radius, and the catch is server-side so no cache record carries
 * one either. Adjacency is the conservative reading - the boulder falls on an animal that has
 * walked under it - and it should not be promoted to the box trap's 2 without a source.
 */
const val DEADFALL_TRIGGER_DISTANCE: Int = 1

/**
 * The deadfall's equivalent of [BOX_TRAP_ATTEMPT_CYCLES].
 *
 * Unsourced. The wiki gives the 3-tick cadence for the box trap only. Borrowing that number here
 * is a guess, not a derivation; it is used rather than the snare's every-cycle roll because a
 * deadfall is capped at one at a time and rolling three times as often would quietly triple the
 * rate of the family that is meant to be the slow one.
 */
const val DEADFALL_ATTEMPT_CYCLES: Int = 3

/**
 * How long the boulder shows `hunting_deadfall_setting` before it becomes an armed trap.
 *
 * Unsourced. No source states how long fitting the log takes, and the state exists in the cache
 * with no ops, so nothing outside the animation depends on the exact figure. Three cycles is a
 * placeholder, and not a derivation from the animation either: `seq.human_laytrap` (5208) is 16
 * frames whose delays sum to 152, longer than three cycles under any reading of that unit. The
 * animation simply outlasts the state change, which costs nothing - the player keeps playing it
 * while the boulder is already armed.
 */
const val DEADFALL_SET_CYCLES: Int = 3

/**
 * The Hunter level a deadfall can first be set at.
 *
 * The wild kebbit's 23 - the lowest of the five deadfall creatures on the *Deadfall* page's own
 * Creatures table. The same shape of gate as the box trap's 27 in [BoxTrapEvents]: without it, the
 * whole deadfall table would be armable from level 1 and would start paying out the moment the
 * player's level caught up with a creature.
 */
const val DEADFALL_LEVEL_REQ: Int = 23

/**
 * "Unlike most hunter traps, only one deadfall trap can be set up at once, generally resulting in
 * slower experience rates." (wiki, *Deadfall*). This is on top of, not instead of, the shared
 * [trapCap][MAX_LAID_TRAPS] allowance.
 */
const val MAX_LAID_DEADFALLS: Int = 1

/**
 * The two logs a deadfall cannot be armed with.
 *
 * "Redwood logs and arctic pine logs cannot be used for deadfall traps." (wiki, *Deadfall*,
 * oldid=15201193). Note `obj.arctic_pine_log` is singular in the cache while every other log is
 * plural; the plural spelling resolves to nothing.
 */
private val DEADFALL_EXCLUDED_LOGS: Set<String> = setOf("obj.redwood_logs", "obj.arctic_pine_log")

/**
 * The Treasure Trails logs, withheld from the deadfall so a clue step cannot be eaten by one.
 *
 * These are ordinary firemaking rows - `obj.blue_logs`, `obj.green_logs`, `obj.red_logs`,
 * `obj.trail_logs_purple` and `obj.trail_logs_white` all have an input row in the packed logs table
 * - so reading "any type of log" off that table sweeps them in, and the set-trap path picks the
 * first usable log by slot order. A player carrying a clue step's coloured logs above their
 * ordinary ones would have the clue item destroyed to arm a boulder.
 *
 * Whether live accepts them is unsourced; the *Deadfall* page names only redwood and arctic pine.
 * Refusing is the recoverable half of that uncertainty - a player told they need logs fetches
 * ordinary ones, where a player whose coloured log was consumed has to redo the clue step. They are
 * excluded outright rather than merely ranked last, so that a player holding *only* coloured logs
 * is refused instead of quietly charged one.
 */
private val DEADFALL_TRAIL_LOGS: Set<String> =
    setOf(
        "obj.blue_logs",
        "obj.green_logs",
        "obj.red_logs",
        "obj.trail_logs_purple",
        "obj.trail_logs_white",
    )

/**
 * Whether a log can arm a deadfall.
 *
 * The domain is the packed firemaking logs table's inputs - "any type of log" is read off that
 * table rather than retyped as a list here, so a log added to firemaking is usable for deadfall on
 * the same day. This is only the exclusion half of that rule - the sourced
 * [DEADFALL_EXCLUDED_LOGS] and the protective [DEADFALL_TRAIL_LOGS] - kept pure and separate so the
 * same predicate the set-trap path applies to every packed row can be tested without a cache. It
 * does not itself assert that [objKey] is a log.
 *
 * Because it is the whole of the eligibility decision, the set-trap path's own choice stays a plain
 * first-by-slot-order pick with no second ranking pass to keep in step with this.
 */
fun isUsableDeadfallLog(objKey: String): Boolean =
    objKey !in DEADFALL_EXCLUDED_LOGS && objKey !in DEADFALL_TRAIL_LOGS

/**
 * The net trap's equivalent of [BOX_TRAP_TRIGGER_DISTANCE], measured from the **net**, not the tree.
 *
 * Unsourced, exactly like [SNARE_TRIGGER_DISTANCE] and [DEADFALL_TRIGGER_DISTANCE]: the *Net trap*
 * page says only that a salamander "may be caught" as it "passes the trap", never a radius, and the
 * catch is server-side so no cache record carries one either. Adjacency is the conservative reading
 * and it should not be promoted to the box trap's 2 without a source.
 *
 * That it is measured from the net follows from the same sentence the occupancy guard comes from -
 * "only when the player is not standing on the net" - which is the only tile the wiki treats as the
 * business end of this family.
 */
const val NET_TRAP_TRIGGER_DISTANCE: Int = 1

/**
 * The net trap's equivalent of [BOX_TRAP_ATTEMPT_CYCLES].
 *
 * Unsourced. The wiki gives the 3-tick cadence for the box trap only. Borrowed here rather than the
 * snare's every-cycle roll for the same reason [DEADFALL_ATTEMPT_CYCLES] borrows it: a net trap is
 * a normal multi-trap technique with published per-level rates, and rolling three times as often as
 * the family those rates were measured on would quietly triple them.
 */
const val NET_TRAP_ATTEMPT_CYCLES: Int = 3

/**
 * How long the tree shows `hunting_sapling_setting_*` before it becomes an armed trap.
 *
 * Unsourced, and the same placeholder as [DEADFALL_SET_CYCLES] for the same reasons: no source
 * states how long stringing the net takes, and the state exists in the cache with no ops, so
 * nothing outside the animation depends on the exact figure.
 */
const val NET_TRAP_SET_CYCLES: Int = 3

/**
 * How long the rope and net a failed net trap drops stay on the ground.
 *
 * Sourced: "the small fishing net and rope will appear on the ground, which the player should pick
 * up, or it will eventually disappear in approximately a minute" (wiki, *Net trap*, oldid=15272929).
 * 100 cycles is ~1 minute at 0.6s/cycle - the same arithmetic [TRAP_LIFETIME_CYCLES] uses, and by
 * coincidence the same number.
 */
const val NET_TRAP_DROP_CYCLES: Int = 100

/**
 * The magic box's equivalent of [BOX_TRAP_TRIGGER_DISTANCE].
 *
 * Unsourced. The *Magic box* page states the occupancy rule and the bait bonus and nothing about
 * range. Adjacency is the conservative reading, consistent with every other family whose radius is
 * unstated; it is deliberately *not* borrowed from the box trap despite the shared name, since the
 * only reason to think the two agree is that both are boxes.
 */
const val MAGIC_BOX_TRIGGER_DISTANCE: Int = 1

/**
 * The magic box's equivalent of [BOX_TRAP_ATTEMPT_CYCLES].
 *
 * Unsourced, like [MAGIC_BOX_TRIGGER_DISTANCE]. The box trap's 3-tick cadence is borrowed rather
 * than the snare's every-cycle roll, on the same reasoning as [DEADFALL_ATTEMPT_CYCLES]: the imp's
 * per-level rate is published, and rolling every cycle would treble the rate that curve describes.
 */
const val MAGIC_BOX_ATTEMPT_CYCLES: Int = 3

internal const val ROPE: String = "obj.rope"

/** `obj.net` is the cache symbol for the Small fishing net (303); `obj.small_fishing_net` is not. */
internal const val SMALL_FISHING_NET: String = "obj.net"

/**
 * What a net trap is strung from, what a successful catch hands back, and what a failed one drops.
 *
 * "With the required Hunter level, a rope and small fishing net in the inventory, clicking on a
 * young tree will set the trap." (wiki, *Net trap*, oldid=15272929.)
 *
 * Held in one place rather than duplicated between the set-trap path and the dismantle path, which
 * is the duplicated-truth bug the reference implementation of this family has.
 */
internal val NET_TRAP_COMPONENTS: List<String> = listOf(ROPE, SMALL_FISHING_NET)

/** The most traps any player can have laid, reached at level 80. */
const val MAX_LAID_TRAPS: Int = 5
