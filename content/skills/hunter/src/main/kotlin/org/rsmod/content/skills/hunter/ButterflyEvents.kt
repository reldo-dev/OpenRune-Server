package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.script.onOpNpc1
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Butterfly netting's one player-facing op - `op1=Catch` exists on all five npcs. Registered by
 * npc rather than content group, for [FalconryEvents]'s reason. Nothing else to register: a catch
 * resolves within the op and no controller is ever created.
 */
class ButterflyEvents @Inject constructor(private val butterfly: HunterButterfly) : PluginScript() {
    override fun ScriptContext.startup() {
        for (creature in ButterflyCreatures.all) {
            onOpNpc1(creature.npc) { with(butterfly) { catchButterfly(it.npc) } }
        }
    }
}
