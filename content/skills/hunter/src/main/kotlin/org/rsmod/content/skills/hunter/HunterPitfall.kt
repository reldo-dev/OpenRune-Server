package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.table.FiremakingLogsRow
import org.rsmod.game.entity.Player

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
 * Only the player-driven halves live here. Teasing a creature, jumping the pit, the roll that
 * decides a catch, the [PitState.Catching] frame and the transition into [PitState.Full] are a
 * later task's, as is registering any of this against a loc op. Nothing in this file is wired to a
 * click.
 *
 * @see PitState for what each varbit value renders as, and which ops the cache declares on it.
 */
class HunterPitfall @Inject constructor(private val xpMods: XpModifiers) {
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
     *   log it was armed with; the deadfall's own KDoc records that refund as *unsourced*, and no
     *   source says a dismantled pit returns anything either.
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
         * Every log a pit accepts, **lowest tier first**.
         *
         * "If players have multiple types of logs in their inventory, the lowest tier of logs will
         * be used first." (wiki, *Pitfall*, oldid=15201220). That is the one rule that makes this a
         * ranked list rather than the deadfall's plain first-by-slot-order pick: the same page's
         * sentence for the deadfall states no preference at all, so that technique is free to take
         * whatever is nearest the top of the backpack and this one is not.
         *
         * "Tier" is read as the log's own Firemaking requirement, which is the only ordering the
         * packed data carries and the one every log tier list in the game is written in. The sort
         * is stable, so rows that share a requirement keep the packed table's own order. Seven of
         * the nineteen rows are Firemaking 1 - normal, achey and the five Treasure Trails colours -
         * and five of those seven are filtered out below, leaving `obj.logs` ahead of
         * `obj.achey_tree_logs` exactly as the packed table has them.
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
                // A row with no requirement at all is the lowest tier there is.
                .sortedBy { it.statReq.firstOrNull()?.t1 ?: 0 }
                .map { it.input.internalName }
        }
    }
}
