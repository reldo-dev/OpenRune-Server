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
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.coroutine.suspension.GameCoroutineSimpleCompletion
import org.rsmod.events.EventBus
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
 * There is still no loc registry, no controller repository and no
 * [org.rsmod.api.random.GameRandom], because [HunterPitfall] takes none of them. A pit is per-player
 * varbit state on permanent map scenery, and neither half of the tease rolls anything.
 *
 * That absence is itself the point, and [HunterPitfallTest] asserts it on the *type* rather than by
 * watching a repository stay untouched: a test that could not delete a map loc even if the
 * production code tried is weaker evidence than one that could. A `locRepo.del` on a pit would take
 * it out of the world for every player until the next restart.
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
 */
class HunterPitfallTestWorld {
    val playerList: PlayerList = PlayerList()
    private val npcList: NpcList = NpcList()

    private val collision = CollisionFlagMap()
    private val eventBus = EventBus()
    private val zoneActivity = ZonePlayerActivityBitSet()

    private val npcRegistry = NpcRegistry(npcList, collision, eventBus)
    private val playerRegistry = PlayerRegistry(playerList, collision, zoneActivity, eventBus)

    val pitfall: HunterPitfall = HunterPitfall(xpMods = XpModifiers(emptySet()))

    private var nextUuid: Long = 1L

    fun addPlayer(hunterLvl: Int = 99): Player {
        val player = Player()
        player.coords = PITFALL_TILE
        player.slotId = playerList.nextFreeSlot() ?: error("No free player slot.")
        player.uuid = nextUuid++
        player.observerUUID = player.uuid
        playerRegistry.add(player)
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

    /** Takes [npc] out of the world for good, the way a permanent despawn would. */
    fun removeNpc(npc: Npc) {
        npcRegistry.del(npc)
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

    fun teasedBy(npc: Npc): PlayerUid? = pitfall.teasedBy(npc)

    fun stopChasing(npc: Npc) {
        pitfall.stopChasing(npc)
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

        val SUNLIGHT_SITE: PitfallSite
            get() = SUNLIGHT_SITES.first()

        val MOONLIGHT_SITE: PitfallSite
            get() = MOONLIGHT_SITES.first()
    }
}
