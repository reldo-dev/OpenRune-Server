package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpContentLoc1
import org.rsmod.api.script.onOpContentLoc2
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The net trap's player-facing ops - all present on the cache types. One content group covers both
 * halves of the trap, so the dispatch keys on the loc *id*, and [HunterTrap] walks from a net back
 * to the tree its controller is anchored to. `Reset` is out of scope, as on the box trap, so
 * [investigate] guards on the armed loc ids.
 */
class NetTrapEvents @Inject constructor(private val traps: HunterTrap) : PluginScript() {
    override fun ScriptContext.startup() {
        // Forces the lazy sets: a bad sapling gameval must throw here, not first surface via the
        // never-delete guard that reads the tree set.
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

    /** `Investigate` exists only on the two armed states; the wording is ours (docs/hunter.md). */
    private suspend fun ProtectedAccess.investigate(loc: BoundLocInfo) =
        investigateTrap(
            loc,
            noun = "net trap",
            armed = { it.id in HunterTrapStates.netTrapArmedLocIds },
        ) {
            traps.netTrapController(it)
        }
}
