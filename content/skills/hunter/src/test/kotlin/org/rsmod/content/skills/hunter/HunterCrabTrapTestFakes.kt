package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.coroutine.GameCoroutine
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
 * A world for crab trapping, and the emptiest of the four.
 *
 * There is no loc registry, no controller repository, no npc registry and no zone bookkeeping -
 * because [HunterCrabTrap] takes none of them. A crab trap lives entirely in the player's own
 * varbits, so a player, an inventory and two stats is the whole world it needs. That absence is
 * itself worth stating: a test that could not delete a map loc even if the production code tried is
 * weaker evidence than one that could, so [CrabTrapTest] asserts the *type* has no such collaborator
 * rather than watching a repository stay untouched.
 *
 * The site locs are built by hand rather than registered, for the same reason: nothing ever looks
 * them up in a repository. A [BoundLocInfo] is what an op hands the content, and building one over
 * the packed type is exactly what `LocInteractions` does.
 *
 * @param hunterXpBonus Added to every `stat.hunter` award; see [hunterXpModifiers].
 */
class HunterCrabTrapTestWorld(hunterXpBonus: Double = 0.0) {
    val random: ScriptedRandom = ScriptedRandom()

    private val playerList: PlayerList = PlayerList()

    val crabTrap: HunterCrabTrap =
        HunterCrabTrap(gameRandom = random, xpMods = hunterXpModifiers(hunterXpBonus))

    private var nextUuid: Long = 1L

    fun addPlayer(hunterLvl: Int = 99, constructionLvl: Int = 99): Player {
        val player = Player()
        player.coords = CRAB_TILE
        player.slotId = playerList.nextFreeSlot() ?: error("No free player slot.")
        player.uuid = nextUuid++
        player.observerUUID = player.uuid
        playerList[player.slotId] = player
        player.statMap.setBaseLevel("stat.hunter", hunterLvl.toByte())
        player.statMap.setCurrentLevel("stat.hunter", hunterLvl.toByte())
        player.statMap.setBaseLevel("stat.construction", constructionLvl.toByte())
        player.statMap.setCurrentLevel("stat.construction", constructionLvl.toByte())
        player.inv = Inventory.create("inv.inv")
        player.inv.owner = player
        // Past zero so `VarPlayerIntMapSetter` takes its real transmit branch rather than the
        // not-logged-in short circuit. The write lands on `NoopClient`, which is what a `Player()`
        // ships with, so the varbit is set exactly as it is in game.
        player.currentMapClock = 1
        player.processedMapClock = 1
        return player
    }

    fun protectedAccess(player: Player): ProtectedAccess =
        ProtectedAccess(player, GameCoroutine(), ProtectedAccessContextFactory.empty())

    fun giveItem(player: Player, obj: String, count: Int = 1) {
        protectedAccess(player).invAdd(player.inv, obj, count)
    }

    /** Everything a build needs and nothing else: the two tools, and one trap's materials. */
    fun giveBuildKit(
        player: Player,
        saw: String = "obj.poh_saw",
        nails: String = "obj.nails",
        nailCount: Int = HunterCrabTrap.CRAB_TRAP_NAIL_COUNT,
    ) {
        giveItem(player, saw)
        giveItem(player, HunterCrabTrap.CRAB_TRAP_HAMMER)
        giveItem(player, HunterCrabTrap.CRAB_TRAP_PLANK)
        giveItem(player, HunterCrabTrap.CRAB_TRAP_BUCKET)
        giveItem(player, nails, nailCount)
    }

    /** Leaves exactly zero free slots in the backpack. */
    fun fillInventory(player: Player, filler: String = "obj.oak_logs") {
        val access = protectedAccess(player)
        while (player.inv.freeSpace() > 0) {
            access.invAdd(player.inv, filler, 1)
        }
    }

    fun itemCount(player: Player, obj: String): Int = player.inv.count(obj)

    /** The [BoundLocInfo] an op on [site] hands the content: the map-placed multiloc parent. */
    fun siteLoc(site: CrabTrapSite): BoundLocInfo {
        val entity = LocEntity(site.locId, LocShape.CentrepieceStraight.id, 0)
        val info =
            LocInfo(
                LocLayerConstants.of(LocShape.CentrepieceStraight.id),
                CRAB_TILE,
                entity,
            )
        val type =
            ServerCacheManager.getObject(site.locId) ?: error("Missing loc type: ${site.loc}")
        return BoundLocInfo(info, type)
    }

    /* State */

    fun stateOf(player: Player, site: CrabTrapSite): Int =
        with(crabTrap) { player.crabTrapState(site) }

    fun setState(player: Player, site: CrabTrapSite, state: Int) {
        org.rsmod.api.player.vars.VarPlayerIntMapSetter.set(player, site.varbit, state)
    }

    /* Ops */

    fun build(player: Player, site: CrabTrapSite): Boolean =
        with(crabTrap) { protectedAccess(player).buildCrabTrap(siteLoc(site)) }

    fun bait(player: Player, site: CrabTrapSite): Boolean =
        with(crabTrap) { protectedAccess(player).baitCrabTrap(siteLoc(site)) }

    fun empty(player: Player, site: CrabTrapSite): Boolean =
        with(crabTrap) { protectedAccess(player).emptyCrabTrap(siteLoc(site)) }

    /** Runs the soft queue's body, exactly as `onPlayerSoftQueueWithArgs` would. */
    fun catchArrives(player: Player, site: CrabTrapSite) {
        with(crabTrap) { player.crabTrapCatchArrives(site.index) }
    }

    fun login(player: Player) {
        with(crabTrap) { player.rearmCrabTrapCatches() }
    }

    /* Queues */

    /** Every pending crab catch on [player], in the order they were scheduled. */
    fun pendingCatches(player: Player): List<PlayerQueueList.Queue> {
        val iterator = player.queueList.iterator() ?: return emptyList()
        val queues = mutableListOf<PlayerQueueList.Queue>()
        while (iterator.hasNext()) {
            queues += iterator.next()
        }
        iterator.cleanUp()
        return queues.filter { it.id == CRAB_CATCH_QUEUE.asQueueId() }
    }

    private fun String.asQueueId(): Int =
        with(dev.openrune.rscm.RSCM) { asRSCM(dev.openrune.rscm.RSCMType.QUEUE) }

    companion object {
        /** Well clear of `RegionRegistry.INSTANCE_MIN_X`, matching the other hunter worlds. */
        val CRAB_TILE: CoordGrid = CoordGrid(3204, 3204, 0)

        /** The first hole on The Pandemonium: the red crab, level 21, plain fish offcuts. */
        val RED_SITE: CrabTrapSite
            get() = CrabTrapSites.all.first { it.creature.level == 21 }

        /** The first hole on The Great Conch: the blue crab, level 48. */
        val BLUE_SITE: CrabTrapSite
            get() = CrabTrapSites.all.first { it.creature.level == 48 }

        /** The first hole on The Crown Jewel: the rainbow crab, level 77, fine offcuts. */
        val RAINBOW_SITE: CrabTrapSite
            get() = CrabTrapSites.all.first { it.creature.level == 77 }
    }
}
