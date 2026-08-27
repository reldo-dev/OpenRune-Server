package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpHeld4
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpWorn2
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerLogout
import org.rsmod.api.script.onPlayerSoftQueue
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Tracking's player-facing ops - all present on the cache types. Registered per loc gameval, not
 * by content group (a group would patch loc.toml ~90 times); the gameval is what handlers
 * dispatch on, with catch spots the deliberate coordinate-matched exception. See docs/hunter.md.
 */
class TrackingEvents @Inject constructor(private val tracking: HunterTracking) : PluginScript() {
    override fun ScriptContext.startup() {
        // Fails the boot, not the first click, when authored geometry strands a burrow: every
        // origin must resolve to at least one enumerated trail. `TrackingNetworksTest` checks the
        // same invariant independently of this boot path.
        for (network in TrackingNetworks.all) {
            for ((origin, trails) in network.trailsByOrigin) {
                check(trails.isNotEmpty()) {
                    "${network.area}: burrow origin $origin has no valid trail."
                }
            }
        }

        for ((loc, network) in TrackingNetworks.burrowLocs) {
            onOpLoc1(loc) {
                arriveDelay()
                with(tracking) { inspectBurrow(network, loc, it.loc) }
            }
        }

        for ((loc, network) in TrackingNetworks.clueLocs) {
            onOpLoc1(loc) {
                arriveDelay()
                // inspectClue's signature takes no BoundLocInfo, unlike its neighbours here, so the
                // handler faces the clicked loc itself before delegating.
                faceLoc(it.loc)
                with(tracking) { inspectClue(network, loc) }
            }
        }

        // Catch spots key on coordinates instead of the gameval - the one deliberate exception, and
        // why [it.loc] rather than [loc] is passed on both ops below.
        for ((loc, network) in TrackingNetworks.catchLocs) {
            onOpLoc1(loc) {
                arriveDelay()
                with(tracking) { searchCatchSpot(network, it.loc) }
            }
            onOpLoc2(loc) {
                arriveDelay()
                with(tracking) { attackCatchSpot(network, it.loc) }
            }
        }

        // The ring of pursuit's own ops - the only handlers here that are obj ops rather than loc
        // ops, and the only ones not driven by the authored tables. `Break` is what makes the
        // charge counter something a player can act on: the count belongs to the player, so a
        // part-spent allowance follows them onto the next ring until one is deliberately broken.
        //
        // The worn `Check` is op2 and not op3: `WornInteractions` maps `IfButtonOp.Op2` to
        // `param.wear_op1`, which is the param the ring carries its `Check` string in. Tumeken's
        // shadow wires the identical param the same way.
        onOpHeld3(HunterTracking.RING_OF_PURSUIT) { with(tracking) { checkRingCharges() } }
        onOpHeld4(HunterTracking.RING_OF_PURSUIT) {
            with(tracking) { breakRing(it.inventory, it.slot) }
        }
        onOpWorn2(HunterTracking.RING_OF_PURSUIT) { with(tracking) { checkRingCharges() } }

        // Without this, every player who logs out mid-trail is retained forever in
        // `HunterTracking`'s `IdentityHashMap`.
        onPlayerLogout { tracking.discardState(player) }

        // loginReset must run from the soft queue, never directly in the login event: a varp/varbit
        // write from `onPlayerLogin` updates the server and leaves the client rendering the stale
        // value, because `VarPlayerIntMapSetter` short-circuits its transmit branch while
        // `processedMapClock == 0` - exactly the state during login. See [TRACKING_RESET_QUEUE] and
        // `HunterTracking.loginReset`.
        onPlayerLogin { player.softQueue(TRACKING_RESET_QUEUE, 1) }
        onPlayerSoftQueue(TRACKING_RESET_QUEUE) { tracking.loginReset(player) }
    }
}
