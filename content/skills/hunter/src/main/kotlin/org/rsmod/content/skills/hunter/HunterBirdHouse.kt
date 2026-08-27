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
 * The wall clock a bird house fills against - a seam so a test can wind fifty minutes forward.
 */
fun interface BirdHouseClock {
    fun epochMinute(): Int
}

/**
 * The import is aliased because it must be: an unaliased `epochMinute()` inside a same-named
 * method compiles into silent infinite recursion - the falconry shadowing shape again.
 */
object SystemBirdHouseClock : BirdHouseClock {
    override fun epochMinute(): Int = wallClockMinute()
}

/**
 * Bird house trapping: place a house, seed it, wait fifty minutes, harvest. The first technique on
 * wall-clock time: the deadline is an epoch minute in a saved varp and the soft queue is only the
 * in-session half - lose the queue and nothing breaks, lose the varp and the house never fills.
 * Structurally the crab trap one step on (a varp instead of a varbit), with the same invariants:
 * no loc repository, private per player, nothing in the world. The five transactions and their
 * sources: docs/hunter.md.
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
     * Why [craftBirdHouse] would refuse [type] right now, or null - the make menu must decide
     * *before* it opens, since `openSkillMulti` shows nothing when no entry is affordable.
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
     * A clockwork used on [type]'s logs: one bird house and the tier's *Crafting* experience - the
     * only way a bird house enters the game. The requirement is Crafting, read from the effective
     * level; both tools are held, not consumed. The Imcando hammer's animation variant is not
     * played. See docs/hunter.md.
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
     * `Build` places the *best* carried house the effective Hunter level allows (published
     * behaviour). No experience: no source gives a Hunter figure for building - an absence across
     * four pages, not a stated zero.
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
     * `Seeds`, greedy over the whole inventory ("insert as many as possible"); high-value first is
     * ours, unsourced either way. A partial insert is remembered and the house starts filling on
     * the click that completes it.
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
     * `Interact` reports status and does not act: the cache carries the op and nothing says what
     * it does; making the left-click harvest would be the guess with a cost.
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
     * The early abort: the clockwork comes back and nothing else does - published, and the whole
     * reason the payout lives on the other state's op. Live's warning prompt is not implemented.
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
     * `Empty`, and the second half of `Reset`. The space is cleared first so a payout that runs
     * out of inventory drops to the ground rather than leaving a paid-out house standing; the
     * clockwork reuse falls out of the ordering.
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
     * The body of the fill queue - re-checked against the varps, because the queue outlives the
     * state that scheduled it, and a matured fill on a bare space would show a house nobody built.
     * Not a [ProtectedAccess] extension: filling is not a player action.
     */
    fun Player.birdHouseFillArrives(spaceIndex: Int) {
        val space = BirdHouseSpaces.all.getOrNull(spaceIndex) ?: return
        matureBirdHouse(space)
    }

    /**
     * Matures the space if its deadline elapsed while nothing was watching - the self-healing half
     * of the timer, and why a lost soft queue costs nothing.
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
     * Rebuilds every pending fill from the varps at login. A house whose deadline already passed
     * is queued for the next cycle rather than written here: `VarPlayerIntMapSetter` skips its
     * transmit branch while `processedMapClock == 0` - exactly the login-event state - so a direct
     * write would leave the client drawing a house full of seeds (docs/hunter.md).
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
     * The house that gets placed: the best carried, or one made on the spot from materials.
     *
     * @return the tier now sitting in the backpack, or null with a message already sent.
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

    // Both level gates: a house made here is placed in the same action, so making a tier the
    // Hunter level cannot place would spend the logs on nothing.
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
     * Everything a depleted house gives, in the published order - guaranteed drops, one seed-nest
     * roll, ten nest rolls each of which may pre-roll a clue. The order matters to scripted draws.
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
     * One clue nest, or its X Marks the Spot substitute (scroll box + empty nest on the ground),
     * through `content/drops`' own helper.
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
     * Backpack, or the floor if it will not fit: the payout is atomic - the space is already
     * cleared, so a rejected item would simply cease to exist; dropping is the recoverable
     * direction.
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
         * Fifty, stated on five pages, never qualified, tier-independent - the round number every
         * source uses rather than a measured one.
         */
        const val BIRDHOUSE_FILL_MINUTES: Int = 50

        /** Game cycles in a minute, at 0.6s a cycle. */
        const val BIRDHOUSE_CYCLES_PER_MINUTE: Int = 100

        /** `name=Clockwork`; the `poh_` prefix is historical. */
        const val CLOCKWORK: String = "obj.poh_clockwork_mechanism"

        /** `obj.chisel` (1755). Held, not consumed. */
        const val CHISEL: String = "obj.chisel"

        /** `obj.hammer` (2347), the same one the crab trap builds with. Held, not consumed. */
        const val HAMMER: String = "obj.hammer"

        /**
         * The cache's own, and it names its own held materials - independent confirmation of the
         * tools from a source that is not the wiki.
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
