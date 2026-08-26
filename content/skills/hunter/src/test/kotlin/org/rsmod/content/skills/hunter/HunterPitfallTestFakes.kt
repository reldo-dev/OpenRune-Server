package org.rsmod.content.skills.hunter

import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.inv.Inventory
import org.rsmod.map.CoordGrid

/**
 * A world for pitfall trapping, and the emptiest in the module - emptier even than the crab trap's.
 *
 * There is no loc registry, no controller repository, no npc registry, no zone bookkeeping and no
 * [org.rsmod.api.random.GameRandom], because [HunterPitfall] takes none of them. A pit is
 * per-player varbit state on permanent map scenery, so a player, two inventories and one stat is
 * the whole world it needs.
 *
 * That absence is itself the point, and [HunterPitfallTest] asserts it on the *type* rather than by
 * watching a repository stay untouched: a test that could not delete a map loc even if the
 * production code tried is weaker evidence than one that could. A `locRepo.del` on a pit would take
 * it out of the world for every player until the next restart.
 *
 * The ops take a [PitfallSite] rather than a `BoundLocInfo`, so unlike the crab trap's world this
 * one never has to build a loc over a packed type at all. Resolving a click to a site is the op
 * layer's job, and the op layer is not wired yet.
 */
class HunterPitfallTestWorld {
    private val playerList: PlayerList = PlayerList()

    val pitfall: HunterPitfall = HunterPitfall(xpMods = XpModifiers(emptySet()))

    private var nextUuid: Long = 1L

    fun addPlayer(hunterLvl: Int = 99): Player {
        val player = Player()
        player.coords = PITFALL_TILE
        player.slotId = playerList.nextFreeSlot() ?: error("No free player slot.")
        player.uuid = nextUuid++
        player.observerUUID = player.uuid
        playerList[player.slotId] = player
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

    companion object {
        /** Well clear of `RegionRegistry.INSTANCE_MIN_X`, matching the other hunter worlds. */
        val PITFALL_TILE: CoordGrid = CoordGrid(3204, 3204, 0)

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
