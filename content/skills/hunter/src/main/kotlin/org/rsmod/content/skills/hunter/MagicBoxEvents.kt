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
 * The magic box's player-facing ops - all present on the cache types (the held op is `Activate`,
 * not `Lay`). All three op1s are the same clear-the-tile transaction, so [HunterTrap.takeTrap]
 * handles the lot: the magic box is portable, and portable is all [HunterTrap] asks of it.
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
        // Read off the creature row: one creature in the family, so the lay requirement and the
        // catch requirement cannot drift apart.
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

    /** `Investigate` exists only on the armed state; the wording is ours (docs/hunter.md). */
    private suspend fun ProtectedAccess.investigate(loc: BoundLocInfo) =
        investigateTrap(loc, noun = "magic box", armed = { it.id == SET_LOC }) {
            conRepo.findExact(it.coords, TRAP_CONTROLLER)
        }

    private companion object {
        private val SET_LOC = HunterTrapStates.MAGIC_BOX_EMPTY.asRSCM(RSCMType.LOC)
    }
}
