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
 * The soft queue that finishes a collapse a logout interrupted, `queue.hunter_pitfall_rebuild`.
 *
 * A player who logs out between a catch and its landing leaves a pit sitting at
 * [PitState.Catching] with nothing left to move it: the collapse ledger is in memory and the
 * varbit is in the save. [HunterPitfall.rebuildPits] is what resolves those, and it cannot run
 * from the login event itself - `VarPlayerIntMapSetter` short-circuits before its transmit branch
 * while `processedMapClock == 0`, which is exactly the state during `SessionStateEvent.Login`, so
 * the varbit would change on the server and the client would go on drawing the collapsing frame.
 * Riding a one-cycle soft queue puts the write after the login clock has moved. Soft because a
 * rebuild must never interrupt whatever the player is doing on the way in, which is the same
 * reasoning [TRACKING_RESET_QUEUE] and `BIRDHOUSE_FILL_QUEUE` carry.
 */
const val PITFALL_REBUILD_QUEUE: String = "queue.hunter_pitfall_rebuild"

/**
 * Pitfall trapping: setting a pit, jumping it, the catch that follows the hunter in, and taking the
 * pit apart again.
 *
 * **Structurally a crab trap, not a trap.** Like [HunterCrabTrap], and unlike the five families
 * [HunterTrap] runs, a pitfall is not an object in the world at all: the map places a
 * `hunting_pitfall_<n>` loc carrying a `multivar`, and the client draws whichever of that loc's
 * `multiloc` children the *viewing player's* `hunt_pitfall_state<n>` varbit selects. So the
 * server's whole job is to write a varbit, and:
 * - **`locRepo` is never touched.** Not `del`, not `change`, not `add`. This class holds no loc,
 *   controller or obj repository, so the invariant the deadfall and net trap need a runtime
 *   `check` to enforce - never delete a permanent map loc - is unrepresentable here rather than
 *   merely guarded. A `del` on a pit would take it out of the world for *every* player until the
 *   next restart. The one repository it does hold is [NpcRepository], because a creature that goes
 *   into a pit dies; it cannot reach scenery.
 * - **Never a computed bit offset.** Every write goes through [VarPlayerIntMapSetter] by the site's
 *   own gameval name and lets the cache place the bits, because the layout has a hole: site 18 ends
 *   at bit 23 of `varp.hunt_pitfall_states_basevar2` and site 19 starts at bit **25**. See
 *   [PitfallSite].
 * - **Pits are private.** Two players setting the same pit do not contend, so there is no owner to
 *   record, no controller to anchor and no tile to key anything on.
 * - **One random draw, and only for three of the five creatures.** [jumpPit] rolls the catch for
 *   the three cats and does not roll at all for the two antelopes; every reward line in
 *   [PitfallCreatures] is a flat quantity, so nothing else here draws. `HunterPitfallTest` pins
 *   both - the flatness, so a ranged line added later fails there rather than silently awarding
 *   its minimum, and the antelopes' draw count, so "cannot fail" cannot decay into "rolls a rate
 *   that always wins".
 *
 * ## Where the clicks come from, and the one hook that is not a click
 *
 * [PitfallEvents] registers all of it: `op3=Trap` and `op1=Jump` and `op2=Dismantle` on the
 * `multiloc` children a pit renders as, `op1=Tease` on the five creature npcs, and the login
 * queue [rebuildPits] rides. Nothing is registered on the twenty-five map-placed base locs, which
 * carry no ops at all.
 *
 * **[tick] is the one thing here that is not an op, and it is not optional.** It is registered on
 * `GameLifecycle.LateCycle`, exactly as [ImplingSpawner] is, and without that line a teased
 * creature follows its hunter across the world forever and every catch stays stuck mid-collapse -
 * with every unit test in this module still green, because they all drive the hook by hand. See
 * [tick] for why nothing else in the engine will stop a chase, and [landCollapses] for why the
 * landing is a cycle count rather than a queue.
 *
 * The player's own vault across the pit is still not modelled: that is an `exactMove` off the
 * clicked loc's angle, and [jumpPit] resolves the catch without moving anybody.
 *
 * @see PitState for what each varbit value renders as, and which ops the cache declares on it.
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
     * Which creature is chasing which player, keyed by npc **identity**.
     *
     * The engine already knows *that* a creature is following someone - `NpcMode.PlayerFollow` plus
     * the npc's `faceEntity` is the whole of it - so this map exists for the one fact the engine
     * does not keep: which player's tease started the chase, as a [PlayerUid] rather than a slot.
     * A slot is reused the moment its player logs out, and the catch this chase is heading towards
     * has to pay the hunter who teased the creature rather than whoever inherited their slot.
     *
     * Not a repository, not persisted, and not on the npc. A restart takes it with it, which is
     * right: a chase is a thing in flight, and a creature that respawns has nobody to chase.
     *
     * Entries are added by [teaseCreature] and removed by [stopChasing], which is the call the
     * catch belongs on, and by [tick] for a chase that ended some other way. The map is in
     * any case bounded by the number of pitfall creatures the map spawns - a few dozen - rather
     * than growing without limit. Identity keying, and the reasoning behind it, is `FalconLinks`'.
     */
    private val chases = IdentityHashMap<Npc, PlayerUid>()

    /**
     * The one pit each creature will not jump into next, keyed by npc **identity**.
     *
     * "Since these creatures will not jump the same pit twice in a row, the next attempt must be at
     * another pit." (wiki, *Pitfall*, oldid=15201220). Read that literally: it is **one** pit per
     * creature, replaced by the next pit it vaults, and **not** a set that accumulates. A creature
     * that jumps over pit A refuses pit A on the very next attempt and is catchable by pit A again
     * as soon as any other pit has intervened. A set would quietly retire the whole hunting ground
     * one pit at a time, which no source describes.
     *
     * An entry is written by a failed [jumpPit] and overwritten by the next failure elsewhere. It
     * is dropped **with the chase it belongs to**: [stopChasing] clears it, and so does the branch
     * of [tick] that handles a creature the world has already taken away. That is what keeps
     * this bounded and what gives a respawn a clean slate, and it has to be the chase rather than
     * the npc's disappearance alone - a chase ended by the leash leaves the creature in the world,
     * and an entry that only [tick] could clear would then never be looked at again, because
     * [tick] iterates [chases]. The refusal is a fact about one lure; a hunter who walks the
     * creature back and starts a new one is making a fresh first attempt.
     *
     * Deliberately **not** cleared by a tease that continues an existing chase: a hunter who
     * re-teases the creature they just failed to catch must still be sent to another pit, or the
     * rule means nothing. A re-tease does not go through [stopChasing], so that reading survives.
     */
    private val lastVaulted = IdentityHashMap<Npc, PitfallSite>()

    /**
     * Every catch that is still falling, and every catch that has landed and not been collected.
     *
     * This is the pit's [PitState.Catching] frame given a length, and it is the only state in this
     * feature that is **not** the player's varbit. It is deliberately transient - a restart takes
     * it, and the varbit it wrote survives, which is the right way round for a ledger whose whole
     * job is to say what is happening *now*. [Collapse] carries what the varbit has no room for and
     * nothing else.
     *
     * A list rather than a map because a pit can hold more than one: see [Collapse] for the
     * documented two-creature window that requires it, and [collectPit] for what an uncollected
     * second entry does to a dismantle.
     */
    private val collapses = ArrayList<Collapse>()

    /**
     * How many creatures have gone into each pit since the log that armed it, keyed by the owning
     * player and the site.
     *
     * [PIT_CAPACITY] bounds **one arming**, not how many catches are sitting in the pit
     * uncollected, and those stop being the same number the moment a hunter collects the first of
     * two. Counting the ledger instead let one pit take a third creature - and a fourth, and a
     * fifth - on the single log [trapPit] spent: collecting creature A frees a place while creature
     * B is still in the air, [takeCatch] correctly leaves such a pit at [PitState.Catching], and
     * that is a state [jumpPit] accepts. The chain repeats for as long as one catch is still
     * falling.
     *
     * Transient, and deliberately not persisted. It is a fact about the log currently in the pit,
     * and a pit's whole saved state is the one 3-bit varbit [PitState] enumerates - there is no
     * companion field, and authoring twenty-five server-side varps to record a count live gives no
     * sign of keeping is the trade [Collapse] already refuses for the second catch itself. A
     * restart therefore hands every pit a fresh arming, which is worth at most one extra creature
     * to a player who was mid-window when it happened.
     *
     * An entry is written by a catch in [jumpPit] and dropped by [setPitState] the moment the pit
     * is armed again or returns to [PitState.Empty] - the two writes that end an arming - so it
     * lives only while a pit is holding something. [tick] drops what a logout leaves behind, as it
     * does for [collapses], because a [PlayerUid] does not survive its player.
     */
    private val armedCatches = HashMap<Arming, Int>()

    /**
     * `Trap` on an empty pit: one log, and the pit becomes a spiked pit.
     *
     * "With the required Hunter level, a knife or fletching knife and logs in the inventory,
     * clicking on a pit will set the trap." (wiki, *Pitfall*, oldid=15201220). The knife is only a
     * tool and is kept; exactly one log is consumed.
     *
     * Everything is verified before anything is consumed. There is no delay in this op, so nothing
     * can change between the checks and the delete - the deadfall's careful "charge past the last
     * refusal, after the delay" dance has nothing to protect against here.
     *
     * The refusal strings are ours. Live's are server-sent and not recoverable offline, and a
     * refusal with no message is a click that does nothing for no stated reason. There is
     * deliberately no message on **success**: the pit visibly becomes a spiked pit, which says it.
     *
     * @return false, with a message already sent, if the pit was not set.
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
     * `Dismantle` on a set or a full pit.
     *
     * The cache declares this op on three of the five states, and they mean two different things:
     * - **[PitState.Set]** takes an armed pit back apart. Nothing is awarded and nothing is handed
     *   back - the log is spent. That differs from [HunterTrap]'s deadfall, which hands back the
     *   log it was armed with, and the difference is structural rather than a guess about the live
     *   game: the deadfall can refund because it remembers - it stores the exact log's obj id in
     *   its own controller (`trapDeadfallLog`), so it hands back precisely what it took. A pitfall
     *   has no such memory. Its entire per-site state is the single 3-bit `hunt_pitfall_state<n>`
     *   varbit [PitState] enumerates, and the cache defines no companion log varbit - all
     *   twenty-five `hunt_pitfall_state<n>` records were checked, and there is no
     *   `hunt_pitfall_log<n>` alongside any of them. A faithful refund would mean authoring
     *   twenty-five new server-side varps to record state the live game does not model, and the
     *   cheap alternative - handing back a generic `obj.logs` regardless of what was spent - would
     *   be an item *transmute*: a player whose only logs were magic would get normal logs back.
     * - **[PitState.Full] / [PitState.FullRotated]** are the same catch facing opposite ways. Both
     *   hand over the creature's loot and its experience.
     *
     * [PitState.Catching] is refused. The cache gives that state no ops at all, so no click can
     * reach it; the choice matters only if some later path ever calls this with a pit mid-collapse.
     * Emptying it would destroy a catch that is still landing and paying out would mint one that
     * has not landed, so it does neither and leaves the pit exactly as it found it. The one thing
     * that really strands a pit there is a logout mid-collapse, and [rebuildPits] resolves that on
     * the way back in.
     *
     * @return false, with a message already sent where one is warranted, if nothing was dismantled.
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
     * Hands over what a full pit holds, then returns it to an empty pit - or leaves it full, if a
     * second creature went in behind the first.
     *
     * Room is checked for the **whole** catch before any of it is awarded, and the pit is left full
     * if there is not enough: a catch that half-landed and spilled the rest on the floor puts a
     * rare fur under a despawn timer, where a refused one is still in the pit when a slot is freed.
     * This is falconry's rule and the crab trap's, in the same words.
     *
     * **One dismantle pays for one creature.** "It is possible, if acting quickly, to lure one
     * creature into a trap and tease a second one into the same trap as the first is still walking
     * over it, netting two kills for one trap ... Dismantle the results of creature A falling.
     * Dismantle the results of creature B falling." (wiki, *Pitfall*, oldid=15201220). So a pit
     * that took two catches is dismantled twice, and what the pit is left showing is [takeCatch]'s
     * whole subject: full while a sibling has landed, still **collapsing** while one is in the air,
     * and empty only when nothing of this player's is left in [collapses] for this site. A pit that
     * is full with nothing in the ledger - which is what a relog leaves, since the ledger is
     * transient and the varbit is not - pays once and empties, exactly as it did before the window
     * existed.
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
     * Removes one landed catch from [site]'s ledger and returns the state that leaves the pit in.
     *
     * ## The invariant
     *
     * **A pit's varbit must be able to hold every entry [collapses] still has for it.** The ledger
     * and the varbit are two halves of one fact, and the varbit is the half that says where a
     * catch can go: a [PitState.Catching] pit is one a landing may be written to, a full one is a
     * pit a sibling may queue behind, and an empty one is a pit nothing may land on at all - which
     * is why [landCollapses] drops an entry that finds one. So [PitState.Empty] is only ever
     * correct when this player has **nothing** left here.
     *
     * This method used to read only the entries that had already **landed**, and so wrote
     * [PitState.Empty] over a pit a sibling was still falling into. That broke the invariant in
     * both directions: the in-flight creature landed on an empty pit and was dropped unpaid, and a
     * player who re-armed the pit and caught a third had the abandoned entry land behind it, ending
     * with two landed catches for one creature - the same mint-from-nothing [clearPits] was fixed
     * for, on a path nothing guarded.
     *
     * ## The order
     *
     * Entries are considered in the order they went in, which is the order they land in, since they
     * all count down at the same rate. The next **landed** one wins if there is one, so a second
     * catch that has already come to rest keeps the pit full at its own rotation and is collected
     * next. Otherwise anything still in the air holds the pit at [PitState.Catching] so it has
     * somewhere to land - and a landing on an already-full pit is a no-op, so preferring the landed
     * entry costs the in-flight one nothing.
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
     * How many creatures this player's copy of [site] is holding, landed or still falling.
     *
     * The ledger's own count, and **not** what [PIT_CAPACITY] is counted against - see
     * [catchesThisArming] for the two-creature pit that made those different numbers. [rebuildPits]
     * is the only caller: a site with a live entry behind it is one a landing is still counting
     * down to, so it is not a site a logout stranded.
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
     * `Tease` on a pitfall creature: the only thing that makes one hostile, and the whole of the
     * setup for a catch.
     *
     * "With a teasing stick in the inventory or a hunter's spear equipped, the player has to tease
     * the creature and then jump over the spiked pit." (wiki, *Pitfall*, oldid=15201220). None of
     * the five creatures carries an `Attack` op in the cache - `op1=Tease` is the only op any of
     * them declares - so there is no other way to get one to follow you.
     *
     * ## The chase is the engine's own, not a follow loop
     *
     * Three lines, in the order [org.rsmod.game.entity.Npc.playerFace] and its two siblings write
     * them, because `NpcMode.PlayerFollow` is exactly this mechanic and needs nothing else:
     * `NpcPlayerFollowModeProcessor` runs every cycle, reads the target back out of the npc's own
     * `faceEntity`, and re-issues a route towards it. There is deliberately no `walkTo` and no
     * per-cycle timer here.
     *
     * `PlayerFollow` was chosen over the combat chase (`opPlayer2`) that every aggressive npc in
     * this codebase uses, for two reasons. Its leash is too short: `NpcInteractionProcessor`
     * cancels an `opPlayer2` interaction once the target is further than `maxRange + attackRange`
     * from the npc's **spawn** tile, which is eight tiles on these creatures' packed defaults -
     * far less than the run from a creature to its pit. And `opPlayer2` is a request to *attack*,
     * which is not what a tease is; see the retaliation note below.
     *
     * The price of that choice is that `PlayerFollow` has **no** leash at all, and the engine will
     * not end this chase on its own. [tick] is where the leash is put back, and it is a
     * prerequisite of this method rather than a refinement of it.
     *
     * The one thing `PlayerFollow` will not survive is a facing lock: `Npc.facePlayer` is a no-op
     * while `isFacingLocked`, which would leave the mode set with no target for the processor to
     * find and reset on the next cycle. That is true of `playerFace`, `playerFaceClose` and
     * `playerEscape` too, and nothing in this feature locks a creature's facing.
     *
     * ## The hunter's spear's +5% is deliberately not implemented
     *
     * "When using hunter's spears, they give a 5% increased chance to successfully tease creatures;
     * this does not use up the spear." (wiki, *Pitfall*, oldid=15201220), and the Jagex newspost
     * that page's sibling cites agrees it is the tease: "they'll give you an increased chance to
     * successfully tease creatures like Kyatt, Ghaark and Larupia by 5%" (*Varlamore: Part One -
     * Overview*, 20 January 2024, quoted at wiki, *Hunter's spear*, oldid=15264550).
     *
     * That is a **relative** modifier on a base tease rate that is published nowhere, and no source
     * describes a failed tease at all - the only failure the *Pitfall* page documents is the
     * creature jumping the pit afterwards, which is the catch and not the tease. So the tease is
     * modelled as certain, and a 5% increase on a certainty has nothing to modify. Shipping any
     * other base rate would mean inventing the number the bonus is a percentage of.
     *
     * Two consequences, both intentional. The spear is accepted as a tool and confers no mechanical
     * advantage, which `HunterPitfallTest` pins so it cannot drift into a silent difference. And
     * the bonus must **not** be moved onto the catch instead: the *Hunter's spear* page's own prose
     * reads it as "increased chance for teased creatures to walk into a pitfall trap", which
     * contradicts both the *Pitfall* page and the newspost it is itself citing. Whoever measures a
     * real base tease rate should add it here, not there.
     *
     * ## Retaliation is blocked on a chase that can end, not merely deferred
     *
     * "These creatures are able to attack the player with melee after teasing them, with a max hit
     * of 5-7 damage depending on the creature", reducible with hunter gear and negated by Protect
     * from Melee (wiki, *Pitfall*, oldid=15201220). A teased creature here follows and does not
     * hit.
     *
     * That is not just a scoping call. **The player cannot fight back at all**: `config/npc` 2908
     * and its four siblings declare `op1=Tease` and no `Attack` op, so there is no click that puts
     * a teased creature into combat and no way to kill one to be rid of it. A creature that hits
     * for 5-7 every few cycles, on top of a chase with nothing to end it, would be neither
     * escapable nor killable - a hunter's only way out would be to log. So a **chase-termination
     * rule is a prerequisite of retaliation, not a sibling of it**, and [tick] is that rule.
     * Whoever builds the attack half inherits a bound already in place and must not widen it.
     *
     * The rest of that half is real combat wiring - an attack mode, a max hit per creature, a
     * damage reduction that reads worn hunter gear, and a prayer interaction - and none of it is
     * this task's. The creatures do carry the stats it would need (`attack`, `strength`,
     * `hitpoints`, `defence` are all packed on all five), so the data is there when somebody
     * builds it.
     *
     * ## No delay
     *
     * `suspend` so this composes with the suspending op handler a later task registers it in, but
     * there is no suspension point: the tease animation is fired and the player is free the same
     * cycle. A lock of even one cycle would break the *Pitfall* page's own "quickly tease nearby
     * creature B ... while creature A is still walking over it" procedure.
     *
     * @return false, with a message already sent where the player could have done something about
     *   it, if nothing was teased.
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
     * `Jump` on a spiked pit: vault it, and whatever is chasing you may go in.
     *
     * "With a teasing stick in the inventory or a hunter's spear equipped, the player has to tease
     * the creature and then jump over the spiked pit. When the creature passes the trap, it may be
     * caught." and "If the prey is successfully caught, the trap will collapse and the creature
     * will fall into the pit ... If not successful, the creature will jump over the trap and the
     * player has to lure it again." (wiki, *Pitfall*, oldid=15201220).
     *
     * So the whole of this op is: is anything of this pit's own kind chasing *this* player and
     * close enough to be crossing, has the log that armed the pit any of its [PIT_CAPACITY] catches
     * left, will it go in, and if it does, which of the two collapsed renderings the pit ends on.
     * A jump with nothing behind you is just a jump.
     *
     * ## Which creature
     *
     * [chases] is filtered by three things, and each one is a bug it prevents:
     * - **the teaser is this player**, so jumping your own pit cannot take somebody else's lure.
     *   That is the fact [chases] exists to keep, as a [PlayerUid] rather than a slot;
     * - **the species is the pit's own**, so a graahk that wandered over a larupia pit cannot be
     *   collected as larupia fur and meat. The five hunting grounds do not overlap, so this cannot
     *   happen by walking - but nothing stops a later task from teasing across one;
     * - **it is within [CATCH_RANGE] of the pit**, which is what "passes the trap" reduces to.
     *
     * The nearest qualifying creature is taken, so a hunter with two on their heels catches the one
     * actually crossing rather than whichever the map happens to iterate first.
     *
     * The player's **own** position is not checked, deliberately. The op layer resolves a click to
     * a loc and walks the player onto it before this runs, exactly as every other hunter op does,
     * and a second distance check here would only disagree with the pathing that put them there.
     *
     * ## The roll, and the two creatures that do not get one
     *
     * [PitfallCreature.successLow] and [PitfallCreature.successHigh] are null for both antelopes,
     * because their catch is documented as certain - "players will **always succeed** in hunting
     * sunlight antelopes" (wiki, *Sunlight antelope*, oldid=15240378), and the same sentence for
     * the moonlight antelope. A null pair therefore means **no draw is taken at all**, not a rate
     * that always wins: the two are indistinguishable in outcome and completely different in
     * meaning, and only the first one is what the source says. `HunterPitfallTest` counts the draws
     * to keep it that way. The three cats roll [SkillingSuccessRate], the same curve every other
     * technique in this module rolls, against pairs [PitfallCreature] documents as derived rather
     * than published.
     *
     * **The hunter's spear's +5% is not applied here, and must not be moved here.** It modifies the
     * *tease*: "they give a 5% increased chance to successfully tease creatures" (wiki, *Pitfall*,
     * oldid=15201220), agreeing with the Jagex newspost that page cites. The *Hunter's spear* page
     * reads the same bonus onto the catch and contradicts both. See [teaseCreature] for the whole
     * argument and for why the tease implements no bonus either; `HunterPitfallTest` pins the
     * spear's absence from this roll so the two pages' disagreement cannot be silently resolved the
     * wrong way by a later edit.
     *
     * ## What a miss costs, and what a catch costs
     *
     * A miss leaves the pit armed and the creature chasing - it has to be lured again - and records
     * the pit in [lastVaulted] so the next attempt has to be at another one. A catch stops the
     * chase, kills the creature by [NpcRepository.despawn] on its own packed respawn timer, and
     * puts the pit into [PitState.Catching] with a [Collapse] to land it.
     *
     * There is deliberately no message on a catch. Live sends one - the 18 June 2020 update made
     * "chat messages about catching large animals in pit traps" filterable, so a string exists -
     * but its text is not recoverable offline, and the pit collapsing in front of the player says
     * it. The three refusal strings below are ours.
     *
     * @return true only if a creature was caught. A vault over an armed pit with nothing behind it
     *   is a legal, silent false, as is a jump that lands on a state no `Jump` op can reach.
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
     * One cycle of everything this feature does on a clock. Register it, or none of it happens.
     *
     * Two unrelated halves, which is why the name is a neutral one rather than either of theirs:
     * - **the chase leash**, below, which is the only thing in the server that will ever end a
     *   teased creature's pursuit;
     * - **[landCollapses]**, where a pit [jumpPit] set collapsing finishes doing so and becomes a
     *   catch the player can dismantle.
     *
     * They share the hook rather than each getting one because a feature that needs a cycle needs
     * it once: whoever registers this for the leash gets the landing with it, and neither half can
     * be wired without the other. [ImplingSpawner.tick] is the module's precedent for a per-cycle
     * hook that advances several things under one neutral name.
     *
     * ## Why this has to exist
     *
     * `NpcMode.PlayerFollow` is unbounded. `NpcModeProcessor` gives it no timeout, no leash and no
     * give-up condition, and `NpcPlayerFollowModeProcessor` **teleports the creature onto the
     * player's own tile** the moment the gap passes its `VALID_DISTANCE` of 15. Without this hook a
     * hunter teases a larupia in Feldip Hills, walks to Varrock, and arrives with a size-2 cat
     * standing on top of them that nothing but a logout will shake off. That is the engine's
     * behaviour, not a bug in it - `PlayerFollow` is what a pet does - so the bound belongs here.
     *
     * Three things end a chase, and the two that are not [stopChasing] are both this method's:
     * - **The teaser is gone.** The slot *and* the [PlayerUid] are checked, because a slot is
     *   reused the moment its player logs out and the slot alone would silently hand the chase to
     *   whoever inherited it. This is the fact [chases] exists to keep.
     * - **The creature has strayed past [CHASE_RANGE] tiles from its spawn tile.**
     *
     * A creature the world has already taken away is dropped without being written to: a mode set
     * on a slotless npc is a mode no processor will ever read again.
     *
     * ## Shape: a leash, not a stopwatch
     *
     * Spawn-anchored distance, matching the engine's own `opPlayer2` leash
     * (`NpcInteractionProcessor` gates on `spawnCoords.chebyshevDistance(target) <= maxRange +
     * attackRange`) rather than a cycle count. A cycle cap would punish the slow hunter and the
     * one who stops to fight a wandering aggressor, and would end a chase that is still going
     * exactly where it should; distance ends only the chase that has left the hunting ground. The
     * creature's **own** tile is measured rather than the player's, which is the same thing in
     * practice - the follow processor keeps it within 15 tiles or teleports it - and reads as what
     * it is: how far this creature has got from home.
     *
     * There is deliberately no cap on a chase that never leaves the hunting ground. A hunter who
     * keeps a creature circling its own pits is doing the technique, not abusing it.
     */
    fun tick() {
        if (chases.isNotEmpty()) {
            // Collected before anything is ended, because `stopChasing` writes the map being read.
            val ended = chases.entries.filterNot { (npc, teaser) -> chaseContinues(npc, teaser) }
            for ((npc, _) in ended) {
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
     * The other half of the cycle hook: pits finish collapsing, and stale ledger entries go.
     *
     * ## Why a cycle count and not a queue
     *
     * The crab trap defers its catch with `player.softQueue(CRAB_CATCH_QUEUE, ...)`, and this is
     * the same shape of problem, so the queue is the idiom to reach for first. The collapse rides
     * the per-cycle hook instead, and the reason is not that a queue was unavailable - this module
     * now declares [PITFALL_REBUILD_QUEUE] and could have declared a second. It is that the
     * collapse has nowhere else to be: [tick] has to exist anyway for the chase leash, a landing
     * costs it one list walk, and putting the two on one hook makes the pair impossible to
     * half-wire. Whoever registers [tick] for the leash gets the landing with it, whereas a queue
     * would have been a second registration that could go missing on its own.
     *
     * ## Where a collapse can land
     *
     * - **[PitState.Catching]** is the ordinary case: the pit finishes collapsing and shows the
     *   corpse, at whichever of the two rotations [jumpPit] recorded.
     * - **[PitState.Full] or [PitState.FullRotated]** means a sibling from the same window has
     *   already landed. The varbit is left exactly as it is and this entry simply stays in the
     *   ledger as a second, uncollected catch; see [Collapse] and [collectPit].
     * - **anything else** means the pit no longer has room for this catch, so the entry is dropped
     *   rather than writing a catch over whatever the player has since done to the pit. The crab
     *   trap's matured-catch-on-an-unbaited-trap branch makes the same call.
     *
     * **The last branch is a backstop, and it was not always one.** It used to be reachable - and
     * destructive - by ordinary play: a dismantle taken while a sibling was still in the air wrote
     * the pit [PitState.Empty], and the creature still falling into it arrived here and was thrown
     * away unpaid. [takeCatch] is where that was fixed, by leaving such a pit [PitState.Catching].
     * With that done, no routine path reaches this branch: [clearPits] takes its own entries with
     * it, and every other write to the varbit either leaves room for what is pending or has nothing
     * pending to leave room for. It stays because it is the one place positioned to notice if some
     * later path breaks that agreement again, and dropping a catch is a smaller failure than
     * stamping one over a pit the player has since re-armed.
     *
     * A landed entry stays until [collectPit] takes it, and is dropped here once its owner is no
     * longer in the world or its pit no longer holds a catch. That is what bounds this list: a
     * player who logs out mid-collapse leaves nothing behind but the varbit, and a varbit stranded
     * in [PitState.Catching] is what a login rebuild reads back - the whole of a pit's persistent
     * state is that one value, which is why nothing here needs saving.
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
     * Ends [npc]'s chase and hands it back to whatever it does when nobody is teasing it.
     *
     * The call the catch belongs on: a creature that has gone into a pit must stop following, and
     * the record of who teased it is what says whose catch it is. `defaultMode` rather than
     * `resetMode` so the creature is explicitly returned to its packed default rather than left on
     * a null mode for the processor to fill in.
     *
     * **[lastVaulted] goes with the chase**, and that is this method's business rather than the
     * caller's, because this is the one call every ending goes through. A refusal outlives the lure
     * that earned it otherwise: a chase ended by the leash leaves the creature standing in the
     * world with a pit it will not go near, and nothing would ever clear it - [tick] only
     * looks at creatures still in [chases], which this has just taken it out of.
     *
     * [tick] is the other caller, for the two ways a chase ends that nobody clicks.
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
     * Returns every one of this player's twenty-five pits to [PitState.Empty].
     *
     * **Test-only, and deliberately left that way.** Nothing in [PitfallEvents] or anywhere else in
     * the server calls it: the recovery it was once described as - the way back out of a pit
     * stranded in [PitState.Catching] - has a real, wired owner in [rebuildPits], which runs off
     * the login queue, and no source describes a "reset my traps" action to hang a second one on.
     * What it is genuinely for is the suites, which need a hunter's fifty-first catch to start from
     * an empty pit without going through the op under test.
     *
     * It writes every site rather than only the non-empty ones because writing [PitState.Empty]
     * over [PitState.Empty] costs nothing and a filtered pass would need the read anyway.
     *
     * The player's half of [collapses] goes with the varbits. A catch left in the ledger after its
     * pit had been emptied would land - or be collected - on a pit that no longer holds anything,
     * which is the one way this feature could mint a creature out of nothing. Their half of
     * [armedCatches] goes with them through [setPitState], since an emptied pit has no log in it.
     */
    fun clearPits(player: Player) {
        for (site in PitfallSites.all) {
            setPitState(player, site, PitState.Empty)
        }
        collapses.removeAll { it.owner == player.uid }
    }

    /**
     * Finishes any collapse a logout stranded, on the way back in.
     *
     * The body of [PITFALL_REBUILD_QUEUE], armed one cycle after login.
     *
     * ## The state this resolves
     *
     * A pit's persistent state is one three-bit varbit and nothing else; [collapses] is in memory
     * and goes with the session. So a player who logs out in the five cycles between [jumpPit] and
     * the landing comes back to a pit reading [PitState.Catching] with no ledger entry behind it,
     * and **nothing in the feature will ever move it again**: [landCollapses] only walks entries
     * that exist, [dismantlePit] deliberately refuses that state, and [trapPit] refuses it too. The
     * pit is bricked - it counts against the trap cap and shows the collapsing frame forever, and
     * no click a player has reaches it. This is the way out, and it is the only one in the game:
     * [clearPits] has no caller outside the test suites.
     *
     * ## Why it finishes the catch rather than returning the pit to [PitState.Set]
     *
     * **Because the creature is already dead.** [PitState.Catching] is written in exactly one
     * place, and by then [jumpPit] has already rolled the catch, despawned the npc and ended the
     * chase. The catch is not pending in any sense the server can still take back; only its
     * *rendering* is unfinished. Writing [PitState.Full] pays out one creature for one creature
     * that really went in, so nothing is minted: a player cannot reach this state without a
     * successful catch having happened.
     *
     * The two alternatives both destroy something the player earned. [PitState.Set] would throw
     * the catch away and leave the pit armed, which reads as generous and is not - the creature is
     * gone from the world either way, so the player is down a catch and up nothing.
     * [PitState.Empty] would throw the catch *and* the log away. This is the same direction the rest of the slice
     * errs in, and the one place it is worth restating: two catches taken in the two-creature
     * window and left across a relog still come back as **one**, because the varbit has no room
     * for the second. Loss, never duplication.
     *
     * The rotation is not recoverable - it lived in the [Collapse] the logout took with it - so
     * every rebuilt pit lands on [PitState.Full] rather than [PitState.FullRotated]. That is
     * cosmetic: the two are the same catch facing opposite ways and [collectPit] pays them
     * identically.
     *
     * A site with a live ledger entry is left alone, so a rebuild can never race a landing that is
     * still counting down. In practice there is none - [landCollapses] drops a departed owner's
     * entries - but the guard costs one lookup and removes the need to reason about it.
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
     * Writes the site's own `hunt_pitfall_state<n>` varbit, which is the entire server side of a
     * pit.
     *
     * [VarPlayerIntMapSetter] by **name** rather than an `intVarBit` delegate, for the reason
     * [HunterCrabTrap] gives: the varbit belongs to the site and is resolved at runtime, so
     * twenty-five sites would otherwise be twenty-five delegates that could each be paired with the
     * wrong pit - and because naming it is what keeps the bit-layout hole at site 19 out of this
     * file entirely.
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
     * One creature falling into one player's copy of one pit.
     *
     * The three things the varbit cannot hold, and nothing else:
     * - **whose pit it is.** A [PlayerUid] rather than a [Player], so a landing cannot be written
     *   to a stranger who inherited the slot; `PlayerUid.resolve` is the lookup [tick] uses.
     * - **which of the two collapsed rotations to draw.** See [crossedFromSouthWest].
     * - **how far through the collapse it is.** [cyclesLeft] counts down to the landing and stops
     *   at zero, where the entry becomes the pit's record of an uncollected catch.
     *
     * ## Why this is a list entry and not a flag on the pit
     *
     * "It is possible, if acting quickly, to lure one creature into a trap and tease a second one
     * into the same trap as the first is still walking over it, netting two kills for one trap."
     * (wiki, *Pitfall*, oldid=15201220). That is a documented technique with a four-step procedure,
     * not a bug, so a pit must accept a second creature while the first is still crossing - which
     * rules out the obvious implementation, a per-pit lock naming the creature that is heading into
     * it. Two things bound the window instead. [COLLAPSE_CYCLES] shuts it in time: the pit is in
     * [PitState.Catching] only until the first catch lands, and a jump on a pit that has finished
     * collapsing catches nothing. [PIT_CAPACITY] shuts it by count, at the two per log the wiki
     * describes, because a hunter with a third creature pre-teased could otherwise fit it inside
     * the same frame - see that constant for why the count is the bound worth having.
     *
     * **The second catch is worth its own dismantle, and that half is the divergence to know
     * about.** A pit's persisted state is a single three-bit varbit with five defined values and no
     * companion, so "this pit holds two" is not expressible in anything that survives a logout. It
     * lives here instead, which means two catches taken in the window and left uncollected across a
     * relog come back as one. Recording it in the save would mean authoring twenty-five new
     * server-side varps for a state the live game gives no sign of keeping, which is a worse trade
     * than losing the second of two catches that a player is expected to collect seconds later.
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
         * How far a teased creature will follow before [tick] takes it off the leash: **64
         * tiles, Chebyshev, from the tile it spawned on**.
         *
         * **This number is chosen, not sourced.** No wiki page, cache field or newspost gives a
         * give-up range for a teased pitfall creature, and none describes one giving up at all -
         * live may well have no such rule, because live's teased creature can be fought and killed
         * and ours cannot. So it is derived from the authored data instead: whatever bound ships
         * must sit clear of every run a legitimate lure asks for.
         *
         * Two figures were measured off [PitfallSites] and the twenty-eight creature spawns in
         * `.data/raw-cache/map/npcs/` (larupia `feldip_hills`, graahk `karamja`, kyatt
         * `rellekka_cold_water`, sunlight `varlamore`, moonlight `hunter_guild`), as Chebyshev
         * tiles from a spawn to a pit of that creature's **own** kind:
         * - to the **nearest** such pit, worst case per creature: larupia 2, graahk 4, kyatt 7,
         *   sunlight **8**, moonlight 3. That is the run the technique asks for when the hunter
         *   sets the pit next to them, and it is tiny.
         * - to the **farthest** such pit, worst case per creature: moonlight 9, sunlight 17, graahk
         *   19, larupia 30, kyatt **41**. That is the run the technique asks for when it does not:
         *   the six kyatt spawns and six kyatt pits fall into two clusters a map apart, so a hunter
         *   whose only set pit is in the far cluster (spawn 2696,3790 to site 5 at 2737,3784)
         *   legitimately leads one 41 tiles.
         *
         * 64 is that worst legitimate run plus 23 tiles - one map square, and better than half as
         * much headroom again. Below about 45 a real lure would start breaking; a hunter would see
         * their kyatt turn round halfway and would have no idea why. Above a few hundred the bound
         * stops being a bound. Between those, the exact figure does not matter much, so it is the
         * round one.
         *
         * Two sanity checks on the scale. It is eight times the leash `opPlayer2` would have given
         * these creatures (`maxRange + attackRange` = 8), which is why that mode was not used. And
         * it is four times `NpcPlayerFollowModeProcessor.VALID_DISTANCE`, so the whole legitimate
         * range still sits inside the engine's teleport-to-target behaviour rather than changing
         * how a chase feels.
         */
        private const val CHASE_RANGE: Int = 64

        /**
         * How close a chasing creature has to be to the pit to be crossing it: **3 tiles,
         * Chebyshev, from the site's own tile**.
         *
         * **Chosen, not sourced.** The wiki says only "when the creature passes the trap", and no
         * page, cache field or newspost puts a number on "passes". Both reference servers landed on
         * the same figure from opposite directions, which is the closest thing to corroboration
         * available: void requires the creature to be within 3 tiles of a staging tile two tiles
         * off the pit before it will walk it over, and 2009scape requires the teased creature to be
         * within 3 tiles of the player, who is standing on the pit.
         *
         * Measured from the pit rather than from the player because the pit is what has to be
         * crossed, and because it is the one of the two that cannot move between the click and this
         * call. A pit is a 2x2 loc and [PitfallSite.coords] is its south-west tile, so a creature
         * standing off its far corner is 3 from the tile this measures and 2 from the loc.
         */
        private const val CATCH_RANGE: Int = 3

        /**
         * How long a pit stays in [PitState.Catching] before the corpse appears: **5 cycles**, or
         * three seconds.
         *
         * **Chosen, not sourced**, and it is the width of the two-creature window rather than a
         * piece of animation timing - which is why it is not read off [PitfallCreature.leapSeq].
         * The wiki documents the window as real and gates it on the player "acting quickly", so the
         * number has to be long enough for a human to click a second creature and then the pit
         * again - two clicks, which is a second and a half at a comfortable pace - and short enough
         * that a hunter who is not doing the trick never notices the pit spent any time collapsing.
         * Three seconds sits between those with room on both sides.
         *
         * Neither reference server offers a figure to borrow: void's 100 ticks and 2009scape's two
         * minutes are both trap *expiry* timers - how long a set pit lasts unattended - which is a
         * different mechanic that this branch does not model at all.
         */
        private const val COLLAPSE_CYCLES: Int = 5

        /**
         * How many creatures **one arming** buys: **two**.
         *
         * The window is bounded by a count as well as by [COLLAPSE_CYCLES], and the count is the
         * bound that is **sourced**. The wiki describes the technique as a two-for-one and spells
         * it out as a four-step procedure naming exactly two creatures - "lure one creature into a
         * trap and tease a second one into the same trap ... netting two kills for one trap" (wiki,
         * *Pitfall*, oldid=15201220) - and no page, newspost or reference server describes a third.
         *
         * **"Two kills for one trap" is read as two per log**, which is why the count lives in
         * [armedCatches] and is read through [catchesThisArming]. Counting the pit's uncollected
         * catches instead is the obvious alternative and it does not hold: collecting the first of
         * two frees a place while the second is still falling, on a pit [takeCatch] correctly
         * leaves at [PitState.Catching] and [jumpPit] therefore still accepts - so one log bought a
         * third creature, and a fourth behind that, for as long as the hunter kept one in the air.
         * `HunterPitfallTest` walks that sequence.
         *
         * Without a count of some kind the ceiling would be whatever [COLLAPSE_CYCLES] happens to
         * allow: a hunter with creatures pre-teased can put a third into the same collapse, and how
         * many is a function of a constant that is admittedly *chosen for feel*. That makes "how
         * many creatures one log buys" a number nobody decided, and one that a later tweak to the
         * collapse timing would silently change. Two is the number the source gives, so two is the
         * number that ships, and lengthening the collapse cannot move it.
         *
         * The refusal is a message rather than a silent false, per this file's rule - though the
         * only remedy it leaves the hunter is another log: collecting what the pit already holds
         * frees nothing, which is the whole point of counting the arming.
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
