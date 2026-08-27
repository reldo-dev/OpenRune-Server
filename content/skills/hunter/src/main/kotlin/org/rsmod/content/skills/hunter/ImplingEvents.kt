package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpNpc1
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Impling catching's two player-facing ops - both present on the cache types. Registered by npc
 * rather than content group, for [ButterflyEvents]'s reason.
 *
 * Beyond those two ops and the spawner's cycle hook there is nothing to register: no timer, because
 * a catch resolves within the op; no area entry or exit, because the npc ids already carry "in
 * Puro-Puro" on their own; no `onAiConTimer`, because no controller is ever created.
 */
class ImplingEvents
@Inject
constructor(private val impling: HunterImpling, private val spawner: ImplingSpawner) :
    PluginScript() {
    override fun ScriptContext.startup() {
        // `.data` places invisible markers rather than implings; without this tick every
        // overworld impling has nothing to click (see [ImplingSpawner]).
        onEvent<GameLifecycle.LateCycle> { spawner.tick() }

        for (creature in ImplingCreatures.all) {
            // Both ids, or every overworld impling is uncatchable; `distinct` for the crystal
            // impling, whose two columns hold the same id.
            for (npc in listOf(creature.npc, creature.npcOverworld).distinct()) {
                onOpNpc1(npc) { with(impling) { catchImpling(it.npc) } }
            }
        }
        for (jar in ImplingLoot.jars) {
            onOpHeld3(jar) { with(impling) { openJar(jar) } }
        }
    }
}
