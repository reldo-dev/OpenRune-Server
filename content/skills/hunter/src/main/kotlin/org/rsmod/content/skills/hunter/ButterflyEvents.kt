package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.script.onOpNpc1
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Butterfly netting's one player-facing op.
 *
 * **`op1=Catch` already exists on all five npcs**; none is invented here. `butterfly_ruby` (5556),
 * `butterfly_glacialis` (5555), `butterfly_snowy` (5554), `butterfly_warlock` (5553) and
 * `moth_sunlight` (12770) each carry it, so this only routes what the client already draws.
 *
 * Registered by npc rather than by content group, for the reason [FalconryEvents] gives: a content
 * group would need a `[gamevals.content]` id *and* a `contentGroup` on each npc in
 * `.data/raw-cache/server/npcs.toml` - the two-declaration rule - and five explicit registrations
 * for five npcs is both shorter and impossible to half-declare.
 *
 * There is nothing else to register. No timer, because a catch resolves within the op; no area exit,
 * because nothing is rented; no `onAiConTimer`, because no controller is ever created.
 */
class ButterflyEvents @Inject constructor(private val butterfly: HunterButterfly) : PluginScript() {
    override fun ScriptContext.startup() {
        for (creature in ButterflyCreatures.all) {
            onOpNpc1(creature.npc) { with(butterfly) { catchButterfly(it.npc) } }
        }
    }
}
