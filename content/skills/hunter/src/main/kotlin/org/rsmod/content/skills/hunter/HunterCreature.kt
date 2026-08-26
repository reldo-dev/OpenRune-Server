package org.rsmod.content.skills.hunter

/**
 * Which technique a creature is caught with, and the whole of how its trap behaves.
 *
 * **The order is persisted.** A laid trap stores this enum's `ordinal` in
 * `varcon.hunter_trap_family`, so a new family may only ever be *appended*: inserting one would
 * silently re-file every trap standing in the world when the server restarts.
 */
enum class TrapFamily {
    SNARE,
    BOX,

    /**
     * The deadfall, which is not a trap the player carries at all: the boulder is a permanent map
     * loc, armed in place with a log and a knife. Nothing is laid, nothing is picked back up, and
     * there is no collectible failed state - so the portable-only paths ([HunterTrap.layTrap],
     * [HunterTrap.takeTrap]) reject it, and every state it moves through is a `locRepo.change` on
     * the boulder rather than a spawn and a delete.
     */
    DEADFALL,

    /**
     * The net trap, the only family that is **two locs**: a young tree, which is a permanent map
     * loc exactly like the deadfall's boulder, plus a spawned "Net trap" on the tile the tree's own
     * angle points at ([netTrapCoords]).
     *
     * The split is not ours - it is how the cache names the eight states. `up`, `setting` and `set`
     * are all `name=Young tree` and live on the tree's tile; `net_set`, `catching`, `full`,
     * `failing` and `failed` are all `name=Net trap` and live on the tile beside it. The wiki's own
     * *Net trap* scenery infobox lists only the second five, confirming the first three belong to a
     * different object.
     *
     * Which half carries a given op therefore decides which tile an op arrives on, and every op
     * that lands on the net has to walk back to the tree, where the controller is anchored.
     */
    NETTRAP,

    /**
     * The magic box, which is portable and reuses the [SNARE]/[BOX] path wholesale - but is not a
     * [BOX].
     *
     * `HunterTrapStates` names a box trap's loc states `hunting_boxtrap_*` with a per-creature
     * suffix; the imp's are the unsuffixed `hunting_imptrap_*` set, and it is laid from
     * `obj.magic_imp_box` rather than `obj.hunting_box_trap`. Filing it under [BOX] would resolve
     * to loc names that do not exist - an RSCM throw at the first catch.
     */
    MAGICBOX;

    /**
     * True for the three families laid from an inventory item onto an empty tile, false for the two
     * that are armed in place on a loc the map already supplied.
     *
     * The distinction is not cosmetic: deleting a deadfall boulder or a young tree the way a
     * portable trap's tile is cleared would take that loc out of the world permanently, because
     * `LocRepository` only schedules a respawn for a delete with a finite duration.
     */
    val portable: Boolean
        get() =
            when (this) {
                SNARE,
                BOX,
                MAGICBOX -> true
                DEADFALL,
                NETTRAP -> false
            }

    /**
     * Whether a player standing on the trap stops it catching anything.
     *
     * Sourced per family, and *not* the same split as [portable]:
     * - "A bird snare will not catch birds if the user is standing directly on the bird snare."
     * - "Box traps won't trap prey if players are standing on the trap itself." (*Box trap >
     *   Mechanics*)
     * - "it may be caught, but only when the player is not standing on the trap" (*Magic box*)
     * - "it may be caught, but only when the player is not standing on the net" (*Net trap*) - note
     *   *the net*, which for this family is the second loc, not the tile the controller sits on.
     * - "Deadfall traps are not prone to failure by standing where they are set." (*Deadfall*) The
     *   one family the wiki exempts, and the one whose trap is a boulder rather than something
     *   underfoot.
     */
    val suppressedByPlayerOnTile: Boolean
        get() =
            when (this) {
                SNARE,
                BOX,
                MAGICBOX,
                NETTRAP -> true
                DEADFALL -> false
            }

    /**
     * How far from the trap a creature is drawn in from, in tiles, measured Chebyshev.
     *
     * Only the box trap's radius is sourced; see the constants themselves for what each is and where
     * it came from. Held here rather than as a `when` inside [HunterTrap] so that all four
     * per-family attribute tables sit together and a sixth family has one file to answer in.
     */
    val triggerDistance: Int
        get() =
            when (this) {
                SNARE -> SNARE_TRIGGER_DISTANCE
                BOX -> BOX_TRAP_TRIGGER_DISTANCE
                MAGICBOX -> MAGIC_BOX_TRIGGER_DISTANCE
                DEADFALL -> DEADFALL_TRIGGER_DISTANCE
                NETTRAP -> NET_TRAP_TRIGGER_DISTANCE
            }

    /** How often an armed trap rolls for a catch, in cycles. Only the box trap's is sourced. */
    val attemptCycles: Int
        get() =
            when (this) {
                SNARE -> SNARE_ATTEMPT_CYCLES
                BOX -> BOX_TRAP_ATTEMPT_CYCLES
                MAGICBOX -> MAGIC_BOX_ATTEMPT_CYCLES
                DEADFALL -> DEADFALL_ATTEMPT_CYCLES
                NETTRAP -> NET_TRAP_ATTEMPT_CYCLES
            }
}

/**
 * One reward line of a catch: an obj and how many of it a single catch awards.
 *
 * A range rather than a count because the bird snare needs one: every bird's wiki infobox lists
 * bones and raw bird meat at "Quantity: 1 | Rarity: Always" but its feather at "Quantity: 5-10 |
 * Rarity: Always", so the quantity is genuinely per-item and genuinely rolled. Chinchompas award a
 * flat one, which is the default here.
 */
data class HunterCatch(val obj: String, val quantity: IntRange = 1..1)

/**
 * Builds one catch's reward lines from the three parallel columns that describe them.
 *
 * The bird snare, the deadfall and falconry all store a catch as `caught_items` plus a `caught_min`
 * and a `caught_max` of the same length, entry `i` of each describing the same line. All three
 * re-derived the same guard and the same `mapIndexed`, which meant three wordings for one failure.
 *
 * The guard is the point of the function rather than a formality. The deadfall's columns are
 * genuinely ragged - wild kebbit, barb-tailed kebbit and pyre fox award three lines, prickly and
 * sabre-toothed award two, because neither drops meat - so nothing may assume a fixed width. A
 * column edit that drops one entry has to fail by name at boot, not as an `IndexOutOfBounds` on the
 * one tick that catches the one creature affected.
 */
internal fun parallelCatches(
    rowId: Int,
    objs: List<String>,
    min: List<Int>,
    max: List<Int>,
): List<HunterCatch> {
    require(min.size == objs.size && max.size == objs.size) {
        "Row $rowId has mismatched caught reward sizes: items=${objs.size}, " +
            "min=${min.size}, max=${max.size}"
    }
    return objs.mapIndexed { i, obj -> HunterCatch(obj, min[i]..max[i]) }
}

/**
 * A single laid-trap creature.
 *
 * [successLow] and [successHigh] are the `(low, high)` pair fed into
 * `SkillingSuccessRate.successRate(low, high, level, maxLevel)`, which computes
 * `(1 + floor(low * (99 - L) / 98 + high * (L - 1) / 98 + 0.5)) / 256` - a 1/256 scale with a +1
 * bias, *not* the `/255` scale the wiki's own `P(L)` formulas are written on. They are the
 * coefficients of that engine formula, fit to reproduce each creature's charted per-level success
 * chance, not the formula evaluated to a probability. A negative [successLow]
 * (black/carnivorous chinchompa, four of the five deadfall creatures) reproduces "if the player's
 * Hunter level is too low, the trap will always fail" on its own, with no guard code. Creatures
 * with a non-negative [successLow] - regular chinchompa's is +6 - do not get that guard implicitly;
 * [HunterTrap.hunterTrapTick] gates the roll explicitly on `owner.hunterLvl >= level`.
 *
 * [caught] is a list because a single catch can award more than one item - a bird snare catch
 * always awards bones, raw bird meat, and a species feather in one go - and each entry carries its
 * own quantity, because the feather's is not one.
 *
 * [locKey] is the suffix the bird snare's and box trap's loc states are named by -
 * `loc.hunting_ojibway_trap_full_<locKey>`, `loc.hunting_boxtrap_full_<locKey>` - and is null for
 * the three families whose states are stored whole instead. It is packed data rather than something
 * derived from [npc] because three of the ten creatures that need it cannot be derived: the tropical
 * wagtail is `npc.multicoloured_bird` on the `_coloured` states, the ferret has no `hunting_bird_`
 * prefix to strip, and the embertailed jerboa is `npc.varlamore_hunterjerboa01` on the `_jerboa`
 * states.
 *
 * The loc columns are all null for the three portable families, which name their states with
 * [locKey] instead:
 * - [trappingLoc] and [fullLoc] are shared by the two fixed-loc families. The net trap's packed
 *   `catching_loc` fills [trappingLoc] - the cache's word for the state differs, the meaning does
 *   not: it is the frame shown between the catch landing and the trap settling.
 * - [trappingLocM] is deadfall-only; nothing else has a mirrored approach model.
 * - [upLoc], [settingLoc], [setLoc] and [netSetLoc] are net-trap-only, and split across that
 *   family's two tiles: the first three are the young tree, the fourth is the net beside it.
 * - [failingLoc] and [failedLoc] are net-trap-only too, because that family recolours its net per
 *   salamander where every other family's failure frames are one loc for the whole table.
 */
data class HunterCreature(
    val family: TrapFamily,
    val npc: String,
    val level: Int,
    val xp: Int,
    val caught: List<HunterCatch>,
    val successLow: Int,
    val successHigh: Int,
    val locKey: String? = null,
    val trappingLoc: String? = null,
    val trappingLocM: String? = null,
    val fullLoc: String? = null,
    val upLoc: String? = null,
    val settingLoc: String? = null,
    val setLoc: String? = null,
    val netSetLoc: String? = null,
    val failingLoc: String? = null,
    val failedLoc: String? = null,
)
