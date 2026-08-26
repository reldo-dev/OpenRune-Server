package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import kotlin.coroutines.startCoroutine
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.registry.npc.NpcRegistry
import org.rsmod.api.registry.player.PlayerRegistry
import org.rsmod.api.registry.player.isSuccess
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.coroutine.suspension.GameCoroutineSimpleCompletion
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.PlayerUid
import org.rsmod.game.inv.Inventory
import org.rsmod.map.CoordGrid
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * A world for pitfall trapping: a player, two inventories, one stat, and the creatures that get
 * teased into a pit.
 *
 * There is still no loc registry and no controller repository, because [HunterPitfall] takes
 * neither. A pit is per-player varbit state on permanent map scenery, so nothing this feature does
 * can reach a loc at all.
 *
 * That absence is itself the point, and [HunterPitfallTest] asserts it on the *type* rather than by
 * watching a repository stay untouched: a test that could not delete a map loc even if the
 * production code tried is weaker evidence than one that could. A `locRepo.del` on a pit would take
 * it out of the world for every player until the next restart.
 *
 * The [NpcRepository] this world builds is the exception the catch needs, and it is not an
 * exception to that invariant: a creature that goes into a pit dies, `despawn` is how every hunter
 * technique kills one, and an npc repository cannot touch scenery.
 *
 * The two registries this world *does* build exist for the tease, and neither is reachable from
 * [HunterPitfall]:
 * - [PlayerRegistry], so a player is given a real [PlayerUid]. Assigning one is what tells two
 *   players apart, and `Player.assignUid` is `@InternalApi`; going through the registry is how the
 *   running server does it. Without it every player carries [PlayerUid.NULL] and "the creature is
 *   chasing *that* player" would pass for any player at all.
 * - [NpcRegistry], so a creature has a slot, a uid and a place in the collision map - the state
 *   `NpcPlayerFollowModeProcessor` reads a follow target back out of.
 *
 * The ops take a [PitfallSite] rather than a `BoundLocInfo`, so unlike the crab trap's world this
 * one never has to build a loc over a packed type at all. Resolving a click to a site is the op
 * layer's job, and the op layer is not wired yet.
 *
 * @param hunterXpBonus Added to every `stat.hunter` award; see [hunterXpModifiers].
 */
class HunterPitfallTestWorld(hunterXpBonus: Double = 0.0) {
    val playerList: PlayerList = PlayerList()
    private val npcList: NpcList = NpcList()

    private val collision = CollisionFlagMap()
    private val eventBus = EventBus()
    private val zoneActivity = ZonePlayerActivityBitSet()

    private val mapClock = MapClock()
    private val npcRegistry = NpcRegistry(npcList, collision, eventBus)
    private val playerRegistry = PlayerRegistry(playerList, collision, zoneActivity, eventBus)
    private val npcRepo = NpcRepository(mapClock, npcRegistry, npcList)

    /**
     * The catch roll, dictated draw by draw.
     *
     * `rate > randomDouble()`, so [ScriptedRandom.ALWAYS_CATCH] catches whatever the level and
     * [ScriptedRandom.HIGHEST_DRAW] misses any rate at or below 256/256 - and the two antelopes are
     * caught either way, because their pair is null and no draw is taken for them at all.
     * [ScriptedRandom.doubleDraws] is what proves that last part rather than merely asserting the
     * outcome.
     */
    val random: ScriptedRandom = ScriptedRandom()

    val pitfall: HunterPitfall =
        HunterPitfall(
            gameRandom = random,
            npcRepo = npcRepo,
            xpMods = hunterXpModifiers(hunterXpBonus),
            playerList = playerList,
        )

    private var nextUuid: Long = 1L

    /**
     * A registered player, optionally in a named [slot].
     *
     * [slot] exists for one test: `PlayerList.nextFreeSlot` walks forward from `lastUsedSlot + 1`
     * and only comes back round to a freed slot after the list has wrapped, so a login that
     * inherits a logged-out player's slot cannot be produced in two lines - it has to be asked
     * for. Everything else takes the next free one.
     */
    fun addPlayer(hunterLvl: Int = 99, slot: Int? = null): Player {
        val player = Player()
        player.coords = PITFALL_TILE
        player.slotId = slot ?: playerList.nextFreeSlot() ?: error("No free player slot.")
        player.uuid = nextUuid++
        player.observerUUID = player.uuid
        // Checked rather than discarded. A `NoAvailableSlot` would leave the player on
        // `PlayerUid.NULL`, and every "the creature is chasing *that* player" assertion in
        // `HunterPitfallTest` would pass for any player at all - which is exactly the vacuity
        // building a real `PlayerRegistry` was for.
        check(playerRegistry.add(player).isSuccess()) { "Could not register test player." }
        player.statMap.setBaseLevel("stat.hunter", hunterLvl.toByte())
        player.statMap.setCurrentLevel("stat.hunter", hunterLvl.toByte())
        player.inv = Inventory.create("inv.inv")
        player.inv.owner = player
        player.worn = Inventory.create("inv.worn")
        player.worn.owner = player
        // Past zero so `VarPlayerIntMapSetter` takes its real transmit branch rather than the
        // not-logged-in short circuit, the same as the crab trap and bird house worlds.
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

    /** A knife and one ordinary log: the whole of what setting a pit costs. */
    fun giveTrapKit(player: Player, knife: String = KNIFE, log: String = LOGS, logs: Int = 1) {
        giveItem(player, knife)
        giveItem(player, log, logs)
    }

    /** Leaves exactly zero free slots in the backpack. */
    fun fillInventory(player: Player, filler: String = FILLER) {
        val access = protectedAccess(player)
        while (player.inv.freeSpace() > 0) {
            access.invAdd(player.inv, filler, 1)
        }
    }

    fun itemCount(player: Player, obj: String): Int = player.inv.count(obj)

    fun wornCount(player: Player, obj: String): Int = player.worn.count(obj)

    fun hunterXp(player: Player): Int = player.statMap.getXP("stat.hunter")

    /** The player's Hunter xp in the engine's own tenths, so a fractional award is observable. */
    fun hunterFineXp(player: Player): Int = player.statMap.getFineXP("stat.hunter")

    /* State */

    fun stateOf(player: Player, site: PitfallSite): PitState = pitfall.pitState(player, site)

    /** The raw varbit value, read back without going through [PitState]. */
    fun varbitOf(player: Player, site: PitfallSite): Int = player.vars[site.varbit]

    fun setState(player: Player, site: PitfallSite, state: PitState) {
        VarPlayerIntMapSetter.set(player, site.varbit, state.varbitValue)
    }

    /* Ops */

    fun trap(player: Player, site: PitfallSite): Boolean =
        with(pitfall) { protectedAccess(player).trapPit(site) }

    fun dismantle(player: Player, site: PitfallSite): Boolean =
        with(pitfall) { protectedAccess(player).dismantlePit(site) }

    fun clearPits(player: Player) {
        pitfall.clearPits(player)
    }

    /** The body of `PITFALL_REBUILD_QUEUE`, which a login arms one cycle in. */
    fun rebuildPits(player: Player) {
        pitfall.rebuildPits(player)
    }

    /* Creatures */

    /** A live creature on [coords], registered so it has a slot and a uid of its own. */
    fun addNpc(internal: String, coords: CoordGrid = CREATURE_TILE): Npc {
        val type =
            ServerCacheManager.getNpc(internal.asRSCM(RSCMType.NPC))
                ?: error("Missing npc type: $internal")
        val npc = Npc(type, coords)
        npcRegistry.add(npc)
        return npc
    }

    /**
     * The pit's **own** creature, live, [tilesAway] tiles east of the pit itself.
     *
     * Spawned from [PitfallSite.creature] rather than from a name the test picks, because a jump
     * only catches the species the pit belongs to and a test that spelled the pairing out again
     * would be free to disagree with the site table. The offset is what a range test varies.
     */
    fun addCreatureAt(site: PitfallSite, tilesAway: Int = 0): Npc =
        addNpc(
            site.creature.npc,
            CoordGrid(x = site.coords.x + tilesAway, z = site.coords.z, level = site.coords.level),
        )

    /** Puts [npc] on the pit at [site], which is where a lure ends. */
    fun moveNpcTo(npc: Npc, site: PitfallSite) {
        npc.coords = site.coords
    }

    /** Takes [npc] out of the world for good, the way a permanent despawn would. */
    fun removeNpc(npc: Npc) {
        npcRegistry.del(npc)
    }

    /** Logs [player] out, freeing their slot for the next player to be given. */
    fun removePlayer(player: Player) {
        check(playerRegistry.del(player).isSuccess()) { "Could not deregister test player." }
    }

    /**
     * Puts [npc] [tiles] tiles east of the tile it spawned on, which is the lure a hunter walks.
     *
     * Only the creature is moved, and that is the honest model rather than a shortcut:
     * `NpcPlayerFollowModeProcessor` re-routes the creature towards the player every cycle and
     * teleports it onto them past fifteen tiles, so the creature's own tile is the one that
     * strays.
     *
     * A plain coordinate write, as `HunterFalconryTestWorld` moves a kebbit. `Npc.teleport` would
     * be the engine's own call but it `check`s that the destination zone is *allocated*, and in a
     * bare [CollisionFlagMap] only the tiles something has already been placed on are - so a lure
     * of any length would die on the check rather than on the leash.
     */
    fun lureNpc(npc: Npc, tiles: Int) {
        val spawn = npc.spawnCoords
        npc.coords = CoordGrid(x = spawn.x + tiles, z = spawn.z, level = spawn.level)
    }

    /**
     * `Tease` on [npc], driven to completion in one call.
     *
     * [HunterPitfall.teaseCreature] is declared `suspend` so it composes with the suspending op
     * handler a later task registers, but it has no suspension point: a tease that locked the
     * player for even one cycle would break the wiki's own "quickly tease creature B while creature
     * A is still walking over the trap" procedure. `startCoroutine` therefore runs it straight
     * through, and the [checkNotNull] below is the assertion of exactly that - the day a delay is
     * added, every tease test fails loudly here rather than quietly returning a half-run result.
     */
    fun tease(player: Player, npc: Npc): Boolean {
        val coroutine = GameCoroutine()
        val access = ProtectedAccess(player, coroutine, ProtectedAccessContextFactory.empty())
        var outcome: Result<Boolean>? = null
        val body: suspend GameCoroutine.() -> Unit = {
            outcome = runCatching { with(pitfall) { access.teaseCreature(npc) } }
        }
        body.startCoroutine(coroutine, GameCoroutineSimpleCompletion)
        val result = checkNotNull(outcome) { "teaseCreature suspended; this harness cannot resume." }
        return result.getOrThrow()
    }

    /**
     * `Jump` on [site], driven to completion in one call.
     *
     * [HunterPitfall.jumpPit] is `suspend` for the same reason the tease is - it composes with the
     * suspending op handler a later task registers it in - and has no suspension point of its own:
     * the catch resolves in the cycle the player jumps, and the collapse is a cycle count on
     * [HunterPitfall.tick] rather than a delay the player waits out. The [checkNotNull] is the
     * assertion of exactly that.
     */
    fun jump(player: Player, site: PitfallSite): Boolean {
        val coroutine = GameCoroutine()
        val access = ProtectedAccess(player, coroutine, ProtectedAccessContextFactory.empty())
        var outcome: Result<Boolean>? = null
        val body: suspend GameCoroutine.() -> Unit = {
            outcome = runCatching { with(pitfall) { access.jumpPit(site) } }
        }
        body.startCoroutine(coroutine, GameCoroutineSimpleCompletion)
        val result = checkNotNull(outcome) { "jumpPit suspended; this harness cannot resume." }
        return result.getOrThrow()
    }

    fun teasedBy(npc: Npc): PlayerUid? = pitfall.teasedBy(npc)

    fun stopChasing(npc: Npc) {
        pitfall.stopChasing(npc)
    }

    /**
     * One cycle of the hook [HunterPitfall.tick] needs registering on
     * `GameLifecycle.LateCycle`.
     *
     * Nothing else in this world runs on a clock, so a chase ends here or it does not end at all -
     * which is exactly the production situation the hook exists for.
     */
    fun tick() {
        pitfall.tick()
    }

    /** [times] cycles of that same hook, which is how a collapse finishes landing. */
    fun tick(times: Int) {
        repeat(times) { pitfall.tick() }
    }

    /** Who [npc] is chasing, read exactly as `NpcPlayerFollowModeProcessor` reads it each cycle. */
    fun chaseTarget(npc: Npc): Player? = npc.facingTarget(playerList)

    companion object {
        /** Well clear of `RegionRegistry.INSTANCE_MIN_X`, matching the other hunter worlds. */
        val PITFALL_TILE: CoordGrid = CoordGrid(3204, 3204, 0)

        /** A few tiles off [PITFALL_TILE], so two creatures never share a square. */
        val CREATURE_TILE: CoordGrid = CoordGrid(3210, 3210, 0)

        val SECOND_CREATURE_TILE: CoordGrid = CoordGrid(3214, 3214, 0)

        const val KNIFE: String = "obj.knife"
        const val FLETCHING_KNIFE: String = "obj.fletching_knife"

        /** Firemaking level 1, and the lowest tier of log a pit accepts. */
        const val LOGS: String = "obj.logs"

        /** Firemaking level 15. */
        const val OAK_LOGS: String = "obj.oak_logs"

        /** Firemaking level 30. */
        const val WILLOW_LOGS: String = "obj.willow_logs"

        /** Firemaking level 45. */
        const val MAPLE_LOGS: String = "obj.maple_logs"

        /** Firemaking level 90, and refused outright: see the *Pitfall* page. */
        const val REDWOOD_LOGS: String = "obj.redwood_logs"

        /** Firemaking 42, refused outright, and singular where every other log is plural. */
        const val ARCTIC_PINE_LOG: String = "obj.arctic_pine_log"

        /**
         * The inventory filler, chosen to be neither a log nor anything a pit can award: a filler
         * that was a log would arm the trap it is meant to be crowding out, and one that was loot
         * would let a stackable award land in a "full" inventory for free.
         */
        const val FILLER: String = "obj.bucket_empty"

        /** Teasing stick, `obj.hunting_teasing_stick` (10029), `wearpos=righthand`. */
        const val TEASING_STICK: String = "obj.hunting_teasing_stick"

        /** Hunter's spear, `obj.hg_hunter_spear` (29305), equipped to the weapon slot. */
        const val HUNTERS_SPEAR: String = "obj.hg_hunter_spear"

        /** Spined larupia, `npc.hunting_jaguar` (2908) - `op1=Tease` and no `Attack` op. */
        const val LARUPIA_NPC: String = "npc.hunting_jaguar"

        /** Horned graahk, `npc.hunting_leopard` (2909). */
        const val GRAAHK_NPC: String = "npc.hunting_leopard"

        /** Moonlight antelope, `npc.moonlight_antelope` (13132), the level 91 creature. */
        const val MOONLIGHT_NPC: String = "npc.moonlight_antelope"

        /** A hunter creature with no pitfall in it: `npc.hunting_chinchompa` (2910). */
        const val CHINCHOMPA_NPC: String = "npc.hunting_chinchompa"

        /** Every pitfall creature's npc, in the table's own order. */
        val CREATURE_NPCS: List<String> =
            listOf(
                "npc.hunting_jaguar",
                "npc.hunting_leopard",
                "npc.hunting_snow_tiger",
                "npc.sunlight_antelope",
                "npc.moonlight_antelope",
            )

        val LARUPIA_SITES: List<PitfallSite>
            get() = PitfallSites.all.filter { it.creature == PitfallCreatures.larupia }

        val GRAAHK_SITES: List<PitfallSite>
            get() = PitfallSites.all.filter { it.creature == PitfallCreatures.graahk }

        val KYATT_SITES: List<PitfallSite>
            get() = PitfallSites.all.filter { it.creature == PitfallCreatures.kyatt }

        val SUNLIGHT_SITES: List<PitfallSite>
            get() = PitfallSites.all.filter { it.creature == PitfallCreatures.sunlight }

        val MOONLIGHT_SITES: List<PitfallSite>
            get() = PitfallSites.all.filter { it.creature == PitfallCreatures.moonlight }

        val LARUPIA_SITE: PitfallSite
            get() = LARUPIA_SITES.first()

        /**
         * A second larupia pit, and the two are far enough apart to matter: site 7 is at
         * (2556, 2893) and site 8 at (2543, 2908), so a creature standing on one is nowhere near
         * the other. That is what makes it the "another pit" the refusal rule sends a creature to.
         */
        val SECOND_LARUPIA_SITE: PitfallSite
            get() = LARUPIA_SITES[1]

        val SUNLIGHT_SITE: PitfallSite
            get() = SUNLIGHT_SITES.first()

        val MOONLIGHT_SITE: PitfallSite
            get() = MOONLIGHT_SITES.first()
    }
}
