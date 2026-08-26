package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import java.util.IdentityHashMap
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo

/**
 * Following a trail from a burrow to the noose: the whole of the tracking technique.
 *
 * "The player inspects scenery objects in the creature's habitat to uncover a creature's tracks.
 * These tracks will eventually lead the player towards the hiding creature. When the hiding creature
 * is found, right-click 'Attack' while wielding a noose wand to receive loot and experience. Each
 * player follows a unique track and does not compete with other players to get kebbits." (wiki,
 * *Tracking*.)
 *
 * That last sentence is why this shares nothing with [HunterTrap] or [HunterFalconry]: there is no
 * controller, no cap, no spawned npc and no world state of any kind. A trail is two things - the
 * player's own segment varbits, which are what the client draws footprints from, and one in-memory
 * entry saying which segments the trail actually runs along and how far along it the player has
 * got. Nothing another player can see, camp or race for.
 *
 * The catch is **deterministic**: no success roll, no `(low, high)` pair. See [TrackingCreature] -
 * the technique page awards loot unconditionally and no tracking creature carries a published
 * catch-rate chart. [gameRandom] is drawn from exactly once per trail, to pick which of the
 * enumerated trails the player gets.
 */
class HunterTracking
@Inject
constructor(
    // Named `gameRandom`, not `random`, for the reason spelled out on [HunterFalconry]: every
    // generating function here is a `ProtectedAccess` extension, `ProtectedAccess` has a `random`
    // property of its own, and the extension receiver's member wins over the dispatch receiver's
    // field. A field called `random` would silently draw from the player's context RNG instead.
    private val gameRandom: GameRandom,
    private val xpMods: XpModifiers,
) {
    /**
     * Whose trail is whose, keyed by **identity** rather than by uid.
     *
     * Not persisted, and it does not need to be: [loginReset] hides every trail at login, so a
     * restart loses nothing a player can tell apart from a fresh inspect. Entries die on the catch,
     * on [clearTrail] and on [discardState].
     */
    private val activeTrails = IdentityHashMap<Player, TrailState>()

    /** [player]'s trail, if they are following one. Read-only; the tests are the only caller. */
    internal fun trailOf(player: Player): TrailState? = activeTrails[player]

    /**
     * `Inspect` on a kebbit burrow: rolls a trail and shows its first segment.
     *
     * [burrowLoc] is the clicked loc's **gameval**, and the burrow row is found by it rather than by
     * [loc]'s coordinates. Each burrow gameval belongs to exactly one row (asserted in
     * `TrackingNetworksTest`), while a loc gameval may be placed on the map more than once - twenty
     * of the clue locs are - so a coordinate comparison would refuse a legitimate click on a second
     * placement, with a "nothing here" message and no clue why.
     *
     * @return false, with a message already sent where one is warranted, if no trail was generated.
     */
    fun ProtectedAccess.inspectBurrow(
        network: TrackingNetwork,
        burrowLoc: String,
        loc: BoundLocInfo,
    ): Boolean {
        faceLoc(loc)

        val creature = network.creature
        if (player.hunterLvl < creature.level) {
            mes("You need a Hunter level of ${creature.level} to track this creature.")
            return false
        }

        val existing = activeTrails[player]
        if (existing != null) {
            if (existing.network == network) {
                mes("You are already following a trail.")
                return false
            }
            // A trail in another area. Its footprints are still rendered on that player's client, so
            // dropping the entry without clearing them would strand a trail nobody can ever finish.
            clearTrail(player, existing.network)
        }

        val burrow = network.burrows.firstOrNull { it.loc == burrowLoc } ?: return false
        val trails = network.trailsByOrigin[burrow.origin].orEmpty()
        if (trails.isEmpty()) {
            // Unreachable while every burrow origin has at least one path to a catch spot, which is
            // what `TrackingNetworksTest` checks. Belt and braces against an authoring mistake.
            return false
        }

        val steps = trails[gameRandom.of(trails.size)]
        val state = TrailState(network, steps, revealed = 1)

        // "Wearing a ring of pursuit when inspecting a kebbit burrow will reveal the kebbit's entire
        // track at once." (wiki, *Tracking*.) Worn, not carried, and unconditional: "the ring of
        // pursuit's effect is now guaranteed to activate, up from a 25% chance" (8 May 2024). It
        // costs a charge whether or not the reveal saved the player any walking.
        if (worn.contains(RING_OF_PURSUIT)) {
            state.revealed = steps.size
            spendRingCharge()
        }

        activeTrails[player] = state
        renderTrail(state)
        mes("You find fresh tracks leading away from the burrow.")
        return true
    }

    /**
     * `Inspect` on a clue - a plant, a cactus, a jungle plant - which uncovers the next segment.
     *
     * Matched on [clueLoc], the clue's own gameval, and never on coordinates. A segment records only
     * the *first* placement of its clue in [TrailSegment.clueCoords], and twenty of the clue locs
     * are placed twice, so a coordinate match would silently reject half of those clicks.
     *
     * @return false, with a message already sent, if this clue is not the next step's.
     */
    fun ProtectedAccess.inspectClue(network: TrackingNetwork, clueLoc: String): Boolean {
        val state = activeTrails[player]
        val next = state?.takeIf { it.network == network }?.nextClue
        if (next == null || next.clue != clueLoc) {
            mes("You search the area but find nothing of interest.")
            return false
        }
        state.revealed += 1
        renderTrail(state)
        mes("You find more tracks leading away.")
        return true
    }

    /**
     * `Search` on a catch spot: says whether this is where the trail ended.
     *
     * The one place a coordinate **is** the right key. A catch gameval covers several placements per
     * area - `hunting_trail_end_polar` is placed four times in Rellekka - and a trail ends at one
     * specific tile, so the gameval cannot tell the hot placement from the cold ones.
     */
    fun ProtectedAccess.searchCatchSpot(network: TrackingNetwork, loc: BoundLocInfo): Boolean {
        faceLoc(loc)
        if (!isHotSpot(network, loc)) {
            mes("You search the area but find nothing of interest.")
            return false
        }
        mes("It looks like something is moving around in there.")
        return true
    }

    /**
     * `Attack` on a catch spot: the noose swing, and the catch if the trail ended here.
     *
     * The wand has to be **wielded**, not carried: "right-click 'Attack' while wielding a noose
     * wand" (wiki, *Tracking*).
     *
     * @return true only if the kebbit was caught.
     */
    suspend fun ProtectedAccess.attackCatchSpot(
        network: TrackingNetwork,
        loc: BoundLocInfo,
    ): Boolean {
        faceLoc(loc)

        if (!worn.contains(NOOSE_WAND)) {
            mes("You need to be wielding a noose wand to catch a kebbit.")
            return false
        }

        if (!isHotSpot(network, loc)) {
            anim(NOOSE_SWING_SEQ)
            mes("You fail to catch anything.")
            return false
        }

        val creature = network.creature
        val loot = listOf(creature.fur, RAW_BEAST_MEAT, BONES)

        // Checked before the swing, not after it, so a full-inventory attempt leaves the kebbit
        // exactly where it was rather than catching it and dropping the reward on the floor. The
        // three rewards are unstackable, but the shared helper is used anyway so that a future
        // stackable line cannot make this over-reject.
        val slotsNeeded = loot.sumOf { hunterInvSlotsNeeded(inv, it, 1) }
        if (inv.freeSpace() < slotsNeeded) {
            mes("You don't have enough inventory space to carry the kebbit.")
            return false
        }

        anim(creature.catchSeq)
        delay(CATCH_ANIM_CYCLES)

        // "Catching a kebbit gives the player bones, raw beast meat, and kebbit fur." (wiki,
        // *Tracking*.) Unconditional - there is no roll to lose.
        for (obj in loot) {
            invAdd(inv, obj, 1)
        }

        // Creature xp is stored x10 so fractional values survive the table.
        val xp = (creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
        statAdvance("stat.hunter", xp)

        clearTrail(player, network)
        return true
    }

    /**
     * Hides every segment of [player]'s trail through [network], one varbit at a time.
     *
     * **Never a varp write.** The trail varps carry unrelated fields - `hunting_trail_basevar7` is
     * varp 925, which also holds `lumbridge_alchemy_high` - so zeroing a varp to clear a trail would
     * silently reset another system's player state. Nor is a bit position computed here: nine of the
     * packed state varbits are not three bits at a predictable offset, so the varbit is named and
     * the cache places it.
     */
    fun clearTrail(player: Player, network: TrackingNetwork) {
        val state = activeTrails[player] ?: return
        if (state.network != network) {
            return
        }
        activeTrails.remove(player)
        for (step in state.steps) {
            VarPlayerIntMapSetter.set(player, step.segment.varbit, 0)
        }
    }

    /** Logout: the varps persist harmlessly until [loginReset]; the map entry must not. */
    fun discardState(player: Player) {
        activeTrails.remove(player)
    }

    /**
     * Hides every placed trail segment there is, which is how a session starts.
     *
     * The trail varps are `Perm`, so a player who logged out mid-trail comes back to footprints the
     * server no longer has an [activeTrails] entry for - a trail that renders and can never be
     * finished. Clearing all of them costs 75 writes once per login and removes the whole class of
     * problem.
     *
     * **Call this from a soft queue, not from the login event.** `VarPlayerIntMapSetter`
     * short-circuits before its transmit branch while `processedMapClock == 0`, which is exactly the
     * state during `SessionStateEvent.Login`: the server would clear the varps and the client would
     * go on drawing the old footprints. See [TRACKING_RESET_QUEUE].
     */
    fun loginReset(player: Player) {
        for (network in TrackingNetworks.all) {
            for (segment in network.segments) {
                VarPlayerIntMapSetter.set(player, segment.varbit, 0)
            }
        }
    }

    /** Whether [loc] is the tile a completed trail through [network] ended at. */
    private fun ProtectedAccess.isHotSpot(network: TrackingNetwork, loc: BoundLocInfo): Boolean {
        val state = activeTrails[player] ?: return false
        return state.network == network && state.complete && state.catchCoords == loc.coords
    }

    /** Writes the footprint value of every revealed step, and nothing else. */
    private fun ProtectedAccess.renderTrail(state: TrailState) {
        for ((varbit, value) in TrailLogic.revealWrites(state.steps, state.revealed)) {
            VarPlayerIntMapSetter.set(player, varbit, value)
        }
    }

    /**
     * Spends one of the ten charges a ring of pursuit holds, destroying it on the last.
     *
     * The counter is a player varp rather than anything on the ring, because the charges belong to
     * the player: see [trackingRingCharges]. It stores charges *used*, so an unwritten varp is a
     * full allowance.
     */
    private fun ProtectedAccess.spendRingCharge() {
        val used = player.trackingRingCharges + 1
        if (used >= RING_CHARGES) {
            invDel(worn, RING_OF_PURSUIT, 1)
            player.trackingRingCharges = 0
            mes("Your ring of pursuit crumbles to dust.")
        } else {
            player.trackingRingCharges = used
        }
    }

    companion object {
        const val NOOSE_WAND: String = "obj.noose_wand"
        const val RING_OF_PURSUIT: String = "obj.ring_of_pursuit"

        /** The `Raw beast meat` every kebbit yields; the cache symbol keeps its spit-roast prefix. */
        const val RAW_BEAST_MEAT: String = "obj.spit_raw_beast_meat"

        const val BONES: String = "obj.bones"

        /**
         * The empty-handed noose swing, played on a miss.
         *
         * The five `hunting_noose_*` creature seqs each animate a specific kebbit being lifted, so
         * none of them can stand in for catching nothing; `hunting_noose_catch` is the generic swing
         * they are all variants of.
         */
        const val NOOSE_SWING_SEQ: String = "seq.hunting_noose_catch"

        /**
         * How many charges a ring of pursuit holds.
         *
         * "The ring will provide 10 charges before disappearing." (wiki, *Ring of pursuit*.) The
         * charges are the player's rather than the ring's - see [trackingRingCharges].
         */
        const val RING_CHARGES: Int = 10

        /** How long the catch animation holds the player before the reward lands. Unsourced. */
        const val CATCH_ANIM_CYCLES: Int = 2
    }
}
