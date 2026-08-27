package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.NpcMode
import jakarta.inject.Inject
import java.util.IdentityHashMap
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.table.FiremakingLogsRow
import org.rsmod.api.utils.skills.SkillingSuccessRate
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.PlayerUid

/**
 * Finishes a collapse a logout interrupted. Rides a one-cycle soft queue, not the login event:
 * `VarPlayerIntMapSetter` skips its transmit branch while `processedMapClock == 0`
 * (docs/hunter.md).
 */
const val PITFALL_REBUILD_QUEUE: String = "queue.hunter_pitfall_rebuild"

/**
 * Pitfall trapping. Structurally a crab trap, not a trap: the server's whole job is a varbit
 * write - `locRepo` is never touched, no bit offset is ever computed (the layout has a hole at
 * site 19), pits are private, and only [jumpPit] draws, and only for the three cats. [tick] is
 * the one non-op hook and it is not optional: without it a teased creature follows its hunter
 * forever and every catch stays mid-collapse - with every unit test still green. The player's own
 * vault is not modelled. Design notes and sources: docs/hunter.md.
 */
class HunterPitfall
@Inject
constructor(
    // Named `gameRandom`, not `random`. `ProtectedAccess` has a `random` property of its own and an
    // extension receiver's member wins over the dispatch receiver's field, so a field called
    // `random` here would be silently shadowed at every use site and the catch roll would draw from
    // the player's context RNG instead of the injected one - compiling, running, and untestable.
    // [HunterButterfly] and [HunterFalconry] carry the same note for the same reason.
    private val gameRandom: GameRandom,
    // The one repository this class holds, and it is not a loc repository. A catch kills the
    // creature that walked into the pit, and `despawn` is how every other hunter technique removes
    // one; it cannot touch scenery. `HunterPitfallTest` still asserts that no *loc*, controller or
    // obj repository is reachable from here, because a `del` on a pit would take it out of the
    // world for every player until the next restart.
    private val npcRepo: NpcRepository,
    private val xpMods: XpModifiers,
    private val playerList: PlayerList,
) {
    /**
     * Which creature is chasing which player, keyed by npc *identity* to a [PlayerUid] - a slot is
     * reused the moment its player logs out. Transient, bounded by the map's creature count;
     * the [FalconLinks] reasoning.
     */
    private val chases = IdentityHashMap<Npc, PlayerUid>()

    /**
     * The one pit each creature will not jump into next - literally one, replaced by the next
     * failure and dropped with the chase, never an accumulating set (docs/hunter.md). Deliberately
     * not cleared by a re-tease, or the rule means nothing.
     */
    private val lastVaulted = IdentityHashMap<Npc, PitfallSite>()

    /**
     * Every catch still falling or landed-and-uncollected - the varbit's transient other half
     * (docs/hunter.md). A list per site: the documented two-creature window needs it.
     */
    private val collapses = ArrayList<Collapse>()

    /**
     * Catches taken on the log currently in each pit - what [PIT_CAPACITY] counts. Not the
     * ledger, which shrinks on collect: counting it let one log take unlimited creatures.
     * Transient; ended by the two writes that end an arming (docs/hunter.md).
     */
    private val armedCatches = HashMap<Arming, Int>()

    /**
     * `Trap` on an empty pit: one log consumed, knife kept, everything verified before anything
     * is charged (no delay, so nothing to protect against). Refusal strings are ours; success is
     * deliberately silent - the pit visibly changes.
     */
    fun ProtectedAccess.trapPit(site: PitfallSite): Boolean {
        val state = pitState(player, site)
        if (state != PitState.Empty) {
            val message =
                if (state == PitState.Set) {
                    "This trap is already set."
                } else {
                    "There is something in this trap already."
                }
            mes(message)
            return false
        }

        val creature = site.creature
        if (player.hunterLvl < creature.level) {
            mes("You need a Hunter level of ${creature.level} to set a trap for this creature.")
            return false
        }

        // Inventory *or* equipment: a fletching knife is a weapon, so the ordinary way to carry one
        // is on the hand, and a player who is wielding theirs would otherwise be told to go and
        // fetch the knife they are holding. The wiki names only the inventory, which is the reading
        // that refuses a tool the player demonstrably has.
        if (KNIVES.none { inv.contains(it) || worn.contains(it) }) {
            mes("You need a knife to set a trap here.")
            return false
        }

        val cap = PitfallLogic.maxTraps(player.hunterLvl)
        if (activePits(player) >= cap) {
            val plural = if (cap == 1) "trap" else "traps"
            mes("You can only have $cap $plural set at your Hunter level.")
            return false
        }

        val log = logTiers.firstOrNull(inv::contains)
        if (log == null) {
            mes("You need some logs to set a trap here.")
            return false
        }

        if (invDel(inv, log, 1).failure) {
            mes("You need some logs to set a trap here.")
            return false
        }

        setPitState(player, site, PitState.Set)
        return true
    }

    /**
     * `Dismantle` on a set or full pit. An armed pit returns nothing - the deadfall refunds only
     * because it *remembers* its log, and a pitfall's whole saved state is one 3-bit varbit with
     * no companion log varbit on any of the 25 sites; a generic refund would be an item transmute.
     * [PitState.Catching] is refused (the cache gives it no ops); a logout mid-collapse is
     * [rebuildPits]'s job.
     */
    fun ProtectedAccess.dismantlePit(site: PitfallSite): Boolean =
        when (pitState(player, site)) {
            PitState.Empty -> {
                mes("There is no trap here to dismantle.")
                false
            }
            PitState.Set -> {
                setPitState(player, site, PitState.Empty)
                true
            }
            PitState.Catching -> {
                mes("The trap is still collapsing.")
                false
            }
            PitState.Full,
            PitState.FullRotated -> collectPit(site)
        }

    /**
     * Pays out a full pit, or leaves it full if a second creature landed behind the first - one
     * dismantle pays one creature (docs/hunter.md). Room is checked for the whole catch first;
     * a refused catch is still in the pit when a slot frees, where a spilled one despawns.
     */
    private fun ProtectedAccess.collectPit(site: PitfallSite): Boolean {
        val creature = site.creature

        // `quantity.first` rather than a roll: every pitfall reward line is a flat quantity, so
        // nothing on the payout path draws. `HunterPitfallTest` pins that, so a ranged line added
        // later fails there rather than silently awarding its minimum here.
        val awards = creature.loot.map { it.obj to it.quantity.first }

        val slotsNeeded = awards.sumOf { (obj, count) -> hunterInvSlotsNeeded(inv, obj, count) }
        if (inv.freeSpace() < slotsNeeded) {
            mes("Your inventory is too full to hold any more.")
            soundSynth("synth.pillory_wrong")
            return false
        }

        for ((obj, count) in awards) {
            invAdd(inv, obj, count)
        }

        // Creature xp is stored x10 so fractional values survive the table.
        val xp = (creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
        statAdvance("stat.hunter", xp)

        setPitState(player, site, takeCatch(player.uid, site))
        return true
    }

    /**
     * Removes one landed catch and returns the state that leaves the pit in. The invariant: the
     * varbit must be able to hold every entry the ledger still has for this player -
     * [PitState.Empty] only when *nothing* is pending, or an in-flight sibling lands unpaid. The
     * next landed entry wins; anything airborne holds the pit at [PitState.Catching]
     * (docs/hunter.md).
     */
    private fun takeCatch(owner: PlayerUid, site: PitfallSite): PitState {
        val pending = collapses.filter { it.owner == owner && it.site == site }
        val collected = pending.firstOrNull(Collapse::landed)
        if (collected != null) {
            collapses.remove(collected)
        }
        val rest = pending.filter { it !== collected }
        val landed = rest.firstOrNull(Collapse::landed)
        return when {
            landed != null -> landed.fullState
            rest.isEmpty() -> PitState.Empty
            else -> PitState.Catching
        }
    }

    /**
     * The ledger's own count, landed or falling - *not* what [PIT_CAPACITY] counts. [rebuildPits]
     * is the only caller.
     */
    private fun pendingCatches(owner: PlayerUid, site: PitfallSite): Int =
        collapses.count { it.owner == owner && it.site == site }

    /**
     * How many creatures this player's copy of [site] has taken on the log currently in it.
     *
     * What [PIT_CAPACITY] is counted against. Unlike [pendingCatches] this does **not** fall when a
     * catch is collected: the log is spent either way, and a pit that has taken its two takes no
     * more until somebody arms it with another.
     */
    private fun catchesThisArming(owner: PlayerUid, site: PitfallSite): Int =
        armedCatches[Arming(owner, site)] ?: 0

    /**
     * `Tease`: the only thing that makes a creature hostile - `op1=Tease` is the only op any of
     * the five declares. Sets the engine's own `NpcMode.PlayerFollow` (three lines, no walk loop);
     * that mode has no leash, so [tick] is a prerequisite. The hunter's spear's +5% modifies a
     * tease rate published nowhere, so the tease is modelled certain and the spear confers no
     * mechanical advantage - and the bonus must not be moved onto the catch. Retaliation is not
     * modelled; the chase leash is its prerequisite. No suspension point: a one-cycle lock would
     * break the wiki's own two-creature procedure. Full reasoning: docs/hunter.md.
     */
    suspend fun ProtectedAccess.teaseCreature(npc: Npc): Boolean {
        // Silent on both: a click that lands on a creature which left the world between the click
        // and this call, or on an npc with no pit to lead it into, is not something to tell the
        // player about. Liveness first, so nothing is read off an npc the registry has torn down.
        if (!npc.isSlotAssigned || !npc.isVisible) {
            return false
        }
        if (npc.visType.id !in PITFALL_NPC_IDS) {
            return false
        }

        // The stick counts held *or* worn, for the reason `trapPit` accepts a worn knife: it is
        // `wearpos=righthand`, so the ordinary way to carry one is on the hand. The spear counts
        // only worn, and that half is sourced rather than inferred - "The spear must be equipped
        // before being able to tease creatures." (wiki, *Hunter's spear*, oldid=15264550).
        val hasTool =
            inv.contains(TEASING_STICK) ||
                worn.contains(TEASING_STICK) ||
                worn.contains(HUNTERS_SPEAR)
        if (!hasTool) {
            mes("You need a teasing stick or a hunter's spear to tease this creature.")
            return false
        }

        // No Hunter level check: the *Pitfall* page gates the trap and says nothing about a level
        // to tease. A player below the creature's level can make it chase them and simply has no
        // pit to lead it into.

        anim(TEASE_SEQ)

        npc.resetMovement()
        npc.mode = NpcMode.PlayerFollow
        npc.facePlayer(player)

        chases[npc] = player.uid
        return true
    }

    /** Who teased [npc] into its current chase, or null if nobody has. */
    fun teasedBy(npc: Npc): PlayerUid? = chases[npc]

    /**
     * `Jump`: vault the pit, and whatever qualifies may go in. [chases] is filtered by teaser
     * (this player, by uid), species (the pit's own), and [CATCH_RANGE]; the nearest qualifying
     * creature is taken. A null success pair means *no draw is taken* - the antelopes' catch is
     * published as certain - which the tests keep distinct from a rate that always wins. A miss
     * records [lastVaulted]; a catch despawns the creature and starts the collapse. The catch
     * message is unrecoverable offline, so a catch is silent. See docs/hunter.md.
     */
    suspend fun ProtectedAccess.jumpPit(site: PitfallSite): Boolean {
        val state = pitState(player, site)

        // The cache declares `Jump` on state 1 alone, so an empty or a full pit cannot be jumped by
        // clicking. State 2 is accepted as well as state 1, and that is the two-creature window
        // rather than an oversight - see [Collapse]. Silent: a click that cannot reach here is not
        // something to write a message for.
        if (state != PitState.Set && state != PitState.Catching) {
            return false
        }

        val creature = site.creature
        val chaser = crossingCreature(player, site) ?: return false

        // A fact about the log in the pit rather than about this creature, so it is asked before
        // the memory below and long before the roll: one arming buys [PIT_CAPACITY] catches and no
        // more, whatever is chasing and however many of them the hunter has already collected.
        if (catchesThisArming(player.uid, site) >= PIT_CAPACITY) {
            mes("There is no room in your trap for another creature.")
            return false
        }

        if (lastVaulted[chaser] == site) {
            // No draw is taken. The refusal is not a failed catch: the creature will not go near
            // this pit at all, and a roll here would make the rule cost a random number and
            // occasionally read as a catch to whoever is counting draws.
            mes("It refuses to go anywhere near that trap again.")
            return false
        }

        if (!rollCatch(creature)) {
            lastVaulted[chaser] = site
            // The same leap either way: the creature clears the pit instead of landing in it, and
            // the cache gives these five one leap sequence apiece rather than a pair.
            chaser.anim(creature.leapSeq)
            mes("It leaps clear over your trap. You will have to lure it into another one.")
            return false
        }

        // Read before the despawn, because the despawn is what makes the creature's tile
        // meaningless, and the side it came from is what picks the corpse's facing.
        val rotated = crossedFromSouthWest(chaser, site)

        // `stopChasing` drops the refusal with the chase; a creature in a pit refuses nothing.
        stopChasing(chaser)
        chaser.anim(creature.leapSeq)
        npcRepo.despawn(chaser, chaser.visType.respawnRate)

        setPitState(player, site, PitState.Catching)
        collapses += Collapse(player.uid, site, rotated)
        // Counted after the state write, and it has to be that way round: [setPitState] drops the
        // count for a pit that has just been armed or emptied, and [PitState.Catching] is neither.
        armedCatches.merge(Arming(player.uid, site), 1, Int::plus)
        return true
    }

    /**
     * The creature this player's jump has a chance of catching, or null if nothing is crossing.
     *
     * Liveness first, so nothing is measured off an npc the registry has torn down; the checks are
     * [jumpPit]'s, in the order that KDoc gives them.
     */
    private fun crossingCreature(player: Player, site: PitfallSite): Npc? {
        val npcId = site.creature.npc.asRSCM(RSCMType.NPC)
        return chases.entries
            .filter { (npc, teaser) ->
                teaser == player.uid &&
                    npc.isSlotAssigned &&
                    npc.isVisible &&
                    npc.visType.id == npcId &&
                    npc.coords.chebyshevDistance(site.coords) <= CATCH_RANGE
            }
            .minByOrNull { (npc, _) -> npc.coords.chebyshevDistance(site.coords) }
            ?.key
    }

    /**
     * Whether this creature goes in, which for two of the five is not a question.
     *
     * A [ProtectedAccess] extension for the Hunter level alone, and the level is the *effective*
     * one, so a boost helps the catch exactly as it helps every other technique's.
     */
    private fun ProtectedAccess.rollCatch(creature: PitfallCreature): Boolean {
        val low = creature.successLow
        val high = creature.successHigh
        if (low == null || high == null) {
            return true
        }
        val rate =
            SkillingSuccessRate.successRate(
                low = low,
                high = high,
                level = player.hunterLvl,
                maxLevel = MAX_HUNTER_LEVEL,
            )
        return rate > gameRandom.randomDouble()
    }

    /**
     * Which of the two collapsed renderings this catch ends on.
     *
     * [PitState.Full] and [PitState.FullRotated] are the same corpse a half-turn apart: the cache
     * pairs every creature's collapsed loc with a `_180` twin (larupia 19232/19235, graahk
     * 19231/19234, kyatt 19233/19236, and a pair each for the two antelopes), and the *Pitfall*
     * page's infobox lists each pair under one "Collapsed trap" entry. Which twin is drawn is
     * therefore purely which way the animal is lying, and the honest input for that is the side it
     * crossed from: a creature that came at the pit from the south or the west lies the opposite
     * way round to one that came from the north or the east.
     *
     * Summed rather than compared per axis because a pit is not axis-aligned to anything - the
     * twenty-five sites face every which way - so this is a diagonal split, which is the cheapest
     * rule that is symmetric and has no undefined case. It is **cosmetic**: both states carry the
     * same `Dismantle` op, [collectPit] pays them identically, and nothing else reads it.
     */
    private fun crossedFromSouthWest(npc: Npc, site: PitfallSite): Boolean =
        (npc.coords.x + npc.coords.z) < (site.coords.x + site.coords.z)

    /**
     * One cycle of everything on a clock: the chase leash and [landCollapses], on one hook so the
     * pair cannot be half-wired. `PlayerFollow` is unbounded and the follow processor teleports
     * the creature onto the player past 15 tiles, so this is the only thing that ever ends a
     * chase: teaser gone (slot *and* uid checked), or strayed past [CHASE_RANGE] from spawn - a
     * leash, not a stopwatch, and deliberately no cap inside the hunting ground (docs/hunter.md).
     */
    fun tick() {
        if (chases.isNotEmpty()) {
            // Copied out before anything is ended, because `stopChasing` writes the map being
            // read - and copied as *creatures*, not as entries. An `IdentityHashMap.Entry` is a
            // live view onto one table slot, and a removal runs `closeDeletion`, which rehashes
            // everything after the hole and can move a later entry into it; a key read back out
            // of a retained entry after that is a neighbour's key, or null. `Entry.getKey` never
            // looks at `modCount`, so there is no `ConcurrentModificationException` to notice it
            // by - the sweep would simply end one chase twice and leave another running forever.
            val ended = chases.filterNot { (npc, teaser) -> chaseContinues(npc, teaser) }.keys
            for (npc in ended) {
                if (npc.isSlotAssigned) {
                    stopChasing(npc)
                } else {
                    chases.remove(npc)
                    lastVaulted.remove(npc)
                }
            }
        }
        // The counts a logout left behind. A [PlayerUid] does not survive its player, so an entry
        // whose owner no longer resolves is one nothing will ever read or clear again - the sweep
        // [landCollapses] does for the ledger, and the whole of what bounds this map.
        if (armedCatches.isNotEmpty()) {
            armedCatches.keys.removeAll { it.owner.resolve(playerList) == null }
        }
        landCollapses()
    }

    /**
     * Pits finish collapsing and stale ledger entries go. A landing on a full pit stays in the
     * ledger as the second catch; a landing on any other state is dropped - a backstop no routine
     * path reaches, kept because dropping a catch is smaller than stamping one over a re-armed
     * pit (docs/hunter.md).
     */
    private fun landCollapses() {
        if (collapses.isEmpty()) {
            return
        }
        val iterator = collapses.iterator()
        while (iterator.hasNext()) {
            val collapse = iterator.next()
            val owner = collapse.owner.resolve(playerList)
            if (owner == null) {
                iterator.remove()
                continue
            }
            if (collapse.landed) {
                // An uncollected catch whose pit no longer shows one. The same backstop as the
                // `else` below, and reachable by no more of a path than that one is.
                if (pitState(owner, collapse.site) !in FULL_STATES) {
                    iterator.remove()
                }
                continue
            }
            if (--collapse.cyclesLeft > 0) {
                continue
            }
            when (pitState(owner, collapse.site)) {
                PitState.Catching -> setPitState(owner, collapse.site, collapse.fullState)
                PitState.Full,
                PitState.FullRotated -> Unit
                else -> iterator.remove()
            }
        }
    }

    /** Whether [npc]'s chase of [teaser] is still one this feature is willing to keep running. */
    private fun chaseContinues(npc: Npc, teaser: PlayerUid): Boolean {
        if (!npc.isSlotAssigned) {
            return false
        }
        // Read exactly as `NpcPlayerFollowModeProcessor` reads it, then checked against the uid the
        // tease recorded: `facingTarget` resolves a *slot*, and a slot outlives its player.
        val target = npc.facingTarget(playerList)
        if (target == null || target.uid != teaser) {
            return false
        }
        return npc.spawnCoords.chebyshevDistance(npc.coords) <= CHASE_RANGE
    }

    /**
     * Ends [npc]'s chase and hands it back to its ordinary wandering; also drops its
     * [lastVaulted] entry, so a respawn gets a clean slate.
     */
    fun stopChasing(npc: Npc) {
        chases.remove(npc)
        lastVaulted.remove(npc)
        npc.defaultMode()
    }

    /** The state this player's own copy of [site] is currently in. */
    fun pitState(player: Player, site: PitfallSite): PitState =
        PitState.of(player.vars[site.varbit])

    /**
     * Returns every one of this player's pits to [PitState.Empty] and drops their ledger entries -
     * the test-and-tooling reset, never called in play.
     */
    fun clearPits(player: Player) {
        for (site in PitfallSites.all) {
            setPitState(player, site, PitState.Empty)
        }
        collapses.removeAll { it.owner == player.uid }
    }

    /**
     * Finishes any collapse a logout stranded: a Catching pit with no live ledger entry lands
     * immediately - the corpse the varbit already promised - ridden in on [PITFALL_REBUILD_QUEUE]
     * because of the login-transmit varp trap (docs/hunter.md).
     */
    fun rebuildPits(player: Player) {
        for (site in PitfallSites.all) {
            if (pitState(player, site) != PitState.Catching) {
                continue
            }
            if (pendingCatches(player.uid, site) > 0) {
                continue
            }
            setPitState(player, site, PitState.Full)
        }
    }

    /** How many of this player's pits are not empty, which is what the cap counts. */
    private fun activePits(player: Player): Int =
        PitfallSites.all.count { pitState(player, it) != PitState.Empty }

    /**
     * The entire server side of a pit: `VarPlayerIntMapSetter` by the site's own gameval name -
     * never a computed bit offset (the packed layout has a hole; docs/hunter.md). Also ends an
     * arming when the write arms or empties the pit.
     */
    private fun setPitState(player: Player, site: PitfallSite, state: PitState) {
        VarPlayerIntMapSetter.set(player, site.varbit, state.varbitValue)
        // The two writes that end an arming, and the one choke point every path to them goes
        // through: [trapPit] puts a fresh log in ([PitState.Set]), and every route back to
        // [PitState.Empty] - a dismantle, the last collection, [clearPits] - leaves a pit with no
        // log in it at all. Every other state is the same arming still going.
        if (state == PitState.Empty || state == PitState.Set) {
            armedCatches.remove(Arming(player, site))
        }
    }

    /**
     * One player's copy of one pit, for as long as the log currently in it lasts.
     *
     * A key and nothing else: [armedCatches] is what it counts for, and a pit's identity here is
     * the player who armed it and the site, exactly as [Collapse]'s first two fields are.
     */
    private data class Arming(val owner: PlayerUid, val site: PitfallSite) {
        constructor(player: Player, site: PitfallSite) : this(player.uid, site)
    }

    /**
     * One creature falling into one player's copy of one pit - what the 3-bit varbit has no room
     * for, held only in memory (docs/hunter.md).
     */
    private class Collapse(val owner: PlayerUid, val site: PitfallSite, val rotated: Boolean) {
        var cyclesLeft: Int = COLLAPSE_CYCLES

        val landed: Boolean
            get() = cyclesLeft == 0

        /** Which of the two collapsed renderings this catch shows once it has landed. */
        val fullState: PitState
            get() = if (rotated) PitState.FullRotated else PitState.Full
    }

    private companion object {
        /**
         * The two tools a pit can be set with, either held or worn.
         *
         * `obj.knife` (946) and `obj.fletching_knife` (31043), the pair [HunterTrap]'s deadfall
         * accepts and the pair the wiki names for both techniques. The fletching knife is
         * `wearpos=lefthand`, which is the whole reason the equipment half of the check exists.
         */
        private val KNIVES: List<String> = listOf("obj.knife", "obj.fletching_knife")

        /**
         * The teasing stick, `obj.hunting_teasing_stick` (10029).
         *
         * `wearpos=righthand` in the cache, which is why [teaseCreature] accepts it held or worn.
         */
        private const val TEASING_STICK: String = "obj.hunting_teasing_stick"

        /**
         * The hunter's spear, `obj.hg_hunter_spear` (29305).
         *
         * A thrown weapon that doubles as a teasing stick, and accepted only when equipped. Never
         * consumed by a tease, which needs no code: nothing in [teaseCreature] deletes it.
         */
        private const val HUNTERS_SPEAR: String = "obj.hg_hunter_spear"

        /**
         * The player's tease animation, `seq.hunting_teasing_animal` (5236).
         *
         * The cache's own: it carries `replaceheldright=hunting_teasing_stick`, so the client puts
         * the stick in the player's hand for the duration whatever they are actually holding.
         */
        private const val TEASE_SEQ: String = "seq.hunting_teasing_animal"

        /**
         * The five npcs a `Tease` op may land on.
         *
         * A set rather than a lookup back to the [PitfallCreature] row, because nothing about the
         * tease varies by creature - the level, the loot and the catch rate all belong to the pit
         * the creature is being led into, not to the tease. Resolved once and memoised, since
         * `RSCM.getReverseMapping` scans the whole npc table and memoises nothing.
         */
        private val PITFALL_NPC_IDS: Set<Int> by lazy {
            PitfallCreatures.all.mapTo(HashSet()) { it.npc.asRSCM(RSCMType.NPC) }
        }

        /**
         * How far a teased creature follows before [tick] leashes it: 64 tiles from spawn.
         * Unsourced - no page states a leash - and chosen to cover every ground's longest
         * tease-to-pit run with margin while still ending a cross-map chase (docs/hunter.md).
         */
        private const val CHASE_RANGE: Int = 64

        /**
         * How close a chasing creature must be to the pit to be crossing: 3 tiles, Chebyshev.
         * Unsourced; "passes the trap" reduced to a radius, sized to the creatures themselves
         * (size 2) plus one tile of approach.
         */
        private const val CATCH_RANGE: Int = 3

        /**
         * How long a pit shows [PitState.Catching] before the corpse appears: 5 cycles.
         * Unsourced - the same fixed-short-step model as `TRAP_SPRING_CYCLES`.
         */
        private const val COLLAPSE_CYCLES: Int = 5

        /**
         * How many creatures one arming buys: two - the wiki's documented quick-tease window
         * (docs/hunter.md). Counted per arming, not per ledger.
         */
        private const val PIT_CAPACITY: Int = 2

        /** The two states a landed catch can be showing. Both carry `Dismantle`; see [PitState]. */
        private val FULL_STATES: Set<PitState> = setOf(PitState.Full, PitState.FullRotated)

        /**
         * Every log a pit accepts, **lowest tier first**.
         *
         * "If players have multiple types of logs in their inventory, the lowest tier of logs will
         * be used first." (wiki, *Pitfall*, oldid=15201220). That is the one rule that makes this a
         * ranked list rather than the deadfall's plain first-by-slot-order pick: the same page's
         * sentence for the deadfall states no preference at all, so that technique is free to take
         * whatever is nearest the top of the backpack and this one is not.
         *
         * "Tier" is read as the log's own Firemaking requirement, which is the only ordering the
         * packed data carries and the one every log tier list in the game is written in. Seven of
         * the nineteen rows are Firemaking 1 - normal, achey and the five Treasure Trails colours -
         * and five of those seven are filtered out below, leaving normal and achey tree logs tied
         * at the bottom. `sortedWith` is stable, so a tie like that would otherwise fall back to
         * whichever order [FiremakingLogsRow.all] happens to return rows in - the packed DB's
         * master-index list when one exists, `dbrows.values` iteration order otherwise - and
         * neither place promises anything about that order. The comparator below breaks the tie
         * itself instead, explicitly preferring `obj.logs`: achey tree logs are a Big Chompy Bird
         * Hunting quest item, and this is not a fact worth leaving to however a future repack
         * happens to enumerate rows.
         *
         * Eligibility is [isUsableDeadfallLog], reused rather than restated. Its two halves are
         * both exactly right here: the *Pitfall* page rules out redwood and arctic pine in the same
         * words the *Deadfall* page does, and the Treasure Trails logs are withheld for the same
         * reason - they are ordinary firemaking rows, so reading "logs" off that table sweeps them
         * in, and a clue step's coloured logs must not be destroyed to arm a pit. The name says
         * "deadfall" because that is where the rule was first needed; the predicate is the
         * module's, not that technique's.
         *
         * "Logs in a log basket will not be used" needs no code: this server models no log basket
         * storage at all, so the only place a log can be is the backpack.
         *
         * Read off the packed table rather than written out here, so a log added to firemaking
         * becomes pit fuel on the same day with nothing to keep in sync.
         */
        private val logTiers: List<String> by lazy {
            FiremakingLogsRow.all()
                .filter { isUsableDeadfallLog(it.input.internalName) }
                .sortedWith(
                    compareBy(
                        // A row with no requirement at all is the *highest* tier, not the
                        // lowest: an absent requirement is far likelier to describe something
                        // exotic than something cheap, and refusing to prefer an unknown row
                        // costs nothing.
                        { it.statReq.firstOrNull()?.t1 ?: Int.MAX_VALUE },
                        // The explicit tie-break `sortedWith` needs at Firemaking 1: `obj.logs`
                        // wins over `obj.achey_tree_logs` by name, not by whatever order the
                        // packed table happens to enumerate rows in.
                        { it.input.internalName != "obj.logs" },
                    )
                )
                .map { it.input.internalName }
        }
    }
}
