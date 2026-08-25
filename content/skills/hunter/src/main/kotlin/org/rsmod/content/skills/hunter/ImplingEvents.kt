package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpNpc1
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Impling catching's one player-facing op.
 *
 * **`op1=Catch` already exists on all six npcs**; none is invented here. `ii_impling_type_1_maze`
 * (1645) through `ii_impling_type_6_maze` (1650) each carry it, so this only routes what the client
 * already draws.
 *
 * Registered by npc rather than by content group, for the reason [ButterflyEvents] and
 * [FalconryEvents] give: a content group would need a `[gamevals.content]` id *and* a `contentGroup`
 * on each npc in `.data/raw-cache/server/npcs.toml` - the two-declaration rule - and six explicit
 * registrations for six npcs is both shorter and impossible to half-declare.
 *
 * There is nothing else to register. No timer, because a catch resolves within the op; no area
 * entry or exit, because the npc ids already carry "in Puro-Puro" on their own; no `onAiConTimer`,
 * because no controller is ever created. `iop3=Loot` on the filled jars is the other half of the
 * reward and is registered here too - it is `iop3` on all six, and like `op1=Catch` it already
 * exists on the objs.
 */
class ImplingEvents @Inject constructor(private val impling: HunterImpling) : PluginScript() {
    override fun ScriptContext.startup() {
        for (creature in ImplingCreatures.all) {
            onOpNpc1(creature.npc) { with(impling) { catchImpling(it.npc) } }
        }
        for (jar in ImplingLoot.jars) {
            onOpHeld3(jar) { with(impling) { openJar(jar) } }
        }
    }
}
