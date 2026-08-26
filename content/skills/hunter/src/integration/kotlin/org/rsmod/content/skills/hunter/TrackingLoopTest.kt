package org.rsmod.content.skills.hunter

import com.google.inject.Injector
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.util.Wearpos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.rsmod.api.game.process.GameCycle
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.interact.LocInteractions
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.loc.LocRegistry
import org.rsmod.api.registry.player.PlayerRegistry
import org.rsmod.api.registry.player.PlayerRegistryResult
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Tracking, driven by the **real** game loop instead of a fake world.
 *
 * The unit suite reaches `inspectBurrow`, `inspectClue` and `attackCatchSpot` by calling them, over
 * a hand-built world of two locs and a scripted RNG. That proves the bodies. It cannot prove any of
 * the three things below, and neither can `hunterVerify`, which decodes the packed cache offline
 * and never boots anything:
 * 1. that a real boot puts the ops on the bus at all, for all ninety-seven registrations;
 * 2. that the authored coordinates name locs a booted world actually holds - `GameMapDecoder`
 *    filters map groups and shifts locs down a plane on bridge tiles, so "placed in the cache"
 *    and "clickable in a running server" are different claims;
 * 3. that a player can walk a trail end to end through the engine's own interaction path.
 *
 * Nothing here is scripted. The trail the server rolls is whatever its own `GameRandom` picks; the
 * walk re-derives which trail that was from the segment varbits the client would render, which is
 * the only channel a real player has either. See [walkATrail].
 *
 * Lives in the integration source set for the reason [ImplingSpawnerLoopTest] does: a booted server
 * contaminates the unit-test JVM, and a second boot in one JVM dies outright.
 */
@Execution(ExecutionMode.SAME_THREAD)
class TrackingLoopTest {
    /**
     * Every tracking op is on the bus after a real boot.
     *
     * `HunterWiringTest` asks the same question of a synthetic bus it populated itself by calling
     * `startup()`. This asks it of the bus `PluginScriptLoader` populated - so a `TrackingEvents`
     * that never got scanned, or that threw during startup and was skipped, fails here and passes
     * there. The lookup is `EventBus.contains`, which is the exact call `LocInteractions.opTrigger`
     * makes when deciding whether a click has a handler.
     */
    @Test
    fun theRealBootRegistersEveryTrackingOp() {
        val world = world()
        var registrations = 0

        for (loc in TrackingNetworks.burrowLocs.keys) {
            assertTrue(world.hasOpLoc1(loc), "no op1 (Inspect) handler for burrow $loc")
            registrations++
        }

        for (loc in TrackingNetworks.clueLocs.keys) {
            assertTrue(world.hasOpLoc1(loc), "no op1 (Inspect) handler for clue $loc")
            registrations++
        }

        for (loc in TrackingNetworks.catchLocs.keys) {
            assertTrue(world.hasOpLoc1(loc), "no op1 (Search) handler for catch spot $loc")
            assertTrue(world.hasOpLoc2(loc), "no op2 (Attack) handler for catch spot $loc")
            registrations += 2
        }

        // 12 burrows + 75 clues + 5 catch gamevals on two ops each. Pinned as a literal so a
        // network dropped from `TrackingNetworks.all` fails here rather than shrinking the loops
        // above into a vacuous pass.
        assertEquals(97, registrations, "tracking should register 97 loc ops")
    }

    /**
     * Every coordinate `TrackingNetworks` authors names a loc the booted world actually holds.
     *
     * All 119 of them - 12 burrows, 75 clues, 32 catch spots - because the lookup is a zone hash
     * and the whole sweep costs milliseconds against an already-booted world.
     *
     * `hunterVerify` check 7 asks this of the packed cache. This asks it of [LocRegistry], which is
     * where a click lands: `GameMapDecoder` drops map groups past its cutoff and shifts locs down a
     * plane on `LINK_BELOW` tiles, so a coordinate can be correct in the cache and absent from the
     * running world. The catch spots are the ones that would hurt - `isHotSpot` compares the
     * authored tile against `BoundLocInfo.coords` straight out of this registry, so a one-plane
     * shift there is a kebbit that can never be caught, with no error anywhere.
     */
    @Test
    fun theAuthoredTrailLocsArePlacedInTheBootedWorld() {
        val world = world()
        val missing = mutableListOf<String>()
        var checked = 0

        for (network in TrackingNetworks.all) {
            for (burrow in network.burrows) {
                checked++
                world.locOrNull(burrow.coords, burrow.loc)
                    ?: missing.add("${network.area} burrow ${burrow.loc} at ${burrow.coords}")
            }
            for (segment in network.segments) {
                checked++
                world.locOrNull(segment.clueCoords, segment.clue)
                    ?: missing.add("${network.area} clue ${segment.clue} at ${segment.clueCoords}")
            }
            for (spot in network.catchSpots) {
                checked++
                world.locOrNull(spot.coords, spot.loc)
                    ?: missing.add("${network.area} catch ${spot.loc} at ${spot.coords}")
            }
        }

        assertEquals(119, checked, "TrackingNetworks should author 119 loc coordinates")
        assertEquals(emptyList<String>(), missing, "authored locs absent from the booted world")
    }

    /**
     * A login clears footprints the server no longer has a trail for.
     *
     * The one claim that a fake world structurally cannot make. `VarPlayerIntMapSetter`
     * short-circuits before its transmit branch while `processedMapClock == 0`, which is exactly
     * the state during `SessionStateEvent.Login`, so `loginReset` rides a soft queue instead - and
     * a soft queue only runs because `PlayerMainProcess` drains it. Calling `loginReset` directly,
     * as a unit test must, proves the loop body and skips the whole mechanism under test.
     *
     * The varbit is written before the player is registered, standing in for one persisted from a
     * session that ended mid-trail.
     */
    @Test
    fun loginClearsAStrandedTrail() {
        val world = world()
        val stranded = TrackingNetworks.all.first().segments.first().varbit

        val player = world.newPlayer()
        VarPlayerIntMapSetter.set(player, stranded, TrailLogic.VISIBLE_FORWARD)
        assertEquals(TrailLogic.VISIBLE_FORWARD, player.vars[stranded], "test setup")

        world.login(player)
        repeat(3) { world.cycle.tick() }

        assertEquals(0, player.vars[stranded], "$stranded should be hidden by the login reset")
        world.logout(player)
    }

    /**
     * The headline: burrow to noose, on the engine's own interaction path, for all five networks.
     *
     * Every click is an [InteractionOp] handed to [LocInteractions] against a [BoundLocInfo] read
     * out of the booted world's [LocRegistry], resolved by `opTrigger` and dispatched into a real
     * `ProtectedAccess` coroutine by `PlayerInteractionProcessor` on a real `GameCycle.tick()`. No
     * handler is called by name anywhere below.
     *
     * The trail is not scripted, so the walk has to find it the way a player does - see
     * [walkATrail]. What it asserts at the end is the whole point: three unstackable rewards in the
     * backpack and the creature's exact experience, produced by clicking scenery.
     */
    @Test
    fun aTrailWalksFromBurrowToCatchOnTheRealLoop() {
        val world = world()
        val player = world.newPlayer()
        world.login(player)
        world.setHunterLevel(player, 99)
        world.wield(player, HunterTracking.NOOSE_WAND, Wearpos.RightHand)

        for (network in TrackingNetworks.all) {
            val xpBefore = player.statMap.getFineXP(HUNTER_STAT)
            val furBefore = player.inv.count(network.creature.fur)
            val bonesBefore = player.inv.count(HunterTracking.BONES)
            val meatBefore = player.inv.count(HunterTracking.RAW_BEAST_MEAT)

            world.walkATrail(player, network)

            val expectedXp =
                WIKI_FINE_XP[network.creature.name]
                    ?: error("no expected xp listed for ${network.creature.name}")
            assertEquals(
                expectedXp,
                player.statMap.getFineXP(HUNTER_STAT) - xpBefore,
                "${network.area}: hunter experience for one ${network.creature.name}",
            )
            assertEquals(
                furBefore + 1,
                player.inv.count(network.creature.fur),
                "${network.area}: ${network.creature.fur} in the backpack",
            )
            assertEquals(
                bonesBefore + 1,
                player.inv.count(HunterTracking.BONES),
                "${network.area}: bones in the backpack",
            )
            assertEquals(
                meatBefore + 1,
                player.inv.count(HunterTracking.RAW_BEAST_MEAT),
                "${network.area}: raw beast meat in the backpack",
            )

            // The catch ends the trail, and every footprint it drew has to go with it: a segment
            // left showing is a trail rendered on a client the server has no state for.
            val showing = network.segments.filter { player.vars[it.varbit] != 0 }.map { it.varbit }
            assertEquals(emptyList<String>(), showing, "${network.area}: footprints left showing")
        }

        world.logout(player)
    }

    /**
     * Walks one network from a burrow to the kebbit, discovering the rolled trail as it goes.
     *
     * The server picks the trail with its own RNG and tells the player nothing but the footprints.
     * So this reads the same thing back: the set of segment varbits showing, and the value in each.
     * [TrailLogic.enumerate] is re-run over the authored graph to list every trail that burrow
     * could have produced, and the observation narrows that list - a trail is a candidate only if
     * its revealed prefix is exactly the segments showing, each with the direction value it would
     * have been drawn at. The next clue to click is then the next segment of a surviving candidate;
     * ties are broken by trying each in turn, which a wrong-clue click makes free.
     *
     * That is deliberately a longer road than reading `HunterTracking`'s own map would be. It means
     * the walk is driven by client-visible state alone, so a trail whose varbits say one thing and
     * whose server state says another cannot pass.
     */
    private fun BootedWorld.walkATrail(player: Player, network: TrackingNetwork) {
        val burrow = network.burrows.first()
        val trails = TrailLogic.enumerate(network, burrow.origin)
        check(trails.isNotEmpty()) { "${network.area}: ${burrow.loc} enumerates no trails" }

        click(player, burrow.coords, burrow.loc, InteractionOp.Op1)
        var showing = footprints(player, network)
        assertEquals(
            1,
            showing.size,
            "${network.area}: inspecting ${burrow.loc} should reveal exactly one segment",
        )

        var guard = TrailLogic.MAX_SEGMENTS + 1
        while (guard-- > 0) {
            val candidates = consistent(trails, showing)
            check(candidates.isNotEmpty()) {
                "${network.area}: no enumerated trail matches the footprints $showing"
            }
            val nextClues =
                candidates
                    .filter { it.size > showing.size }
                    .map { it[showing.size].segment }
                    .distinctBy(TrailSegment::clue)
            if (nextClues.isEmpty()) {
                break
            }
            val advanced =
                nextClues.any { segment ->
                    click(player, segment.clueCoords, segment.clue, InteractionOp.Op1)
                    val now = footprints(player, network)
                    (now.size > showing.size).also { if (it) showing = now }
                }
            // A trail two segments long and one three segments long can share a prefix, so a clue
            // that refuses to advance is not by itself a failure - it is how a short trail ends.
            // The claim is only safe because a trail that is *not* finished cannot be caught: the
            // attack below asks `isHotSpot`, which needs `revealed >= steps.size`.
            if (!advanced) {
                check(candidates.any { it.size == showing.size }) {
                    "${network.area}: no clue advanced the trail past $showing"
                }
                break
            }
        }
        check(guard >= 0) { "${network.area}: trail never finished revealing" }

        val finished = consistent(trails, showing).filter { it.size == showing.size }
        check(finished.isNotEmpty()) { "${network.area}: trail is not complete at $showing" }

        val caught =
            finished
                .map { it.last().to }
                .distinct()
                .any { coords ->
                    val spot = network.catchSpots.first { it.coords == coords }
                    val before = player.inv.count(network.creature.fur)
                    click(player, spot.coords, spot.loc, InteractionOp.Op2)
                    player.inv.count(network.creature.fur) > before
                }
        check(caught) { "${network.area}: attacking the trail's end caught nothing" }
    }

    /** The segment varbits currently showing, with the value each was drawn at. */
    private fun footprints(player: Player, network: TrackingNetwork): Map<String, Int> =
        network.segments
            .associate { it.varbit to player.vars[it.varbit] }
            .filterValues { it != 0 }

    /**
     * The trails whose first `showing.size` steps are exactly the segments showing, each carrying
     * the value [TrailLogic.revealValue] would have written for the direction it was walked in.
     */
    private fun consistent(
        trails: List<List<TrailStep>>,
        showing: Map<String, Int>,
    ): List<List<TrailStep>> =
        trails.filter { steps ->
            val prefix = steps.take(showing.size)
            prefix.size == showing.size &&
                prefix.all { showing[it.segment.varbit] == TrailLogic.revealValue(it) }
        }

    /**
     * One click, driven to completion.
     *
     * The player is teleported onto the loc first so nothing depends on the route finder reaching
     * it: standing on the tile makes `isWithinOpRange` true through `collides`, and the op fires on
     * the next tick without a step being taken. Everything after that is the engine's - `opTrigger`
     * resolving the handler against the packed type, `ProtectedAccessLauncher` opening the
     * coroutine, and `arriveDelay`/`delay` suspending it across ticks.
     */
    private fun BootedWorld.click(
        player: Player,
        coords: CoordGrid,
        loc: String,
        op: InteractionOp,
    ) {
        val info = locOrNull(coords, loc) ?: error("$loc is not placed at $coords")
        val type = ServerCacheManager.getObject(info.id) ?: error("no packed type for $loc")
        PathingEntityCommon.telejump(player, collision, coords)
        locInteractions.interact(player, BoundLocInfo(info, type), op)
        runUntilIdle(player)
    }

    /**
     * Ticks until the player has no interaction left and no suspended coroutine.
     *
     * Bounded rather than looping forever: an op that never resumes is a bug worth failing on, and
     * a `while (true)` would hang the build instead of naming it.
     */
    private fun BootedWorld.runUntilIdle(player: Player, maxCycles: Int = 20) {
        var cycles = 0
        do {
            cycle.tick()
        } while ((player.interaction != null || player.isAccessProtected) && ++cycles < maxCycles)
        check(cycles < maxCycles) { "op did not finish within $maxCycles cycles" }
    }

    private class BootedWorld(injector: Injector) {
        val cycle: GameCycle = injector.getInstance(GameCycle::class.java)
        val collision: CollisionFlagMap = injector.getInstance(CollisionFlagMap::class.java)
        val locInteractions: LocInteractions = injector.getInstance(LocInteractions::class.java)

        private val eventBus: EventBus = injector.getInstance(EventBus::class.java)
        private val locRegistry: LocRegistry = injector.getInstance(LocRegistry::class.java)
        private val players: PlayerRegistry = injector.getInstance(PlayerRegistry::class.java)
        private val launcher: ProtectedAccessLauncher =
            injector.getInstance(ProtectedAccessLauncher::class.java)

        /** The lookup `LocInteractions.opTrigger` performs before it dispatches a click. */
        fun hasOpLoc1(loc: String): Boolean =
            eventBus.contains(LocEvents.Op1::class.java, loc.asRSCM(RSCMType.LOC).toLong())

        fun hasOpLoc2(loc: String): Boolean =
            eventBus.contains(LocEvents.Op2::class.java, loc.asRSCM(RSCMType.LOC).toLong())

        fun locOrNull(coords: CoordGrid, loc: String) =
            locRegistry.findType(coords, loc.asRSCM(RSCMType.LOC))

        private var nextUuid = 1L

        fun newPlayer(): Player {
            val player = Player()
            player.username = "trailwalker"
            player.displayName = "Trailwalker"
            player.accountId = 1
            player.characterId = 1
            // `PlayerRegistry.add` derives the uid from these, and account loading is what sets
            // them on a real login.
            player.uuid = nextUuid++
            player.observerUUID = player.uuid
            player.coords = SPAWN
            return player
        }

        /**
         * The login the network layer performs, minus the network.
         *
         * `AccountLoadResponseHook` allocates a slot, registers the player and publishes `Login`;
         * `PlayerRegistry.add` publishes `Initialize` on the way, which is what builds the backpack
         * and worn container, so the order matters. `onPlayerLogin` - the hook tracking's reset
         * rides - is `SessionStateEvent.Login`, so this is the event that matters here.
         *
         * `EngineLogin` is deliberately **not** published. It is the client-resync half of a login:
         * every one of its handlers writes down a channel, and `LoginScript`'s reads the realm
         * config, which only exists after `startupGame` has brought the database up. Nothing in
         * tracking observes it.
         */
        fun login(player: Player) {
            player.slotId = players.playerList.nextFreeSlot() ?: error("no free player slot")
            val result = players.add(player)
            check(result is PlayerRegistryResult.Add.Success) { "failed to register: $result" }
            eventBus.publish(SessionStateEvent.Login(player))
        }

        fun logout(player: Player) {
            eventBus.publish(SessionStateEvent.Logout(player))
            players.del(player)
        }

        fun setHunterLevel(player: Player, level: Int) {
            player.statMap.setBaseLevel(HUNTER_STAT, level.toByte())
            player.statMap.setCurrentLevel(HUNTER_STAT, level.toByte())
        }

        fun wield(player: Player, obj: String, wearpos: Wearpos) {
            val equipped = launcher.launch(player) { invAdd(worn, obj, 1, slot = wearpos.slot) }
            check(equipped) { "could not equip $obj" }
        }
    }

    /** The booted world, built once per JVM; see [BootedGame] for why it is shared this widely. */
    private fun world(): BootedWorld =
        shared ?: BootedWorld(BootedGame.injector).also { shared = it }

    private companion object {
        private var shared: BootedWorld? = null

        private const val HUNTER_STAT = "stat.hunter"

        /** Lumbridge; anywhere valid will do, since every click teleports first. */
        private val SPAWN = CoordGrid(0, 50, 50, 16, 16)

        /**
         * Hunter experience per catch, x10 as `PlayerStatMap.getFineXP` reports it.
         *
         * Literals off the wiki technique table rather than `TrackingCreatures.xp`, so that this
         * asserts the award against a source outside the code under test. `hunterVerify` check 9
         * pins the same five numbers independently.
         */
        private val WIKI_FINE_XP =
            mapOf(
                "polar kebbit" to 300,
                "common kebbit" to 360,
                "feldip weasel" to 480,
                "desert devil" to 660,
                "razor-backed kebbit" to 3485,
            )
    }
}
