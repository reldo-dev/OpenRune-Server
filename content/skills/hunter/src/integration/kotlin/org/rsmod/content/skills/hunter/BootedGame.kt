package org.rsmod.content.skills.hunter

import com.google.inject.Injector
import org.rsmod.server.app.GameServer

/**
 * The one booted server this source set gets.
 *
 * A second boot in the same JVM does not fail politely - `GameMapDecoder` re-registers the map
 * areas into the same `AreaIndex` and throws `Key already registered`. The suite runs
 * single-threaded in a single JVM by design (see the `integration` suite in `build.gradle.kts`), so
 * the boot has to be shared across *classes*, not merely across the tests in one class.
 *
 * Booting is also the whole cost of this suite - roughly nine seconds against a handful of
 * milliseconds for everything the tests then do - so sharing is what keeps a real-world test cheap
 * enough to run on every change.
 *
 * Every test here only reads the world or advances it: nothing adds or removes a loc or an npc,
 * and each test that logs a player in logs it out again in a `finally`, so no test leaves a
 * registered player behind even when an assertion fails.
 *
 * One piece of state IS shared and mutated, and it is worth knowing before adding a test here: the
 * `GameCycle` clock. Walking a trail ticks it a hundred-odd times, so a later class sees a world
 * that has already advanced. That is benign for what lives here today - `ImplingSpawner` adopts a
 * fixed list of map markers, so its anchor count cannot drift, and extra cycles only make a spawn
 * more likely to have happened - but a test that asserts something has *not* happened yet, or
 * counts events per cycle, would be reading someone else's clock. Write those against their own
 * counters, not against the shared one.
 */
internal object BootedGame {
    val injector: Injector by lazy {
        val server = GameServer()
        val injector = server.createInjector()
        server.prepareGame(injector)
        injector
    }
}
