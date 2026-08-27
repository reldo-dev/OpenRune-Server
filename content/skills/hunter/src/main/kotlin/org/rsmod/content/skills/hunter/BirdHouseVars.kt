package org.rsmod.content.skills.hunter

import org.rsmod.api.player.vars.intVarp
import org.rsmod.game.entity.Player

/**
 * The two things a bird house needs that its packed varp has no room for - never transmitted,
 * both `Perm`. The module's second contract with existing player saves.
 */

/** Zero can only mean unwritten: a real deadline is an epoch minute, ~29.6 million and rising. */
private const val NO_BIRDHOUSE_DEADLINE: Int = 0

private var Player.birdHouseReady1: Int by intVarp("varp.hunter_birdhouse_ready_1")
private var Player.birdHouseReady2: Int by intVarp("varp.hunter_birdhouse_ready_2")
private var Player.birdHouseReady3: Int by intVarp("varp.hunter_birdhouse_ready_3")
private var Player.birdHouseReady4: Int by intVarp("varp.hunter_birdhouse_ready_4")

private var Player.birdHouseSeeds1: Int by intVarp("varp.hunter_birdhouse_seeds_1")
private var Player.birdHouseSeeds2: Int by intVarp("varp.hunter_birdhouse_seeds_2")
private var Player.birdHouseSeeds3: Int by intVarp("varp.hunter_birdhouse_seeds_3")
private var Player.birdHouseSeeds4: Int by intVarp("varp.hunter_birdhouse_seeds_4")

/**
 * The epoch minute each space finishes filling. Wall clock, because the fill must elapse while its
 * owner is logged out - only a saved var outlives the session. Named delegates, not a computed
 * name, so a bad varp symbol throws at boot.
 */
internal var Player.birdHouseReadyAt: List<Int>
    get() = listOf(birdHouseReady1, birdHouseReady2, birdHouseReady3, birdHouseReady4)
    set(value) {
        require(value.size == BIRDHOUSE_SPACES) {
            "Expected $BIRDHOUSE_SPACES bird house deadlines: $value"
        }
        birdHouseReady1 = value[0]
        birdHouseReady2 = value[1]
        birdHouseReady3 = value[2]
        birdHouseReady4 = value[3]
    }

/**
 * How many of the ten seed units each space has taken so far, while it is in the `_built` state.
 *
 * Needs its own var because partial progress is **invisible to the client**: a tier has three
 * states, and a house holding six seeds shows the same `(empty)` model as one holding none. The
 * alternative - refusing anything short of a full fill - would strand a player carrying six seeds,
 * and the wiki is explicit that types may be mixed and that a click inserts as many as possible.
 *
 * Reset to zero when the house starts filling, so a dismantled-and-rebuilt space never inherits the
 * previous house's progress.
 */
internal var Player.birdHouseSeedUnits: List<Int>
    get() = listOf(birdHouseSeeds1, birdHouseSeeds2, birdHouseSeeds3, birdHouseSeeds4)
    set(value) {
        require(value.size == BIRDHOUSE_SPACES) {
            "Expected $BIRDHOUSE_SPACES bird house seed counts: $value"
        }
        birdHouseSeeds1 = value[0]
        birdHouseSeeds2 = value[1]
        birdHouseSeeds3 = value[2]
        birdHouseSeeds4 = value[3]
    }

internal fun Player.birdHouseReadyAt(space: BirdHouseSpace): Int = birdHouseReadyAt[space.index]

internal fun Player.setBirdHouseReadyAt(space: BirdHouseSpace, epochMinute: Int) {
    birdHouseReadyAt = birdHouseReadyAt.toMutableList().also { it[space.index] = epochMinute }
}

internal fun Player.clearBirdHouseReadyAt(space: BirdHouseSpace) {
    setBirdHouseReadyAt(space, NO_BIRDHOUSE_DEADLINE)
}

internal fun Player.birdHouseSeedUnits(space: BirdHouseSpace): Int = birdHouseSeedUnits[space.index]

internal fun Player.setBirdHouseSeedUnits(space: BirdHouseSpace, units: Int) {
    birdHouseSeedUnits = birdHouseSeedUnits.toMutableList().also { it[space.index] = units }
}

/** The number of spaces both var blocks are sized against. */
internal const val BIRDHOUSE_SPACES: Int = 4
