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
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.table.FiremakingLogsRow
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.PlayerUid

/**
 * Pitfall trapping: the two halves a player performs by hand - setting a pit, and taking one apart.
 *
 * **Structurally a crab trap, not a trap.** Like [HunterCrabTrap], and unlike the five families
 * [HunterTrap] runs, a pitfall is not an object in the world at all: the map places a
 * `hunting_pitfall_<n>` loc carrying a `multivar`, and the client draws whichever of that loc's
 * `multiloc` children the *viewing player's* `hunt_pitfall_state<n>` varbit selects. So the
 * server's whole job is to write a varbit, and:
 * - **`locRepo` is never touched.** Not `del`, not `change`, not `add`. This class holds no loc,
 *   controller, npc or obj repository, so the invariant the deadfall and net trap need a runtime
 *   `check` to enforce - never delete a permanent map loc - is unrepresentable here rather than
 *   merely guarded. A `del` on a pit would take it out of the world for *every* player until the
 *   next restart.
 * - **Never a computed bit offset.** Every write goes through [VarPlayerIntMapSetter] by the site's
 *   own gameval name and lets the cache place the bits, because the layout has a hole: site 18 ends
 *   at bit 23 of `varp.hunt_pitfall_states_basevar2` and site 19 starts at bit **25**. See
 *   [PitfallSite].
 * - **Pits are private.** Two players setting the same pit do not contend, so there is no owner to
 *   record, no controller to anchor and no tile to key anything on.
 * - **No random draw.** Neither half of this class rolls anything: the catch roll belongs to the
 *   creature that walks into the pit, and every reward line in [PitfallCreatures] is a flat
 *   quantity, so no [org.rsmod.api.random.GameRandom] is injected. `HunterPitfallTest` pins that
 *   flatness, so a ranged line added later fails there rather than silently awarding its minimum.
 *
 * ## What this class is not, yet
 *
 * Jumping the pit, the roll that decides a catch, the [PitState.Catching] frame and the transition
 * into [PitState.Full] are a later task's, as is registering any of this against a loc or npc op.
 * Nothing in this file is wired to a click.
 *
 * **[tickChases] is the one thing here that is not an op, and it is not optional.** It needs a
 * `GameLifecycle.LateCycle` registration - `onEvent<GameLifecycle.LateCycle> { pitfall.tickChases()
 * }`, exactly as [ImplingSpawner] gets one - and without it a teased creature follows its hunter
 * across the world forever. See [tickChases] for why nothing else in the engine will stop it.
 *
 * @see PitState for what each varbit value renders as, and which ops the cache declares on it.
 */
class HunterPitfall
@Inject
constructor(private val xpMods: XpModifiers, private val playerList: PlayerList) {
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
     * catch belongs on, and by [tickChases] for a chase that ended some other way. The map is in
     * any case bounded by the number of pitfall creatures the map spawns - a few dozen - rather
     * than growing without limit. Identity keying, and the reasoning behind it, is `FalconLinks`'.
     */
    private val chases = IdentityHashMap<Npc, PlayerUid>()

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
     * has not landed, so it does neither and leaves the pit exactly as it found it. [clearPits] is
     * the escape hatch if a pit is ever stranded there.
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
     * Hands over what a full pit holds, then returns it to an empty pit.
     *
     * Room is checked for the **whole** catch before any of it is awarded, and the pit is left full
     * if there is not enough: a catch that half-landed and spilled the rest on the floor puts a
     * rare fur under a despawn timer, where a refused one is still in the pit when a slot is freed.
     * This is falconry's rule and the crab trap's, in the same words.
     */
    private fun ProtectedAccess.collectPit(site: PitfallSite): Boolean {
        val creature = site.creature

        // `quantity.first` rather than a roll: every pitfall reward line is a flat quantity, which
        // is why this class holds no `GameRandom`. `HunterPitfallTest` pins that, so a ranged line
        // added later fails there rather than silently awarding its minimum here.
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

        setPitState(player, site, PitState.Empty)
        return true
    }

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
     * not end this chase on its own. [tickChases] is where the leash is put back, and it is a
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
     * rule is a prerequisite of retaliation, not a sibling of it**, and [tickChases] is that rule.
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
     * The cycle hook that gives every chase somewhere to stop. Register it, or none of them do.
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
    fun tickChases() {
        if (chases.isEmpty()) {
            return
        }
        // Collected before anything is ended, because `stopChasing` writes to the map being read.
        val ended = chases.entries.filterNot { (npc, teaser) -> chaseContinues(npc, teaser) }
        for ((npc, _) in ended) {
            if (npc.isSlotAssigned) {
                stopChasing(npc)
            } else {
                chases.remove(npc)
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
     * [tickChases] is the other caller, for the two ways a chase ends that nobody clicks.
     */
    fun stopChasing(npc: Npc) {
        chases.remove(npc)
        npc.defaultMode()
    }

    /** The state this player's own copy of [site] is currently in. */
    fun pitState(player: Player, site: PitfallSite): PitState =
        PitState.of(player.vars[site.varbit])

    /**
     * Returns every one of this player's twenty-five pits to [PitState.Empty].
     *
     * The whole of a player's pitfall state is these twenty-five varbits, so this is the one call
     * that resets the technique - and the only way back out of a pit stranded in
     * [PitState.Catching], which [dismantlePit] deliberately refuses to touch. It writes every site
     * rather than only the non-empty ones because writing [PitState.Empty] over
     * [PitState.Empty] costs nothing and a filtered pass would need the read anyway.
     */
    fun clearPits(player: Player) {
        for (site in PitfallSites.all) {
            setPitState(player, site, PitState.Empty)
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
         * How far a teased creature will follow before [tickChases] takes it off the leash: **64
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
