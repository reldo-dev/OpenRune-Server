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
import org.rsmod.api.repo.obj.ObjRepository
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
 * Lay, advance, collect and collapse for all five trap families.
 *
 * A laid trap is a [Controller] anchored at its tile, exactly as woodcutting models a felled tree.
 * The tile is the key for everything: the controller, the loc chain and the cap all resolve from
 * it, so there is no separate bookkeeping map to keep in sync.
 *
 * The player-facing ops are not registered here - they belong to the per-family scripts, which also
 * register `onAiConTimer(TRAP_CONTROLLER)` exactly once, since it is family-agnostic.
 *
 * **Two of the five families are not traps on a tile; they are state machines on locs that were
 * always there.** The three portable families spawn a loc and delete it again, and
 * [LocRepository.del] with an infinite duration is how a spawned loc is cleared. Running that
 * against a deadfall boulder or a net trap's young tree would delete a *map* loc, and
 * `LocRepository` only schedules a respawn for a delete with a finite duration
 * (`LocRepository.kt:98-131`) - so that boulder or tree would be gone from the world until the next
 * restart. Every transition of either therefore goes through [changeDeadfallLoc] or
 * [changeNetTrapTreeLoc], both of which are a `locRepo.change`, and [clearTrapLoc] carries a hard
 * check that it is never handed one of their loc ids.
 *
 * **The net trap is the one family that owns two tiles.** Its controller is anchored at the young
 * tree; its net is a spawned loc on [netTrapCoords] of that tree, carrying the tree's own angle so
 * that an op landing on the net can walk back ([netTrapAnchor]). The tree is change-only; the net
 * is an ordinary spawn and is deleted like any other.
 */
class HunterTrap
@Inject
constructor(
    private val locRepo: LocRepository,
    private val conRepo: ControllerRepository,
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val playerRepo: PlayerRepository,
    private val playerList: PlayerList,
    private val random: GameRandom,
    private val xpMods: XpModifiers,
    private val mapClock: MapClock,
) {
    /**
     * Spawns the set-state loc and its controller at [coords], consuming one trap item.
     *
     * Portable families only: a deadfall is armed in place on its boulder by [setDeadfall] and a
     * net trap on its young tree by [setNetTrap].
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

        // Both null only for the two fixed-loc families, neither of which reaches here: nothing is
        // consumed to arm a boulder or a tree, and nothing is spawned on an empty tile.
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
        // stated anywhere; slot order is ours. Resolved to an obj *here* rather than after the
        // delay so that the log type this check accepted is the log type the player is charged,
        // even if they rearrange or add to their inventory while the set plays out.
        val log = inv.firstOrNull { it != null && it.id in usableLogIds }
        if (log == null) {
            mes("You need some logs to set up a deadfall trap.")
            return false
        }
        val logId = log.id
        val logObj = RSCM.getReverseMapping(RSCMType.OBJ, logId)

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

        // The log is consumed *here*, past the last path that can still refuse, and not before the
        // delay. Charging it up front lost the player a log for nothing twice over: on the
        // `stillOurs` failure above - two players can both start on the same boulder, because
        // mid-set there is only an op-less `SETTING` loc and no controller for the pre-check at the
        // top to see - and on a logout during the delay, where the timed change reverts the boulder
        // but this coroutine never resumes to arm it. Nothing between the two positions needs the
        // log to be gone. It is also what [dismantleDeadfall] already argues for in the other
        // direction: an aborted set must not cost an item with no notice.
        //
        // Re-checked rather than assumed held: the set delay is long enough to bank, drop or trade
        // the log away, and that earns the same refusal the pre-check gives rather than a boulder
        // armed for free. The boulder needs no cleanup on this path - the timed change above is
        // still pending and reverts it.
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

    /**
     * Strings a net between the young tree [loc] and the tile its angle points at, and anchors a
     * controller on the tree.
     *
     * "With the required Hunter level, a rope and small fishing net in the inventory, clicking on a
     * young tree will set the trap." (wiki, *Net trap*.) The level is the creature's own, read off
     * the tree the player clicked rather than a family-wide constant - every young tree belongs to
     * exactly one salamander, so the tree already says which requirement applies.
     *
     * The tile the net wants may be occupied, and if it is **the set is refused outright** rather
     * than shuffled to another neighbour. A net trap is two locs that have to find each other, and
     * the only thing recording where the net went is the tree's own angle ([netTrapCoords]); a
     * fallback tile would need state nothing else in the feature keeps, and half a trap - a tree
     * that looks armed with no net beside it - would consume the rope and net for something that
     * can never spring. Refusing costs the player a walk to the next tree and nothing else.
     *
     * @return false, with a message already sent, if the player cannot set a net trap here.
     */
    suspend fun ProtectedAccess.setNetTrap(loc: BoundLocInfo): Boolean {
        val creature = HunterCreatures.byNetTrapLoc(loc.id) ?: return false
        if (loc.id !in HunterTrapStates.netTrapUpLocIds) {
            return false
        }

        if (player.hunterLvl < creature.level) {
            mes("You need a Hunter level of ${creature.level} to set a trap on this tree.")
            return false
        }

        if (conRepo.findExact(loc.coords, TRAP_CONTROLLER) != null) {
            mes("This trap is already set.")
            return false
        }

        val netCoords = netTrapCoords(loc.coords, loc.angle)
        if (!canTakeTrap(netCoords)) {
            mes("There isn't enough room to set a net trap here.")
            return false
        }

        val laid = trapAllowance() ?: return false

        if (!inv.contains(ROPE) || !inv.contains(SMALL_FISHING_NET)) {
            mes("You need a rope and a small fishing net to set a net trap.")
            return false
        }

        anim("seq.human_laytrap")

        // Timed, for the reason [setDeadfall]'s setting state is: it reverts the tree on its own if
        // the delay below never resumes, so a logout mid-set cannot strand a *permanent map loc*
        // wearing an op-less setting state. The extra cycle keeps the safety net from racing the
        // normal path.
        changeNetTrapTreeLoc(
            loc.coords,
            HunterTrapStates.settingLoc(creature),
            NET_TRAP_SET_CYCLES + 1,
        )
        delay(NET_TRAP_SET_CYCLES)

        // Anything but the setting state (or the plain tree, if the safety revert above beat us to
        // the resume) means someone else has taken the tree in the meantime. The net's tile is
        // re-checked too: it was free when the set started, and three cycles is long enough for
        // somebody to have laid a box trap on it.
        val current = netTrapTree(loc.coords)
        val stillOurs =
            current != null &&
                current.id in HunterTrapStates.netTrapSettableLocIds &&
                conRepo.findExact(loc.coords, TRAP_CONTROLLER) == null &&
                canTakeTrap(netCoords)
        if (!stillOurs) {
            mes("Someone else is already using this tree.")
            return false
        }

        // Charged here, past the last path that can still refuse, for the reasons spelled out in
        // [setDeadfall]. Taken as a pair: a player who loses the net mid-set gets the rope back
        // rather than paying half the cost of a trap that was never built.
        if (invDel(inv, ROPE, 1).failure) {
            mes("You need a rope and a small fishing net to set a net trap.")
            return false
        }
        if (invDel(inv, SMALL_FISHING_NET, 1).failure) {
            invAdd(inv, ROPE, 1)
            mes("You need a rope and a small fishing net to set a net trap.")
            return false
        }

        changeNetTrapTreeLoc(loc.coords, HunterTrapStates.armedTreeLoc(creature))
        // Spawned carrying the *tree's* angle, which is what makes [netTrapTreeCoords] able to walk
        // back here from an op that landed on the net. Nothing else records the pairing.
        locRepo.add(
            netCoords,
            HunterTrapStates.netSetLoc(creature),
            Int.MAX_VALUE,
            loc.angle,
            LocShape.CentrepieceStraight,
        )

        val spawn = Controller(TRAP_CONTROLLER, loc.coords)
        conRepo.add(spawn, TRAP_LIFETIME_CYCLES)
        spawn.trapOwner = player.uid.packed
        spawn.trapFamily = TrapFamily.NETTRAP.ordinal
        spawn.trapCreature = CREATURE_NONE
        spawn.aiTimer(1)

        // Re-swept rather than reusing `laid`: three cycles is long enough for one of this player's
        // other traps to have collapsed.
        player.hunterTrapCoords = player.sweepTrapCoords() + loc.coords.packed
        return true
    }

    /**
     * Takes a net trap back apart from either of its two locs: hands the rope and net back, deletes
     * the spawned net and returns the tree to its unset state.
     *
     * A net trap with no controller is not an error: it is a sprung-and-empty wreck whose trap
     * already ended. Nothing is handed back in that case - a failed catch dropped the rope and net
     * on the ground at the time it failed, and handing them back here as well would mint a second
     * pair.
     */
    fun ProtectedAccess.dismantleNetTrap(loc: BoundLocInfo): Boolean {
        val anchor = netTrapAnchor(loc) ?: return false
        val controller = conRepo.findExact(anchor, TRAP_CONTROLLER)
        if (controller != null && controller.trapOwner != player.uid.packed) {
            mes("This isn't your trap.")
            return false
        }

        if (controller != null) {
            val slotsNeeded = NET_TRAP_COMPONENTS.sumOf { hunterInvSlotsNeeded(inv, it, 1) }
            if (inv.freeSpace() < slotsNeeded) {
                mes("Your inventory is too full to hold any more.")
                soundSynth("synth.pillory_wrong")
                return false
            }
            for (obj in NET_TRAP_COMPONENTS) {
                invAdd(inv, obj, 1)
            }
        }

        endTrapLoc(TrapFamily.NETTRAP, anchor)

        if (controller != null) {
            conRepo.del(controller)
            player.sweepTrapCoords()
        }
        return true
    }

    /**
     * `Check` on a full net. The op lands on the *net* loc, a tile away from the controller, so the
     * anchor is walked back to before the shared collect transaction runs.
     */
    fun ProtectedAccess.collectNetTrap(loc: BoundLocInfo): Boolean {
        val anchor = netTrapAnchor(loc) ?: return false
        return collectTrapAt(anchor)
    }

    /**
     * The controller of the net trap either half of [loc] belongs to, or null once it has ended.
     *
     * Exists so the `Investigate` handler can ask the same question the other families ask with a
     * bare `conRepo.findExact(loc.coords, ...)`: for a net trap that call would look on the wrong
     * tile whenever the player clicked the net.
     */
    fun netTrapController(loc: BoundLocInfo): Controller? {
        val anchor = netTrapAnchor(loc) ?: return null
        return conRepo.findExact(anchor, TRAP_CONTROLLER)
    }

    /**
     * The tile a net trap's controller sits on, given either of its two locs, or null if [loc] is
     * neither or its partner is missing.
     *
     * The tree half is its own anchor. The net half is walked back through [netTrapTreeCoords] and
     * then **checked**: a net whose computed tree tile does not actually hold a young tree is a
     * desynced pair, and acting on the wrong tile is worse than refusing. That check is the only
     * thing standing between a wrong offset mapping and an op that changes an unrelated loc.
     */
    private fun netTrapAnchor(loc: BoundLocInfo): CoordGrid? =
        when (loc.id) {
            in HunterTrapStates.netTrapTreeLocIds -> loc.coords
            in HunterTrapStates.netTrapNetLocIds -> {
                val anchor = netTrapTreeCoords(loc.coords, loc.angle)
                anchor.takeIf { netTrapTree(it) != null }
            }
            else -> null
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
            //
            // If a deadfall ever did reach here, [clearTrapLoc]'s check throws rather than deleting
            // the boulder. That is deliberate and is the same priority as the `check` two lines
            // below: a thrown tick is loud and recoverable by restarting, where a permanent delete
            // silently takes a boulder spot out of the world until then. The cost is that one
            // corrupt controller takes the tick down instead of tidying itself away.
            clearTrapLoc(coords)
            conRepo.del(this)
            return
        }

        val loc = findTrapLoc(family, coords)
        if (loc == null) {
            // Make sure the controller lived beyond a single tick; otherwise something is
            // recreating traps faster than the loc can be registered.
            check(mapClock > creationCycle + 1) { "Hunter trap loc deleted faster than expected." }
            // A net trap's state loc is its net, a tile from the tree the controller is anchored
            // to. Losing the net still has to put the tree back, or a permanent map loc is left
            // bent over with nothing left alive to unbend it.
            if (family == TrapFamily.NETTRAP) {
                revertNetTrapTree(coords)
            }
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
            // trap. A settle that ends the trap outright - the deadfall's and the net trap's failed
            // catches - deletes the controller, so there is nothing left to tick.
            if (settle(family, owner)) {
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
        if ((mapClock.cycle - creationCycle) % family.attemptCycles != 0) {
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
        // one whose trap is a boulder rather than something underfoot. Every other family applies
        // it, including the two added since - see [TrapFamily.suppressedByPlayerOnTile], which is
        // deliberately *not* [TrapFamily.portable]: the net trap is not portable and is not exempt.
        //
        // `loc.coords`, not `coords`: the tile that matters is the one the trap's business end is
        // on, which for the net trap is its net rather than the tree the controller sits on ("only
        // when the player is not standing on the net", wiki, Net trap). Every other family's state
        // loc is on the controller's own tile, so the two are the same tile for them.
        val centre = loc.coords
        if (
            family.suppressedByPlayerOnTile &&
                playerRepo.findAll(centre).any { it.isValidTarget() }
        ) {
            return
        }

        val target = nearbyCreature(family, centre) ?: return

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
            val dx = npc.coords.x - centre.x
            val dz = npc.coords.z - centre.z
            advanceTrapLoc(family, coords, HunterTrapStates.trappingLoc(creature, dx, dz))
        } else {
            trapCreature = CREATURE_FAILED
            advanceTrapLoc(family, coords, HunterTrapStates.failingLoc(family, creature))
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
    fun ProtectedAccess.collectTrap(loc: BoundLocInfo): Boolean = collectTrapAt(loc.coords)

    /**
     * [collectTrap] keyed on the controller's tile rather than the clicked loc's.
     *
     * The two are the same tile for every family but the net trap, whose `Check` op lands on the
     * net a tile away from the tree the controller is anchored to - see [collectNetTrap].
     */
    private fun ProtectedAccess.collectTrapAt(coords: CoordGrid): Boolean {
        val controller = conRepo.findExact(coords, TRAP_CONTROLLER) ?: return false
        if (controller.trapOwner != player.uid.packed) {
            mes("This isn't your trap.")
            return false
        }

        val family = TrapFamily.entries.getOrNull(controller.trapFamily) ?: return false
        val creature = HunterCreatures.all.getOrNull(controller.trapCreature)

        // Rolled once, up front: the space check below and the awards further down have to agree
        // on the same numbers, and a second roll would let a collect be accepted for five feathers
        // and then hand out ten.
        // `this@HunterTrap.random`, not `random`: this is a `ProtectedAccess` extension, and that
        // receiver has a `random` of its own which would silently win over the injected field - the
        // shadowing trap [HunterFalconry] and [HunterButterfly] avoid by naming theirs `gameRandom`.
        // The bare name was safe while this roll lived inside a non-extension helper; it is not
        // safe here.
        val awards =
            creature?.caught.orEmpty().map {
                it.obj to rollQuantity(this@HunterTrap.random, it.quantity)
            }

        // Whatever the trap was built from comes back alongside everything it caught. A deadfall
        // contributes nothing: its boulder stays where it is and the log it was armed with went
        // with the catch. A net trap contributes two things, not one.
        val returned = trapComponents(family)

        // A stackable award only costs a slot when the player isn't already carrying it - counting
        // it unconditionally over-rejects a legitimate collect (e.g. a chinchompa catch when the
        // player already holds that chinchompa type, or a feather catch when they already hold that
        // feather colour).
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
            // Creature xp is stored x10 so fractional values survive the table.
            val xp = (creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
            statAdvance("stat.hunter", xp)
        }

        endTrapLoc(family, coords)
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
     * Portable families only. The deadfall's and the net trap's op1s are routed by state instead -
     * [setDeadfall] / [dismantleDeadfall] / [collectTrap], [setNetTrap] / [dismantleNetTrap] /
     * [collectNetTrap] - because their states are several different transactions on a loc that must
     * never be deleted.
     */
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
    private fun Controller.settle(family: TrapFamily, owner: Player?): Boolean {
        // A deadfall that sprang and caught nothing has no collectible failed state - the cache has
        // no `hunting_deadfall_failed` at all. The boulder goes straight back to unset and the log
        // is lost, so the controller has no reason to outlive the spring.
        if (family == TrapFamily.DEADFALL && trapCreature == CREATURE_FAILED) {
            changeDeadfallLoc(coords, HunterTrapStates.failedLoc(family))
            conRepo.del(this)
            return false
        }

        // A net trap that sprang and caught nothing is over too, and it is the one family whose
        // failure hands the player's materials back on the *ground* rather than into a wreck they
        // dismantle: "If not successful, the tree will snap back to its original position, and the
        // small fishing net and rope will appear on the ground" (wiki, Net trap). Both halves of
        // that sentence are here - the tree reverts, the pair drops - and the controller ends,
        // which is why the wreck the net is left as owes the player nothing.
        if (family == TrapFamily.NETTRAP && trapCreature == CREATURE_FAILED) {
            endNetTrap(owner)
            return false
        }

        val settled =
            if (trapCreature == CREATURE_FAILED) {
                HunterTrapStates.failedLoc(family, netTrapCreature(family, coords))
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

    /**
     * Ends the trap and deletes its controller; the stored coord is reclaimed by the next sweep.
     *
     * A portable trap leaves its collapsed state on the ground for a while, so the owner can still
     * come back for the trap item.
     *
     * A deadfall leaves nothing behind - the boulder is simply unset again - so its owner is told,
     * where a portable trap's wreck on the ground says it for itself. [owner] is null when the
     * collapse *is* the owner logging out, and there is nobody to tell.
     *
     * A net trap collapses the same way it fails: the tree unbends, the net is left as a wreck and
     * the rope and net drop. Sharing that path with [settle] is deliberate - an unattended net trap
     * that simply timed out must not silently swallow two items that a failed catch would have
     * given back.
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
        if (family == TrapFamily.NETTRAP) {
            endNetTrap(owner)
            return
        }
        spawnTrapLoc(coords, HunterTrapStates.failedLoc(family), TRAP_COLLAPSE_LINGER_CYCLES)
        conRepo.del(this)
    }

    /**
     * The one way a net trap ends without its owner collecting it: the net becomes a wreck that
     * lingers, the tree snaps back upright, the rope and net drop, and the controller goes.
     *
     * The pair is dropped **on the trap's own tile**, not the player's. The wiki says only that
     * they "appear on the ground", and the trap is the only position that always exists - a net
     * trap can fail while its owner is halfway across the map, or ([owner] null) while they are
     * logged out entirely, and there is no player tile to use then. Dropping at the trap is also
     * what a player walking back to check it expects to find. [owner] is passed as the receiver so
     * the drop is private to them first, exactly like a kill's loot.
     */
    private fun Controller.endNetTrap(owner: Player?) {
        val creature = netTrapCreature(TrapFamily.NETTRAP, coords)
        changeNetLoc(
            coords,
            HunterTrapStates.failedLoc(TrapFamily.NETTRAP, creature),
            TRAP_COLLAPSE_LINGER_CYCLES,
        )
        revertNetTrapTree(coords)
        for (obj in NET_TRAP_COMPONENTS) {
            objRepo.add(obj, coords, NET_TRAP_DROP_CYCLES, receiver = owner)
        }
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

    /**
     * The first creature of [family] within its trigger distance of [centre].
     *
     * [centre] is the trap's business end rather than its controller's tile: the two differ only
     * for the net trap, whose net sits a tile from the tree the controller is anchored to.
     */
    private fun nearbyCreature(
        family: TrapFamily,
        centre: CoordGrid,
    ): Pair<Npc, HunterCreature>? =
        npcRepo
            .findAll(ZoneKey.from(centre), zoneRadius = 1)
            .filter { npc ->
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

    /**
     * The loc carrying the trap's *state*, given the tile its controller is anchored to.
     *
     * A portable trap owns its tile's centrepiece layer outright, so its shape is enough to find
     * it. A deadfall does not: it is whichever of nineteen boulder states the tile is currently
     * wearing, at whatever shape and angle the map gave it, so it is found by id instead. A net
     * trap's state is not on the controller's tile at all - the tree stays `set` for the whole
     * armed-and-sprung life of the trap and it is the net beside it that moves through
     * `net_set` -> `catching`/`failing` -> `full`/`failed`.
     */
    private fun findTrapLoc(family: TrapFamily, coords: CoordGrid): LocInfo? =
        when (family) {
            TrapFamily.SNARE,
            TrapFamily.BOX,
            TrapFamily.MAGICBOX -> locRepo.findExact(coords, LocShape.CentrepieceStraight)
            TrapFamily.DEADFALL ->
                locRepo.findAll(coords).firstOrNull { it.id in HunterTrapStates.deadfallLocIds }
            TrapFamily.NETTRAP -> findNetLoc(coords)
        }

    /** Moves a trap into its next state, whichever way its family is allowed to change locs. */
    private fun advanceTrapLoc(family: TrapFamily, coords: CoordGrid, internal: String) {
        when (family) {
            TrapFamily.SNARE,
            TrapFamily.BOX,
            TrapFamily.MAGICBOX -> spawnTrapLoc(coords, internal)
            TrapFamily.DEADFALL -> changeDeadfallLoc(coords, internal)
            TrapFamily.NETTRAP -> changeNetLoc(coords, internal)
        }
    }

    /**
     * Clears a trap off its tile for good: the portable wreck goes, the boulder comes back, and a
     * net trap's two locs are undone together - the spawned net deleted, the tree changed back.
     */
    private fun endTrapLoc(family: TrapFamily, coords: CoordGrid) {
        when (family) {
            TrapFamily.SNARE,
            TrapFamily.BOX,
            TrapFamily.MAGICBOX -> clearTrapLoc(coords)
            TrapFamily.DEADFALL -> changeDeadfallLoc(coords, HunterTrapStates.DEADFALL_BOULDER)
            TrapFamily.NETTRAP -> {
                delNetLoc(coords)
                revertNetTrapTree(coords)
            }
        }
    }

    /** The young tree on [coords], whichever of its three states it is wearing. */
    private fun netTrapTree(coords: CoordGrid): LocInfo? =
        locRepo.findAll(coords).firstOrNull { it.id in HunterTrapStates.netTrapTreeLocIds }

    /**
     * The creature a net trap on [coords] belongs to, read off its tree rather than off what it
     * caught.
     *
     * The failure and collapse paths both need it while `trapCreature` still says "nothing caught",
     * and the tree is the only thing that knows - every young tree in the world belongs to exactly
     * one salamander. Null for every other family, whose branches never read it.
     */
    private fun netTrapCreature(family: TrapFamily, coords: CoordGrid): HunterCreature? =
        if (family != TrapFamily.NETTRAP) {
            null
        } else {
            netTrapTree(coords)?.let { HunterCreatures.byNetTrapLoc(it.id) }
        }

    /** The spawned net belonging to the tree on [coords]. */
    private fun findNetLoc(treeCoords: CoordGrid): LocInfo? {
        val tree = netTrapTree(treeCoords) ?: return null
        val netCoords = netTrapCoords(treeCoords, tree.angle)
        return locRepo.findAll(netCoords).firstOrNull {
            it.id in HunterTrapStates.netTrapNetLocIds
        }
    }

    /**
     * Moves the spawned net into its next state, in place.
     *
     * `locRepo.change` rather than a delete and a fresh add, so the angle carries across - the net
     * is the only record of where its tree is ([netTrapTreeCoords]), and a state change that reset
     * it to some default would strand every op that lands on the net afterwards.
     */
    private fun changeNetLoc(
        treeCoords: CoordGrid,
        internal: String,
        duration: Int = Int.MAX_VALUE,
    ) {
        val current = findNetLoc(treeCoords) ?: return
        val into =
            ServerCacheManager.getObject(internal.asRSCM(RSCMType.LOC))
                ?: error("Missing net trap loc type: $internal")
        locRepo.change(current, into, duration)
    }

    /**
     * Deletes the spawned net.
     *
     * This is the *only* delete anywhere in the net trap, and it is checked: the loc it is about to
     * remove has to be one of the twenty-five `name=Net trap` states. A young tree can never reach
     * here - not merely because callers pass the tree's tile rather than the net's, but because a
     * tree id would fail the check outright.
     */
    private fun delNetLoc(treeCoords: CoordGrid) {
        val net = findNetLoc(treeCoords) ?: return
        check(net.id in HunterTrapStates.netTrapNetLocIds) {
            "Refusing to delete net trap loc ${net.id} at ${net.coords}: it is not a spawned net."
        }
        locRepo.del(net, Int.MAX_VALUE)
    }

    /**
     * Puts a young tree back to its unset state.
     *
     * A `locRepo.change`, exactly like [changeDeadfallLoc] and for exactly the same reason: the
     * tree is a permanent map loc, and reverting it by deleting whatever is on the tile would take
     * that tree out of the world until the next restart.
     */
    private fun revertNetTrapTree(coords: CoordGrid) {
        val creature = netTrapCreature(TrapFamily.NETTRAP, coords) ?: return
        changeNetTrapTreeLoc(coords, HunterTrapStates.upLoc(creature))
    }

    /**
     * The one and only way a young tree ever changes state; the net trap's [changeDeadfallLoc].
     *
     * `locRepo.change`, never `locRepo.del`. See that function for the full argument - it applies
     * word for word, with "boulder" read as "tree".
     */
    private fun changeNetTrapTreeLoc(
        coords: CoordGrid,
        internal: String,
        duration: Int = Int.MAX_VALUE,
    ) {
        val current = netTrapTree(coords) ?: return
        val into =
            ServerCacheManager.getObject(internal.asRSCM(RSCMType.LOC))
                ?: error("Missing net trap tree loc type: $internal")
        locRepo.change(current, into, duration)
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
        // Neither of the two fixed-loc families may ever reach here: this delete is permanent, and
        // a permanent delete of a map loc is never respawned. See [changeDeadfallLoc].
        //
        // The net trap's *net* is a spawn and is deletable - but only through [delNetLoc], which
        // checks that it really is one. All forty sapling ids are refused here rather than only the
        // fifteen tree states, because nothing legitimately reaches this path holding a net either:
        // the portable teardown is keyed on the controller's own tile, which for a net trap is
        // always the tree's. A net id arriving here means the two halves have desynced, and that is
        // worth a thrown tick.
        check(
            loc.id !in HunterTrapStates.deadfallLocIds &&
                loc.id !in HunterTrapStates.netTrapLocIds
        ) {
            "Refusing to delete hunter loc ${loc.id} at $coords: it is a permanent map loc."
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
         * The single inventory item a portable trap is laid from.
         *
         * Null for the two fixed-loc families, which have none: a boulder and a young tree are
         * permanent map locs, armed in place and never carried. A placeholder obj here to satisfy
         * the `when` would let [layTrap] spawn a bird snare on an empty tile in their name.
         */
        private fun trapObj(family: TrapFamily): String? =
            when (family) {
                TrapFamily.SNARE -> "obj.hunting_ojibway_bird_snare"
                TrapFamily.BOX -> "obj.hunting_box_trap"
                TrapFamily.MAGICBOX -> "obj.magic_imp_box"
                TrapFamily.DEADFALL,
                TrapFamily.NETTRAP -> null
            }

        /**
         * Everything a trap was built from, which is what a successful collect hands back.
         *
         * Separate from [trapObj] because it is not one-to-one: a portable trap is laid from and
         * returns the same single item, a deadfall returns nothing (its log went with the catch),
         * and a net trap returns the two things it was strung from.
         */
        private fun trapComponents(family: TrapFamily): List<String> =
            when (family) {
                TrapFamily.SNARE,
                TrapFamily.BOX,
                TrapFamily.MAGICBOX -> listOfNotNull(trapObj(family))
                TrapFamily.DEADFALL -> emptyList()
                TrapFamily.NETTRAP -> NET_TRAP_COMPONENTS
            }
    }
}
