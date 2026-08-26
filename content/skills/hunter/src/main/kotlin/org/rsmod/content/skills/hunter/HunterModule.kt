package org.rsmod.content.skills.hunter

import org.rsmod.plugin.module.PluginModule

class HunterModule : PluginModule() {
    override fun bind() {
        bindInstance<HunterTrap>()
        bindInstance<HunterFalconry>()
        bindInstance<HunterButterfly>()
        bindInstance<HunterCrabTrap>()
        bindInstance<HunterImpling>()
        // Bird houses fill on wall-clock time, so the clock is a collaborator rather than a call.
        // Bound as an instance because the production implementation is an object with no state.
        bindSingleton<BirdHouseClock>(SystemBirdHouseClock)
        bindInstance<HunterBirdHouse>()
        bindInstance<HunterTracking>()
        bindInstance<HunterPitfall>()
    }
}
