package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpContentLoc1
import org.rsmod.api.script.onOpContentLoc2
import org.rsmod.api.script.onOpContentLoc3
import org.rsmod.api.script.onOpContentLoc4
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftQueueWithArgs
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Bird house trapping's player-facing ops.
 *
 * **Every op registered here already exists on the cache type; none is invented.** Read off the 28
 * packed children:
 *
 * | state | op1 | op2 | op3 | op4 |
 * |---|---|---|---|---|
 * | `birdhouse_not_built` | `Build` | | | |
 * | `<tier>_built` | | `Seeds` | | |
 * | `<tier>_full` | | `Seeds` | `Dismantle` | |
 * | `<tier>_bird` | `Interact` | `Seeds` | `Empty` | `Reset` |
 *
 * **That grid is why one content group is right here rather than four.** The op *indices* line up
 * across the states even though the labels do not: op1 is `Build` on a bare space and `Interact` on
 * a full one, op2 is `Seeds` wherever it appears, op3 is `Dismantle` on a filling house and `Empty`
 * on a full one, and op4 is `Reset` alone. Four handlers cover the whole family, each dispatching on
 * the space's varp - which is the value the client rendered from and the value the server is about
 * to overwrite, so the two cannot disagree. Splitting the group per state would put the same `when`
 * in four places and buy nothing.
 *
 * **None of the 28 is the loc the map placed.** The four `birdhouse_<n>` spaces carry no ops at all;
 * they are `multiloc` parents whose child is chosen by the viewing player's own varp, and
 * `LocInteractions.opTrigger` resolves that child *before* it looks for a handler. So the group
 * belongs on the children and the event arrives with `loc` as the map-placed space - exactly the
 * shape [CrabTrapEvents] documents.
 *
 * `onAiConTimer` is registered nowhere here. A bird house has no controller: nothing of it lives in
 * the world, and its fifty minutes are wall-clock time that no controller could outlive anyway.
 */
class BirdHouseEvents @Inject constructor(private val birdHouse: HunterBirdHouse) : PluginScript() {
    override fun ScriptContext.startup() {
        // Forces the lazy space table, so a bird house gameval that does not resolve - a space loc,
        // its varp, or a child loc a tier row names - throws here rather than at whichever click
        // happens to touch it first. Every space is derived from the packed cache, so this is also
        // what proves the derivation ran at all.
        check(BirdHouseSpaces.all.size == BIRDHOUSE_SPACES) {
            "Expected $BIRDHOUSE_SPACES bird house spaces, resolved ${BirdHouseSpaces.all.size}."
        }

        onOpContentLoc1(BIRD_HOUSE_GROUP) { op1(it.loc) }
        onOpContentLoc2(BIRD_HOUSE_GROUP) { op2(it.loc) }
        onOpContentLoc3(BIRD_HOUSE_GROUP) { op3(it.loc) }
        onOpContentLoc4(BIRD_HOUSE_GROUP) { op4(it.loc) }

        onPlayerSoftQueueWithArgs<Int>(BIRDHOUSE_FILL_QUEUE) {
            with(birdHouse) { player.birdHouseFillArrives(args) }
        }

        // The deadline is saved with the player and the queue is not, so the in-session half has to
        // be rebuilt on the way back in - and anything that matured while the player was away has to
        // be noticed. Both are the same call.
        onPlayerLogin { with(birdHouse) { player.rearmBirdHouseFills() } }
    }

    /** `Build` on a bare space, `Interact` on a full house. */
    private suspend fun ProtectedAccess.op1(loc: BoundLocInfo) {
        arriveDelay()
        matureBefore(loc)
        with(birdHouse) {
            if (BirdHouseTypes.stateOf(spaceState(loc)) == null) {
                buildBirdHouse(loc)
            } else {
                inspectBirdHouse(loc)
            }
        }
    }

    /** `Seeds`, on all three built states. The refusals live in the handler. */
    private suspend fun ProtectedAccess.op2(loc: BoundLocInfo) {
        arriveDelay()
        matureBefore(loc)
        with(birdHouse) { addBirdHouseSeeds(loc) }
    }

    /** `Dismantle` on a filling house, `Empty` on a full one. */
    private suspend fun ProtectedAccess.op3(loc: BoundLocInfo) {
        arriveDelay()
        matureBefore(loc)
        with(birdHouse) {
            when (BirdHouseTypes.stateOf(spaceState(loc))) {
                BirdHouseState.Filling -> dismantleBirdHouse(loc)
                BirdHouseState.Full -> emptyBirdHouse(loc)
                else -> inspectBirdHouse(loc)
            }
        }
    }

    /** `Reset` on a full house: the payout and a fresh house in one action. */
    private suspend fun ProtectedAccess.op4(loc: BoundLocInfo) {
        arriveDelay()
        matureBefore(loc)
        with(birdHouse) { emptyBirdHouse(loc, rebuild = true) }
    }

    /**
     * Matures the space if its fifty minutes elapsed while nothing was watching.
     *
     * The self-healing half of the timer, and the reason a lost soft queue costs nothing: a player
     * who logs in and walks straight to a house that finished overnight gets a full one whether or
     * not the login re-arm's queue has fired yet. It runs *after* [ProtectedAccess.arriveDelay], so
     * a house that matures during the walk is already full by the time the op reads its state.
     */
    private fun ProtectedAccess.matureBefore(loc: BoundLocInfo) {
        val space = BirdHouseSpaces.byLocId(loc.id) ?: return
        with(birdHouse) { player.matureBirdHouse(space) }
    }

    private fun ProtectedAccess.spaceState(loc: BoundLocInfo): Int {
        val space = BirdHouseSpaces.byLocId(loc.id) ?: return BirdHouseSpaces.BARE
        return with(birdHouse) { player.birdHouseState(space) }
    }

    private companion object {
        private const val BIRD_HOUSE_GROUP = "content.hunter_bird_house"
    }
}
