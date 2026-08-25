package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.rsmod.api.game.process.GameCycle
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.server.app.GameServer

/**
 * The spawner, driven by the **real** game loop instead of a fake world.
 *
 * Every other test in this module builds its own world out of hand-written fakes, which is fine for
 * logic but cannot prove the one thing the spawner depends on: that it is reached at all. This boots
 * the real Guice injector, loads the real map and the real plugin scripts, and steps `GameCycle`
 * directly - so the markers are the ones in `.data`, the registration is the one `ImplingEvents`
 * performs, and `GameLifecycle.LateCycle` is published by the engine rather than called by the test.
 *
 * It is also fast, which is the point. 200 game cycles is two minutes of wall clock on a running
 * server; here it is 200 calls to `tick()` and the whole test is seconds. Stepping the clock rather
 * than sleeping on it is what makes this deterministic where driving a client is not.
 *
 * Lives in its own source set because a booted server contaminates the unit-test JVM - the cache
 * manager and registries are singletons - and a second boot in one JVM dies outright.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ImplingSpawnerLoopTest {
    @Test
    fun everyMarkerInTheMapDataBecomesAnAnchor() {
        val world = world()
        world.cycle.tick()

        // The markers `.data` actually places: 33 common, 19 uncommon, 4 rare, 4 rare `_maze` and
        // the single Prifddinas one. Asserted against the count rather than a shape, so a marker
        // set that shrinks - or a symbol this spawner stops recognising - fails here.
        assertEquals(61, world.spawner.anchorCount, "the spawner should adopt every placed marker")
    }

    /**
     * A marker in the real map produces a real impling once the reveal delay elapses.
     *
     * The claim the fakes could not make. Nothing places a catchable impling at this tile - only the
     * invisible marker - so anything found here came from the spawner running on the engine's own
     * cycle.
     */
    @Test
    fun aMarkerProducesAnImplingOnTheRealLoop() {
        val world = world()

        repeat(REVEAL_CYCLES + 5) { world.cycle.tick() }

        assertNotNull(world.implingNear(FELDIP_MARKER), "no impling appeared near $FELDIP_MARKER")
    }

    private class BootedWorld(val cycle: GameCycle, val spawner: ImplingSpawner, val npcs: NpcRepository) {
        /**
         * Searched over a zone radius rather than the exact tile: implings carry `wanderRange` 100,
         * so one that spawned cycles ago has had time to walk off the marker it came from. Finding
         * it *near* the marker rather than *on* it is the honest assertion, and it incidentally
         * shows the wander override is doing something.
         */
        fun implingNear(coords: CoordGrid): String? =
            npcs
                .findAll(ZoneKey.from(coords), zoneRadius = 2)
                .filter { it.isVisible }
                .firstNotNullOfOrNull { npc -> ImplingCreatures.byNpcId(npc.visType.id)?.npc }
    }

    /**
     * The booted world, built once per JVM.
     *
     * A second `createInjector` in the same JVM dies on `Key already registered`, so this is shared
     * rather than per-test. Both tests only read and advance, and the first is ordered to run before
     * the impling one purely by name.
     */
    private fun world(): BootedWorld = shared ?: bootedWorld().also { shared = it }

    private fun bootedWorld(): BootedWorld {
        val server = GameServer()
        val injector = server.createInjector()
        server.prepareGame(injector)
        return BootedWorld(
            cycle = injector.getInstance(GameCycle::class.java),
            spawner = injector.getInstance(ImplingSpawner::class.java),
            npcs = injector.getInstance(NpcRepository::class.java),
        )
    }

    private companion object {
        private var shared: BootedWorld? = null

        /** [ImplingSpawner]'s own two-minute reveal delay. */
        private const val REVEAL_CYCLES = 200

        /** A common marker in Feldip Hills, from `.data/raw-cache/map/npcs/feldip_hills.toml`. */
        private val FELDIP_MARKER = CoordGrid(0, 39, 44, 4, 59)
    }
}
