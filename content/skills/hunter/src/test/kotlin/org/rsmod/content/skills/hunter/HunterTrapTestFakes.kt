package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.gamevals.GameValProvider
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import java.io.File
import java.nio.file.Paths
import kotlin.coroutines.startCoroutine
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.controller.ControllerRegistry
import org.rsmod.api.registry.loc.LocRegistry
import org.rsmod.api.registry.loc.LocRegistryNormal
import org.rsmod.api.registry.loc.LocRegistryRegion
import org.rsmod.api.registry.npc.NpcRegistry
import org.rsmod.api.registry.obj.ObjRegistry
import org.rsmod.api.registry.player.PlayerRegistry
import org.rsmod.api.registry.region.RegionRegistry
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.controller.ControllerRepository
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.repo.player.PlayerRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.coroutine.suspension.GameCoroutineSimpleCompletion
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.Controller
import org.rsmod.game.entity.ControllerList
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.inv.Inventory
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.loc.LocZoneKey
import org.rsmod.game.map.LocZoneStorage
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.region.RegionListLarge
import org.rsmod.game.region.RegionListSmall
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.plugin.scripts.ScriptContext
import org.rsmod.routefinder.collision.CollisionFlagMap
import org.rsmod.routefinder.loc.LocLayerConstants

/**
 * The JUnit resource every world-driven hunter test locks, so no two of them run at once.
 *
 * `test-conventions` enables parallel execution repo-wide; see the note on [HunterTrapTestWorld].
 */
const val HUNTER_TEST_WORLD_LOCK: String = "hunter-test-world"

/**
 * A [GameRandom] whose every draw is dictated by the test.
 *
 * This is the piece the live game cannot offer: a failed catch is a rare event a player cannot
 * force, and the failing-loc branch of [HunterTrap.hunterTrapTick] is otherwise only reachable by
 * waiting for one. [randomDouble] is the draw the catch roll compares its success rate against -
 * `rate > randomDouble()` - so `0.0` makes every non-zero rate a catch and `1.0` makes every rate a
 * miss, whatever the creature or the owner's level.
 *
 * [doubleDraws] is asserted on as well as set: the tick is documented to short-circuit an
 * under-levelled attempt *before* the roll so it "never consumes a random draw", and a counter is
 * the only way to observe that.
 */
class ScriptedRandom(var nextDouble: Double = HIGHEST_DRAW, var nextInt: Int = 0) : GameRandom {
    var doubleDraws: Int = 0
        private set

    var intDraws: Int = 0
        private set

    override fun randomDouble(): Double {
        doubleDraws++
        return nextDouble
    }

    override fun of(maxExclusive: Int): Int {
        intDraws++
        return nextInt.coerceIn(0, maxExclusive - 1)
    }

    override fun of(minInclusive: Int, maxInclusive: Int): Int {
        intDraws++
        return nextInt.coerceIn(minInclusive, maxInclusive)
    }

    companion object {
        /** Lower than any success rate the engine formula can produce, so the catch always lands. */
        const val ALWAYS_CATCH: Double = 0.0

        /**
         * The highest draw [randomDouble] is allowed to return, which misses any rate at or below
         * `256/256`.
         *
         * It does **not** miss every rate. `SkillingSuccessRate.successRate` is unclamped -
         * `(1 + floor(low*(99-L)/98 + high*(L-1)/98 + 0.5)) / 256` with a `high` above 256 exceeds
         * `1.0` outright - so a level-99 hunter's chance at the wild kebbit (`high = 385`, giving
         * `386/256 = 1.51`) is above any legal draw and the catch is certain whatever this returns.
         * Tests that need a *miss* therefore set the owner to the creature's own requirement level,
         * where the rate is a real fraction, rather than to 99.
         */
        const val HIGHEST_DRAW: Double = 1.0
    }
}

/**
 * The packed cache and gameval mappings, loaded once per test JVM.
 *
 * Not a fake. Every name in this feature - the four varcons, nineteen deadfall loc states, the
 * creature dbtables - resolves through `RSCM` into the packed cache, and a stub would only prove
 * the stub agrees with itself. The real `.data/cache/SERVER` is ~2s to decode in-process, which is
 * cheap enough to pay once, and it makes the creature rows, loc ids and varcon definitions the same
 * ones the running server sees.
 *
 * `ServerCacheManager` resolves `.data` relative to `user.dir`, which Gradle sets to the module
 * directory, so the property is repointed at the repo root before either load.
 */
object HunterTestCache {
    private var loaded = false

    val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: error("Not inside the OpenRune-Server checkout.")
        }
        dir
    }

    @Synchronized
    fun load() {
        if (loaded) return
        System.setProperty("user.dir", repoRoot.absolutePath)
        GameValProvider.load("${repoRoot.absolutePath}/")
        ServerCacheManager.init(Paths.get(repoRoot.absolutePath, ".data", "cache", "SERVER"), 240)
        startInvTransactions()
        loaded = true
    }

    /**
     * `api:invtx` keeps its transaction machinery in module-internal `lateinit` globals that
     * [InvTransactionsScript] fills in at boot, so `invAdd` and `invDel` throw
     * `UninitializedPropertyAccessException` until that script has run. It is the only plugin script
     * the harness starts, and it is started once per JVM because the state it writes is global.
     */
    private fun startInvTransactions() {
        val script = InvTransactionsScript(PlayerItemStorage(emptySet()))
        val context = ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache())
        with(script) { context.startup() }
    }
}

/**
 * A single tile's worth of game world: real repositories over hand-built registries, with nothing
 * mocked but [random].
 *
 * Seven of [HunterTrap]'s eight collaborators are final classes with no interface, so they cannot be
 * subclassed into fakes; they are instead constructed for real over empty registries, which is both
 * possible and preferable - `locRepo.change` really does have to behave like `locRepo.change` for
 * the deadfall invariant to mean anything.
 *
 * Coordinates default well below [RegionRegistry.INSTANCE_MIN_X] so every loc goes through
 * `LocRegistryNormal` rather than the instanced-region path.
 */
class HunterTrapTestWorld {
    val mapClock: MapClock = MapClock()
    val random: ScriptedRandom = ScriptedRandom()

    val playerList: PlayerList = PlayerList()
    private val npcList: NpcList = NpcList()
    private val controllerList: ControllerList = ControllerList()

    private val collision = CollisionFlagMap()
    private val eventBus = EventBus()
    private val zoneUpdates = ZoneUpdateMap()
    private val zoneActivity = ZonePlayerActivityBitSet()

    val locZones: LocZoneStorage = LocZoneStorage()

    private val locRegNormal = LocRegistryNormal(zoneUpdates, collision, locZones)
    private val conRegistry = ControllerRegistry(mapClock, controllerList)
    private val npcRegistry = NpcRegistry(npcList, collision, eventBus)
    private val regionRegistry =
        RegionRegistry(
            RegionListSmall(),
            RegionListLarge(),
            locRegNormal,
            collision,
            locZones,
            npcRegistry,
            conRegistry,
            zoneActivity,
        )
    private val locRegRegion = LocRegistryRegion(zoneUpdates, collision, locZones, regionRegistry)
    private val locRegistry = LocRegistry(locZones, locRegNormal, locRegRegion)
    private val playerRegistry = PlayerRegistry(playerList, collision, zoneActivity, eventBus)
    private val objRegistry = ObjRegistry(zoneUpdates)

    val locRepo: LocRepository = LocRepository(mapClock, locRegistry, regionRegistry)
    val conRepo: ControllerRepository = ControllerRepository(conRegistry, controllerList)
    val npcRepo: NpcRepository = NpcRepository(mapClock, npcRegistry, npcList)
    val objRepo: ObjRepository = ObjRepository(mapClock, objRegistry)
    val playerRepo: PlayerRepository = PlayerRepository(playerRegistry)

    val trap: HunterTrap =
        HunterTrap(
            locRepo = locRepo,
            conRepo = conRepo,
            npcRepo = npcRepo,
            objRepo = objRepo,
            playerRepo = playerRepo,
            playerList = playerList,
            random = random,
            xpMods = XpModifiers(emptySet()),
            mapClock = mapClock,
        )

    /** Runs one cycle of [controller]'s trap, exactly as `onAiConTimer(TRAP_CONTROLLER)` would. */
    fun tick(controller: Controller) {
        with(trap) { controller.hunterTrapTick() }
    }

    fun advance(cycles: Int = 1) {
        repeat(cycles) { mapClock.tick() }
    }

    /* Players */

    private var nextUuid: Long = 1L

    fun addPlayer(coords: CoordGrid, hunterLvl: Int = 99, hitpoints: Int = 10): Player {
        val player = Player()
        player.coords = coords
        player.slotId = playerList.nextFreeSlot() ?: error("No free player slot.")
        player.uuid = nextUuid++
        // Set at login by the account layer, and never null on a real player. `Obj.fromOwner`
        // errors without it, which the net trap's drop-on-fail path is the first hunter code to
        // reach: a dropped obj is private to its receiver first, and this is who that is.
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

    /**
     * A [ProtectedAccess] over [player] backed by [ProtectedAccessContextFactory.empty], whose every
     * dependency throws on first touch.
     *
     * That is enough for the collect path, which only needs the player's own inventory, stat map and
     * varps - but not for anything that routes through an interaction, an area check or the client,
     * so the lay paths stay out of reach. See the KDoc on `HunterProtectedAccessTest`.
     */
    fun protectedAccess(player: Player): ProtectedAccess =
        ProtectedAccess(player, GameCoroutine(), ProtectedAccessContextFactory.empty())

    /**
     * Runs [block] as a protected-access op and returns its value, driving the world clock forward
     * a cycle at a time until the coroutine finishes.
     *
     * A non-suspending op (`layTrap`, `collectTrap`, `takeTrap`, `dismantleDeadfall`) completes
     * before the loop is entered and costs no cycles; only `setDeadfall` suspends.
     */
    fun <T> runProtected(
        player: Player,
        maxCycles: Int = 20,
        op: suspend HunterTrap.(ProtectedAccess) -> T,
    ): T = startProtected(player, op).await(maxCycles)

    /**
     * [runProtected] with the cycles left to the caller, so a test can inspect the world *during* a
     * suspended op - which is the only way to observe that `setDeadfall` does not charge the log
     * until after its delay.
     */
    fun <T> startProtected(
        player: Player,
        op: suspend HunterTrap.(ProtectedAccess) -> T,
    ): ProtectedRun<T> {
        val coroutine = GameCoroutine()
        val access = ProtectedAccess(player, coroutine, ProtectedAccessContextFactory.empty())
        val run = ProtectedRun<T>(this, player, coroutine)
        val body: suspend GameCoroutine.() -> Unit = {
            run.complete(runCatching { trap.op(access) })
        }
        syncPlayerClock(player)
        // `resumeWithModalProtectedAccess` rejects a resume whose coroutine is not the player's
        // active one, exactly as `Player.launch` would have set it.
        player.activeCoroutine = coroutine
        body.startCoroutine(coroutine, GameCoroutineSimpleCompletion)
        return run
    }

    /**
     * `PathingEntity.isDelayed` reads [Player.processedMapClock], not [Player.currentMapClock] - the
     * game loop advances both, and a suspended `delay` never resumes if only the latter moves.
     */
    internal fun syncPlayerClock(player: Player) {
        player.currentMapClock = mapClock.cycle
        player.processedMapClock = mapClock.cycle
    }

    /** The [BoundLocInfo] an op on the loc currently at [coords] would hand to the content. */
    fun boundLocAt(coords: CoordGrid): BoundLocInfo? {
        val loc = locAt(coords) ?: return null
        val type = ServerCacheManager.getObject(loc.id) ?: error("Missing loc type: ${loc.id}")
        return BoundLocInfo(loc, type)
    }

    /** Drops [player] out of the world the way a logout does, leaving its traps orphaned. */
    fun removePlayer(player: Player) {
        playerRegistry.del(player)
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

    /**
     * True while [npc] is still on the map. `NpcRepository.despawn` hides the npc and its avatar
     * rather than unregistering it - the zone entry stays put so the respawn timer can bring it
     * back - so the hidden flag, not zone membership, is what "despawned" means here.
     */
    fun npcIsSpawned(npc: Npc): Boolean =
        npc.isVisible && npcRepo.findAll(ZoneKey.from(npc.coords)).any { it === npc }

    /* Locs */

    /**
     * Registers [internal] as a *map* loc on [coords] - a permanent one the game map supplied, not
     * a spawn. This is what a deadfall boulder is, and the distinction is the whole point of the
     * deadfall's `locRepo.change`-only rule: a spawned loc can be deleted and re-added, a map loc
     * deleted with an infinite duration never comes back.
     */
    fun addMapLoc(
        coords: CoordGrid,
        internal: String,
        shape: LocShape = LocShape.CentrepieceStraight,
        angle: Int = 0,
    ): LocInfo {
        val entity = LocEntity(internal.asRSCM(RSCMType.LOC), shape.id, angle)
        val layer = LocLayerConstants.of(shape.id)
        locZones.mapLocs[ZoneKey.from(coords), LocZoneKey(ZoneGrid.from(coords), layer)] = entity
        return LocInfo(layer, coords, entity)
    }

    /** The trap loc currently on [coords], whatever family or state it is in. */
    fun locAt(coords: CoordGrid): LocInfo? = locRepo.findAll(coords).firstOrNull()

    fun locNameAt(coords: CoordGrid): String? =
        locAt(coords)?.let { dev.openrune.rscm.RSCM.getReverseMapping(RSCMType.LOC, it.id) }

    /** True when [coords] still carries a deadfall boulder in any of its nineteen states. */
    fun deadfallPresent(coords: CoordGrid): Boolean =
        locRepo.findAll(coords).any { it.id in HunterTrapStates.deadfallLocIds }

    /**
     * True when [coords] still carries a young tree in any of its three states.
     *
     * The net trap's half of the never-delete invariant, and the exact analogue of
     * [deadfallPresent]: a tree deleted with an infinite duration is gone from the world until the
     * next restart, so "the tile still holds a tree" is the assertion every net-trap transition has
     * to survive.
     */
    fun netTrapTreePresent(coords: CoordGrid): Boolean =
        locRepo.findAll(coords).any { it.id in HunterTrapStates.netTrapTreeLocIds }

    /** The name of the spawned "Net trap" loc belonging to the tree on [coords], if there is one. */
    fun netLocNameAt(treeCoords: CoordGrid): String? {
        val net = netLocAt(treeCoords) ?: return null
        return dev.openrune.rscm.RSCM.getReverseMapping(RSCMType.LOC, net.id)
    }

    /** The spawned "Net trap" loc belonging to the tree on [coords], if there is one. */
    fun netLocAt(treeCoords: CoordGrid): LocInfo? {
        val tree = locRepo.findAll(treeCoords).firstOrNull {
            it.id in HunterTrapStates.netTrapTreeLocIds
        } ?: return null
        val netCoords = netTrapCoords(treeCoords, tree.angle)
        return locRepo.findAll(netCoords).firstOrNull {
            it.id in HunterTrapStates.netTrapNetLocIds
        }
    }

    /* Ground objs */

    /** Every obj currently lying on [coords], by internal name. */
    fun objNamesAt(coords: CoordGrid): List<String> =
        objRepo
            .findAll(coords)
            .mapNotNull { dev.openrune.rscm.RSCM.getReverseMapping(RSCMType.OBJ, it.type) }
            .toList()

    /* Traps */

    /**
     * Lays a portable trap the way [HunterTrap.layTrap] does, minus the inventory half of it: the
     * set-state loc plus a controller carrying the three varcons that are the whole of a trap's
     * state. Split out because `layTrap` is a `ProtectedAccess` extension and unreachable here.
     */
    fun layPortableTrap(family: TrapFamily, coords: CoordGrid, owner: Player): Controller {
        require(family.portable) { "Use `armDeadfall` or `armNetTrap` for the fixed-loc families." }
        locRepo.add(
            coords,
            checkNotNull(HunterTrapStates.setLoc(family)),
            Int.MAX_VALUE,
            org.rsmod.game.loc.LocAngle.West,
            LocShape.CentrepieceStraight,
        )
        return addTrapController(family, coords, owner)
    }

    /**
     * Arms a boulder the way [HunterTrap.setDeadfall] does: the map loc is changed - never deleted
     * and respawned - into the armed state, and a controller recording the log is added.
     */
    fun armDeadfall(coords: CoordGrid, owner: Player, log: String? = "obj.logs"): Controller {
        val boulder =
            locRepo.findAll(coords).firstOrNull { it.id in HunterTrapStates.deadfallLocIds }
                ?: error("No deadfall boulder registered on $coords.")
        val into =
            ServerCacheManager.getObject(HunterTrapStates.DEADFALL_ARMED.asRSCM(RSCMType.LOC))
                ?: error("Missing loc type: ${HunterTrapStates.DEADFALL_ARMED}")
        locRepo.change(boulder, into, Int.MAX_VALUE)

        val controller = addTrapController(TrapFamily.DEADFALL, coords, owner)
        if (log != null) {
            controller.trapDeadfallLog = log.asRSCM(RSCMType.OBJ)
        }
        return controller
    }

    /**
     * Registers a young tree as a *map* loc on [coords], the way the game map supplies one.
     *
     * The angle is the whole point: it is what decides which tile the net half lands on, and it is
     * the only thing that lets an op on the net walk back here. [addMapLoc]'s default of 0 is
     * `LocAngle.West`.
     */
    fun addNetTrapTree(
        coords: CoordGrid,
        creature: HunterCreature,
        angle: LocAngle = LocAngle.West,
    ): LocInfo = addMapLoc(coords, HunterTrapStates.upLoc(creature), angle = angle.id)

    /**
     * Arms a net trap the way [HunterTrap.setNetTrap] does: the tree map loc is *changed* into its
     * armed state - never deleted and respawned - the net is spawned on the tile the tree's angle
     * points at, carrying the tree's angle, and a controller is anchored on the tree.
     */
    fun armNetTrap(coords: CoordGrid, owner: Player, creature: HunterCreature): Controller {
        val tree =
            locRepo.findAll(coords).firstOrNull { it.id in HunterTrapStates.netTrapTreeLocIds }
                ?: error("No young tree registered on $coords.")
        val armed = HunterTrapStates.armedTreeLoc(creature)
        val into =
            ServerCacheManager.getObject(armed.asRSCM(RSCMType.LOC))
                ?: error("Missing loc type: $armed")
        locRepo.change(tree, into, Int.MAX_VALUE)

        locRepo.add(
            netTrapCoords(coords, tree.angle),
            HunterTrapStates.netSetLoc(creature),
            Int.MAX_VALUE,
            tree.angle,
            LocShape.CentrepieceStraight,
        )

        return addTrapController(TrapFamily.NETTRAP, coords, owner)
    }

    private fun addTrapController(
        family: TrapFamily,
        coords: CoordGrid,
        owner: Player,
    ): Controller {
        val controller = Controller(TRAP_CONTROLLER, coords)
        conRepo.add(controller, TRAP_LIFETIME_CYCLES)
        controller.trapOwner = owner.uid.packed
        controller.trapFamily = family.ordinal
        controller.trapCreature = TRAP_CREATURE_NONE
        controller.aiTimer(1)
        return controller
    }

    fun controllerAt(coords: CoordGrid): Controller? = conRepo.findExact(coords, TRAP_CONTROLLER)

    companion object {
        /**
         * A tile far from [RegionRegistry.INSTANCE_MIN_X], so the normal loc registry is used, and
         * mid-zone rather than on a zone boundary so a creature placed one tile away in any
         * direction is still inside the tick's own zone sweep.
         */
        val TRAP_TILE: CoordGrid = CoordGrid(3204, 3204, 0)

        /** [Controller.trapCreature] while the trap is armed and empty; see `HunterTrap`. */
        const val TRAP_CREATURE_NONE: Int = -1

        /** [Controller.trapCreature] once the trap has sprung and failed; see `HunterTrap`. */
        const val TRAP_CREATURE_FAILED: Int = -2
    }
}

/** A protected-access op in flight; see [HunterTrapTestWorld.startProtected]. */
class ProtectedRun<T>
internal constructor(
    private val world: HunterTrapTestWorld,
    private val player: Player,
    private val coroutine: GameCoroutine,
) {
    private var outcome: Result<T>? = null

    val isFinished: Boolean
        get() = outcome != null

    internal fun complete(result: Result<T>) {
        outcome = result
    }

    /** Advances the world and the op by one cycle. */
    fun advanceCycle() {
        world.advance()
        world.syncPlayerClock(player)
        coroutine.advance()
    }

    fun await(maxCycles: Int = 20): T {
        var cycles = 0
        while (outcome == null && cycles++ < maxCycles) {
            advanceCycle()
        }
        val result =
            checkNotNull(outcome) { "Protected-access op did not finish in $maxCycles cycles." }
        player.activeCoroutine = null
        return result.getOrThrow()
    }
}
