package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpHeld3
import org.rsmod.api.script.onOpNpc1
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Impling catching's one player-facing op.
 *
 * **`op1=Catch` already exists on every impling npc**; none is invented here. Both forms of all
 * twelve carry it - the overworld `ii_impling_type_1..12` and the Puro-Puro `_maze` ids alike - so
 * this only routes what the client already draws.
 *
 * Registered by npc rather than by content group, for the reason [ButterflyEvents] and
 * [FalconryEvents] give: a content group would need a `[gamevals.content]` id *and* a `contentGroup`
 * on each npc in `.data/raw-cache/server/npcs.toml` - the two-declaration rule - and six explicit
 * registrations for six npcs is both shorter and impossible to half-declare.
 *
 * Beyond those two ops and the spawner's cycle hook there is nothing to register: no timer,
 * because a catch resolves within the op; no area entry or exit, because the npc ids already carry
 * "in Puro-Puro" on their own; no `onAiConTimer`, because no controller is ever created. `iop3=Loot` on the filled jars is the other half of the
 * reward and is registered here too - it is `iop3` on all six, and like `op1=Catch` it already
 * exists on the objs.
 */
class ImplingEvents
@Inject
constructor(private val impling: HunterImpling, private val spawner: ImplingSpawner) :
    PluginScript() {
    override fun ScriptContext.startup() {
        // The one thing here that is not an op. `.data` places invisible markers rather than
        // implings, so without this every overworld impling and every high-tier Puro-Puro one has
        // nothing to click; see [ImplingSpawner].
        onEvent<GameLifecycle.LateCycle> { spawner.tick() }

        for (creature in ImplingCreatures.all) {
            // Both of the creature's ids. They are one creature with one rate and one reward, but
            // two npcs the client can draw `Catch` on, and registering only the Puro-Puro form
            // would leave every overworld impling uncatchable. `distinct` because the crystal
            // impling has no Puro-Puro form and holds the same id in both columns.
            for (npc in listOf(creature.npc, creature.npcOverworld).distinct()) {
                onOpNpc1(npc) { with(impling) { catchImpling(it.npc) } }
            }
        }
        for (jar in ImplingLoot.jars) {
            onOpHeld3(jar) { with(impling) { openJar(jar) } }
        }
    }
}
