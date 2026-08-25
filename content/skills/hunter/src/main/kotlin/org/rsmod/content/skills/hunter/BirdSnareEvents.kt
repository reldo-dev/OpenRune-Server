package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.repo.controller.ControllerRepository
import org.rsmod.api.script.onAiConTimer
import org.rsmod.api.script.onOpContentLoc1
import org.rsmod.api.script.onOpContentLoc2
import org.rsmod.api.script.onOpHeld1
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * The bird snare's player-facing ops, and the trap tick both families share.
 *
 * Every op here already exists on the cache type - `iop1=Lay` on obj 10006, `op1=Dismantle` /
 * `op2=Investigate` on `hunting_ojibway_trap` (9345), `op1=Dismantle` on
 * `hunting_ojibway_trap_broken` (9344) and `op1=Check` on each `_full_<biome>`. Nothing here
 * invents an option; it routes the ones the client already draws.
 *
 * The three `Dismantle`/`Check` ops are all op1, so `content.hunter_bird_snare` catches all of them
 * with a single registration and [HunterTrap.takeTrap] decides what the tile owes the player.
 */
class BirdSnareEvents
@Inject
constructor(private val traps: HunterTrap, private val conRepo: ControllerRepository) :
    PluginScript() {
    override fun ScriptContext.startup() {
        onOpHeld1("obj.hunting_ojibway_bird_snare") { lay() }
        onOpContentLoc1("content.hunter_bird_snare") { takeDown(it.loc) }
        onOpContentLoc2("content.hunter_bird_snare") { investigate(it.loc) }

        // Registered exactly once in the codebase. The handler keys on the controller type, which
        // is shared by both families, so Task 8's box trap deliberately does not repeat it - a
        // second registration would run every laid trap's tick twice per cycle.
        onAiConTimer(TRAP_CONTROLLER) { with(traps) { controller.hunterTrapTick() } }
    }

    private fun ProtectedAccess.lay() {
        with(traps) { layTrap(TrapFamily.SNARE, player.coords) }
    }

    private suspend fun ProtectedAccess.takeDown(loc: BoundLocInfo) {
        arriveDelay()
        with(traps) { takeTrap(loc, TrapFamily.SNARE) }
    }

    /**
     * `Investigate` exists only on the armed snare. Live's wording for it is not recoverable
     * offline - the text is server-sent, so it is in neither the cache nor the wiki - so these
     * strings are ours. What they report is the real controller state, not a canned line.
     */
    private suspend fun ProtectedAccess.investigate(loc: BoundLocInfo) =
        investigateTrap(loc, noun = "snare") {
            conRepo.findExact(it.coords, TRAP_CONTROLLER)
        }
}
