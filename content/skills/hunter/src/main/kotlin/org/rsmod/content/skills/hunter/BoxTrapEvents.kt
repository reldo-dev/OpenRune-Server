package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.repo.controller.ControllerRepository
import org.rsmod.api.script.onOpContentLoc1
import org.rsmod.api.script.onOpContentLoc2
import org.rsmod.api.script.onOpHeld1
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The box trap's player-facing ops.
 *
 * Every op here already exists on the cache type - `iop1=Lay` on obj 10008 (`hunting_box_trap`),
 * `op1=Dismantle` / `op2=Investigate` on `hunting_boxtrap_empty` (9380), `op1=Check` on each
 * `_full_<creature>`, and `op1=Dismantle` on `hunting_boxtrap_failed` (9385). As with
 * [BirdSnareEvents], nothing here invents an option; it routes the ones the client already draws.
 * `hunting_boxtrap_failed` was missing its `content.hunter_box_trap` group entirely - added to
 * `loc.toml` alongside this class, the same gap Task 7 closed for `hunting_ojibway_trap_broken`
 * - so a collapsed box trap was previously unclearable. [HunterTrap.takeTrap] already handles that
 *   branch; it is reused here, not reimplemented.
 *
 * Unlike the bird snare, the box trap's `_full_<creature>` and `_failed` states carry a real op2 of
 * their own - `Reset`, which re-arms a collapsed trap in place. That is explicitly out of scope for
 * v1 (design decision, not a cache gap). Because [onOpContentLoc2] dispatches on the content group
 * and op slot, not on the op's label, the single op2 registration below would otherwise run
 * `investigate()`'s text against a Reset click too; [investigate] guards on the loc id for exactly
 * that reason.
 *
 * `onAiConTimer(TRAP_CONTROLLER)` is registered exactly once, in [BirdSnareEvents]; it is
 * family-agnostic and this class deliberately does not repeat it - a second registration would run
 * every laid trap's tick twice per cycle.
 */
class BoxTrapEvents
@Inject
constructor(private val traps: HunterTrap, private val conRepo: ControllerRepository) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onOpHeld1("obj.hunting_box_trap") { lay() }
        onOpContentLoc1("content.hunter_box_trap") { takeDown(it.loc) }
        onOpContentLoc2("content.hunter_box_trap") { investigate(it.loc) }
    }

    private fun ProtectedAccess.lay() {
        // The family gate: separate from the per-creature roll gate in HunterTrap.hunterTrapTick,
        // which only stops a catch, not the lay itself. Without this, the level-53 chinchompa
        // table would be layable - and, once a player's level catches up, catchable - from level
        // 27 (the lowest box-trap creature level in the design spec) downward.
        if (player.hunterLvl < BOX_TRAP_LEVEL_REQ) {
            mes("You need a Hunter level of $BOX_TRAP_LEVEL_REQ to lay a box trap.")
            return
        }

        // Missing gate: live also requires Eagles' Peak to be completed before box traps can be
        // used at all (wiki, "Hunting techniques > Box trapping"). The quest is not modelled
        // anywhere in this repo - zero references in content/quest, api/, or the gamevals - so
        // there is no quest string to hand QuestRequirements.hasCompleted. Left unenforced rather
        // than fabricating a check; wire this the day the quest lands.

        with(traps) { layTrap(TrapFamily.BOX, player.coords) }
    }

    private suspend fun ProtectedAccess.takeDown(loc: BoundLocInfo) {
        arriveDelay()
        with(traps) { takeTrap(loc, TrapFamily.BOX) }
    }

    /**
     * `Investigate` exists only on the armed (`hunting_boxtrap_empty`) state; every other state
     * under this content group reaches op2 via `Reset`, which v1 does not implement. See the class
     * doc for why that guard has to live here rather than in the loc data.
     */
    private suspend fun ProtectedAccess.investigate(loc: BoundLocInfo) {
        arriveDelay()
        if (loc.id != SET_LOC) {
            mes("Nothing interesting happens.")
            return
        }

        val controller = conRepo.findExact(loc.coords, TRAP_CONTROLLER)
        when {
            controller == null -> mes("This trap has collapsed.")
            controller.trapOwner != player.uid.packed -> mes("This isn't your trap.")
            else -> mes("The box trap is set. Nothing has sprung it yet.")
        }
    }

    private companion object {
        private const val BOX_TRAP_LEVEL_REQ = 27
        private val SET_LOC =
            checkNotNull(HunterTrapStates.setLoc(TrapFamily.BOX)).asRSCM(RSCMType.LOC)
    }
}
