package org.rsmod.content.skills.hunter

import org.rsmod.api.controller.vars.intVarCon
import org.rsmod.api.player.vars.intVarp
import org.rsmod.game.entity.Controller
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.PlayerUid

/**
 * Everything a laid trap writes to save data, and the sentinels that make it readable.
 *
 * Grouped in one file because this is the module's contract with existing player saves, and it is
 * the surface a new technique is most likely to break by accident. Two rules govern all of it and
 * neither is visible from a single accessor:
 * - [Controller.trapFamily] holds a [TrapFamily] **ordinal**, so that enum may only be appended to.
 * - [Controller.trapCreature] holds an index into **[HunterCreatures.all]**, so that list may only
 *   be appended to. `MagicBoxTest` pins both, the second literally.
 *
 * The negative sentinels exist because a varcon has no null: an unset int reads as 0, which is a
 * legitimate index into both of the above, so "armed and empty" and "sprung and failed" have to be
 * values that no real index can collide with.
 */

/** [Controller.trapCreature] while the trap is still armed and has caught nothing. */
internal const val CREATURE_NONE: Int = -1

/** [Controller.trapCreature] once the trap has sprung and failed. */
internal const val CREATURE_FAILED: Int = -2

/**
 * The value an unwritten `varp.hunter_trap_coord_*` reads back as. `CoordGrid.ZERO` is off-map, so
 * it can never collide with a real trap tile.
 */
private const val EMPTY_TRAP_COORD: Int = 0

/**
 * The value an unwritten `varcon.hunter_trap_deadfall_log` reads back as. No deadfall path ever
 * writes it: every usable log has a positive obj id, so zero unambiguously means "no log recorded",
 * which is the case for every trap of the other two families.
 */
internal const val NO_TRAP_LOG: Int = 0

/** Packed [PlayerUid] of whoever laid the trap. */
var Controller.trapOwner: Int by intVarCon("varcon.hunter_trap_owner")

/** [TrapFamily] ordinal. */
var Controller.trapFamily: Int by intVarCon("varcon.hunter_trap_family")

/**
 * Index into [HunterCreatures.all] once the trap has caught something, or [CREATURE_NONE] /
 * [CREATURE_FAILED]. Together with [trapFamily] this is the whole of a trap's state.
 */
var Controller.trapCreature: Int by intVarCon("varcon.hunter_trap_creature")

/**
 * The obj id of the log a deadfall was armed with, or [NO_TRAP_LOG]. Recorded because dismantling
 * hands that exact log back, and "any type of log" means it is genuinely not derivable.
 */
var Controller.trapDeadfallLog: Int by intVarCon("varcon.hunter_trap_deadfall_log")

private var Player.trapCoord1: Int by intVarp("varp.hunter_trap_coord_1")
private var Player.trapCoord2: Int by intVarp("varp.hunter_trap_coord_2")
private var Player.trapCoord3: Int by intVarp("varp.hunter_trap_coord_3")
private var Player.trapCoord4: Int by intVarp("varp.hunter_trap_coord_4")
private var Player.trapCoord5: Int by intVarp("varp.hunter_trap_coord_5")

/**
 * The packed coords of every trap this player believes it has laid, at most [MAX_LAID_TRAPS].
 *
 * Coords, not a counter. Controllers and timed locs are runtime-only, so a counter leaks: a trap
 * that collapses while the player is away, or a server restart, never runs the decrement and the
 * player permanently loses a slot. A coord can be re-checked against the world, which makes the
 * leak structurally impossible instead of something four code paths have to remember.
 */
var Player.hunterTrapCoords: List<Int>
    get() =
        listOf(trapCoord1, trapCoord2, trapCoord3, trapCoord4, trapCoord5).filter {
            it != EMPTY_TRAP_COORD
        }
    set(value) {
        require(value.size <= MAX_LAID_TRAPS) {
            "Cannot store more than $MAX_LAID_TRAPS trap coords: $value"
        }
        trapCoord1 = value.getOrElse(0) { EMPTY_TRAP_COORD }
        trapCoord2 = value.getOrElse(1) { EMPTY_TRAP_COORD }
        trapCoord3 = value.getOrElse(2) { EMPTY_TRAP_COORD }
        trapCoord4 = value.getOrElse(3) { EMPTY_TRAP_COORD }
        trapCoord5 = value.getOrElse(4) { EMPTY_TRAP_COORD }
    }
