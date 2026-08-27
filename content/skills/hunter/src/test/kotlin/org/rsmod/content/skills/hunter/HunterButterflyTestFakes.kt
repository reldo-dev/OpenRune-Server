package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.registry.npc.NpcRegistry
import org.rsmod.api.registry.player.PlayerRegistry
import org.rsmod.api.registry.zone.ZonePlayerActivityBitSet
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.coroutine.GameCoroutine
import org.rsmod.events.EventBus
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.inv.Inventory
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * A world for the two netting techniques.
 *
 * Butterfly netting and impling catching touch no locs, no controllers and no ground objs, so this
 * is a player, an npc registry and nothing else - even [HunterFalconryTestWorld]'s controller
 * repository is dead weight here. Nothing is mocked but [random].
 *
 * The one thing it has that no other world does is a **worn** inventory, because "wielding a net" is
 * a worn-slot question and the barehanded branch is decided by its absence.
 *
 * It carries both [butterfly] and [impling] rather than being copied into a world file of its own:
 * the two techniques share the nets, the barehanded cost, the faster curve and the jar swap, so a
 * second world would be this one with a different field name and a second place for the
 * worn-inventory setup to drift.
 *
 * @param hunterXpBonus Added to every `stat.hunter` award; see [hunterXpModifiers].
 */
class HunterButterflyTestWorld(hunterXpBonus: Double = 0.0) {
    val mapClock: MapClock = MapClock()
    val random: ScriptedRandom = ScriptedRandom()

    private val playerList: PlayerList = PlayerList()
    val npcList: NpcList = NpcList()

    private val collision = CollisionFlagMap()
    private val eventBus = EventBus()
    private val zoneActivity = ZonePlayerActivityBitSet()

    private val npcRegistry = NpcRegistry(npcList, collision, eventBus)
    private val playerRegistry = PlayerRegistry(playerList, collision, zoneActivity, eventBus)

    val npcRepo: NpcRepository = NpcRepository(mapClock, npcRegistry, npcList)

    val butterfly: HunterButterfly =
        HunterButterfly(npcRepo = npcRepo, gameRandom = random, xpMods = hunterXpModifiers(hunterXpBonus))

    private var nextUuid: Long = 1L

    fun addPlayer(coords: CoordGrid = BUTTERFLY_TILE, hunterLvl: Int = 99): Player {
        val player = Player()
        player.coords = coords
        player.slotId = playerList.nextFreeSlot() ?: error("No free player slot.")
        player.uuid = nextUuid++
        player.observerUUID = player.uuid
        playerRegistry.add(player)
        playerRegistry.change(player, ZoneKey.NULL, ZoneKey.from(coords))
        player.statMap.setBaseLevel("stat.hunter", hunterLvl.toByte())
        player.statMap.setCurrentLevel("stat.hunter", hunterLvl.toByte())
        player.inv = Inventory.create("inv.inv")
        player.inv.owner = player
        // `InvMapInit` builds this at login on a real player; `HunterButterfly.isHoldingNet` reads
        // it unconditionally, so a world without one would throw on the first catch rather than
        // report "barehanded".
        player.worn = Inventory.create("inv.worn")
        player.worn.owner = player
        return player
    }

    fun protectedAccess(player: Player): ProtectedAccess =
        ProtectedAccess(player, GameCoroutine(), ProtectedAccessContextFactory.empty())

    fun giveItem(player: Player, obj: String, count: Int = 1) {
        protectedAccess(player).invAdd(player.inv, obj, count)
    }

    /** Puts [obj] in the worn inventory, which is what "wielding" means for both nets. */
    fun wield(player: Player, obj: String) {
        protectedAccess(player).invAdd(player.worn, obj, 1)
    }

    /** Leaves exactly zero free slots in the backpack. */
    fun fillInventory(player: Player, filler: String = "obj.oak_logs") {
        val access = protectedAccess(player)
        while (player.inv.freeSpace() > 0) {
            access.invAdd(player.inv, filler, 1)
        }
    }

    fun addNpc(internal: String, coords: CoordGrid = BUTTERFLY_TILE.translateX(1)): Npc {
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

    /**
     * Runs a butterfly op against [player].
     *
     * No coroutine driving, unlike the falconry and trap worlds: `catchButterfly` does not suspend,
     * which is itself part of the design - there is no flight to wait for.
     */
    fun <T> run(player: Player, op: HunterButterfly.(ProtectedAccess) -> T): T {
        val access = protectedAccess(player)
        player.currentMapClock = mapClock.cycle
        player.processedMapClock = mapClock.cycle
        return butterfly.op(access)
    }

    companion object {
        /** Well clear of `RegionRegistry.INSTANCE_MIN_X`, and mid-zone. */
        val BUTTERFLY_TILE: CoordGrid = CoordGrid(3204, 3204, 0)
    }
}
