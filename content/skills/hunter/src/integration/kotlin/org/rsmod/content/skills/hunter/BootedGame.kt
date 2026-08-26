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
 * Every test here only reads the world or advances it. Nothing removes locs, and each test that
 * logs a player in logs it out again, so ordering between classes does not matter.
 */
internal object BootedGame {
    val injector: Injector by lazy {
        val server = GameServer()
        val injector = server.createInjector()
        server.prepareGame(injector)
        injector
    }
}
