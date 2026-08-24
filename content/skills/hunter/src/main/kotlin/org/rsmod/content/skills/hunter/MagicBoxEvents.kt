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
 * The magic box's player-facing ops.
 *
 * Every op here already exists on the cache type - `iop1=Activate` on obj 10025 (`magic_imp_box`),
 * `op1=Deactivate` / `op2=Investigate` on `hunting_imptrap_empty` (19223), `op1=Retrieve` on
 * `hunting_imptrap_full` (19226) and `op1=Deactivate` on `hunting_imptrap_failed` (19224). Nothing
 * here invents an option; it routes the ones the client already draws. Note the held op is
 * `Activate`, not the `Lay` the other two portable families use.
 *
 * All three op1s are the same transaction - clear the tile, hand back whatever is on it - so
 * [HunterTrap.takeTrap] handles the lot with a single registration, exactly as it does for the bird
 * snare and box trap. This is the whole reason the magic box needed no new engine path: it is
 * portable, and portable is the only thing [HunterTrap] asks of it.
 *
 * `onAiConTimer(TRAP_CONTROLLER)` is registered exactly once, in [BirdSnareEvents]; it is
 * family-agnostic and this class deliberately does not repeat it - a second registration would run
 * every laid trap's tick twice per cycle.
 */
class MagicBoxEvents
@Inject
constructor(private val traps: HunterTrap, private val conRepo: ControllerRepository) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onOpHeld1("obj.magic_imp_box") { lay() }
        onOpContentLoc1("content.hunter_magic_box") { takeDown(it.loc) }
        onOpContentLoc2("content.hunter_magic_box") { investigate(it.loc) }
    }

    private fun ProtectedAccess.lay() {
        // "With 71 Hunter, the player may set up a magic box." (wiki, Magic box.) Read off the
        // creature row rather than retyped as a constant the way the box trap's 27 is: this family
        // has exactly one creature, so its lay requirement and its catch requirement are the same
        // number by construction and cannot drift apart.
        val required = HunterCreatures.magicBox.level
        if (player.hunterLvl < required) {
            mes("You need a Hunter level of $required to set up a magic box.")
            return
        }

        with(traps) { layTrap(TrapFamily.MAGICBOX, player.coords) }
    }

    private suspend fun ProtectedAccess.takeDown(loc: BoundLocInfo) {
        arriveDelay()
        with(traps) { takeTrap(loc, TrapFamily.MAGICBOX) }
    }

    /**
     * `Investigate` exists only on the armed (`hunting_imptrap_empty`) state - the full state's op2
     * slot is empty and the failed state has none at all, but [onOpContentLoc2] dispatches on the
     * content group and op slot rather than the label, so the guard lives here. Live's wording is
     * not recoverable offline - the text is server-sent, so it is in neither the cache nor the wiki
     * - so these strings are ours. What they report is the real controller state.
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
            else -> mes("The magic box is set. Nothing has sprung it yet.")
        }
    }

    private companion object {
        private val SET_LOC = HunterTrapStates.MAGIC_BOX_EMPTY.asRSCM(RSCMType.LOC)
    }
}
