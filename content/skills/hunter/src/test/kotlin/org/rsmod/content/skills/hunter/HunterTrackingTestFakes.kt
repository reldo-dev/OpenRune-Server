package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.util.Wearpos
import kotlin.coroutines.startCoroutine
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.coroutine.suspension.GameCoroutineSimpleCompletion
import org.rsmod.game.MapClock
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.inv.Inventory
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.loc.LocLayerConstants

/**
 * A world for tracking, and emptier still than [HunterCrabTrapTestWorld].
 *
 * Tracking touches no npcs, no controllers and no loc registry: a trail is the player's own varbits
 * plus one in-memory entry, so a player, an inventory, a worn container and a scripted RNG is the
 * whole world it needs. The two locs it does take - the burrow it was clicked on and the catch spot
 * it is attacking - are built by hand over their packed types, exactly as [HunterCrabTrapTestWorld]
 * builds its sites and for the same reason: a [BoundLocInfo] is what an op hands the content, and
 * nothing here ever looks one up in a repository.
 *
 * The [MapClock] exists only so [runProtected] can drive a suspending op; nothing in tracking reads
 * it.
 */
class HunterTrackingTestWorld {
    val mapClock: MapClock = MapClock()
    val random: ScriptedRandom = ScriptedRandom()

    private val playerList: PlayerList = PlayerList()

    val tracking: HunterTracking =
        HunterTracking(gameRandom = random, xpMods = XpModifiers(emptySet()))

    /* Players */

    private var nextUuid: Long = 1L

    fun addPlayer(hunterLvl: Int = 99): Player {
        val player = Player()
        player.coords = ORIGIN
        player.slotId = playerList.nextFreeSlot() ?: error("No free player slot.")
        player.uuid = nextUuid++
        player.observerUUID = player.uuid
        playerList[player.slotId] = player
        player.statMap.setBaseLevel("stat.hunter", hunterLvl.toByte())
        player.statMap.setCurrentLevel("stat.hunter", hunterLvl.toByte())
        player.inv = Inventory.create("inv.inv")
        player.inv.owner = player
        // The noose wand and the ring of pursuit are both equipment, and every check in
        // `HunterTracking` reads `worn`; a world without one throws on the first.
        player.worn = Inventory.create("inv.worn")
        player.worn.owner = player
        player.client = RecordingClient()
        // Past zero so `VarPlayerIntMapSetter` takes its real transmit branch rather than the
        // not-logged-in short circuit - which is the branch a trail write has to survive.
        player.currentMapClock = 1
        player.processedMapClock = 1
        return player
    }

    fun protectedAccess(player: Player): ProtectedAccess =
        ProtectedAccess(player, GameCoroutine(), ProtectedAccessContextFactory.empty())

    /** Everything [player] has been told, newest last. */
    fun messages(player: Player): List<String> =
        (player.client as RecordingClient).written.map(Any::toString)

    fun wasTold(player: Player, fragment: String): Boolean =
        messages(player).any { fragment in it }

    fun giveItem(player: Player, obj: String, count: Int = 1) {
        protectedAccess(player).invAdd(player.inv, obj, count)
    }

    /** Equips [obj] at [wearpos], which is what the client's own `Wield`/`Wear` option does. */
    fun wear(player: Player, obj: String, wearpos: Wearpos) {
        protectedAccess(player).invAdd(player.worn, obj, 1, slot = wearpos.slot)
    }

    fun wieldNooseWand(player: Player) {
        wear(player, HunterTracking.NOOSE_WAND, Wearpos.RightHand)
    }

    fun wearRingOfPursuit(player: Player) {
        wear(player, HunterTracking.RING_OF_PURSUIT, Wearpos.Ring)
    }

    fun removeRingOfPursuit(player: Player) {
        protectedAccess(player).invDel(player.worn, HunterTracking.RING_OF_PURSUIT, 1)
    }

    /** Leaves exactly [free] slots in the backpack. */
    fun fillInventory(player: Player, free: Int = 0, filler: String = "obj.oak_logs") {
        val access = protectedAccess(player)
        while (player.inv.freeSpace() > free) {
            access.invAdd(player.inv, filler, 1)
        }
    }

    fun itemCount(player: Player, obj: String): Int = player.inv.count(obj)

    /* Vars */

    /** The value the client would render for [varbit] - read back one varbit at a time, never a
     *  varp: varp 925 carries unrelated fields (e.g., `lumbridge_alchemy_high`), so a varp write
     *  would silently reset them. */
    fun varbitOf(player: Player, varbit: String): Int = player.vars[varbit]

    fun setVarbit(player: Player, varbit: String, value: Int) {
        VarPlayerIntMapSetter.set(player, varbit, value)
    }

    /** Every segment varbit of [network], in authoring order. */
    fun segmentValues(player: Player, network: TrackingNetwork): List<Int> =
        network.segments.map { varbitOf(player, it.varbit) }

    /* Locs */

    private fun locAt(internal: String, coords: CoordGrid): BoundLocInfo {
        val id = internal.asRSCM(RSCMType.LOC)
        val entity = LocEntity(id, LocShape.CentrepieceStraight.id, 0)
        val info = LocInfo(LocLayerConstants.of(LocShape.CentrepieceStraight.id), coords, entity)
        val type = ServerCacheManager.getObject(id) ?: error("Missing loc type: $internal")
        return BoundLocInfo(info, type)
    }

    fun burrowLoc(network: TrackingNetwork): BoundLocInfo {
        val burrow = network.burrows.first()
        return locAt(burrow.loc, burrow.coords)
    }

    fun catchLoc(network: TrackingNetwork, coords: CoordGrid): BoundLocInfo {
        val spot =
            network.catchSpots.firstOrNull { it.coords == coords }
                ?: error("No catch spot at $coords")
        return locAt(spot.loc, spot.coords)
    }

    /* Ops */

    fun inspectBurrow(player: Player, network: TrackingNetwork): Boolean =
        with(tracking) {
            protectedAccess(player)
                .inspectBurrow(network, network.burrows.first().loc, burrowLoc(network))
        }

    fun inspectClue(player: Player, network: TrackingNetwork, clue: String): Boolean =
        with(tracking) { protectedAccess(player).inspectClue(network, clue) }

    fun searchCatchSpot(player: Player, network: TrackingNetwork, coords: CoordGrid): Boolean =
        with(tracking) { protectedAccess(player).searchCatchSpot(network, catchLoc(network, coords)) }

    fun attackCatchSpot(player: Player, network: TrackingNetwork, coords: CoordGrid): Boolean =
        runProtected(player) { access ->
            with(access) { attackCatchSpot(network, catchLoc(network, coords)) }
        }

    /** `Check`, which the ring carries both held and worn; both ops call the one function. */
    fun checkRingCharges(player: Player) {
        with(tracking) { protectedAccess(player).checkRingCharges() }
    }

    /** `Break` on the ring in backpack [slot]; [giveItem] fills an empty inventory from slot 0. */
    fun breakRing(player: Player, slot: Int = 0) {
        with(tracking) { protectedAccess(player).breakRing(player.inv, slot) }
    }

    /** Reveals every step of [player]'s trail by clicking each remaining clue in turn. */
    fun followToTheEnd(player: Player, network: TrackingNetwork) {
        var guard = TrailLogic.MAX_SEGMENTS + 1
        while (guard-- > 0) {
            val next = tracking.trailOf(player)?.nextClue ?: return
            check(inspectClue(player, network, next.clue)) { "Clue ${next.clue} did not advance." }
        }
        error("Trail did not complete.")
    }

    /**
     * Drives a suspending op to completion, bounded rather than looping forever: an op that never
     * resumes is a bug these tests exist to catch, and an unbounded loop would hang instead of
     * failing. Copied from [HunterFalconryTestWorld.runProtected], with the receiver rebound.
     */
    fun <T> runProtected(
        player: Player,
        maxCycles: Int = 30,
        op: suspend HunterTracking.(ProtectedAccess) -> T,
    ): T {
        val coroutine = GameCoroutine()
        val access = ProtectedAccess(player, coroutine, ProtectedAccessContextFactory.empty())
        var outcome: Result<T>? = null
        val body: suspend GameCoroutine.() -> Unit = {
            outcome = runCatching { tracking.op(access) }
        }
        syncPlayerClock(player)
        player.activeCoroutine = coroutine
        body.startCoroutine(coroutine, GameCoroutineSimpleCompletion)

        var cycles = 0
        while (outcome == null && cycles++ < maxCycles) {
            mapClock.tick()
            syncPlayerClock(player)
            coroutine.advance()
        }
        val result = checkNotNull(outcome) { "Tracking op did not finish in $maxCycles cycles." }
        player.activeCoroutine = null
        return result.getOrThrow()
    }

    private fun syncPlayerClock(player: Player) {
        player.currentMapClock = mapClock.cycle
        player.processedMapClock = mapClock.cycle
    }

    companion object {
        /* The synthetic network's graph nodes. Coordinates are arbitrary and unplaced; only the
         * adjacency matters, exactly as in [TrackingTrailTest]. */
        val ORIGIN: CoordGrid = CoordGrid(x = 2700, z = 3800, level = 0)
        val NODE_A: CoordGrid = CoordGrid(x = 2700, z = 3801, level = 0)
        val NODE_B: CoordGrid = CoordGrid(x = 2701, z = 3801, level = 0)

        /** Where trail 0 and trail 3 end. */
        val HOT_SPOT: CoordGrid = CoordGrid(x = 2700, z = 3802, level = 0)

        /** Where trail 1 and trail 2 end - the same loc gameval, a different tile. */
        val COLD_SPOT: CoordGrid = CoordGrid(x = 2701, z = 3802, level = 0)

        /**
         * A five-segment diamond with a rung, built on **real** `varbit.hunting_trail_state8_*`
         * gamevals so that every write goes through the same `ServerCacheManager` resolution the
         * running server uses, and on real clue and catch locs so the gameval matching is exercised
         * against names that exist.
         *
         * ```
         *   ORIGIN --s0-- A --s1-- HOT
         *   ORIGIN --s2-- B --s3-- COLD
         *              A --s4-- B
         * ```
         *
         * `TrailLogic.enumerate` walks the segments in authoring order, so the four trails come out
         * in a fixed order that [ScriptedRandom.nextInt] selects from:
         *
         * | index | steps           | ends at  |
         * |-------|-----------------|----------|
         * | 0     | s0, s1          | HOT      |
         * | 1     | s0, s4, s3      | COLD     |
         * | 2     | s2, s3          | COLD     |
         * | 3     | s2, s4 reversed, s1 | HOT  |
         *
         * Both catch spots deliberately share one loc gameval, because that is what the map does -
         * `hunting_trail_end_polar` is placed four times in Rellekka - and it is why a catch spot is
         * the one thing here matched on coordinate rather than on gameval.
         *
         * [block] picks which `hunting_trail_state<block>_*` run of varbits the segments name, so
         * that two calls can build two networks a player can hold trails in one after the other.
         * Two networks off the *same* block would write the same varbits, and clearing the first
         * would be indistinguishable from never having rendered the second.
         */
        fun network(
            creature: TrackingCreature = TrackingCreatures.polar,
            block: Int = 8,
        ): TrackingNetwork =
            TrackingNetwork(
                area = "test_area_$block",
                creature = creature,
                segments =
                    listOf(
                        segment(block, 0, ORIGIN, NODE_A),
                        segment(block, 1, NODE_A, HOT_SPOT),
                        segment(block, 2, ORIGIN, NODE_B),
                        segment(block, 3, NODE_B, COLD_SPOT),
                        segment(block, 4, NODE_A, NODE_B),
                    ),
                burrows =
                    listOf(
                        TrailBurrow(
                            loc = "loc.hunting_trail_spawn1",
                            coords = ORIGIN,
                            origin = ORIGIN,
                        )
                    ),
                catchSpots =
                    listOf(
                        TrailCatchSpot("loc.hunting_trail_end_polar", HOT_SPOT),
                        TrailCatchSpot("loc.hunting_trail_end_polar", COLD_SPOT),
                    ),
            )

        private fun segment(
            block: Int,
            index: Int,
            endA: CoordGrid,
            endB: CoordGrid,
        ): TrailSegment =
            TrailSegment(
                varbit = "varbit.hunting_trail_state${block}_$index",
                clue = "loc.hunting_trail_clue${block}_$index",
                // Off to one side of the run, as a real clue placement is. Never compared against.
                clueCoords = CoordGrid(x = 2690 + index, z = 3790, level = 0),
                endA = endA,
                endB = endB,
            )
    }
}

/** A [Client] that keeps everything written to it, so a test can read back what a player was told. */
private class RecordingClient : Client<Any, Any> {
    val written: MutableList<Any> = mutableListOf()

    override fun close() {}

    override fun write(message: Any) {
        written += message
    }

    override fun read(player: Player) {}

    override fun flush() {}

    override fun flushHighPriority() {}

    override fun unregister(service: Any, player: Player) {}
}
