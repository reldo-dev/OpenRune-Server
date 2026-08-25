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
 * The deadfall's player-facing ops.
 *
 * Every op here already exists on the cache type - `op1=Set-trap` on `hunting_deadfall_boulder`
 * (19215), `op1=Dismantle` / `op2=Investigate` on `hunting_deadfall_trap` (19217), and `op1=Check`
 * on each `_full_<creature>`. As with [BirdSnareEvents] and [BoxTrapEvents], nothing here invents an
 * option; it routes the ones the client already draws.
 *
 * All three op1s share one content group, so the single registration below dispatches on which
 * boulder state was clicked. That is the whole reason the deadfall cannot reuse
 * [HunterTrap.takeTrap] the way the two portable families do: `Set-trap` and `Dismantle` are
 * opposite transactions on the same content group, not two spellings of "clear this tile".
 *
 * There is no dispatch to write for op2: `Investigate` is the only op2 in this group, and it exists
 * on the armed state alone (the transient setting, trapping and failing frames carry no ops at all,
 * which is why they have no content group).
 *
 * `onAiConTimer(TRAP_CONTROLLER)` is registered exactly once, in [BirdSnareEvents]; it is
 * family-agnostic and this class deliberately does not repeat it - a second registration would run
 * every laid trap's tick twice per cycle.
 */
class DeadfallEvents
@Inject
constructor(private val traps: HunterTrap, private val conRepo: ControllerRepository) :
    PluginScript() {
    override fun ScriptContext.startup() {
        // Forces the lazy set, so a deadfall loc gameval that does not resolve throws here rather
        // than at whichever call site happens to touch it first. That matters because the set is
        // read by [HunterTrap]'s guard on the *portable* teardown path: without this, a bad
        // deadfall gameval would first surface as a failed bird snare pickup. The two loc ids in
        // the companion below already resolve eagerly at class load for the same reason.
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
            // The `_full_<creature>` states, one per deadfall creature, all carrying `Check`.
            in HunterTrapStates.deadfallFullLocIds -> with(traps) { collectTrap(loc) }
            else -> mes("Nothing interesting happens.")
        }
    }

    /**
     * `Investigate` exists only on the armed state. Live's wording for it is not recoverable
     * offline - the text is server-sent, so it is in neither the cache nor the wiki - so these
     * strings are ours. What they report is the real controller state, not a canned line.
     */
    private suspend fun ProtectedAccess.investigate(loc: BoundLocInfo) =
        investigateTrap(loc, noun = "deadfall") {
            conRepo.findExact(it.coords, TRAP_CONTROLLER)
        }

    private companion object {
        private val BOULDER_LOC = HunterTrapStates.DEADFALL_BOULDER.asRSCM(RSCMType.LOC)
        private val ARMED_LOC = HunterTrapStates.DEADFALL_ARMED.asRSCM(RSCMType.LOC)
    }
}
