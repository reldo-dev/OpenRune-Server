package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import kotlin.math.abs
import org.rsmod.api.controller.vars.intVarCon
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.player.vars.intVarp
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.controller.ControllerRepository
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.utils.skills.SkillingSuccessRate
import org.rsmod.game.MapClock
import org.rsmod.game.entity.Controller
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.PlayerUid
import org.rsmod.game.inv.Inventory
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocShape
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey

/** The controller type every laid trap of either family is anchored to. */
const val TRAP_CONTROLLER: String = "controller.hunter_trap"

/**
 * The trap's lifetime in map cycles, i.e. how long an untouched trap has before it collapses.
 *
 * 100 cycles is ~1 minute at 0.6s/cycle, seeded from RuneLite's client-side `HunterTrap.TRAP_TIME`
 * overlay figure. That is not server truth - it is a starting value to confirm in-game.
 */
const val TRAP_LIFETIME_CYCLES: Int = 100

/**
 * How long the `_trapping_` / `_failing_` loc is shown before it settles into `_full_` /
 * `_failed_`.
 *
 * The design records that live's real duration for these intermediate states is not answerable
 * offline; a fixed short step is the honest model, and it is the only reason both states exist as
 * separate locs at all.
 */
const val TRAP_SPRING_CYCLES: Int = 2

/**
 * How long a collapsed trap is left on the ground after its controller is gone. Finite so the loc
 * cleans itself up rather than being stranded by an owner who never comes back for it.
 */
const val TRAP_COLLAPSE_LINGER_CYCLES: Int = 100

/** Only a creature this close to the trap tile can spring it. */
const val TRAP_TRIGGER_DISTANCE: Int = 1

/** The most traps any player can have laid, reached at level 80. */
const val MAX_LAID_TRAPS: Int = 5

private const val MAX_HUNTER_LEVEL: Int = 99

/** [Controller.trapCreature] while the trap is still armed and has caught nothing. */
private const val CREATURE_NONE: Int = -1

/** [Controller.trapCreature] once the trap has sprung and failed. */
private const val CREATURE_FAILED: Int = -2

/**
 * The value an unwritten `varp.hunter_trap_coord_*` reads back as. `CoordGrid.ZERO` is off-map, so
 * it can never collide with a real trap tile.
 */
private const val EMPTY_TRAP_COORD: Int = 0

/** Packed [PlayerUid] of whoever laid the trap. */
var Controller.trapOwner: Int by intVarCon("varcon.hunter_trap_owner")

/** [TrapFamily] ordinal. */
var Controller.trapFamily: Int by intVarCon("varcon.hunter_trap_family")

/**
 * Index into [HunterCreatures.all] once the trap has caught something, or [CREATURE_NONE] /
 * [CREATURE_FAILED]. Together with [trapFamily] this is the whole of a trap's state.
 */
var Controller.trapCreature: Int by intVarCon("varcon.hunter_trap_creature")

private var Player.trapCoord1: Int by intVarp("varp.hunter_trap_coord_1")
private var Player.trapCoord2: Int by intVarp("varp.hunter_trap_coord_2")
private var Player.trapCoord3: Int by intVarp("varp.hunter_trap_coord_3")
private var Player.trapCoord4: Int by intVarp("varp.hunter_trap_coord_4")
private var Player.trapCoord5: Int by intVarp("varp.hunter_trap_coord_5")

/**
 * The packed coords of every trap this player believes it has laid, at most [MAX_LAID_TRAPS].
 *
 * Coords, not a counter. Controllers and timed locs are runtime-only, so a counter leaks: a trap
 * that collapses while the player is away, or a server restart, never runs the decrement and the
 * player permanently loses a slot. A coord can be re-checked against the world, which makes the
 * leak structurally impossible instead of something four code paths have to remember.
 */
var Player.hunterTrapCoords: List<Int>
    get() =
        listOf(trapCoord1, trapCoord2, trapCoord3, trapCoord4, trapCoord5).filter {
            it != EMPTY_TRAP_COORD
        }
    set(value) {
        require(value.size <= MAX_LAID_TRAPS) {
            "Cannot store more than $MAX_LAID_TRAPS trap coords: $value"
        }
        trapCoord1 = value.getOrElse(0) { EMPTY_TRAP_COORD }
        trapCoord2 = value.getOrElse(1) { EMPTY_TRAP_COORD }
        trapCoord3 = value.getOrElse(2) { EMPTY_TRAP_COORD }
        trapCoord4 = value.getOrElse(3) { EMPTY_TRAP_COORD }
        trapCoord5 = value.getOrElse(4) { EMPTY_TRAP_COORD }
    }

/**
 * Lay, advance, collect and collapse for both trap families.
 *
 * A laid trap is a [Controller] anchored at its tile, exactly as woodcutting models a felled tree.
 * The tile is the key for everything: the controller, the loc chain and the cap all resolve from
 * it, so there is no separate bookkeeping map to keep in sync.
 *
 * The player-facing ops are not registered here - they belong to the per-family scripts, which also
 * register `onAiConTimer(TRAP_CONTROLLER)` exactly once, since it is family-agnostic.
 */
class HunterTrap
@Inject
constructor(
    private val locRepo: LocRepository,
    private val conRepo: ControllerRepository,
    private val npcRepo: NpcRepository,
    private val playerList: PlayerList,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
    private val mapClock: MapClock,
) {
    /**
     * Spawns the set-state loc and its controller at [coords], consuming one trap item.
     *
     * @return false, with a message already sent, if the player is at their cap or the tile cannot
     *   take a trap.
     */
    fun ProtectedAccess.layTrap(family: TrapFamily, coords: CoordGrid): Boolean {
        // Sweep before the cap check: a trap that died while the player was elsewhere must not
        // still be occupying a slot.
        val laid = player.sweepTrapCoords()
        val cap = trapCap(player.hunterLvl)
        if (laid.size >= cap) {
            val plural = if (cap == 1) "trap" else "traps"
            mes("You can only lay $cap $plural at your Hunter level.")
            return false
        }

        if (!canTakeTrap(coords)) {
            mes("You can't set a trap here.")
            return false
        }

        val trapObj = trapObj(family)
        if (invDel(inv, trapObj, 1).failure) {
            val name = ServerCacheManager.getItem(trapObj.asRSCM(RSCMType.OBJ))?.name?.lowercase()
            mes("You don't have a ${name ?: "trap"} to lay.")
            return false
        }

        spawnTrapLoc(coords, HunterTrapStates.setLoc(family))

        val spawn = Controller(TRAP_CONTROLLER, coords)
        conRepo.add(spawn, TRAP_LIFETIME_CYCLES)
        spawn.trapOwner = player.uid.packed
        spawn.trapFamily = family.ordinal
        spawn.trapCreature = CREATURE_NONE
        spawn.aiTimer(1)

        player.hunterTrapCoords = laid + coords.packed
        return true
    }

    /**
     * One cycle of a laid trap. Re-arms itself every tick, and deliberately does **not** call
     * [Controller.resetDuration] while nothing is near, so an unattended trap decays toward
     * collapse rather than sitting armed forever.
     */
    fun Controller.hunterTrapTick() {
        val family = TrapFamily.entries.getOrNull(trapFamily)
        if (family == null) {
            // Defensive: the varcon defaults to 0 (SNARE), so this should be unreachable, but a
            // corrupt ordinal must not strand a controller-less loc on the tile forever.
            clearTrapLoc(coords)
            conRepo.del(this)
            return
        }

        val loc = locRepo.findExact(coords, LocShape.CentrepieceStraight)
        if (loc == null) {
            // Make sure the controller lived beyond a single tick; otherwise something is
            // recreating traps faster than the loc can be registered.
            check(mapClock > creationCycle + 1) { "Hunter trap loc deleted faster than expected." }
            conRepo.del(this)
            return
        }

        // Traps belong to a logged-in owner: the roll needs their live Hunter level, and live
        // despawns a player's traps when they leave.
        val owner = PlayerUid(trapOwner).resolve(playerList)
        if (owner == null) {
            collapse(family)
            return
        }

        // `duration` is the trap's remaining lifetime. ControllerRepository deletes an expired
        // controller silently, which would strand the loc, so collapse one cycle early instead.
        if (duration <= 1) {
            collapse(family)
            return
        }

        if (trapCreature != CREATURE_NONE) {
            // Already sprung. Settle the intermediate loc into its terminal state (a no-op once
            // settled) and keep ticking so the collapse above can still reclaim an uncollected
            // trap.
            settle(family)
            aiTimer(1)
            return
        }

        val target = nearbyCreature(family)
        if (target == null) {
            aiTimer(1)
            return
        }

        val (npc, creature) = target

        // "If the player's Hunter level is too low, the trap will always fail." (wiki). A
        // negative `successLow` already reproduces this for black/carnivorous chinchompa, but
        // regular chinchompa's `successLow` is positive (+6), so without this gate a level-1
        // player would catch a level-53 creature at a small but non-zero rate. Short-circuits
        // before the roll, so an under-levelled attempt never consumes a random draw.
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
            spawnTrapLoc(coords, HunterTrapStates.trappingLoc(creature, approachFrom(npc)))
        } else {
            trapCreature = CREATURE_FAILED
            spawnTrapLoc(coords, HunterTrapStates.failingLoc(family))
        }

        // A sprung trap waits for its owner rather than continuing to decay from wherever its
        // lifetime happened to be when the creature arrived.
        resetDuration()
        aiTimer(TRAP_SPRING_CYCLES)
    }

    /**
     * Takes down a sprung trap: awards the catch, returns the trap item and clears the tile.
     *
     * @return false, with a message already sent where one is warranted, if the trap is not this
     *   player's or there is no room for what it holds.
     */
    fun ProtectedAccess.collectTrap(loc: BoundLocInfo): Boolean {
        val controller = conRepo.findExact(loc.coords, TRAP_CONTROLLER) ?: return false
        if (controller.trapOwner != player.uid.packed) {
            mes("This isn't your trap.")
            return false
        }

        val family = TrapFamily.entries.getOrNull(controller.trapFamily) ?: return false
        val creature = HunterCreatures.all.getOrNull(controller.trapCreature)
        val trapObj = trapObj(family)

        // The trap item comes back alongside everything it caught. A stackable award only costs
        // a slot when the player isn't already carrying it - counting it unconditionally over-
        // rejects a legitimate collect (e.g. a chinchompa catch when the player already holds
        // that chinchompa type, or a feather catch when they already hold that feather colour).
        val caught = creature?.caught.orEmpty()
        val slotsNeeded = (caught + trapObj).count { needsInvSlot(inv, it) }
        if (inv.freeSpace() < slotsNeeded) {
            mes("Your inventory is too full to hold any more.")
            soundSynth("synth.pillory_wrong")
            return false
        }

        for (obj in caught) {
            invAdd(inv, obj, 1)
        }
        invAdd(inv, trapObj, 1)

        if (creature != null) {
            // Creature xp is stored x10 so fractional values survive the table.
            val xp = (creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
            statAdvance("stat.hunter", xp)
        }

        clearTrapLoc(loc.coords)
        conRepo.del(controller)
        player.sweepTrapCoords()
        return true
    }

    /**
     * The whole of op1 on a trap loc: `Check` on a sprung trap, `Dismantle` on an armed or a
     * collapsed one. All three are the same transaction - clear the tile, hand back whatever is on
     * it - so they share one entry point rather than one per loc state.
     *
     * A collapsed trap outlives its controller by [TRAP_COLLAPSE_LINGER_CYCLES], so a *missing*
     * controller is an ordinary case here, not an error. There is then nobody left to check
     * ownership against, so whoever clears the tile keeps the trap item; the item was consumed once
     * on lay and the loc is deleted in the same call, so this cannot mint a second one.
     */
    fun ProtectedAccess.takeTrap(loc: BoundLocInfo, family: TrapFamily): Boolean {
        if (conRepo.findExact(loc.coords, TRAP_CONTROLLER) != null) {
            return collectTrap(loc)
        }

        val trapObj = trapObj(family)
        if (needsInvSlot(inv, trapObj) && inv.freeSpace() < 1) {
            mes("Your inventory is too full to hold any more.")
            soundSynth("synth.pillory_wrong")
            return false
        }

        invAdd(inv, trapObj, 1)
        clearTrapLoc(loc.coords)
        player.sweepTrapCoords()
        return true
    }

    /**
     * Drops every stored coord that no longer has a live trap of this player's on it, writes the
     * survivors back, and returns them.
     */
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

    /** Replaces the intermediate loc with the terminal one. No-op once it is already in place. */
    private fun Controller.settle(family: TrapFamily) {
        val settled =
            if (trapCreature == CREATURE_FAILED) {
                HunterTrapStates.failedLoc(family)
            } else {
                val creature = HunterCreatures.all.getOrNull(trapCreature) ?: return
                HunterTrapStates.fullLoc(creature)
            }
        val current = locRepo.findExact(coords, LocShape.CentrepieceStraight) ?: return
        if (current.id == settled.asRSCM(RSCMType.LOC)) {
            return
        }
        spawnTrapLoc(coords, settled)
    }

    /**
     * Ends the trap: leaves the collapsed state on the ground for a while so the owner can still
     * pick it up, then deletes the controller. The stored coord is reclaimed by the next sweep.
     */
    private fun Controller.collapse(family: TrapFamily) {
        spawnTrapLoc(coords, HunterTrapStates.failedLoc(family), TRAP_COLLAPSE_LINGER_CYCLES)
        conRepo.del(this)
    }

    private fun Controller.nearbyCreature(family: TrapFamily): Pair<Npc, HunterCreature>? =
        npcRepo
            .findAll(ZoneKey.from(coords), zoneRadius = 1)
            .filter { npc ->
                npc.coords.level == coords.level &&
                    npc.coords.chebyshevDistance(coords) <= TRAP_TRIGGER_DISTANCE
            }
            .mapNotNull { npc ->
                val internal = RSCM.getReverseMapping(RSCMType.NPC, npc.visType.id)
                val creature = internal?.let(HunterCreatures::byNpc)
                creature?.takeIf { it.family == family }?.let { npc to it }
            }
            .firstOrNull()

    /**
     * Which compass side of the trap the creature is standing on, as the `n`/`e`/`s`/`w` suffix the
     * box trap's `_trapping_` locs are keyed by. Ties and a same-tile creature fall to `n`. Snare
     * ignores it. Whether live picks the direction this way is unverified.
     */
    private fun Controller.approachFrom(npc: Npc): Char {
        val dx = npc.coords.x - coords.x
        val dz = npc.coords.z - coords.z
        return when {
            abs(dz) >= abs(dx) && dz >= 0 -> 'n'
            abs(dz) >= abs(dx) -> 's'
            dx >= 0 -> 'e'
            else -> 'w'
        }
    }

    /**
     * Whether awarding one more [internal] to [inv] costs a free slot. A stackable item already
     * present just grows its existing stack and needs none; everything else - including a stackable
     * item not yet held at all - needs exactly one.
     */
    private fun needsInvSlot(inv: Inventory, internal: String): Boolean {
        val stackable =
            ServerCacheManager.getItem(internal.asRSCM(RSCMType.OBJ))?.isStackable == true
        return !stackable || !inv.contains(internal)
    }

    private fun canTakeTrap(coords: CoordGrid): Boolean =
        conRepo.findExact(coords, TRAP_CONTROLLER) == null &&
            locRepo.findExact(coords, LocShape.CentrepieceStraight) == null &&
            locRepo.findExact(coords, LocShape.CentrepieceDiagonal) == null

    private fun spawnTrapLoc(coords: CoordGrid, internal: String, duration: Int = Int.MAX_VALUE) {
        locRepo.add(coords, internal, duration, LocAngle.West, LocShape.CentrepieceStraight)
    }

    private fun clearTrapLoc(coords: CoordGrid) {
        val loc = locRepo.findExact(coords, LocShape.CentrepieceStraight) ?: return
        locRepo.del(loc, Int.MAX_VALUE)
    }

    private companion object {
        /** Read from the effective level, so temporary boosts raise the cap. */
        private fun trapCap(level: Int): Int =
            when {
                level >= 80 -> 5
                level >= 60 -> 4
                level >= 40 -> 3
                level >= 20 -> 2
                else -> 1
            }

        private fun trapObj(family: TrapFamily): String =
            when (family) {
                TrapFamily.SNARE -> "obj.hunting_ojibway_bird_snare"
                TrapFamily.BOX -> "obj.hunting_box_trap"
            }
    }
}
