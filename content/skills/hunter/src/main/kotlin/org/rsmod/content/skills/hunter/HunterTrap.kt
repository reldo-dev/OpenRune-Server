package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.controller.ControllerRepository
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.player.PlayerRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.table.FiremakingLogsRow
import org.rsmod.api.utils.skills.SkillingSuccessRate
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Controller
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.PlayerUid
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey

/**
 * Lay, advance, collect and collapse for the trap families.
 *
 * A laid trap is a [Controller] anchored at its tile; the controller, the loc chain and the cap
 * all resolve from the tile. The player-facing ops belong to the per-family scripts, which also
 * register `onAiConTimer(TRAP_CONTROLLER)` exactly once, since it is family-agnostic. Design notes
 * and sources: docs/hunter.md.
 */
class HunterTrap
@Inject
constructor(
    private val locRepo: LocRepository,
    private val conRepo: ControllerRepository,
    private val npcRepo: NpcRepository,
    private val playerRepo: PlayerRepository,
    private val playerList: PlayerList,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
    private val mapClock: MapClock,
) {
    fun ProtectedAccess.layTrap(family: TrapFamily, coords: CoordGrid): Boolean {
        val laid = trapAllowance() ?: return false

        if (!canTakeTrap(coords)) {
            mes("You can't set a trap here.")
            return false
        }

        val setLoc = HunterTrapStates.setLoc(family) ?: return false
        val trapObj = trapObj(family) ?: return false
        if (invDel(inv, trapObj, 1).failure) {
            val name = ServerCacheManager.getItem(trapObj.asRSCM(RSCMType.OBJ))?.name?.lowercase()
            mes("You don't have a ${name ?: "trap"} to lay.")
            return false
        }

        spawnTrapLoc(coords, setLoc)

        val spawn = Controller(TRAP_CONTROLLER, coords)
        conRepo.add(spawn, TRAP_LIFETIME_CYCLES)
        spawn.trapOwner = player.uid.packed
        spawn.trapFamily = family.ordinal
        spawn.trapCreature = CREATURE_NONE
        spawn.aiTimer(1)

        player.hunterTrapCoords = laid + coords.packed
        return true
    }

    // The knife is a kept tool; the log is consumed and its obj id recorded, because dismantling
    // hands that exact log back. Maniacal monkeys' bananas are out of scope (docs/hunter.md).
    suspend fun ProtectedAccess.setDeadfall(loc: BoundLocInfo): Boolean {
        if (player.hunterLvl < DEADFALL_LEVEL_REQ) {
            mes("You need a Hunter level of $DEADFALL_LEVEL_REQ to set up a deadfall trap.")
            return false
        }

        if (!inv.contains(KNIFE) && !inv.contains(FLETCHING_KNIFE)) {
            mes("You need a knife to set up a deadfall trap.")
            return false
        }

        if (conRepo.findExact(loc.coords, TRAP_CONTROLLER) != null) {
            mes("This trap is already set.")
            return false
        }

        val laid = trapAllowance() ?: return false
        if (deadfallCount(laid) >= MAX_LAID_DEADFALLS) {
            mes("You can only have one deadfall trap set up at a time.")
            return false
        }

        // Slot order is ours (docs/hunter.md). Resolved *before* the delay so the log this check
        // accepted is the log the player is charged, whatever they do to their inventory meanwhile.
        val log = inv.firstOrNull { it != null && it.id in usableLogIds }
        if (log == null) {
            mes("You need some logs to set up a deadfall trap.")
            return false
        }
        val logId = log.id
        val logObj = RSCM.getReverseMapping(RSCMType.OBJ, logId)

        anim("seq.human_laytrap")

        // Timed: it reverts the boulder on its own if the delay never resumes, so a logout mid-set
        // cannot strand a permanent map loc in an op-less state. The extra cycle keeps the safety
        // net from racing the normal path.
        changeDeadfallLoc(loc.coords, HunterTrapStates.DEADFALL_SETTING, DEADFALL_SET_CYCLES + 1)
        delay(DEADFALL_SET_CYCLES)

        // Anything but the setting state (or the boulder, if the safety revert above beat us to the
        // resume) means someone else has taken the boulder in the meantime.
        val current = findTrapLoc(TrapFamily.DEADFALL, loc.coords)
        val stillOurs =
            current != null &&
                current.id in SETTABLE_DEADFALL_LOCS &&
                conRepo.findExact(loc.coords, TRAP_CONTROLLER) == null
        if (!stillOurs) {
            mes("Someone else is already using this boulder.")
            return false
        }

        // Charged past the last path that can refuse, not before the delay: an aborted set must
        // not cost a log with no notice (docs/hunter.md). Re-checked rather than assumed held -
        // the delay is long enough to bank the log, and that earns a refusal, not a free arm.
        if (invDel(inv, logObj, 1).failure) {
            mes("You need some logs to set up a deadfall trap.")
            return false
        }

        changeDeadfallLoc(loc.coords, HunterTrapStates.DEADFALL_ARMED)

        val spawn = Controller(TRAP_CONTROLLER, loc.coords)
        conRepo.add(spawn, TRAP_LIFETIME_CYCLES)
        spawn.trapOwner = player.uid.packed
        spawn.trapFamily = TrapFamily.DEADFALL.ordinal
        spawn.trapCreature = CREATURE_NONE
        spawn.trapDeadfallLog = logId
        spawn.aiTimer(1)

        // Re-swept rather than reusing `laid`: three cycles is long enough for one of this player's
        // other traps to have collapsed.
        player.hunterTrapCoords = player.sweepTrapCoords() + loc.coords.packed
        return true
    }

    // That the log comes back is unsourced - see docs/hunter.md. A deadfall with no controller is
    // not an error: something already tore it down, and there is nothing to hand back.
    fun ProtectedAccess.dismantleDeadfall(loc: BoundLocInfo): Boolean {
        val controller = conRepo.findExact(loc.coords, TRAP_CONTROLLER)
        if (controller != null && controller.trapOwner != player.uid.packed) {
            mes("This isn't your trap.")
            return false
        }

        val log = controller?.deadfallLogObj()
        if (log != null) {
            if (inv.freeSpace() < hunterInvSlotsNeeded(inv, log, 1)) {
                mes("Your inventory is too full to hold any more.")
                soundSynth("synth.pillory_wrong")
                return false
            }
            invAdd(inv, log, 1)
        }

        changeDeadfallLoc(loc.coords, HunterTrapStates.DEADFALL_BOULDER)

        if (controller != null) {
            conRepo.del(controller)
            player.sweepTrapCoords()
        }
        return true
    }

    // Deliberately never resets an idle trap's duration: unattended traps decay toward collapse.
    fun Controller.hunterTrapTick() {
        val family = TrapFamily.entries.getOrNull(trapFamily)
        if (family == null) {
            // A corrupt ordinal must not strand a controller-less loc on the tile forever. A
            // deadfall reaching here throws in [clearTrapLoc] instead: loud and restartable beats
            // silently deleting a map loc.
            clearTrapLoc(coords)
            conRepo.del(this)
            return
        }

        val loc = findTrapLoc(family, coords)
        if (loc == null) {
            check(mapClock > creationCycle + 1) { "Hunter trap loc deleted faster than expected." }
            conRepo.del(this)
            return
        }

        // Traps belong to a logged-in owner: live despawns a player's traps when they leave.
        val owner = PlayerUid(trapOwner).resolve(playerList)
        if (owner == null) {
            collapse(family, owner = null)
            return
        }

        // ControllerRepository deletes an expired controller silently, which would strand the
        // loc, so collapse one cycle early instead.
        if (duration <= 1) {
            collapse(family, owner)
            return
        }

        if (trapCreature != CREATURE_NONE) {
            // Already sprung: settle, and keep ticking so the collapse above can still reclaim an
            // uncollected trap.
            if (settle(family, owner)) {
                aiTimer(1)
            }
            return
        }

        // Re-armed every cycle whatever the family's attempt cadence is: this tick is also what
        // notices the expiring lifetime above.
        aiTimer(1)

        // Phased on the trap's own creation cycle so traps laid on different cycles do not all
        // roll in lockstep. Cadence sources: docs/hunter.md.
        if ((mapClock.cycle - creationCycle) % family.attemptCycles != 0) {
            return
        }

        // A player standing on the trap blocks the roll only - the trap still ages toward
        // collapse. `isValidTarget()` is load-bearing: `PlayerRegistry.findAll` does not filter
        // hidden or mid-logout players, and one parked here would suppress every catch silently.
        // Sources and the accepted trap-camping consequence: docs/hunter.md.
        val centre = loc.coords
        if (
            family.suppressedByPlayerOnTile &&
                playerRepo.findAll(centre).any { it.isValidTarget() }
        ) {
            return
        }

        val target = nearbyCreature(family, centre) ?: return

        val (npc, creature) = target

        // A positive `successLow` (regular chinchompa) gives a real catch chance below the level
        // requirement, so the gate is explicit, and it short-circuits before the roll so an
        // under-levelled attempt never consumes a random draw. See docs/hunter.md.
        val caught =
            owner.hunterLvl >= creature.level &&
                SkillingSuccessRate.successRate(
                    low = creature.successLow,
                    high = creature.successHigh,
                    level = owner.hunterLvl,
                    maxLevel = MAX_HUNTER_LEVEL,
                ) > random.randomDouble()

        npcRepo.despawn(npc, npc.visType.respawnRate)

        if (caught) {
            trapCreature = HunterCreatures.all.indexOf(creature)
            val dx = npc.coords.x - centre.x
            val dz = npc.coords.z - centre.z
            advanceTrapLoc(family, coords, HunterTrapStates.trappingLoc(creature, dx, dz))
        } else {
            trapCreature = CREATURE_FAILED
            advanceTrapLoc(family, coords, HunterTrapStates.failingLoc(family, creature))
        }

        // A sprung trap waits for its owner rather than continuing to decay.
        resetDuration()
        aiTimer(TRAP_SPRING_CYCLES)
    }

    fun ProtectedAccess.collectTrap(loc: BoundLocInfo): Boolean = collectTrapAt(loc.coords)

    private fun ProtectedAccess.collectTrapAt(coords: CoordGrid): Boolean {
        val controller = conRepo.findExact(coords, TRAP_CONTROLLER) ?: return false
        if (controller.trapOwner != player.uid.packed) {
            mes("This isn't your trap.")
            return false
        }

        val family = TrapFamily.entries.getOrNull(controller.trapFamily) ?: return false
        val creature = HunterCreatures.all.getOrNull(controller.trapCreature)

        // Rolled once, up front: the space check and the awards must agree on the same numbers.
        // `this@HunterTrap.random`, not `random` - the `ProtectedAccess` receiver has a `random`
        // of its own that silently shadows the injected field.
        val awards =
            creature?.caught.orEmpty().map {
                it.obj to rollQuantity(this@HunterTrap.random, it.quantity)
            }

        val returned = trapComponents(family)

        // A stackable award the player already holds costs no slot; see [hunterInvSlotsNeeded].
        val slotsNeeded =
            awards.sumOf { (obj, count) -> hunterInvSlotsNeeded(inv, obj, count) } +
                returned.sumOf { hunterInvSlotsNeeded(inv, it, 1) }
        if (inv.freeSpace() < slotsNeeded) {
            mes("Your inventory is too full to hold any more.")
            soundSynth("synth.pillory_wrong")
            return false
        }

        for ((obj, count) in awards) {
            invAdd(inv, obj, count)
        }
        for (obj in returned) {
            invAdd(inv, obj, 1)
        }

        if (creature != null) {
            // Stored x10.
            val xp = (creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
            statAdvance("stat.hunter", xp)
        }

        endTrapLoc(family, coords)
        conRepo.del(controller)
        player.sweepTrapCoords()
        return true
    }

    // A collapsed trap outlives its controller, so a missing controller is an ordinary case:
    // whoever clears the tile keeps the trap item, consumed once on lay - it cannot mint twice.
    fun ProtectedAccess.takeTrap(loc: BoundLocInfo, family: TrapFamily): Boolean {
        if (conRepo.findExact(loc.coords, TRAP_CONTROLLER) != null) {
            return collectTrap(loc)
        }

        val trapObj = trapObj(family) ?: return false
        if (inv.freeSpace() < hunterInvSlotsNeeded(inv, trapObj, 1)) {
            mes("Your inventory is too full to hold any more.")
            soundSynth("synth.pillory_wrong")
            return false
        }

        invAdd(inv, trapObj, 1)
        clearTrapLoc(loc.coords)
        player.sweepTrapCoords()
        return true
    }

    private fun Player.sweepTrapCoords(): List<Int> {
        val stored = hunterTrapCoords
        val live =
            stored.filter { packed ->
                val controller = conRepo.findExact(CoordGrid(packed), TRAP_CONTROLLER)
                controller != null && controller.trapOwner == uid.packed
            }
        if (live.size != stored.size) {
            hunterTrapCoords = live
        }
        return live
    }

    // Replaces the intermediate loc with the terminal one; false if the trap is finished and its
    // controller deleted.
    private fun Controller.settle(family: TrapFamily, owner: Player?): Boolean {
        // A failed deadfall has no collectible state (no `hunting_deadfall_failed` in the cache):
        // the boulder unsets, the log is lost, and the controller need not outlive the spring.
        if (family == TrapFamily.DEADFALL && trapCreature == CREATURE_FAILED) {
            changeDeadfallLoc(coords, HunterTrapStates.failedLoc(family))
            conRepo.del(this)
            return false
        }

        val settled =
            if (trapCreature == CREATURE_FAILED) {
                HunterTrapStates.failedLoc(family)
            } else {
                val creature = HunterCreatures.all.getOrNull(trapCreature) ?: return true
                HunterTrapStates.fullLoc(creature)
            }
        val current = findTrapLoc(family, coords) ?: return true
        if (current.id == settled.asRSCM(RSCMType.LOC)) {
            return true
        }
        advanceTrapLoc(family, coords, settled)
        return true
    }

    // The wreck stays on the ground for a while, so the owner can still come back for the trap
    // item. [owner] is null when the collapse *is* the owner logging out.
    private fun Controller.collapse(family: TrapFamily, owner: Player?) {
        if (family == TrapFamily.DEADFALL) {
            changeDeadfallLoc(coords, HunterTrapStates.failedLoc(family))
            // The string is ours; live's server-sent wording is not recoverable offline.
            owner?.mes("Your deadfall trap has collapsed.", ChatType.GameMessage)
            conRepo.del(this)
            return
        }
        spawnTrapLoc(coords, HunterTrapStates.failedLoc(family), TRAP_COLLAPSE_LINGER_CYCLES)
        conRepo.del(this)
    }

    private fun ProtectedAccess.trapAllowance(): List<Int>? {
        // Sweep before the cap check: a trap that died while the player was away must not still
        // occupy a slot. The cap reads the effective level, so boosts raise it.
        val laid = player.sweepTrapCoords()
        val cap = TrapLadder.cap(player.hunterLvl)
        if (laid.size >= cap) {
            val plural = if (cap == 1) "trap" else "traps"
            mes("You can only lay $cap $plural at your Hunter level.")
            return null
        }
        return laid
    }

    private fun deadfallCount(coords: List<Int>): Int =
        coords.count { packed ->
            val controller = conRepo.findExact(CoordGrid(packed), TRAP_CONTROLLER)
            controller != null && controller.trapFamily == TrapFamily.DEADFALL.ordinal
        }

    private fun Controller.deadfallLogObj(): String? {
        val id = trapDeadfallLog
        return if (id == NO_TRAP_LOG) null else RSCM.getReverseMapping(RSCMType.OBJ, id)
    }

    // The visibility filter is load-bearing: despawn only *hides* a caught creature, so without
    // it one creature is caught by several traps at once (docs/hunter.md). [Npc.isVisible] and
    // deliberately not `isValidTarget()`, which requires `hitpoints > 0` - no creature declares any.
    private fun nearbyCreature(
        family: TrapFamily,
        centre: CoordGrid,
    ): Pair<Npc, HunterCreature>? =
        npcRepo
            .findAll(ZoneKey.from(centre), zoneRadius = 1)
            .filter { npc ->
                npc.isVisible &&
                    npc.coords.level == centre.level &&
                    npc.coords.chebyshevDistance(centre) <= family.triggerDistance
            }
            .mapNotNull { npc ->
                val creature = HunterCreatures.byNpcId(npc.visType.id)
                creature?.takeIf { it.family == family }?.let { npc to it }
            }
            .firstOrNull()

    private fun canTakeTrap(coords: CoordGrid): Boolean =
        conRepo.findExact(coords, TRAP_CONTROLLER) == null &&
            locRepo.findExact(coords, LocShape.CentrepieceStraight) == null &&
            locRepo.findExact(coords, LocShape.CentrepieceDiagonal) == null

    private fun findTrapLoc(family: TrapFamily, coords: CoordGrid): LocInfo? =
        when (family) {
            TrapFamily.SNARE,
            TrapFamily.BOX -> locRepo.findExact(coords, LocShape.CentrepieceStraight)
            TrapFamily.DEADFALL ->
                locRepo.findAll(coords).firstOrNull { it.id in HunterTrapStates.deadfallLocIds }
        }

    private fun advanceTrapLoc(family: TrapFamily, coords: CoordGrid, internal: String) {
        when (family) {
            TrapFamily.SNARE,
            TrapFamily.BOX -> spawnTrapLoc(coords, internal)
            TrapFamily.DEADFALL -> changeDeadfallLoc(coords, internal)
        }
    }

    private fun endTrapLoc(family: TrapFamily, coords: CoordGrid) {
        when (family) {
            TrapFamily.SNARE,
            TrapFamily.BOX -> clearTrapLoc(coords)
            TrapFamily.DEADFALL -> changeDeadfallLoc(coords, HunterTrapStates.DEADFALL_BOULDER)
        }
    }

    /**
     * The one and only way a boulder changes state: `locRepo.change`, never `del` - a permanent
     * map loc deleted with an infinite duration never respawns (docs/hunter.md). A finite
     * [duration] reverts to the map loc underneath, which is the setting frame's safety net.
     */
    private fun changeDeadfallLoc(
        coords: CoordGrid,
        internal: String,
        duration: Int = Int.MAX_VALUE,
    ) {
        val current = findTrapLoc(TrapFamily.DEADFALL, coords) ?: return
        val into =
            ServerCacheManager.getObject(internal.asRSCM(RSCMType.LOC))
                ?: error("Missing deadfall loc type: $internal")
        locRepo.change(current, into, duration)
    }

    private fun spawnTrapLoc(coords: CoordGrid, internal: String, duration: Int = Int.MAX_VALUE) {
        locRepo.add(coords, internal, duration, LocAngle.West, LocShape.CentrepieceStraight)
    }

    private fun clearTrapLoc(coords: CoordGrid) {
        val loc = locRepo.findExact(coords, LocShape.CentrepieceStraight) ?: return
        // A permanent delete of a map loc is never respawned; see [changeDeadfallLoc].
        check(loc.id !in HunterTrapStates.deadfallLocIds) {
            "Refusing to delete hunter loc ${loc.id} at $coords: it is a permanent map loc."
        }
        locRepo.del(loc, Int.MAX_VALUE)
    }

    private companion object {
        private const val KNIFE: String = "obj.knife"
        private const val FLETCHING_KNIFE: String = "obj.fletching_knife"

        // Read off the packed firemaking logs table so "any type of log" stays true on its own;
        // a log added to firemaking becomes deadfall fuel with nothing to keep in sync.
        private val usableLogIds: Set<Int> by lazy {
            FiremakingLogsRow.all()
                .map { it.input }
                .filter { isUsableDeadfallLog(it.internalName) }
                .mapTo(HashSet()) { it.id }
        }

        // Still showing the setting frame, or already reverted by that frame's safety timer.
        private val SETTABLE_DEADFALL_LOCS: Set<Int> by lazy {
            setOf(
                HunterTrapStates.DEADFALL_BOULDER.asRSCM(RSCMType.LOC),
                HunterTrapStates.DEADFALL_SETTING.asRSCM(RSCMType.LOC),
            )
        }

        private fun trapObj(family: TrapFamily): String? =
            when (family) {
                TrapFamily.SNARE -> "obj.hunting_ojibway_bird_snare"
                TrapFamily.BOX -> "obj.hunting_box_trap"
                TrapFamily.DEADFALL -> null
            }

        // What a successful collect hands back alongside the catch.
        private fun trapComponents(family: TrapFamily): List<String> =
            when (family) {
                TrapFamily.SNARE,
                TrapFamily.BOX -> listOfNotNull(trapObj(family))
                TrapFamily.DEADFALL -> emptyList()
            }
    }
}
