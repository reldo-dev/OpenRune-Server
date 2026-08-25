package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import kotlin.coroutines.startCoroutine
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.registry.controller.ControllerRegistry
import org.rsmod.api.registry.npc.NpcRegistry
import org.rsmod.api.registry.player.PlayerRegistry
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.repo.controller.ControllerRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.coroutine.suspension.GameCoroutineSimpleCompletion
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Controller
import org.rsmod.game.entity.ControllerList
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.inv.Inventory
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * A world for falconry, built the same way [HunterTrapTestWorld] is: real repositories over
 * hand-built registries, with nothing mocked but [random].
 *
 * Its own class rather than an extension of the trap world, and smaller than one. Falconry touches
 * no locs at all - no `LocRegistry`, no `LocZoneStorage`, no region plumbing - so half of that
 * world's setup would be dead weight here. More to the point, [HunterTrapTestWorld.runProtected]
 * binds its lambda receiver to `HunterTrap`, and generalising that would have meant editing every
 * one of the 81 trap tests' harness. A separate ~30 lines of registry construction is cheaper than
 * touching a green suite.
 */
class HunterFalconryTestWorld {
    val mapClock: MapClock = MapClock()
    val random: ScriptedRandom = ScriptedRandom()

    val playerList: PlayerList = PlayerList()
    private val npcList: NpcList = NpcList()
    private val controllerList: ControllerList = ControllerList()

    private val collision = CollisionFlagMap()
    private val eventBus = EventBus()
    private val zoneActivity = ZonePlayerActivityBitSet()

    private val conRegistry = ControllerRegistry(mapClock, controllerList)
    private val npcRegistry = NpcRegistry(npcList, collision, eventBus)
    private val playerRegistry = PlayerRegistry(playerList, collision, zoneActivity, eventBus)

    val conRepo: ControllerRepository = ControllerRepository(conRegistry, controllerList)
    val npcRepo: NpcRepository = NpcRepository(mapClock, npcRegistry, npcList)

    val falconry: HunterFalconry =
        HunterFalconry(
            conRepo = conRepo,
            npcRepo = npcRepo,
            playerList = playerList,
            gameRandom = random,
            xpMods = XpModifiers(emptySet()),
        )

    fun advance(cycles: Int = 1) {
        repeat(cycles) { mapClock.tick() }
    }

    /** Runs one cycle of [controller]'s falcon, exactly as `onAiConTimer(FALCON_CONTROLLER)` would. */
    fun tickFalcon(controller: Controller) {
        with(falconry) { controller.falconTick() }
    }

    /**
     * One whole cycle for the falcon on [coords]: the map clock moves, the controller's remaining
     * lifetime is decremented, and then its AI timer fires.
     *
     * The decrement is **replicated, not called**. `ControllerRepository.processDurations` - the
     * `controller.duration-- <= 0` loop the real game process runs once per cycle - is `internal` to
     * `api:repo` and so unreachable from a content module's tests. Without it `duration` never moves
     * and every timeout test passes vacuously, which is exactly what happened before this existed:
     * the trap suite sidesteps the problem by assigning `controller.duration = 1` outright, which
     * proves the collapse branch but not that a lifetime actually elapses.
     *
     * Deletion of an expired controller is deliberately *not* replicated, because [falconTick] is
     * documented to give up one cycle early precisely so the repository never gets the chance.
     *
     * @return false once there is no falcon left on [coords].
     */
    fun advanceFalconCycle(coords: CoordGrid): Boolean {
        val controller = falconControllerAt(coords) ?: return false
        advance()
        controller.duration--
        tickFalcon(controller)
        return true
    }

    /**
     * Advances until the falcon on [coords] gives up, or fails if it outlives its timeout.
     *
     * Bounded rather than looping until the controller vanishes: a timeout that never fires is the
     * bug these tests exist to catch, and an unbounded loop would hang instead of failing.
     */
    fun tickFalconUntilGone(coords: CoordGrid, maxCycles: Int = FALCON_TIMEOUT_CYCLES + 5) {
        repeat(maxCycles) {
            if (!advanceFalconCycle(coords)) return
        }
        check(falconControllerAt(coords) == null) {
            "Falcon at $coords outlived $maxCycles cycles without timing out."
        }
    }

    /* Players */

    private var nextUuid: Long = 1L

    fun addPlayer(coords: CoordGrid, hunterLvl: Int = 99, hitpoints: Int = 10): Player {
        val player = Player()
        player.coords = coords
        player.slotId = playerList.nextFreeSlot() ?: error("No free player slot.")
        player.uuid = nextUuid++
        player.observerUUID = player.uuid
        playerRegistry.add(player)
        playerRegistry.change(player, ZoneKey.NULL, ZoneKey.from(coords))
        player.statMap.setBaseLevel("stat.hunter", hunterLvl.toByte())
        player.statMap.setCurrentLevel("stat.hunter", hunterLvl.toByte())
        player.statMap.setBaseLevel("stat.hitpoints", hitpoints.toByte())
        player.statMap.setCurrentLevel("stat.hitpoints", hitpoints.toByte())
        player.inv = Inventory.create("inv.inv")
        player.inv.owner = player
        return player
    }

    /** Drops [player] out of the world the way a logout does, orphaning any falcon they sent. */
    fun removePlayer(player: Player) {
        playerRegistry.del(player)
    }

    /**
     * A [ProtectedAccess] over [player] backed by [ProtectedAccessContextFactory.empty], whose every
     * dependency throws on first touch - enough for inventory work and nothing else. See the note on
     * [HunterTrapTestWorld.protectedAccess].
     */
    fun protectedAccess(player: Player): ProtectedAccess =
        ProtectedAccess(player, GameCoroutine(), ProtectedAccessContextFactory.empty())

    fun giveItem(player: Player, obj: String, count: Int = 1) {
        protectedAccess(player).invAdd(player.inv, obj, count)
    }

    fun giveCoins(player: Player, amount: Int) {
        giveItem(player, "obj.coins", amount)
    }

    /** Leaves exactly zero free slots, so any award has to be refused. */
    fun fillInventory(player: Player) {
        val access = protectedAccess(player)
        while (player.inv.freeSpace() > 0) {
            access.invAdd(player.inv, "obj.oak_logs", 1)
        }
    }

    fun <T> runProtected(
        player: Player,
        maxCycles: Int = 30,
        op: suspend HunterFalconry.(ProtectedAccess) -> T,
    ): T {
        val coroutine = GameCoroutine()
        val access = ProtectedAccess(player, coroutine, ProtectedAccessContextFactory.empty())
        var outcome: Result<T>? = null
        val body: suspend GameCoroutine.() -> Unit = {
            outcome = runCatching { falconry.op(access) }
        }
        syncPlayerClock(player)
        player.activeCoroutine = coroutine
        body.startCoroutine(coroutine, GameCoroutineSimpleCompletion)

        var cycles = 0
        while (outcome == null && cycles++ < maxCycles) {
            advance()
            syncPlayerClock(player)
            coroutine.advance()
        }
        val result = checkNotNull(outcome) { "Falconry op did not finish in $maxCycles cycles." }
        player.activeCoroutine = null
        return result.getOrThrow()
    }

    private fun syncPlayerClock(player: Player) {
        player.currentMapClock = mapClock.cycle
        player.processedMapClock = mapClock.cycle
    }

    /* Npcs */

    fun addNpc(internal: String, coords: CoordGrid): Npc {
        val type =
            ServerCacheManager.getNpc(internal.asRSCM(RSCMType.NPC))
                ?: error("Missing npc type: $internal")
        val npc = Npc(type, coords)
        npcRegistry.add(npc)
        return npc
    }

    /** See [HunterTrapTestWorld.npcIsSpawned]: the hidden flag, not zone membership, is the test. */
    fun npcIsSpawned(npc: Npc): Boolean =
        npc.isVisible && npcRepo.findAll(ZoneKey.from(npc.coords)).any { it === npc }

    /** The internal name of the first visible npc on [coords], if any. */
    fun npcNameAt(coords: CoordGrid): String? =
        npcRepo
            .findAll(coords)
            .filter { it.isVisible }
            .firstNotNullOfOrNull { RSCM.getReverseMapping(RSCMType.NPC, it.visType.id) }

    /* Falcons */

    fun falconControllerAt(coords: CoordGrid): Controller? =
        conRepo.findExact(coords, FALCON_CONTROLLER)

    /**
     * Puts a falcon-with-prey on [coords] the way a successful [HunterFalconry.catchKebbit] does:
     * the matching npc plus a controller carrying the owner and the creature index.
     *
     * Split out because `catchKebbit` needs a live kebbit and a rented bird, and most of the cases
     * that matter here - timeout, a stranger's retrieve, a full inventory - start from a catch that
     * has already happened.
     */
    fun placeCaughtFalcon(
        coords: CoordGrid,
        owner: Player,
        creature: FalconryCreature,
    ): Npc {
        val falcon = addNpc(creature.falconNpc, coords)
        val controller = Controller(FALCON_CONTROLLER, coords)
        conRepo.add(controller, FALCON_TIMEOUT_CYCLES)
        controller.falconOwner = owner.uid.packed
        controller.falconCreature = FalconryCreatures.all.indexOf(creature)
        controller.aiTimer(1)
        return falcon
    }
}
