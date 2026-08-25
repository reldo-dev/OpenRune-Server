package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onAiConTimer
import org.rsmod.api.script.onAreaExit
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onOpNpc3
import org.rsmod.game.entity.Npc
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Falconry's player-facing ops.
 *
 * **Every op registered here already exists on the cache type; none is invented.** Matthias
 * (`hunting_npc_falconer`, 1340) carries `op3=Quick-falcon`, each kebbit carries `op1=Catch`
 * (`huntingbeast_speedy` 5531 and its two siblings), and each falcon-with-prey carries
 * `op1=Retrieve` (`hunting_falcon_onspeedy` 1342, `hunting_falcon_onsilent` 1344,
 * `hunting_falcon_onspeedy2` 1343). This class routes what the client already draws.
 *
 * Registered by npc rather than by content group, unlike the trap families' loc ops. A content group
 * would need a `[gamevals.content]` id *and* a `contentGroup` on each npc in
 * `.data/raw-cache/server/npcs.toml` - the two-declaration rule - and six explicit registrations for
 * six npcs is both shorter and impossible to half-declare.
 *
 * `onAiConTimer(FALCON_CONTROLLER)` is registered here and only here. It is a different controller
 * type from the trap's, so it does not collide with [BirdSnareEvents]'s registration of
 * `TRAP_CONTROLLER`; both would run every cycle regardless, on their own controllers.
 */
class FalconryEvents @Inject constructor(private val falconry: HunterFalconry) : PluginScript() {
    override fun ScriptContext.startup() {
        onOpNpc3("npc.hunting_npc_falconer") { with(falconry) { rentFalcon() } }

        for (creature in FalconryCreatures.all) {
            onOpNpc1(creature.npc) { catchKebbit(it.npc) }
            onOpNpc1(creature.falconNpc) { retrieveFalcon(it.npc) }
        }

        onAiConTimer(FALCON_CONTROLLER) { with(falconry) { controller.falconTick() } }

        onAreaExit("area.piscatoris_falconry") { leaveFalconryArea() }
    }

    private suspend fun ProtectedAccess.catchKebbit(target: Npc) {
        with(falconry) { catchKebbit(target) }
    }

    private fun ProtectedAccess.retrieveFalcon(falcon: Npc) {
        with(falconry) { retrieveFalcon(falcon) }
    }

    /**
     * Strips the rented glove on the way out - but **not on logout**.
     *
     * The guard is load-bearing and easy to miss: `PlayerAreaProcessor` deliberately fires area-exit
     * queues on the cycle a player is queued to log out (`forceExitAreas = pendingLogout ||
     * forceDisconnect`), so an unguarded handler here would confiscate the glove of anyone who
     * simply logged off inside the enclosure. The wiki is explicit that this is wrong: the enclosure
     * strips on leaving "whether by walking out, crossing the stile, or teleporting", and the
     * activity is re-armed on the next login instead.
     *
     * Walking out and teleporting both land here with no extra work, because that same processor
     * recomputes areas from `coords` alone and does not care how the coords changed.
     */
    private fun ProtectedAccess.leaveFalconryArea() {
        if (player.pendingLogout || player.forceDisconnect) {
            return
        }
        with(falconry) { stripFalconGloves() }
    }
}
