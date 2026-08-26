package org.rsmod.content.skills.hunter

import org.rsmod.api.player.vars.intVarp
import org.rsmod.game.entity.Player

/**
 * The two things a bird house needs that its packed varp has no room for.
 *
 * The space's own varp (1626-1629) holds `0..27` and that is the whole of what the *client* needs:
 * which tier, and which of its three states. Neither of the values below is ever transmitted, and
 * both are `Perm`, because a bird house fills on wall-clock time and its owner is expected to log
 * out during the fifty minutes.
 *
 * Grouped in one file for the reason [HunterTrapVars] is: this is the module's second contract with
 * existing player saves, and it is the surface a later technique is most likely to break by
 * accident.
 */

/**
 * The value an unwritten `varp.hunter_birdhouse_ready_*` reads back as, and therefore the value that
 * means "this space has no deadline".
 *
 * Zero is safe as a sentinel in a way it would not be for a tick counter: the stored value is an
 * *epoch minute*, so a genuine deadline is around 29.6 million and rising. A zero can only mean
 * unwritten, and it reads correctly for every account that has never touched a bird house.
 */
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
 * The epoch minute each space's bird house finishes filling, or [NO_BIRDHOUSE_DEADLINE].
 *
 * Wall clock rather than cycles, because the fill has to elapse while its owner is logged out - the
 * one requirement that rules out every runtime timer the engine has. A queue, a controller and a
 * soft timer all die with the session; only a saved var outlives it.
 *
 * Four named delegates rather than one indexed accessor because `intVarp` resolves its var by name
 * at class load: a computed name would resolve to nothing and throw at the first seeding rather than
 * at boot, which is the failure this module keeps rediscovering.
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
