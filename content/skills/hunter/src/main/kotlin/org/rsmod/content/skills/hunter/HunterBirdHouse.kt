package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.craftingLvl
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.utils.time.epochMinute as wallClockMinute
import org.rsmod.content.drops.clueScrollTransformObj
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.BoundLocInfo

/** The queue a filling bird house matures on; see [HunterBirdHouse.addBirdHouseSeeds]. */
const val BIRDHOUSE_FILL_QUEUE: String = "queue.hunter_birdhouse_fill"

/**
 * The wall clock a bird house fills against.
 *
 * A one-method seam over `RuneTime.epochMinute` rather than a direct call, so a test can wind fifty
 * minutes forward without sleeping through them. It is the only collaborator here that has no
 * production alternative - everything else is a real repository.
 */
fun interface BirdHouseClock {
    fun epochMinute(): Int
}

/**
 * The production clock: the wall clock, in minutes since the Unix epoch.
 *
 * The import is aliased because it must be. An unaliased `epochMinute()` inside a method of the same
 * name resolves to **this method**, not to the imported one, and compiles into a silent infinite
 * recursion - the same shadowing shape that cost this module a wrong RNG in falconry, in a place
 * where nothing but a stack overflow would have shown it.
 */
object SystemBirdHouseClock : BirdHouseClock {
    override fun epochMinute(): Int = wallClockMinute()
}

/**
 * Bird house trapping: place a house, seed it, wait fifty minutes, harvest it.
 *
 * **The first technique in this module that runs on wall-clock time.** Every other one is a
 * controller ticking on the map clock or a soft queue on the player, and both die with the session.
 * A bird house does not: its owner is expected to seed four of them and log out for the better part
 * of an hour. So the deadline is an **epoch minute in a saved varp** ([birdHouseReadyAt]) and the
 * soft queue is only the in-session half - a convenience that fires while the player happens to be
 * online, re-armed against the varp at login and re-checked on every interaction. Lose the queue and
 * nothing breaks; lose the varp and the house never fills.
 *
 * Structurally it is the crab trap one step on. `HunterCrabTrap` writes a **varbit** the client
 * renders a trap from; this writes a **varp** the client renders a bird house from, and inherits
 * the same three properties:
 * - **`locRepo` is never touched.** This class holds no loc repository, so the "never delete a
 *   map-placed loc" invariant the deadfall and net trap enforce with a hard `check` cannot be broken
 *   here at all.
 * - **Bird houses are private.** Four fixed spaces, per-player varps, no contention, no owner to
 *   record and no tile to key anything on.
 * - **Nothing about the technique lives in the world**, which is why the fill is a queue on the
 *   player rather than a controller on the space.
 *
 * ## The five transactions
 *
 * **Craft** ([craftBirdHouse]) is where a bird house comes from: a clockwork used on the tier's logs,
 * with a chisel and a hammer carried, awards the tier's Crafting experience. It is the only entry
 * point to the whole technique - nothing else in the game gives a bird house - and it is also
 * [placeBirdHouse]'s fallback, so `Build` and `Reset` make one out of materials when no pre-made
 * house is carried.
 *
 * **Build** ([buildBirdHouse]) places the best bird house the player is carrying and can use. The
 * Hunter requirement is checked here and **only** here: "Dismantling a birdhouse does not have a
 * Hunter level requirement, meaning boosts only need to be used when placing higher tier
 * birdhouses". It is read from the effective level, so a potion raises it.
 *
 * **Seeds** ([addBirdHouseSeeds]) inserts greedily - "they simply assume you wish to insert as many
 * as possible" (3 May 2018) - and types mix. See [BirdHouseSeeds] for which half of the capacity
 * model is published.
 *
 * **Dismantle** ([dismantleBirdHouse]) is the early abort, and the one place this technique is
 * deliberately unkind: "The birdhouse must be completely depleted of seeds before **any** rewards
 * can be claimed from it. Emptying the birdhouse early will save the clockwork but lose the
 * birdhouse, the remaining seeds, and any loot that might have accumulated." The clockwork comes
 * back and nothing else does.
 *
 * **Empty** ([emptyBirdHouse]) is the payout, and **Reset** is the same payout followed by placing a
 * fresh house in one action.
 */
class HunterBirdHouse
@Inject
constructor(
    // Named `gameRandom`, not `random`, for the reason [HunterButterfly] documents: a field called
    // `random` inside a `ProtectedAccess` extension is silently shadowed by the receiver's own.
    private val gameRandom: GameRandom,
    private val xpMods: XpModifiers,
    private val objRepo: ObjRepository,
    private val birdHouseClock: BirdHouseClock,
) {
    /**
     * Why [craftBirdHouse] would refuse [type] right now, or null if it would not.
     *
     * Separate from [craftBirdHouse] because the make menu has to decide *before* it opens:
     * `openSkillMulti` shows nothing at all when no entry is affordable, so a player one Crafting
     * level short of a tier would otherwise click a clockwork onto logs and watch nothing happen.
     * The order is the order the messages are worth reading in - the level first, because it is the
     * one a run cannot fix.
     */
    fun ProtectedAccess.birdHouseCraftRefusal(type: BirdHouseType): String? =
        when {
            player.craftingLvl < type.craftingLevel ->
                "You need a Crafting level of ${type.craftingLevel} to make that."
            !inv.contains(CHISEL) || !inv.contains(HAMMER) ->
                "You need a chisel and a hammer to make a bird house."
            !inv.contains(type.logs) || !inv.contains(CLOCKWORK) ->
                "You need ${type.logs.objName()} and a clockwork to make that."
            else -> null
        }

    /**
     * A clockwork used on [type]'s logs: one bird house, and the tier's Crafting experience.
     *
     * **The only way a bird house enters the game.** [BirdHouseType.logs] and
     * [BirdHouseType.craftingXp] are read off the row rather than restated here, which is why the
     * nine tiers need one function and not nine.
     *
     * A `HeldU` pair rather than a make menu, because the pair already names the product: one log
     * type plus a clockwork has exactly one outcome, unlike a knife on logs, which is the case make
     * menus exist for. Nine registrations in [BirdHouseEvents] cover the family and no interface,
     * content group or gameval is involved.
     *
     * The requirement is **Crafting**, not Hunter - "Crafting 5-90 if you wish to make your own
     * houses", and the Hunter level is checked only when the house is placed. It is read from the
     * effective level, so a boost makes a tier the base level cannot.
     *
     * Both tools are held, not consumed, and the animation is the cache's own `birdhouse_make`. The
     * Imcando hammer's variant (`seq.birdhouse_make_imcando_hammer`, 8916) is not played; nothing in
     * this module knows about that hammer yet.
     *
     * @return false, with a message already sent, if nothing was made.
     */
    fun ProtectedAccess.craftBirdHouse(type: BirdHouseType): Boolean {
        val refusal = birdHouseCraftRefusal(type)
        if (refusal != null) {
            mes(refusal)
            return false
        }

        // Charged one at a time and refunded on the second failure, the way the crab trap charges
        // its three materials. Two deletes and one add can never overflow the backpack, so the
        // product needs no space check.
        if (invDel(inv, type.logs, 1).failure) {
            return false
        }
        if (invDel(inv, CLOCKWORK, 1).failure) {
            invAdd(inv, type.logs, 1)
            return false
        }

        anim(BIRDHOUSE_MAKE_SEQ)
        invAdd(inv, type.obj, 1)
        // Stored x10, as every experience column in this module is.
        val xp = (type.craftingXp / 10.0) * xpMods.get(player, "stat.crafting")
        statAdvance("stat.crafting", xp)
        mes("You carve the ${type.logs.objName()} and fit the clockwork inside.")
        return true
    }

    /**
     * `Build` on a bare space.
     *
     * Places the **best** bird house carried, which is published behaviour: "Bird houses now have a
     * left click/tap 'build' option which will erect the best bird house in your inventory" (18 April
     * 2019). "Best" is read as the highest tier the player's current Hunter level allows, so a
     * carried redwood house is skipped rather than refused when the level is short of 89.
     *
     * No experience is awarded. The tier's Crafting experience belongs to the crafting action that
     * made the item and its Hunter experience to the harvest; **no source gives a Hunter figure for
     * building**, and that is an absence across four pages rather than a stated zero.
     *
     * @return false, with a message already sent, if nothing was placed.
     */
    fun ProtectedAccess.buildBirdHouse(loc: BoundLocInfo): Boolean {
        val space = BirdHouseSpaces.byLocId(loc.id) ?: return false
        if (player.birdHouseState(space) != BirdHouseSpaces.BARE) {
            mes("There is already a bird house here.")
            return false
        }
        return placeBirdHouse(space)
    }

    /**
     * `Seeds` on a built house.
     *
     * Greedy over the whole inventory, high-value seeds first so that a player carrying both kinds
     * spends the expensive ones - which is the wrong preference for a player's wallet and the right
     * one for "insert as many as possible", since it is also the order that fills the house in the
     * fewest items. **Unsourced either way**: the wiki says only that as many as possible go in.
     *
     * A partial insert is a legitimate outcome and is remembered in [birdHouseSeedUnits]; the house
     * only starts filling on the click that completes it.
     *
     * @return false, with a message already sent, if no seed was inserted.
     */
    fun ProtectedAccess.addBirdHouseSeeds(loc: BoundLocInfo): Boolean {
        val space = BirdHouseSpaces.byLocId(loc.id) ?: return false
        val state = player.birdHouseState(space)
        val type = BirdHouseTypes.byVarpValue(state)
        if (type == null) {
            mes("You need to build a bird house here first.")
            return false
        }
        when (BirdHouseTypes.stateOf(state)) {
            BirdHouseState.Filling -> {
                mes("This bird house is already full of seeds.")
                return false
            }
            BirdHouseState.Full -> {
                mes("This bird house is full of birds. You should empty it first.")
                return false
            }
            else -> Unit
        }

        var units = player.birdHouseSeedUnits(space)
        var inserted = 0
        // `BirdHouseSeeds.all` is high-value first, so a player carrying both kinds fills the house
        // in the fewest items. Which stack live prefers is unstated; this ordering is at least
        // stable and does not depend on inventory layout.
        for (seed in BirdHouseSeeds.all) {
            while (units < BirdHouseSeeds.BIRDHOUSE_SEED_UNITS && inv.contains(seed)) {
                val cost = checkNotNull(BirdHouseSeeds.unitsOf(seed))
                if (units + cost > BirdHouseSeeds.BIRDHOUSE_SEED_UNITS) {
                    // A high-value seed cannot half-fill the last unit, so stop trying this one and
                    // let a low-value seed finish the house.
                    break
                }
                if (invDel(inv, seed, 1).failure) {
                    break
                }
                units += cost
                inserted++
            }
        }

        if (inserted == 0) {
            // Two different failures, and they are worth distinguishing: a house one unit short can
            // only be finished by a low-value seed, so a player holding nothing but ranarrs is not
            // holding "no suitable seeds" - they are holding seeds that will not fit.
            val carrying = BirdHouseSeeds.all.any(inv::contains)
            if (carrying) {
                mes("None of your seeds will fit in this bird house.")
            } else {
                mes("You don't have any seeds to put in this bird house.")
            }
            return false
        }

        player.setBirdHouseSeedUnits(space, units)
        if (units < BirdHouseSeeds.BIRDHOUSE_SEED_UNITS) {
            mes("You add some seeds to the bird house. It needs more.")
            return true
        }

        startBirdHouseFilling(space, type)
        mes("You fill the bird house with seeds.")
        return true
    }

    /**
     * `Interact` on a full bird house - a status message and nothing else.
     *
     * The cache carries this op and **nothing says what it does**. No client script drives it, and
     * the wiki mentions only that "the time remaining can be roughly determined by checking the seed
     * level in the birdhouse". Making it harvest would be the guess with a cost: it is the
     * left-click, harvesting is irreversible, and `Empty` is deliberately a separate op three slots
     * along. So it reports and does not act.
     */
    fun ProtectedAccess.inspectBirdHouse(loc: BoundLocInfo) {
        val space = BirdHouseSpaces.byLocId(loc.id) ?: return
        val state = player.birdHouseState(space)
        when (BirdHouseTypes.stateOf(state)) {
            BirdHouseState.Full -> mes("The bird house is full of birds.")
            BirdHouseState.Filling -> mes("The bird house is full of seeds. Birds are gathering.")
            BirdHouseState.Empty -> mes("The bird house is empty. It needs seeds.")
            null -> mes("There is nothing built here.")
        }
    }

    /**
     * `Dismantle` on a filling bird house: the early abort.
     *
     * Returns the clockwork and **nothing else** - no experience, no nests, and not the seeds. That
     * is published, and it is the whole reason the payout lives on the other state's op. The
     * warning prompt live shows here is not implemented.
     *
     * @return false, with a message already sent, if nothing was dismantled.
     */
    fun ProtectedAccess.dismantleBirdHouse(loc: BoundLocInfo): Boolean {
        val space = BirdHouseSpaces.byLocId(loc.id) ?: return false
        val state = player.birdHouseState(space)
        if (BirdHouseTypes.stateOf(state) != BirdHouseState.Filling) {
            mes("Nothing interesting happens.")
            return false
        }
        clearBirdHouse(space)
        awardOrDrop(CLOCKWORK, 1)
        mes("You dismantle the bird house, losing the seeds inside it.")
        return true
    }

    /**
     * `Empty` on a full bird house, and the second half of `Reset`.
     *
     * The space is cleared first and everything is awarded afterwards, so a payout that runs out of
     * inventory drops to the ground rather than leaving a house that has already paid out still
     * standing. [rebuild] is `Reset`: the same payout, then a fresh house from the inventory in one
     * action, "reusing the clockwork mechanism from the previous birdhouse" - which falls out for
     * free here, since the clockwork is handed back before the rebuild looks for a house.
     *
     * @return false, with a message already sent, if the house was not ready.
     */
    fun ProtectedAccess.emptyBirdHouse(loc: BoundLocInfo, rebuild: Boolean = false): Boolean {
        val space = BirdHouseSpaces.byLocId(loc.id) ?: return false
        val state = player.birdHouseState(space)
        val type = BirdHouseTypes.byVarpValue(state)
        if (type == null || BirdHouseTypes.stateOf(state) != BirdHouseState.Full) {
            mes("Nothing interesting happens.")
            return false
        }

        clearBirdHouse(space)
        payOutBirdHouse(type)

        if (rebuild && !placeBirdHouse(space, quiet = true)) {
            // Reset with nothing to place is not a failure - the house was still emptied - so this
            // says what happened rather than reporting an error. The replacement may be made out of
            // logs and the clockwork this payout just handed back, which live also does; only a
            // player carrying neither a house nor materials lands here.
            mes("You have no bird house to put back here.")
        }
        return true
    }

    /**
     * The body of [BIRDHOUSE_FILL_QUEUE]: this space's fifty minutes are up.
     *
     * Re-checked against the varps rather than trusted, because the queue outlives the state that
     * scheduled it: the player may have dismantled the house in the meantime, and a matured fill
     * landing on a bare space would show a bird house nobody built.
     *
     * Not a [ProtectedAccess] extension. A bird house filling is not a player action and must land
     * while its owner is banking or fighting rather than wait for them to be free.
     */
    fun Player.birdHouseFillArrives(spaceIndex: Int) {
        val space = BirdHouseSpaces.all.getOrNull(spaceIndex) ?: return
        matureBirdHouse(space)
    }

    /**
     * Matures [space] if its deadline has passed, and returns whether it did.
     *
     * Called from the queue, from login, and from every interaction, which is what makes a lost
     * queue harmless: the varp holds the deadline, so any of the three can be the one that notices.
     */
    fun Player.matureBirdHouse(space: BirdHouseSpace): Boolean {
        val state = birdHouseState(space)
        val type = BirdHouseTypes.byVarpValue(state) ?: return false
        if (BirdHouseTypes.stateOf(state) != BirdHouseState.Filling) {
            return false
        }
        val readyAt = birdHouseReadyAt(space)
        if (readyAt > birdHouseClock.epochMinute()) {
            return false
        }
        setBirdHouseState(space, type.birdState)
        clearBirdHouseReadyAt(space)
        return true
    }

    /**
     * Matures whatever finished while this player was away, and re-arms the rest.
     *
     * The two halves of a filling bird house have different lifetimes: the deadline is saved with the
     * player, the queue is not. This is the crab trap's login re-arm with the one difference that
     * matters - it re-queues the **remaining** time rather than the full delay, because a bird house
     * genuinely does fill while its owner is logged out and restarting the clock would make logging
     * out a punishment.
     *
     * A house whose deadline has already passed is queued for the next cycle rather than written
     * here. `VarPlayerIntMapSetter` skips its transmit branch entirely while `processedMapClock` is
     * still zero, which is exactly the state during the login event, so a direct write would update
     * the server and leave the client drawing a house full of seeds.
     */
    fun Player.rearmBirdHouseFills() {
        val now = birdHouseClock.epochMinute()
        for (space in BirdHouseSpaces.all) {
            val state = birdHouseState(space)
            if (BirdHouseTypes.stateOf(state) != BirdHouseState.Filling) {
                continue
            }
            val remaining = birdHouseReadyAt(space) - now
            val cycles = if (remaining <= 0) 1 else remaining * BIRDHOUSE_CYCLES_PER_MINUTE
            softQueue(BIRDHOUSE_FILL_QUEUE, cycles, space.index)
        }
    }

    /** The varp value this player's copy of [space] is currently showing. */
    fun Player.birdHouseState(space: BirdHouseSpace): Int = vars[space.varp]

    /* Internals. */

    /**
     * Places the best carried bird house on [space], making one out of materials if none is carried.
     *
     * The fallback is live's: "Bringing the extra clockwork allows the player to craft the bird
     * houses while running to the next one" - the run is logs, seeds and one clockwork, and `Build`
     * and `Reset` turn those into a house on the spot. `Reset` gets it for free, because the payout
     * hands the clockwork back before this looks for anything.
     *
     * A pre-made house always wins over materials: it costs nothing to place and the logs stay in
     * the bag. Only when none is usable does the crafting path run, and it picks the best tier the
     * player can both *make* and *place* - both levels, both materials and both tools - so a
     * carried redwood log is skipped rather than refused.
     *
     * @return false if the player is carrying neither a usable house nor the materials for one.
     */
    private fun ProtectedAccess.placeBirdHouse(
        space: BirdHouseSpace,
        quiet: Boolean = false,
    ): Boolean {
        val carried =
            BirdHouseTypes.all.lastOrNull {
                it.hunterLevel <= player.hunterLvl && inv.contains(it.obj)
            }
        val best = carried ?: makeBirdHouseToPlace(quiet) ?: return false
        if (invDel(inv, best.obj, 1).failure) {
            return false
        }
        player.setBirdHouseState(space, best.builtState)
        player.setBirdHouseSeedUnits(space, 0)
        player.clearBirdHouseReadyAt(space)
        mes("You place the ${best.obj.objName()} and it's ready to be seeded.")
        return true
    }

    /**
     * Makes a house out of carried materials because none was carried ready-made.
     *
     * @return the tier now sitting in the backpack, or null with a message already sent.
     */
    private fun ProtectedAccess.makeBirdHouseToPlace(quiet: Boolean): BirdHouseType? {
        val makeable = bestMakeableBirdHouse()
        if (makeable == null) {
            if (!quiet) {
                // Three refusals worth telling apart. A player holding a redwood house at Hunter 80
                // has a level problem, not a materials one, and sending them to the bank for logs
                // they already carry would be the wrong instruction.
                val carrying = BirdHouseTypes.all.any { inv.contains(it.obj) }
                if (carrying) {
                    mes("Your Hunter level is too low to place any bird house you're carrying.")
                } else {
                    mes("You need a bird house, or the logs and clockwork to make one.")
                }
            }
            return null
        }
        return if (craftBirdHouse(makeable)) makeable else null
    }

    /**
     * The best tier the player can make *and* place right now.
     *
     * Both level gates, not just the Crafting one: a house made here is placed in the same action,
     * so making a tier the Hunter level cannot place would spend the logs on nothing.
     */
    private fun ProtectedAccess.bestMakeableBirdHouse(): BirdHouseType? {
        if (!inv.contains(CHISEL) || !inv.contains(HAMMER) || !inv.contains(CLOCKWORK)) {
            return null
        }
        return BirdHouseTypes.all.lastOrNull {
            it.hunterLevel <= player.hunterLvl &&
                it.craftingLevel <= player.craftingLvl &&
                inv.contains(it.logs)
        }
    }

    /** Moves [space] into the filling state and stamps its deadline. */
    private fun ProtectedAccess.startBirdHouseFilling(space: BirdHouseSpace, type: BirdHouseType) {
        player.setBirdHouseState(space, type.fullState)
        player.setBirdHouseSeedUnits(space, 0)
        player.setBirdHouseReadyAt(space, birdHouseClock.epochMinute() + BIRDHOUSE_FILL_MINUTES)
        // In-session convenience only. The deadline above is what actually decides when the house is
        // ready; this just means a player who stays online sees it happen without clicking.
        softQueue(
            BIRDHOUSE_FILL_QUEUE,
            BIRDHOUSE_FILL_MINUTES * BIRDHOUSE_CYCLES_PER_MINUTE,
            space.index,
        )
    }

    /** Returns [space] to a bare patch and forgets everything about the house that was on it. */
    private fun ProtectedAccess.clearBirdHouse(space: BirdHouseSpace) {
        player.setBirdHouseState(space, BirdHouseSpaces.BARE)
        player.setBirdHouseSeedUnits(space, 0)
        player.clearBirdHouseReadyAt(space)
    }

    /**
     * Everything a fully depleted bird house gives: the guaranteed drops, the seed nest roll, and
     * the ten nest rolls.
     *
     * Order is the published order, and it matters for the random draws a test scripts: guaranteed
     * items first, then one seed-nest roll, then ten nest rolls each of which may pre-roll a clue.
     */
    private fun ProtectedAccess.payOutBirdHouse(type: BirdHouseType) {
        awardOrDrop(CLOCKWORK, 1)
        // "Always dropped to the ground, even if there is space in the inventory" - published twice,
        // on the drop table footnote and in the 3 May 2018 changelog. This is the one award here
        // that is on the ground by rule rather than by fallback.
        objRepo.add(
            RAW_BIRD_MEAT,
            player.coords,
            BIRDHOUSE_DROP_CYCLES,
            receiver = player,
            count = RAW_BIRD_MEAT_COUNT,
        )
        awardOrDrop(FEATHER, gameRandom.pick(30, 40, 50, 60))

        if (BirdHouseNests.seedNestChance(player.hunterLvl) > gameRandom.randomDouble()) {
            awardOrDrop(BirdHouseNests.SEED_NEST, 1)
        }

        val rabbitFoot = player.worn.contains(BirdHouseNests.STRUNG_RABBIT_FOOT)
        var clues = 0
        repeat(BirdHouseNests.NEST_ROLLS) {
            if (BirdHouseNests.nestRollChance(type.nestPermille, player.hunterLvl) <=
                gameRandom.randomDouble()
            ) {
                return@repeat
            }
            val clue = if (clues < BirdHouseNests.MAX_CLUES_PER_HOUSE) rollClueNest() else null
            if (clue != null) {
                clues++
                awardClueNest(clue)
            } else {
                awardOrDrop(BirdHouseNests.rollNestType(gameRandom, rabbitFoot), 1)
            }
        }

        // Stored x10, as every hunter experience column is.
        val xp = (type.hunterXp / 10.0) * xpMods.get(player, "stat.hunter")
        statAdvance("stat.hunter", xp)
    }

    /** The clue nest this successful roll gives, or null - the pre-roll, rarest tier first. */
    private fun ProtectedAccess.rollClueNest(): BirdHouseNests.ClueNest? =
        BirdHouseNests.CLUE_NESTS.firstOrNull { nest ->
            gameRandom.of(nest.denominatorFor(player)) == 0
        }

    /**
     * Awards one clue nest, or its *X Marks the Spot* substitute.
     *
     * "If the player has completed X Marks the Spot, clue nests are replaced with one scroll box and
     * one empty nest. Note that the empty nest drops on the ground instead of going to the
     * inventory." The transform goes through `content/drops`' own helper rather than a second copy
     * of the rule, which is what every drop table in the repo does with a clue.
     */
    private fun ProtectedAccess.awardClueNest(nest: BirdHouseNests.ClueNest) {
        val box = player.clueScrollTransformObj(nest.obj)
        if (box == null) {
            awardOrDrop(nest.obj, 1)
            return
        }
        awardOrDrop(box, 1)
        objRepo.add(
            BirdHouseNests.EMPTY_NEST,
            player.coords,
            BIRDHOUSE_DROP_CYCLES,
            receiver = player,
        )
    }

    /**
     * Puts [count] of [obj] in the backpack, or on the floor if it will not fit.
     *
     * The floor rather than a refusal, because the payout is atomic: by the time anything is awarded
     * the space has already been cleared, so a rejected item would simply cease to exist. Which of
     * the two live does with an overflowing bird house is unstated; dropping is the recoverable
     * direction, and the raw bird meat is already going there by rule.
     */
    private fun ProtectedAccess.awardOrDrop(obj: String, count: Int) {
        if (inv.freeSpace() >= hunterInvSlotsNeeded(inv, obj, count)) {
            invAdd(inv, obj, count)
            return
        }
        objRepo.add(obj, player.coords, BIRDHOUSE_DROP_CYCLES, receiver = player, count = count)
    }

    private fun Player.setBirdHouseState(space: BirdHouseSpace, state: Int) {
        VarPlayerIntMapSetter.set(this, space.varp, state)
    }

    private fun String.objName(): String =
        ServerCacheManager.getItem(asRSCM(RSCMType.OBJ))?.name?.lowercase() ?: "bird house"

    companion object {
        /**
         * How long a bird house takes to fill, in minutes.
         *
         * Fifty, stated on five separate pages and never qualified, and **tier-independent**: the
         * per-tier table has no timer column and no tier page names a different figure. The wiki
         * hedges ("around", "about"), so this is the round number every source uses rather than a
         * measured one.
         */
        const val BIRDHOUSE_FILL_MINUTES: Int = 50

        /** Game cycles in a minute, at 0.6s a cycle. */
        const val BIRDHOUSE_CYCLES_PER_MINUTE: Int = 100

        /**
         * `obj.poh_clockwork_mechanism` (8792) is `name=Clockwork`; the `poh_` prefix is
         * historical.
         */
        const val CLOCKWORK: String = "obj.poh_clockwork_mechanism"

        /** `obj.chisel` (1755). Held, not consumed. */
        const val CHISEL: String = "obj.chisel"

        /** `obj.hammer` (2347), the same one the crab trap builds with. Held, not consumed. */
        const val HAMMER: String = "obj.hammer"

        /**
         * `seq.birdhouse_make` (7057).
         *
         * The cache's own, and it names its own materials: `replaceheldright=hammer`,
         * `replaceheldleft=logs` - which is independent confirmation that those two are what the
         * action holds, from a source that is not the wiki.
         */
        const val BIRDHOUSE_MAKE_SEQ: String = "seq.birdhouse_make"

        /** `obj.spit_raw_bird_meat` (9978). There is no bare `raw_bird_meat` symbol. */
        const val RAW_BIRD_MEAT: String = "obj.spit_raw_bird_meat"

        /** Published, and the same on every tier. */
        const val RAW_BIRD_MEAT_COUNT: Int = 10

        const val FEATHER: String = "obj.feather"

        /**
         * How long a payout that went to the floor stays there.
         *
         * The same figure the net trap drops its rope and net for, and for the same absence of a
         * source: nothing states a duration for a bird house drop. 100 cycles is ~1 minute.
         */
        const val BIRDHOUSE_DROP_CYCLES: Int = 100
    }
}
