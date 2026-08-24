package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.controller.vars.intVarCon
import org.rsmod.api.player.isValidTarget
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.player.vars.intVarp
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
import org.rsmod.game.inv.Inventory
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.game.loc.LocAngle
import org.rsmod.game.loc.LocInfo
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

/**
 * How close a creature has to be to a box trap to be lured into it, in tiles.
 *
 * "Any ferret or chinchompa within a 2-tile radius of the box trap (forming a 5x5 square centred on
 * the trap) can be attracted." (wiki, *Box trap > Mechanics*).
 */
const val BOX_TRAP_TRIGGER_DISTANCE: Int = 2

/**
 * The bird snare's equivalent of [BOX_TRAP_TRIGGER_DISTANCE].
 *
 * Unsourced. Neither the *Bird snare* page nor *Hunter > Hunting techniques > Bird snaring* states
 * a radius - the latter says only that snares "have a chance to attract and catch birds as they fly
 * by" - and no cache record carries one, because the catch is entirely server-side. Adjacency is
 * the conservative reading and is what has been in place since the snare landed; do not promote it
 * to the box trap's 2 without a source.
 */
const val SNARE_TRIGGER_DISTANCE: Int = 1

/**
 * How often a laid box trap rolls for a catch, in cycles.
 *
 * "Once a box trap has been set, it will make an attempt every 3 ticks (1.8 seconds) to lure in an
 * animal that is currently in range." (wiki, *Box trap > Mechanics*). Rolling every cycle instead
 * would triple the effective catch rate at the same per-attempt chance.
 */
const val BOX_TRAP_ATTEMPT_CYCLES: Int = 3

/**
 * The bird snare's equivalent of [BOX_TRAP_ATTEMPT_CYCLES].
 *
 * Unsourced, like [SNARE_TRIGGER_DISTANCE]: the wiki gives the 3-tick cadence for the box trap
 * only. One attempt per cycle is what the snare has always done here, kept as the accepted
 * approximation rather than borrowed from the other family.
 */
const val SNARE_ATTEMPT_CYCLES: Int = 1

/**
 * The deadfall's equivalent of [BOX_TRAP_TRIGGER_DISTANCE].
 *
 * Unsourced, exactly like [SNARE_TRIGGER_DISTANCE]: the *Deadfall* page describes what the trap is
 * and what cannot set it, never a radius, and the catch is server-side so no cache record carries
 * one either. Adjacency is the conservative reading - the boulder falls on an animal that has
 * walked under it - and it should not be promoted to the box trap's 2 without a source.
 */
const val DEADFALL_TRIGGER_DISTANCE: Int = 1

/**
 * The deadfall's equivalent of [BOX_TRAP_ATTEMPT_CYCLES].
 *
 * Unsourced. The wiki gives the 3-tick cadence for the box trap only. Borrowing that number here
 * is a guess, not a derivation; it is used rather than the snare's every-cycle roll because a
 * deadfall is capped at one at a time and rolling three times as often would quietly triple the
 * rate of the family that is meant to be the slow one.
 */
const val DEADFALL_ATTEMPT_CYCLES: Int = 3

/**
 * How long the boulder shows `hunting_deadfall_setting` before it becomes an armed trap.
 *
 * Unsourced. No source states how long fitting the log takes, and the state exists in the cache
 * with no ops, so nothing outside the animation depends on the exact figure. Three cycles is a
 * placeholder chosen to roughly cover `seq.human_laytrap`.
 */
const val DEADFALL_SET_CYCLES: Int = 3

/**
 * The Hunter level a deadfall can first be set at.
 *
 * The wild kebbit's 23 - the lowest of the five deadfall creatures on the *Deadfall* page's own
 * Creatures table. The same shape of gate as the box trap's 27 in [BoxTrapEvents]: without it, the
 * whole deadfall table would be armable from level 1 and would start paying out the moment the
 * player's level caught up with a creature.
 */
const val DEADFALL_LEVEL_REQ: Int = 23

/**
 * "Unlike most hunter traps, only one deadfall trap can be set up at once, generally resulting in
 * slower experience rates." (wiki, *Deadfall*). This is on top of, not instead of, the shared
 * [trapCap][MAX_LAID_TRAPS] allowance.
 */
const val MAX_LAID_DEADFALLS: Int = 1

/**
 * The two logs a deadfall cannot be armed with.
 *
 * "Redwood logs and arctic pine logs cannot be used for deadfall traps." (wiki, *Deadfall*,
 * oldid=15201193). Note `obj.arctic_pine_log` is singular in the cache while every other log is
 * plural; the plural spelling resolves to nothing.
 */
private val DEADFALL_EXCLUDED_LOGS: Set<String> = setOf("obj.redwood_logs", "obj.arctic_pine_log")

/**
 * Whether a log can arm a deadfall.
 *
 * The domain is the packed firemaking logs table's inputs - "any type of log" is read off that
 * table rather than retyped as a list here, so a log added to firemaking is usable for deadfall on
 * the same day. This is only the exclusion half of that rule, kept pure and separate so the same
 * predicate the set-trap path applies to every packed row can be tested without a cache. It does
 * not itself assert that [objKey] is a log.
 */
fun isUsableDeadfallLog(objKey: String): Boolean = objKey !in DEADFALL_EXCLUDED_LOGS

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

/**
 * The value an unwritten `varcon.hunter_trap_deadfall_log` reads back as. No deadfall path ever
 * writes it: every usable log has a positive obj id, so zero unambiguously means "no log recorded",
 * which is the case for every trap of the other two families.
 */
private const val NO_TRAP_LOG: Int = 0

/** Packed [PlayerUid] of whoever laid the trap. */
var Controller.trapOwner: Int by intVarCon("varcon.hunter_trap_owner")

/** [TrapFamily] ordinal. */
var Controller.trapFamily: Int by intVarCon("varcon.hunter_trap_family")

/**
 * Index into [HunterCreatures.all] once the trap has caught something, or [CREATURE_NONE] /
 * [CREATURE_FAILED]. Together with [trapFamily] this is the whole of a trap's state.
 */
var Controller.trapCreature: Int by intVarCon("varcon.hunter_trap_creature")

/**
 * The obj id of the log a deadfall was armed with, or [NO_TRAP_LOG]. Recorded because dismantling
 * hands that exact log back, and "any type of log" means it is genuinely not derivable.
 */
var Controller.trapDeadfallLog: Int by intVarCon("varcon.hunter_trap_deadfall_log")

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
 * Lay, advance, collect and collapse for all three trap families.
 *
 * A laid trap is a [Controller] anchored at its tile, exactly as woodcutting models a felled tree.
 * The tile is the key for everything: the controller, the loc chain and the cap all resolve from
 * it, so there is no separate bookkeeping map to keep in sync.
 *
 * The player-facing ops are not registered here - they belong to the per-family scripts, which also
 * register `onAiConTimer(TRAP_CONTROLLER)` exactly once, since it is family-agnostic.
 *
 * **The deadfall is not a trap on a tile; it is a state machine on a boulder that was always
 * there.** Everything else here spawns a loc and deletes it again, and
 * [LocRepository.del] with an infinite duration is how a spawned loc is cleared. Running that
 * against a deadfall would delete a *map* loc, and `LocRepository` only schedules a respawn for a
 * delete with a finite duration (`LocRepository.kt:98-131`) - so the boulder would be gone from the
 * world until the next restart. Every deadfall transition therefore goes through
 * [changeDeadfallLoc], which is a `locRepo.change`, and [clearTrapLoc] carries a hard check that it
 * is never handed one.
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
    /**
     * Spawns the set-state loc and its controller at [coords], consuming one trap item.
     *
     * Portable families only: a deadfall is armed in place on its boulder by [setDeadfall].
     *
     * @return false, with a message already sent, if the player is at their cap or the tile cannot
     *   take a trap.
     */
    fun ProtectedAccess.layTrap(family: TrapFamily, coords: CoordGrid): Boolean {
        val laid = trapAllowance() ?: return false

        if (!canTakeTrap(coords)) {
            mes("You can't set a trap here.")
            return false
        }

        // Null only for the deadfall, which never reaches here: nothing is consumed to arm a
        // boulder and nothing is spawned on an empty tile.
        val trapObj = trapObj(family) ?: return false
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
     * Arms the boulder [loc] with one log and its controller.
     *
     * "Clicking a boulder with a knife or fletching knife and any type of log in the inventory (or a
     * banana when hunting maniacal monkeys) will set the trap." (wiki, *Deadfall*). The knife is
     * only a tool and is kept; the log is consumed and its obj id recorded, because dismantling
     * hands that exact log back. Maniacal monkeys and their bananas are out of scope - the whole
     * chain is quest-gated on Monkey Madness II, which is not modelled here.
     *
     * @return false, with a message already sent, if the player cannot set a deadfall here.
     */
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

        // First usable log in the inventory. Which one live picks when several are held is not
        // stated anywhere; slot order is ours.
        val log = inv.firstOrNull { it != null && it.id in usableLogIds }
        if (log == null) {
            mes("You need some logs to set up a deadfall trap.")
            return false
        }

        val logObj = RSCM.getReverseMapping(RSCMType.OBJ, log.id)
        if (invDel(inv, logObj, 1).failure) {
            return false
        }

        anim("seq.human_laytrap")

        // Timed, and this is the only deadfall change that is: it reverts the boulder on its own if
        // the delay below never resumes - a logout mid-set would otherwise strand a *permanent map
        // loc* wearing an op-less setting state, unusable until the next restart. The extra cycle
        // keeps the safety net from racing the normal path, which cancels it by changing the loc
        // again one cycle earlier.
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

        changeDeadfallLoc(loc.coords, HunterTrapStates.DEADFALL_ARMED)

        val spawn = Controller(TRAP_CONTROLLER, loc.coords)
        conRepo.add(spawn, TRAP_LIFETIME_CYCLES)
        spawn.trapOwner = player.uid.packed
        spawn.trapFamily = TrapFamily.DEADFALL.ordinal
        spawn.trapCreature = CREATURE_NONE
        spawn.trapDeadfallLog = log.id
        spawn.aiTimer(1)

        // Re-swept rather than reusing `laid`: three cycles is long enough for one of this player's
        // other traps to have collapsed.
        player.hunterTrapCoords = player.sweepTrapCoords() + loc.coords.packed
        return true
    }

    /**
     * Takes an armed deadfall back apart: hands the log back and returns the boulder to its unset
     * state.
     *
     * That the log comes back is **unsourced**. No source says what dismantling an armed deadfall
     * gives you; the two portable families hand their trap item back, and the log is the deadfall's
     * only equivalent, so it is returned rather than destroyed. The alternative - silently
     * consuming it - would make an accidental Dismantle cost the player an item with no notice.
     *
     * A deadfall with no controller is not an error: it is a boulder someone armed and something
     * already tore down. There is nothing to hand back in that case, and the state change is a
     * no-op.
     */
    fun ProtectedAccess.dismantleDeadfall(loc: BoundLocInfo): Boolean {
        val controller = conRepo.findExact(loc.coords, TRAP_CONTROLLER)
        if (controller != null && controller.trapOwner != player.uid.packed) {
            mes("This isn't your trap.")
            return false
        }

        val log = controller?.deadfallLogObj()
        if (log != null) {
            if (inv.freeSpace() < invSlotsNeeded(inv, log, 1)) {
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

        val loc = findTrapLoc(family, coords)
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
            collapse(family, owner = null)
            return
        }

        // `duration` is the trap's remaining lifetime. ControllerRepository deletes an expired
        // controller silently, which would strand the loc, so collapse one cycle early instead.
        if (duration <= 1) {
            collapse(family, owner)
            return
        }

        if (trapCreature != CREATURE_NONE) {
            // Already sprung. Settle the intermediate loc into its terminal state (a no-op once
            // settled) and keep ticking so the collapse above can still reclaim an uncollected
            // trap. A settle that ends the trap outright - the deadfall's failed catch - deletes
            // the controller, so there is nothing left to tick.
            if (settle(family)) {
                aiTimer(1)
            }
            return
        }

        // Re-armed every cycle whatever the family's attempt cadence is. This tick is also what
        // notices the expiring lifetime above, and a controller whose duration runs out between two
        // ticks is deleted by ControllerRepository without anything clearing its loc.
        aiTimer(1)

        // "Once a box trap has been set, it will make an attempt every 3 ticks (1.8 seconds) to
        // lure in an animal that is currently in range." (wiki). Phased on the trap's own creation
        // cycle rather than the raw map clock, so traps laid on different cycles do not all roll
        // in lockstep.
        if ((mapClock.cycle - creationCycle) % attemptCycles(family) != 0) {
            return
        }

        // "A bird snare will not catch birds if the user is standing directly on the bird snare."
        // (wiki, Bird snare) / "Box traps won't trap prey if players are standing on the trap
        // itself." (wiki, Box trap > Mechanics). Any player, not just the owner: the box trap's
        // wording is the plural, general one, and the same section describes the lure as reusing
        // NPC aggression, which no creature resolves onto an occupied tile. Only the roll is
        // blocked - the trap still ages toward collapse, otherwise standing on one would hold it
        // open indefinitely.
        //
        // `isValidTarget()` is required here, not `.any()` alone: `PlayerRegistry.findAll`'s own
        // KDoc warns it does not filter out hidden players or those mid-logout, and an invisible or
        // logging-out player parked on the tile would otherwise suppress every catch silently, with
        // no message and nothing observable to diagnose it by.
        //
        // Known consequence, accepted rather than fixed: a second, visible player can stand here to
        // camp someone else's trap, suppressing every roll while the lifetime keeps decaying toward
        // a forced collapse. Every trap loc is blockwalk=no (confirmed in cache), so nothing stops
        // them physically standing on it. The trap item still comes back via the wreck on collapse,
        // so this only costs time, and it matches live's plural "players" wording - this is a known
        // griefing/denial-of-service vector, not an oversight.
        //
        // The deadfall is explicitly exempt: "Deadfall traps are not prone to failure by standing
        // where they are set." (wiki, Deadfall). It is the one family the wiki says so of, and the
        // one whose trap is a boulder rather than something underfoot.
        if (family.portable && playerRepo.findAll(coords).any { it.isValidTarget() }) {
            return
        }

        val target = nearbyCreature(family) ?: return

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
            val dx = npc.coords.x - coords.x
            val dz = npc.coords.z - coords.z
            setTrapLoc(family, coords, HunterTrapStates.trappingLoc(creature, dx, dz))
        } else {
            trapCreature = CREATURE_FAILED
            setTrapLoc(family, coords, HunterTrapStates.failingLoc(family))
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

        // Rolled once, up front: the space check below and the awards further down have to agree
        // on the same numbers, and a second roll would let a collect be accepted for five feathers
        // and then hand out ten.
        val awards = creature?.caught.orEmpty().map { it.obj to rollQuantity(it.quantity) }

        // The trap item comes back alongside everything it caught - for the two families that have
        // one. A deadfall contributes nothing here: its boulder stays where it is and the log it
        // was armed with went with the catch, so the list is simply empty rather than a branch.
        val returned = listOfNotNull(trapObj(family))

        // A stackable award only costs a slot when the player isn't already carrying it - counting
        // it unconditionally over-rejects a legitimate collect (e.g. a chinchompa catch when the
        // player already holds that chinchompa type, or a feather catch when they already hold that
        // feather colour).
        val slotsNeeded =
            awards.sumOf { (obj, count) -> invSlotsNeeded(inv, obj, count) } +
                returned.sumOf { invSlotsNeeded(inv, it, 1) }
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
            // Creature xp is stored x10 so fractional values survive the table.
            val xp = (creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
            statAdvance("stat.hunter", xp)
        }

        endTrapLoc(family, loc.coords)
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
     *
     * Portable families only. The deadfall's op1 is routed by state instead - [setDeadfall],
     * [dismantleDeadfall] or [collectTrap] - because its three states are three different
     * transactions on a loc that must never be deleted.
     */
    fun ProtectedAccess.takeTrap(loc: BoundLocInfo, family: TrapFamily): Boolean {
        if (conRepo.findExact(loc.coords, TRAP_CONTROLLER) != null) {
            return collectTrap(loc)
        }

        val trapObj = trapObj(family) ?: return false
        if (inv.freeSpace() < invSlotsNeeded(inv, trapObj, 1)) {
            mes("Your inventory is too full to hold any more.")
            soundSynth("synth.pillory_wrong")
            return false
        }

        invAdd(inv, trapObj, 1)
        clearTrapLoc(loc.coords)
        player.sweepTrapCoords()
        return true
    }

    /** A fixed quantity costs no random draw; only a real range consumes one. */
    private fun rollQuantity(quantity: IntRange): Int =
        if (quantity.first == quantity.last) quantity.first else random.of(quantity)

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

    /**
     * Replaces the intermediate loc with the terminal one. No-op once it is already in place.
     *
     * @return false if the trap is finished and its controller deleted, i.e. there is nothing left
     *   to tick.
     */
    private fun Controller.settle(family: TrapFamily): Boolean {
        // A deadfall that sprang and caught nothing has no collectible failed state - the cache has
        // no `hunting_deadfall_failed` at all. The boulder goes straight back to unset and the log
        // is lost, so the controller has no reason to outlive the spring.
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
        setTrapLoc(family, coords, settled)
        return true
    }

    /**
     * Ends the trap and deletes its controller; the stored coord is reclaimed by the next sweep.
     *
     * A portable trap leaves its collapsed state on the ground for a while, so the owner can still
     * come back for the trap item.
     *
     * A deadfall leaves nothing behind - the boulder is simply unset again - so its owner is told,
     * where a portable trap's wreck on the ground says it for itself. [owner] is null when the
     * collapse *is* the owner logging out, and there is nobody to tell.
     */
    private fun Controller.collapse(family: TrapFamily, owner: Player?) {
        if (family == TrapFamily.DEADFALL) {
            changeDeadfallLoc(coords, HunterTrapStates.failedLoc(family))
            // Live's own wording for this is not recoverable offline: the text is server-sent, so
            // it is in neither the cache nor the wiki. This string is ours.
            owner?.mes("Your deadfall trap has collapsed.", ChatType.GameMessage)
            conRepo.del(this)
            return
        }
        spawnTrapLoc(coords, HunterTrapStates.failedLoc(family), TRAP_COLLAPSE_LINGER_CYCLES)
        conRepo.del(this)
    }

    /** @return the live trap coords, or null with a message sent if the player is at their cap. */
    private fun ProtectedAccess.trapAllowance(): List<Int>? {
        // Sweep before the cap check: a trap that died while the player was elsewhere must not
        // still be occupying a slot.
        val laid = player.sweepTrapCoords()
        val cap = trapCap(player.hunterLvl)
        if (laid.size >= cap) {
            val plural = if (cap == 1) "trap" else "traps"
            mes("You can only lay $cap $plural at your Hunter level.")
            return null
        }
        return laid
    }

    /** How many of the packed [coords] still hold a live deadfall controller. */
    private fun deadfallCount(coords: List<Int>): Int =
        coords.count { packed ->
            val controller = conRepo.findExact(CoordGrid(packed), TRAP_CONTROLLER)
            controller != null && controller.trapFamily == TrapFamily.DEADFALL.ordinal
        }

    /** The log a deadfall was armed with, or null for a trap that never recorded one. */
    private fun Controller.deadfallLogObj(): String? {
        val id = trapDeadfallLog
        return if (id == NO_TRAP_LOG) null else RSCM.getReverseMapping(RSCMType.OBJ, id)
    }

    private fun Controller.nearbyCreature(family: TrapFamily): Pair<Npc, HunterCreature>? =
        npcRepo
            .findAll(ZoneKey.from(coords), zoneRadius = 1)
            .filter { npc ->
                npc.coords.level == coords.level &&
                    npc.coords.chebyshevDistance(coords) <= triggerDistance(family)
            }
            .mapNotNull { npc ->
                val internal = RSCM.getReverseMapping(RSCMType.NPC, npc.visType.id)
                val creature = internal?.let(HunterCreatures::byNpc)
                creature?.takeIf { it.family == family }?.let { npc to it }
            }
            .firstOrNull()

    /**
     * How many free slots awarding [count] of [internal] to [inv] costs. A stackable item already
     * present just grows its existing stack and needs none whatever the count; a stackable item not
     * yet held at all needs exactly one; anything else needs one per item.
     */
    private fun invSlotsNeeded(inv: Inventory, internal: String, count: Int): Int {
        val stackable =
            ServerCacheManager.getItem(internal.asRSCM(RSCMType.OBJ))?.isStackable == true
        return when {
            !stackable -> count
            inv.contains(internal) -> 0
            else -> 1
        }
    }

    private fun canTakeTrap(coords: CoordGrid): Boolean =
        conRepo.findExact(coords, TRAP_CONTROLLER) == null &&
            locRepo.findExact(coords, LocShape.CentrepieceStraight) == null &&
            locRepo.findExact(coords, LocShape.CentrepieceDiagonal) == null

    /**
     * Finds the trap on [coords].
     *
     * A portable trap owns its tile's centrepiece layer outright, so its shape is enough to find
     * it. A deadfall does not: it is whichever of nineteen boulder states the tile is currently
     * wearing, at whatever shape and angle the map gave it, so it is found by id instead.
     */
    private fun findTrapLoc(family: TrapFamily, coords: CoordGrid): LocInfo? =
        if (family.portable) {
            locRepo.findExact(coords, LocShape.CentrepieceStraight)
        } else {
            locRepo.findAll(coords).firstOrNull { it.id in HunterTrapStates.deadfallLocIds }
        }

    /** Moves a trap into its next state, whichever way its family is allowed to change locs. */
    private fun setTrapLoc(family: TrapFamily, coords: CoordGrid, internal: String) {
        if (family.portable) {
            spawnTrapLoc(coords, internal)
        } else {
            changeDeadfallLoc(coords, internal)
        }
    }

    /** Clears a trap off its tile for good: the portable wreck goes, the boulder comes back. */
    private fun endTrapLoc(family: TrapFamily, coords: CoordGrid) {
        if (family.portable) {
            clearTrapLoc(coords)
        } else {
            changeDeadfallLoc(coords, HunterTrapStates.DEADFALL_BOULDER)
        }
    }

    /**
     * The one and only way a deadfall boulder ever changes state.
     *
     * `locRepo.change`, never `locRepo.del`. The boulder is a permanent map loc: `LocRepository`
     * only schedules a respawn for a delete with a *finite* duration (`LocRepository.kt:98-131`),
     * so deleting one the way [clearTrapLoc] clears a portable trap's tile would take that boulder
     * spot out of the world until the next restart. `change` also carries the map loc's own angle
     * and shape across, which matters twice over: the boulder faces a fixed way, and it is the
     * exact id/shape/angle triple that lets the registry recognise the tile as holding its map loc
     * again rather than a spawned copy of it.
     *
     * [duration] is a duration on the *change*, not on a delete: a finite one reverts the boulder
     * to the map loc underneath, which is why the setting state can use it as a safety net.
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
        // A deadfall boulder must never reach here: this delete is permanent, and a permanent
        // delete of a map loc is never respawned. See [changeDeadfallLoc].
        check(loc.id !in HunterTrapStates.deadfallLocIds) {
            "Refusing to delete deadfall loc ${loc.id} at $coords: it is a permanent map loc."
        }
        locRepo.del(loc, Int.MAX_VALUE)
    }

    private companion object {
        private const val KNIFE: String = "obj.knife"
        private const val FLETCHING_KNIFE: String = "obj.fletching_knife"

        /**
         * Every log the deadfall accepts, read off the packed firemaking logs table and filtered by
         * [isUsableDeadfallLog].
         *
         * Sourced from that table rather than a list written out here so that "any type of log"
         * stays true of the logs this server actually has: a log added to firemaking becomes
         * deadfall fuel on the same day, with nothing to keep in sync.
         */
        private val usableLogIds: Set<Int> by lazy {
            FiremakingLogsRow.all()
                .map { it.input }
                .filter { isUsableDeadfallLog(it.internalName) }
                .mapTo(HashSet()) { it.id }
        }

        /**
         * The two states a boulder may be in when a set-trap finishes: still showing the setting
         * frame, or already reverted to the plain boulder by that frame's safety timer.
         */
        private val SETTABLE_DEADFALL_LOCS: Set<Int> by lazy {
            setOf(
                HunterTrapStates.DEADFALL_BOULDER.asRSCM(RSCMType.LOC),
                HunterTrapStates.DEADFALL_SETTING.asRSCM(RSCMType.LOC),
            )
        }

        /** Read from the effective level, so temporary boosts raise the cap. */
        private fun trapCap(level: Int): Int =
            when {
                level >= 80 -> 5
                level >= 60 -> 4
                level >= 40 -> 3
                level >= 20 -> 2
                else -> 1
            }

        /**
         * The inventory item a portable trap is laid from and comes back as.
         *
         * Null for the deadfall, which has none: the boulder is a permanent map loc, armed in place
         * with a log and never carried. A placeholder obj here to satisfy the `when` would mint
         * bird snares out of boulders on every collect.
         */
        private fun trapObj(family: TrapFamily): String? =
            when (family) {
                TrapFamily.SNARE -> "obj.hunting_ojibway_bird_snare"
                TrapFamily.BOX -> "obj.hunting_box_trap"
                TrapFamily.DEADFALL -> null
            }

        /** Per-family, because only the box trap's radius is sourced. */
        private fun triggerDistance(family: TrapFamily): Int =
            when (family) {
                TrapFamily.SNARE -> SNARE_TRIGGER_DISTANCE
                TrapFamily.BOX -> BOX_TRAP_TRIGGER_DISTANCE
                TrapFamily.DEADFALL -> DEADFALL_TRIGGER_DISTANCE
            }

        /** Per-family, because only the box trap's cadence is sourced. */
        private fun attemptCycles(family: TrapFamily): Int =
            when (family) {
                TrapFamily.SNARE -> SNARE_ATTEMPT_CYCLES
                TrapFamily.BOX -> BOX_TRAP_ATTEMPT_CYCLES
                TrapFamily.DEADFALL -> DEADFALL_ATTEMPT_CYCLES
            }
    }
}
