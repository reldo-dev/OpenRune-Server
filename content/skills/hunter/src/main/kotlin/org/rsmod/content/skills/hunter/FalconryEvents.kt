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
 * Falconry's player-facing ops - all present on the cache types. Registered by npc rather than by
 * content group: six explicit registrations are shorter than the two-declaration alternative and
 * impossible to half-declare. `FALCON_CONTROLLER` is its own controller type, so its timer does
 * not collide with the trap tick's.
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
     * Strips the rented glove on the way out - but **not on logout**: `PlayerAreaProcessor` fires
     * area-exits on the logout cycle too, and an unguarded handler would confiscate the glove of
     * anyone who logged off inside the enclosure (docs/hunter.md).
     */
    private fun ProtectedAccess.leaveFalconryArea() {
        if (player.pendingLogout || player.forceDisconnect) {
            return
        }
        with(falconry) { stripFalconGloves() }
    }
}
