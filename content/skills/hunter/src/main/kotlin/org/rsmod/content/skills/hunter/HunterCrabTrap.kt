package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.constructionLvl
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.loc.BoundLocInfo

/** The queue a baited trap's crab arrives on; see [HunterCrabTrap.baitCrabTrap]. */
const val CRAB_CATCH_QUEUE: String = "queue.hunter_crab_catch"

/**
 * Crab trapping: build the trap, bait it, wait, empty it.
 *
 * **Structurally unlike every other technique in this module, and the reason it is its own script
 * rather than a sixth [TrapFamily].** A crab trap is not an object in the world at all. The map
 * places a `crab_trap_<site>_<n>` loc carrying no ops and a `multivar`; the client draws whichever of
 * that loc's `multiloc` children the *viewing player's* varbit selects. So the server's whole job is
 * to write a varbit, and:
 * - **`locRepo` is never touched.** Not `del`, not `change`, not `add`. The invariant the deadfall
 *   and net trap need a hard `check` to enforce - never delete a permanent map loc - is satisfied
 *   structurally here, because this class holds no reference to a loc repository to break it with.
 * - **Traps are private.** Two players baiting the same hole do not contend, so there is no owner to
 *   record, no controller to anchor and no tile to key anything on.
 * - **There is no roll.** "Unlike other methods, players cannot fail to catch a crab" (wiki, *Crab
 *   trapping*), restated in that page's Strategy section as the reason the guild hunter outfit and
 *   anti-odour salt have no effect here. A baited trap fills on a fixed timer, so no `(low, high)`
 *   pair is stored and [GameRandom] is drawn from **only** to pick the rainbow crab's colourway.
 *
 * Filing it as a [TrapFamily] would have meant answering that enum's questions with placeholders -
 * what obj is it laid from, what is its trigger radius, what is its attempt cadence, what does its
 * failure state look like - for a technique that has none of those, and growing six `when`
 * expressions in [HunterTrap] with a branch that never runs.
 *
 * ## The three steps
 *
 * **Build** ([buildCrabTrap]) is the first cross-skill gate in the module: 10 Construction, a saw and
 * a hammer held, and one bucket, one plank and two nails consumed. It is not Hunter-gated - "All
 * traps can be built immediately, regardless of level" - and it is permanent: "Built traps remain
 * there permanently and cannot be removed; they only need to be built once."
 *
 * **Bait** ([baitCrabTrap]) is the first *mandatory* bait in the module. Every other technique treats
 * bait as an unmodelled bonus; here it is a distinct op on a distinct loc state, and an unbaited trap
 * catches nothing ever, because nothing is scheduled until it is baited. This is where the Hunter
 * level and the trap cap are checked.
 *
 * **Empty** ([emptyCrabTrap]) covers both of the states that carry it: a full trap hands over the
 * crab and its xp, a baited-but-empty one hands the bait back.
 */
class HunterCrabTrap
@Inject
constructor(
    // Named `gameRandom`, not `random`, for the reason [HunterButterfly] documents: a field called
    // `random` inside a `ProtectedAccess` extension is silently shadowed by the receiver's own.
    private val gameRandom: GameRandom,
    private val xpMods: XpModifiers,
) {
    /**
     * `Build-trap` on a hole.
     *
     * Everything is verified before anything is consumed. There is no delay in this op, so nothing
     * can change between the checks and the deletes - but the deletes are still unwound on failure
     * rather than trusted, because a half-built trap would charge the player for a hole that is
     * still a hole.
     *
     * @return false, with a message already sent, if the trap was not built.
     */
    fun ProtectedAccess.buildCrabTrap(loc: BoundLocInfo): Boolean {
        val site = CrabTrapSites.byLocId(loc.id) ?: return false
        val state = crabTrapState(site)
        if (state != site.unbuiltState) {
            // Live's own wording is server-sent and not recoverable offline; this string is ours.
            mes("There is already a trap built here.")
            return false
        }

        if (player.constructionLvl < CRAB_TRAP_CONSTRUCTION_LEVEL) {
            mes("You need a Construction level of $CRAB_TRAP_CONSTRUCTION_LEVEL to build a crab trap.")
            return false
        }

        if (CRAB_TRAP_SAWS.none(inv::contains) || !inv.contains(CRAB_TRAP_HAMMER)) {
            mes("You need a saw and a hammer to build a crab trap.")
            return false
        }

        // Resolved before anything is charged: "two nails of any type", and which type live picks
        // when several are carried is unstated, so first-by-slot-order is ours - the convention
        // [HunterTrap.setDeadfall] uses to pick a log. Unlike that pick this one also requires the
        // stack to be big enough, because a single leftover bronze nail in the first slot must not
        // refuse a build the hundred steel nails further down could pay for.
        val nails = inv.firstNotNullOfOrNull { obj -> obj?.let { nailObj(it) } }
        val materials =
            listOf(CRAB_TRAP_PLANK to 1, CRAB_TRAP_BUCKET to 1) +
                listOfNotNull(nails?.let { it to CRAB_TRAP_NAIL_COUNT })
        val missing =
            nails == null || materials.any { (obj, count) -> inv.count(obj) < count }
        if (missing) {
            mes("You need a plank, a bucket and $CRAB_TRAP_NAIL_COUNT nails to build a crab trap.")
            return false
        }

        val charged = mutableListOf<Pair<String, Int>>()
        for ((obj, count) in materials) {
            if (invDel(inv, obj, count).failure) {
                for ((refund, amount) in charged) {
                    invAdd(inv, refund, amount)
                }
                mes(
                    "You need a plank, a bucket and $CRAB_TRAP_NAIL_COUNT nails to build a crab trap."
                )
                return false
            }
            charged += obj to count
        }

        setCrabTrapState(site, site.builtState)
        mes("You build a crab trap over the hole.")
        return true
    }

    /**
     * `Bait` on a built, empty trap.
     *
     * The Hunter level is checked here rather than at build time because that is where the wiki puts
     * it: building is level-free and only the number of *active* traps is capped. Which bait the
     * trap takes is the site's, not the player's choice - red and blue traps take fish offcuts and
     * rainbow traps fine fish offcuts, and the cache renders a different baited model for each.
     *
     * @return false, with a message already sent, if the trap was not baited.
     */
    fun ProtectedAccess.baitCrabTrap(loc: BoundLocInfo): Boolean {
        val site = CrabTrapSites.byLocId(loc.id) ?: return false
        val state = crabTrapState(site)
        if (state == site.unbuiltState) {
            mes("You need to build a trap here first.")
            return false
        }
        if (state != site.builtState) {
            mes("That trap is already baited.")
            return false
        }

        val creature = site.creature
        if (player.hunterLvl < creature.level) {
            mes("You need a Hunter level of ${creature.level} to use this trap.")
            return false
        }

        val cap = crabTrapCap(player.hunterLvl)
        if (player.activeCrabTraps() >= cap) {
            val plural = if (cap == 1) "trap" else "traps"
            mes("You can only have $cap active $plural at your Hunter level.")
            return false
        }

        if (invDel(inv, creature.bait, 1).failure) {
            val name = creature.bait.baitName()
            mes("You need some $name to bait this trap.")
            return false
        }

        setCrabTrapState(site, site.baitedState)

        // Soft, and on the player rather than on the tile. A crab trap has no controller to hang a
        // timer off - there is nothing of it in the world - and the whole loop is to bait a trap and
        // walk to the next one, so the arrival must not interrupt whatever the player is doing when
        // it lands.
        player.softQueue(CRAB_CATCH_QUEUE, creature.catchDelay, site.index)
        return true
    }

    /**
     * `Empty` on a baited or a full trap.
     *
     * Both states carry the same op, and this is both transactions:
     * - **full** hands over the crab and its xp, and leaves the trap built and empty;
     * - **baited** hands the bait back and leaves the trap built and empty.
     *
     * That the bait comes back is **unsourced** - no source says what emptying a trap the crab has
     * not reached yet does with the offcuts. Returning them is the recoverable half of that
     * uncertainty, the same argument [HunterTrap.dismantleDeadfall] makes for the log: a mis-click
     * that silently ate an item is worse than one that did not. It is also what keeps a trap baited
     * across a logout from being stuck forever - see [rearmCrabTrapCatches].
     *
     * @return false, with a message already sent where one is warranted, if nothing was emptied.
     */
    fun ProtectedAccess.emptyCrabTrap(loc: BoundLocInfo): Boolean {
        val site = CrabTrapSites.byLocId(loc.id) ?: return false
        val state = crabTrapState(site)

        val variant = site.variantAt(state)
        if (variant == null) {
            if (state != site.baitedState) {
                mes("There is nothing in that trap.")
                return false
            }
            if (inv.freeSpace() < hunterInvSlotsNeeded(inv, site.creature.bait, 1)) {
                mes("Your inventory is too full to hold any more.")
                soundSynth("synth.pillory_wrong")
                return false
            }
            invAdd(inv, site.creature.bait, 1)
            setCrabTrapState(site, site.builtState)
            return true
        }

        if (inv.freeSpace() < hunterInvSlotsNeeded(inv, variant.caught, 1)) {
            mes("Your inventory is too full to hold any more.")
            soundSynth("synth.pillory_wrong")
            return false
        }

        invAdd(inv, variant.caught, 1)

        // Creature xp is stored x10 so fractional values survive the table.
        val xp = (site.creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
        statAdvance("stat.hunter", xp)

        setCrabTrapState(site, site.builtState)
        return true
    }

    /**
     * The body of [CRAB_CATCH_QUEUE]: the crab has reached the trap.
     *
     * Re-checked rather than assumed, because the queue outlives the state that scheduled it: the
     * player may have emptied the trap - taking the bait back - in the meantime, and a matured catch
     * landing on an unbaited trap would mint a crab out of nothing. This is what "the trap does
     * nothing until it is baited" reduces to in code, and it is the one branch a live client cannot
     * be made to exercise.
     *
     * Not a [ProtectedAccess] extension: a crab arriving is not a player action, and it must land
     * while the player is walking, banking or fighting rather than wait for them to be free.
     */
    fun Player.crabTrapCatchArrives(siteIndex: Int) {
        val site = CrabTrapSites.all.getOrNull(siteIndex) ?: return
        if (crabTrapState(site) != site.baitedState) {
            return
        }

        // The only random draw in the whole technique, and only when there is a real choice: red and
        // blue have one colourway, so they never consume one.
        val variants = site.creature.variants
        val pick = if (variants.size == 1) 0 else gameRandom.of(variants.size)
        setCrabTrapState(site, site.fullStates[pick])
    }

    /**
     * Re-arms a pending catch for every trap this player left baited.
     *
     * The two halves of a baited trap have different lifetimes: the varbit is persisted with the
     * player, the queue is not. Without this, a trap baited a second before a logout would come back
     * baited forever, and its owner would have to empty it - getting the bait back - to use it again.
     * Re-queuing the full delay is the honest reading of "after a set period of time": the alternative
     * of maturing every baited trap on login would invent a rule that time away from the game counts.
     */
    fun Player.rearmCrabTrapCatches() {
        for (site in CrabTrapSites.all) {
            if (crabTrapState(site) == site.baitedState) {
                softQueue(CRAB_CATCH_QUEUE, site.creature.catchDelay, site.index)
            }
        }
    }

    /** How many of this player's traps are baited or full, which is what the cap counts. */
    fun Player.activeCrabTraps(): Int =
        CrabTrapSites.all.count { it.isActive(crabTrapState(it)) }

    /** The varbit value this player's copy of [site] is currently showing. */
    fun Player.crabTrapState(site: CrabTrapSite): Int = vars[site.varbit]

    private fun ProtectedAccess.crabTrapState(site: CrabTrapSite): Int =
        player.crabTrapState(site)

    private fun ProtectedAccess.setCrabTrapState(site: CrabTrapSite, state: Int) {
        player.setCrabTrapState(site, state)
    }

    /**
     * Writes the site's own `multiVarBit`, which is the entire server side of a crab trap.
     *
     * `VarPlayerIntMapSetter` rather than an `intVarBit` delegate, because the varbit is resolved
     * from the loc at runtime rather than named in source - twenty sites would otherwise be twenty
     * delegates that could each be paired with the wrong loc.
     */
    private fun Player.setCrabTrapState(site: CrabTrapSite, state: Int) {
        VarPlayerIntMapSetter.set(this, site.varbit, state)
    }

    /** The `Fish offcuts` / `Fine fish offcuts` half of a "you need some X" message. */
    private fun String.baitName(): String =
        ServerCacheManager.getItem(asRSCM(RSCMType.OBJ))?.name?.lowercase() ?: "bait"

    /**
     * The nail obj [held] is, or null if it is not a nail or the stack is too small to build with.
     *
     * Reverse-mapped from the held obj's id rather than compared by name, because an [InvObj] only
     * carries an id - the same route `HunterTrap.deadfallLogObj` takes back to a symbol.
     */
    private fun ProtectedAccess.nailObj(held: InvObj): String? {
        if (held.id !in nailIds) {
            return null
        }
        val obj = RSCM.getReverseMapping(RSCMType.OBJ, held.id) ?: return null
        return obj.takeIf { inv.count(it) >= CRAB_TRAP_NAIL_COUNT }
    }

    companion object {
        /**
         * The Construction level a crab trap is built at.
         *
         * Twice sourced and in agreement. The wiki: "Players must first build hinged-lid traps over
         * holes at set locations, requiring 10 Construction to do so." The cache says it too, and
         * per creature: `skill_feature_hunter_red_crab` (dbrow 11798) and its blue and rainbow
         * siblings each carry `data=skill,22,10,-1` alongside their Hunter requirement, where 22 is
         * Construction in the client's skill enum and the `-1` marks it as belonging to no feature
         * group of its own.
         *
         * It is a single constant rather than a column because all three rows carry the same 10, and
         * a column repeated identically three times invites the belief that it varies.
         */
        const val CRAB_TRAP_CONSTRUCTION_LEVEL: Int = 10

        /** `obj.woodplank` (960) is `name=Plank`; there is no `obj.plank`. */
        const val CRAB_TRAP_PLANK: String = "obj.woodplank"

        /** `obj.bucket_empty` (1925) is `name=Bucket`; the plain `obj.bucket` is a milk bucket. */
        const val CRAB_TRAP_BUCKET: String = "obj.bucket_empty"

        /** `obj.hammer` (2347). */
        const val CRAB_TRAP_HAMMER: String = "obj.hammer"

        /** "one bucket, one normal plank, and two nails of any type for each trap." */
        const val CRAB_TRAP_NAIL_COUNT: Int = 2

        /**
         * The saws a build accepts.
         *
         * The wiki names only "a saw". `obj.poh_saw` (8794, `name=Saw`) is that saw; the crystal saw
         * and Amy's saw are the two items that stand in for it everywhere else in Construction, and
         * refusing them would make this the one build in the game they do not work for.
         *
         * **Unsourced for this technique specifically** - no page says a crystal saw builds a crab
         * trap. Accepting them is the recoverable direction of that uncertainty: a player refused a
         * legitimate tool is stuck, where a player allowed one is merely ahead of the source. Live's
         * tool leniency also extends to a toolbelt this server does not model, so the check is on the
         * inventory alone.
         */
        val CRAB_TRAP_SAWS: List<String> =
            listOf("obj.poh_saw", "obj.eyeglo_crystal_saw", "obj.wearable_saw")

        /**
         * Every nail "of any type", which is eight distinct stackable objs.
         *
         * Note what is **not** here: `obj.any_nails` (32923). It looks like exactly the right answer
         * - it is even `name=Steel nails` - and it is a display-only duplicate with no cost, no cert
         * and no placeholder link, so no player can ever hold one. `obj.nails` (1539) is the real
         * steel nails, and is the one entry whose symbol does not say which metal it is.
         */
        val CRAB_TRAP_NAILS: List<String> =
            listOf(
                "obj.nails_bronze",
                "obj.nails_iron",
                "obj.nails",
                "obj.nails_black",
                "obj.nails_mithril",
                "obj.nails_adamant",
                "obj.nails_rune",
                "obj.nails_dragon",
            )

        /** Resolved once, so a nail gameval that does not exist fails at class load, not at a build. */
        private val nailIds: Set<Int> by lazy {
            CRAB_TRAP_NAILS.mapTo(HashSet()) { it.asRSCM(RSCMType.OBJ) }
        }

        /**
         * How many traps may be baited or full at once.
         *
         * "the number of simultaneous active (baited or full) traps is limited by Hunter level: 21 →
         * 2, 40 → 3, 60 → 4, 80 → 5" (wiki, *Crab trapping > Setting up*). This is the ordinary
         * hunter trap ladder with its bottom rung removed, and it agrees with `HunterTrap`'s
         * `trapCap` at every level this technique is reachable at, since the lowest crab is 21 and
         * that function's `1` applies only below 20. It is written out again rather than shared
         * because it is a different table from a different source that happens to coincide, and
         * because `trapCap` counts controllers on tiles, which a crab trap has none of.
         *
         * Read from the effective level, so a boost raises the cap.
         */
        fun crabTrapCap(level: Int): Int =
            when {
                level >= 80 -> 5
                level >= 60 -> 4
                level >= 40 -> 3
                else -> 2
            }
    }
}
