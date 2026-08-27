package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.controller.ControllerRepository
import org.rsmod.api.script.onOpContentLoc1
import org.rsmod.api.script.onOpContentLoc2
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The deadfall's player-facing ops - all present on the cache types. Its three op1s share one
 * content group, so the registration dispatches on which boulder state was clicked: `Set-trap` and
 * `Dismantle` are opposite transactions on one group, which is why [HunterTrap.takeTrap] cannot be
 * reused here.
 */
class DeadfallEvents
@Inject
constructor(private val traps: HunterTrap, private val conRepo: ControllerRepository) :
    PluginScript() {
    override fun ScriptContext.startup() {
        // Forces the lazy set: a bad deadfall gameval must throw here, not first surface as a
        // failed bird snare pickup via the portable teardown guard that reads this set.
        check(HunterTrapStates.deadfallLocIds.isNotEmpty()) {
            "No deadfall loc ids resolved; the deadfall content group would never match."
        }

        onOpContentLoc1("content.hunter_deadfall") { op1(it.loc) }
        onOpContentLoc2("content.hunter_deadfall") { investigate(it.loc) }
    }

    private suspend fun ProtectedAccess.op1(loc: BoundLocInfo) {
        arriveDelay()
        when (loc.id) {
            BOULDER_LOC -> with(traps) { setDeadfall(loc) }
            ARMED_LOC -> with(traps) { dismantleDeadfall(loc) }
            in HunterTrapStates.deadfallFullLocIds -> with(traps) { collectTrap(loc) }
            else -> mes("Nothing interesting happens.")
        }
    }

    /** Live's server-sent `Investigate` wording is not recoverable offline; the strings are ours. */
    private suspend fun ProtectedAccess.investigate(loc: BoundLocInfo) =
        investigateTrap(loc, noun = "deadfall") {
            conRepo.findExact(it.coords, TRAP_CONTROLLER)
        }

    private companion object {
        private val BOULDER_LOC = HunterTrapStates.DEADFALL_BOULDER.asRSCM(RSCMType.LOC)
        private val ARMED_LOC = HunterTrapStates.DEADFALL_ARMED.asRSCM(RSCMType.LOC)
    }
}
