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
 * Crab trapping's player-facing ops.
 *
 * **Every op registered here already exists on the cache type; none is invented.**
 * `op1=Build-trap` on `crab_trap_unbuilt` (58904), `op1=Bait` on `crab_trap_built` (58905), and
 * `op1=Empty` on both baited states and all five full ones. There is no op2 anywhere in the family,
 * which is why nothing registers one.
 *
 * **One content group, nine locs, and none of them is the loc the map placed.** The twenty
 * `crab_trap_<site>_<n>` locs carry no ops at all - they are `multiloc` parents whose child is chosen
 * by the viewing player's own varbit. `LocInteractions.opTrigger` resolves that child *before* it
 * looks for a handler, so the group belongs on the children and the event arrives with `loc` as the
 * map-placed site and `vis`/`type` as the state the player can see. The dispatch below therefore
 * reads the state from the **varbit**, not from the loc it was handed: that is the value the client
 * rendered from and the value the server is about to overwrite, so the two cannot disagree.
 *
 * `onAiConTimer` is registered nowhere here. A crab trap has no controller, because it has nothing in
 * the world to anchor one to; the wait between baiting and catching is a soft queue on the player.
 */
class CrabTrapEvents @Inject constructor(private val crabTrap: HunterCrabTrap) : PluginScript() {
    override fun ScriptContext.startup() {
        // Forces the lazy site table, so a crab-trap gameval that does not resolve - a site loc, its
        // varbit, or a full-trap loc a creature row names - throws here rather than at whichever
        // click happens to touch it first. Every site is derived from the packed cache, so this is
        // also what proves the derivation ran at all.
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

    /**
     * The whole family's op1, dispatched on the trap's current state rather than on its loc id.
     *
     * `Build-trap`, `Bait` and `Empty` are three different transactions on one content group - the
     * same shape the deadfall's `Set-trap`/`Dismantle`/`Check` needs - and here the state is a varbit
     * rather than a loc id, so there is nothing to `when` on but the varbit.
     */
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
        /** Five holes on each of The Pandemonium, two Great Conch shores and The Crown Jewel. */
        private const val CRAB_TRAP_SITES: Int = 20
    }
}
