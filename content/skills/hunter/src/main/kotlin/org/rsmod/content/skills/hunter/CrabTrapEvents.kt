package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpContentLoc1
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftQueueWithArgs
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Crab trapping's player-facing ops - all present on the cache types. One content group covers the
 * nine lifecycle locs, which are `multiloc` children of the op-less map-placed sites; the dispatch
 * reads the state from the *varbit*, the value the client rendered from and the server is about to
 * overwrite. No controller anywhere: the wait is a soft queue on the player.
 */
class CrabTrapEvents @Inject constructor(private val crabTrap: HunterCrabTrap) : PluginScript() {
    override fun ScriptContext.startup() {
        // Forces the lazy site table so a bad gameval throws here, not at the first click; also
        // proves the cache derivation ran at all.
        check(CrabTrapSites.all.size == CRAB_TRAP_SITES) {
            "Expected $CRAB_TRAP_SITES crab trap sites, resolved ${CrabTrapSites.all.size}."
        }

        onOpContentLoc1("content.hunter_crab_trap") { op1(it.loc) }

        onPlayerSoftQueueWithArgs<Int>(CRAB_CATCH_QUEUE) {
            with(crabTrap) { player.crabTrapCatchArrives(args) }
        }

        // A baited trap's varbit is saved with the player and its pending catch is not, so the
        // queue has to be rebuilt on the way back in or the trap stays baited forever.
        onPlayerLogin { with(crabTrap) { player.rearmCrabTrapCatches() } }
    }

    // Three transactions on one group, dispatched on the varbit - the only state there is.
    private suspend fun ProtectedAccess.op1(loc: BoundLocInfo) {
        arriveDelay()
        val site = CrabTrapSites.byLocId(loc.id)
        if (site == null) {
            mes("Nothing interesting happens.")
            return
        }
        val state = with(crabTrap) { player.crabTrapState(site) }
        with(crabTrap) {
            when {
                state == site.unbuiltState -> buildCrabTrap(loc)
                state == site.builtState -> baitCrabTrap(loc)
                site.isActive(state) -> emptyCrabTrap(loc)
                else -> mes("Nothing interesting happens.")
            }
        }
    }

    private companion object {
        // Five holes each on The Pandemonium, two Great Conch shores and The Crown Jewel.
        private const val CRAB_TRAP_SITES: Int = 20
    }
}
