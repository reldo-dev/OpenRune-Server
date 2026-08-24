package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpContentLoc1
import org.rsmod.api.script.onOpContentLoc2
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The net trap's player-facing ops.
 *
 * Every op here already exists on the cache type - `op1=Set-trap` on each `hunting_sapling_up_*`,
 * `op1=Dismantle` / `op2=Investigate` on each `_set_*` **and** each `_net_set_*`, `op1=Check` on
 * each `_full_*`, and `op1=Dismantle` on each `_failed_*`. As with the three families before it,
 * nothing here invents an option; it routes the ones the client already draws.
 *
 * **One content group, two physically distinct locs.** `content.hunter_net_trap` covers both halves
 * of the trap - the young tree the player clicks to set it and the "Net trap" that appears on the
 * tile beside it - so the dispatch below has to key on the loc *id*, not on the group. Slice 1's
 * deadfall needed the same shape for a different reason (`Set-trap` and `Dismantle` are opposite
 * transactions on one boulder); here the states are additionally split across two tiles, and it is
 * [HunterTrap] that walks from a net back to the tree its controller is anchored to.
 *
 * `Reset` is the op2 on the `_full_*` and `_failed_*` states and is out of scope for v1, exactly as
 * it is for the box trap. Because [onOpContentLoc2] dispatches on the content group and op slot
 * rather than on the op's label, [investigate] guards on the loc id for that reason.
 *
 * `onAiConTimer(TRAP_CONTROLLER)` is registered exactly once, in [BirdSnareEvents]; it is
 * family-agnostic and this class deliberately does not repeat it - a second registration would run
 * every laid trap's tick twice per cycle.
 */
class NetTrapEvents @Inject constructor(private val traps: HunterTrap) : PluginScript() {
    override fun ScriptContext.startup() {
        // Forces the lazy sets, so a sapling loc gameval that does not resolve throws here rather
        // than at whichever call site happens to touch it first. That matters most for the tree
        // half, which is read by [HunterTrap]'s guard against the portable teardown path ever
        // deleting a permanent map loc.
        check(HunterTrapStates.netTrapTreeLocIds.isNotEmpty()) {
            "No net trap tree loc ids resolved; the net trap content group would never match."
        }
        check(HunterTrapStates.netTrapNetLocIds.isNotEmpty()) {
            "No net trap net loc ids resolved; a set net trap could never be taken down."
        }

        onOpContentLoc1("content.hunter_net_trap") { op1(it.loc) }
        onOpContentLoc2("content.hunter_net_trap") { investigate(it.loc) }
    }

    private suspend fun ProtectedAccess.op1(loc: BoundLocInfo) {
        arriveDelay()
        when (loc.id) {
            in HunterTrapStates.netTrapUpLocIds -> with(traps) { setNetTrap(loc) }
            // Both halves of an armed trap carry `Dismantle`, so either one takes it down.
            in HunterTrapStates.netTrapArmedLocIds -> with(traps) { dismantleNetTrap(loc) }
            in HunterTrapStates.netTrapFullLocIds -> with(traps) { collectNetTrap(loc) }
            // A sprung-and-empty net. Its rope and net are already on the ground - the failure
            // dropped them - so this only clears the wreck.
            in HunterTrapStates.netTrapFailedLocIds -> with(traps) { dismantleNetTrap(loc) }
            else -> mes("Nothing interesting happens.")
        }
    }

    /**
     * `Investigate` exists only on the two armed states; every other state under this content group
     * reaches op2 via `Reset`, which v1 does not implement. Live's wording is not recoverable
     * offline - the text is server-sent, so it is in neither the cache nor the wiki - so these
     * strings are ours. What they report is the real controller state, not a canned line.
     */
    private suspend fun ProtectedAccess.investigate(loc: BoundLocInfo) {
        arriveDelay()
        if (loc.id !in HunterTrapStates.netTrapArmedLocIds) {
            mes("Nothing interesting happens.")
            return
        }

        val controller = traps.netTrapController(loc)
        when {
            controller == null -> mes("This trap has collapsed.")
            controller.trapOwner != player.uid.packed -> mes("This isn't your trap.")
            else -> mes("The net trap is set. Nothing has sprung it yet.")
        }
    }
}
