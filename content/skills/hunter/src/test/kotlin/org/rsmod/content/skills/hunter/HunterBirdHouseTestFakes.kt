package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.registry.obj.ObjRegistry
import org.rsmod.api.registry.zone.ZoneUpdateMap
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.inv.Inventory
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocEntity
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.game.queue.PlayerQueueList
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.loc.LocLayerConstants

/**
 * A clock a test can wind, standing in for the wall clock.
 *
 * The whole point of bird houses is that fifty minutes pass without the player being there, so the
 * only alternative to this seam is a test that sleeps for fifty minutes. [advance] is the thing
 * under test as much as the ops are: a house matures because *time* passed, not because a tick fired.
 */
class WoundBirdHouseClock(var minute: Int = START_MINUTE) : BirdHouseClock {
    override fun epochMinute(): Int = minute

    fun advance(minutes: Int) {
        minute += minutes
    }

    companion object {
        /**
         * A plausible epoch minute, so the stored deadlines look like production values rather than
         * small integers - which is what would hide a sign error or a units mix-up.
         */
        const val START_MINUTE: Int = 29_600_000
    }
}

/**
 * A random that plays a written script and then falls back.
 *
 * [ScriptedRandom] holds one value per kind, which is enough for a technique that draws once. A bird
 * house payout draws **at least twelve times** - a feather quantity, a seed nest, and ten nest rolls
 * each of which may take several more - so a single value cannot express "the third roll succeeds and
 * the rest do not". Both queues drain in order and then repeat the fallback forever.
 */
class ScriptedDrawRandom(
    private val doubles: MutableList<Double> = mutableListOf(),
    private val ints: MutableList<Int> = mutableListOf(),
    var fallbackDouble: Double = 1.0,
    var fallbackInt: Int = 0,
) : GameRandom {
    var doubleDraws: Int = 0
        private set

    var intDraws: Int = 0
        private set

    fun queueDoubles(vararg values: Double): ScriptedDrawRandom = apply { doubles += values.toList() }

    fun queueInts(vararg values: Int): ScriptedDrawRandom = apply { ints += values.toList() }

    override fun randomDouble(): Double {
        doubleDraws++
        return if (doubles.isEmpty()) fallbackDouble else doubles.removeAt(0)
    }

    override fun of(maxExclusive: Int): Int {
        intDraws++
        val value = if (ints.isEmpty()) fallbackInt else ints.removeAt(0)
        return value.coerceIn(0, maxExclusive - 1)
    }

    override fun of(minInclusive: Int, maxInclusive: Int): Int {
        intDraws++
        val value = if (ints.isEmpty()) fallbackInt else ints.removeAt(0)
        return value.coerceIn(minInclusive, maxInclusive)
    }
}

/**
 * A world for bird house trapping, and the emptiest of the five.
 *
 * There is no loc registry, no controller repository and no npc registry, because [HunterBirdHouse]
 * takes none of them: a bird house lives entirely in the player's own varps. The single repository it
 * does take is [ObjRepository], and only because the raw bird meat is published as always landing on
 * the floor. That absence is worth stating rather than hiding - [BirdHouseTest] asserts the *type*
 * has no loc collaborator, which is stronger evidence than watching a repository stay untouched.
 *
 * The space locs are built by hand rather than registered, for the same reason [CrabTrapSite]'s are:
 * nothing ever looks them up, and a [BoundLocInfo] over the packed type is exactly what
 * `LocInteractions` hands an op.
 *
 * @param hunterXpBonus Added to every `stat.hunter` award; see [hunterXpModifiers].
 * @param craftingXpBonus Added to the `stat.crafting` award a craft pays; see [hunterXpModifiers].
 */
class HunterBirdHouseTestWorld(hunterXpBonus: Double = 0.0, craftingXpBonus: Double = 0.0) {
    val random: ScriptedDrawRandom = ScriptedDrawRandom()
    val clock: WoundBirdHouseClock = WoundBirdHouseClock()

    private val mapClock: MapClock = MapClock()
    private val playerList: PlayerList = PlayerList()
    private val objRegistry = ObjRegistry(ZoneUpdateMap())

    val objRepo: ObjRepository = ObjRepository(mapClock, objRegistry)

    val birdHouse: HunterBirdHouse =
        HunterBirdHouse(
            gameRandom = random,
            xpMods = hunterXpModifiers(hunterXpBonus, craftingXpBonus),
            objRepo = objRepo,
            birdHouseClock = clock,
        )

    private var nextUuid: Long = 1L

    fun addPlayer(hunterLvl: Int = 99, craftingLvl: Int = 99): Player {
        val player = Player()
        player.coords = BIRDHOUSE_TILE
        player.slotId = playerList.nextFreeSlot() ?: error("No free player slot.")
        player.uuid = nextUuid++
        player.observerUUID = player.uuid
        playerList[player.slotId] = player
        player.statMap.setBaseLevel("stat.hunter", hunterLvl.toByte())
        player.statMap.setCurrentLevel("stat.hunter", hunterLvl.toByte())
        player.statMap.setBaseLevel("stat.crafting", craftingLvl.toByte())
        player.statMap.setCurrentLevel("stat.crafting", craftingLvl.toByte())
        player.inv = Inventory.create("inv.inv")
        player.inv.owner = player
        player.worn = Inventory.create("inv.worn")
        player.worn.owner = player
        // Past zero so `VarPlayerIntMapSetter` takes its real transmit branch rather than the
        // not-logged-in short circuit, the same as the crab trap world.
        player.currentMapClock = 1
        player.processedMapClock = 1
        return player
    }

    fun protectedAccess(player: Player): ProtectedAccess =
        ProtectedAccess(player, GameCoroutine(), ProtectedAccessContextFactory.empty())

    fun giveItem(player: Player, obj: String, count: Int = 1) {
        protectedAccess(player).invAdd(player.inv, obj, count)
    }

    fun wearItem(player: Player, obj: String) {
        protectedAccess(player).invAdd(player.worn, obj, 1)
    }

    fun itemCount(player: Player, obj: String): Int = player.inv.count(obj)

    /** Leaves exactly zero free slots in the backpack. */
    fun fillInventory(player: Player, filler: String = "obj.oak_logs") {
        val access = protectedAccess(player)
        while (player.inv.freeSpace() > 0) {
            access.invAdd(player.inv, filler, 1)
        }
    }

    /** The [BoundLocInfo] an op on [space] hands the content: the map-placed multiloc parent. */
    fun spaceLoc(space: BirdHouseSpace): BoundLocInfo {
        val entity = LocEntity(space.locId, LocShape.CentrepieceStraight.id, 0)
        val info = LocInfo(LocLayerConstants.of(LocShape.CentrepieceStraight.id), BIRDHOUSE_TILE, entity)
        val type = ServerCacheManager.getObject(space.locId) ?: error("Missing loc type: ${space.loc}")
        return BoundLocInfo(info, type)
    }

    /* State */

    fun stateOf(player: Player, space: BirdHouseSpace): Int =
        with(birdHouse) { player.birdHouseState(space) }

    fun setState(player: Player, space: BirdHouseSpace, state: Int) {
        VarPlayerIntMapSetter.set(player, space.varp, state)
    }

    fun readyAt(player: Player, space: BirdHouseSpace): Int = player.birdHouseReadyAt(space)

    fun seedUnits(player: Player, space: BirdHouseSpace): Int = player.birdHouseSeedUnits(space)

    /* Ops */

    /** The body an `onOpHeldU` pair runs once the make menu has returned an amount of one. */
    fun craft(player: Player, type: BirdHouseType): Boolean =
        with(birdHouse) { protectedAccess(player).craftBirdHouse(type) }

    /** The silent check the make menu gates on before it opens. */
    fun craftRefusal(player: Player, type: BirdHouseType): String? =
        with(birdHouse) { protectedAccess(player).birdHouseCraftRefusal(type) }

    /** Both tools a craft holds but never spends. */
    fun giveCraftingTools(player: Player) {
        giveItem(player, HunterBirdHouse.CHISEL)
        giveItem(player, HunterBirdHouse.HAMMER)
    }

    /** Everything one house of [type] costs: the logs, a clockwork, and both tools. */
    fun giveCraftingKit(player: Player, type: BirdHouseType, houses: Int = 1) {
        giveCraftingTools(player)
        giveItem(player, type.logs, houses)
        giveItem(player, HunterBirdHouse.CLOCKWORK, houses)
    }

    fun build(player: Player, space: BirdHouseSpace): Boolean =
        with(birdHouse) { protectedAccess(player).buildBirdHouse(spaceLoc(space)) }

    fun seed(player: Player, space: BirdHouseSpace): Boolean =
        with(birdHouse) { protectedAccess(player).addBirdHouseSeeds(spaceLoc(space)) }

    fun dismantle(player: Player, space: BirdHouseSpace): Boolean =
        with(birdHouse) { protectedAccess(player).dismantleBirdHouse(spaceLoc(space)) }

    fun empty(player: Player, space: BirdHouseSpace, rebuild: Boolean = false): Boolean =
        with(birdHouse) { protectedAccess(player).emptyBirdHouse(spaceLoc(space), rebuild) }

    /** Runs the soft queue's body, exactly as `onPlayerSoftQueueWithArgs` would. */
    fun fillArrives(player: Player, space: BirdHouseSpace) {
        with(birdHouse) { player.birdHouseFillArrives(space.index) }
    }

    fun login(player: Player) {
        with(birdHouse) { player.rearmBirdHouseFills() }
    }

    /**
     * Drops every pending fill, which is what a logout does.
     *
     * Queues live only as long as the session. A test that seeds a house and then calls [login]
     * without this is not modelling a logout at all - it is modelling one session that queued twice,
     * and it would let a login re-arm that scheduled nothing pass.
     */
    fun newSession(player: Player) {
        player.clearQueue(BIRDHOUSE_FILL_QUEUE)
    }

    /* Ground objs */

    /** Every obj currently lying on [coords], by internal name. */
    fun objNamesAt(coords: CoordGrid = BIRDHOUSE_TILE): List<String> =
        objRepo
            .findAll(coords)
            .mapNotNull { RSCM.getReverseMapping(RSCMType.OBJ, it.type) }
            .toList()

    /** How many of [obj] are lying on [coords]. */
    fun groundCount(obj: String, coords: CoordGrid = BIRDHOUSE_TILE): Int =
        objRepo.findAll(coords).filter { it.type == obj.asObjId() }.sumOf { it.count }

    /* Queues */

    /** Every pending bird house fill on [player], in the order they were scheduled. */
    fun pendingFills(player: Player): List<PlayerQueueList.Queue> {
        val iterator = player.queueList.iterator() ?: return emptyList()
        val queues = mutableListOf<PlayerQueueList.Queue>()
        while (iterator.hasNext()) {
            queues += iterator.next()
        }
        iterator.cleanUp()
        return queues.filter { it.id == BIRDHOUSE_FILL_QUEUE.asQueueId() }
    }

    private fun String.asQueueId(): Int = with(RSCM) { asRSCM(RSCMType.QUEUE) }

    private fun String.asObjId(): Int = with(RSCM) { asRSCM(RSCMType.OBJ) }

    companion object {
        /** Well clear of `RegionRegistry.INSTANCE_MIN_X`, matching the other hunter worlds. */
        val BIRDHOUSE_TILE: CoordGrid = CoordGrid(3204, 3204, 0)

        /** The first space, Mushroom Meadow north. */
        val FIRST_SPACE: BirdHouseSpace
            get() = BirdHouseSpaces.all.first()

        /** The last space, Verdant Valley south-west - used to prove the four do not share state. */
        val LAST_SPACE: BirdHouseSpace
            get() = BirdHouseSpaces.all.last()

        val NORMAL: BirdHouseType
            get() = checkNotNull(BirdHouseTypes.byObj("obj.birdhouse_normal"))

        val REDWOOD: BirdHouseType
            get() = checkNotNull(BirdHouseTypes.byObj("obj.birdhouse_redwood"))

        /** A quantity-10 seed. */
        const val LOW_SEED: String = "obj.barley_seed"

        /** A second quantity-10 seed, for the wiki's own 7-plus-3 mixing example. */
        const val OTHER_LOW_SEED: String = "obj.hammerstone_hop_seed"

        /** A quantity-5 seed. */
        const val HIGH_SEED: String = "obj.ranarr_seed"
    }
}
