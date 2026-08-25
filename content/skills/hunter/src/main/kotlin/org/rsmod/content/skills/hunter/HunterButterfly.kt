package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.utils.skills.SkillingSuccessRate
import org.rsmod.game.entity.Npc

/** The plain net, `obj.hunting_butterfly_net` (10010), `name=Butterfly net`, worn in the hand. */
const val BUTTERFLY_NET: String = "obj.hunting_butterfly_net"

/**
 * The Temple Trekking reward net, `obj.ii_magic_butterfly_net` (11259).
 *
 * A separate item rather than a variant: it has its own model, its own `wearpos=righthand` entry and
 * `tradeable=no`, and the wiki charts it as its own catch-rate series.
 */
const val MAGIC_BUTTERFLY_NET: String = "obj.ii_magic_butterfly_net"

/** The empty jar a catch consumes, `obj.butterfly_jar` (10012). One per butterfly kept. */
const val BUTTERFLY_JAR: String = "obj.butterfly_jar"

/** The net-swing animation, `seq.human_butterflynet_swing` (5209). Real, and not invented here. */
const val BUTTERFLY_NET_SWING: String = "seq.human_butterflynet_swing"

private const val MAX_HUNTER_LEVEL: Int = 99

/**
 * Butterfly netting: click the creature, roll once, done.
 *
 * **Structurally the simplest technique in the skill, and the reason this shares no code with
 * [HunterTrap].** Nothing is laid, nothing is transformed, nothing is spawned and nothing is
 * remembered - so there is no loc, no [TrapFamily] entry, no controller, no varcon, no timeout and
 * no trap cap. It borrows one idea from [HunterFalconry], the only other non-trap technique: the
 * creature table is read into its own list, indexed by nothing, so the trap engine's persisted
 * indices cannot be disturbed by adding to it.
 *
 * The whole of the mechanic, from *Butterfly (Hunter)* (oldid=15242004):
 * - "Butterflies and moths are a type of Hunter creature that can be caught while wielding a
 *   butterfly net or magic butterfly net. They can also be caught barehanded, which requires a
 *   Hunter level 10 above the normal requirement."
 * - "When released (or caught without a butterfly jar in the inventory), butterflies will boost the
 *   player's combat skills. Catching a butterfly in a jar allows it to be stored away for later."
 *
 * Read that second line carefully, because the obvious reading of it is wrong: the *reward* does not
 * depend on whether a net was used. It depends on whether an empty jar is carried. Barehanded is a
 * level gate and nothing else - same xp, same jar, same curve shape.
 *
 * ## What is not modelled
 *
 * **The boost a jarless catch applies.** Each creature grants a different one (Attack +15%+4 for the
 * ruby harvest, Defence for the sapphire glacialis, 15 Hitpoints for the snowy knight, Strength for
 * the black warlock, a multi-stat restore for the sunlight moth), and the released-from-a-jar half
 * of that same effect is a separate `Release` item op on six objs. A jarless catch here awards the
 * Hunter xp, removes the creature and says so; it does not boost. That is a stated gap, not an
 * oversight - the alternative was to refuse a jarless catch, which would be an invention, or to
 * ship six half-wired stat effects on the back of a data slice.
 */
class HunterButterfly
@Inject
constructor(
    private val npcRepo: NpcRepository,
    // Named `gameRandom`, not `random`. `ProtectedAccess` has a `random` property of its own and an
    // extension receiver's member wins over the dispatch receiver's field, so a field called
    // `random` here would be silently shadowed at every use site and the catch roll would draw from
    // the player's context RNG instead of the injected one - compiling, running, and untestable.
    // The same trap [HunterFalconry] documents; it is a `ProtectedAccess` extension for the same
    // reason.
    private val gameRandom: GameRandom,
    private val xpMods: XpModifiers,
) {
    /**
     * `Catch` on a butterfly: gate on level, roll once, and jar it if there is a jar.
     *
     * There is no delay and no lock. Falconry suspends for the bird's flight because the falcon
     * physically travels; a net swing lands on the tile the player is already standing next to, and
     * no source describes a wait. That is also why nothing re-checks the target afterwards the way
     * `catchKebbit` has to - nothing can have happened in between.
     *
     * @return true only if the butterfly was caught; false covers a miss, an under-levelled attempt
     *   and an npc that is not a butterfly at all.
     */
    fun ProtectedAccess.catchButterfly(target: Npc): Boolean {
        val creature = ButterflyCreatures.byNpcId(target.visType.id) ?: return false

        val barehanded = !isHoldingNet()
        val required = creature.level + if (barehanded) BAREHANDED_LEVELS else 0

        // Gated before the roll, exactly as the trap tick and the falcon gate their own, so an
        // under-levelled attempt never consumes a random draw. Every butterfly has a positive
        // `successLow`, so none of them refuses an under-levelled hunter implicitly.
        if (player.hunterLvl < required) {
            val how = if (barehanded) " barehanded" else ""
            mes("You need a Hunter level of $required to catch this$how.")
            return false
        }

        faceEntitySquare(target)
        if (!barehanded) {
            anim(BUTTERFLY_NET_SWING)
        }

        val bonus = if (usesFasterCurve()) NET_BONUS else 0
        val caught =
            SkillingSuccessRate.successRate(
                low = creature.successLow + bonus,
                high = creature.successHigh + bonus,
                level = player.hunterLvl,
                maxLevel = MAX_HUNTER_LEVEL,
            ) > gameRandom.randomDouble()

        if (!caught) {
            // A miss leaves the butterfly where it is - "Multiple players may attempt to catch a
            // given butterfly at the same time", so it is not consumed by being tried for.
            mes("You fail to catch the butterfly.")
            return false
        }

        // Jarred *before* the creature is removed, so a jar swap that somehow fails leaves the
        // butterfly on the map rather than deleting it for nothing.
        val jarred = jarCatch(creature)

        npcRepo.despawn(target, target.visType.respawnRate)

        // Creature xp is stored x10 so fractional values survive the table. Awarded on the catch,
        // not on collection - unlike every other technique, because there is nothing to collect.
        val xp = (creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
        statAdvance("stat.hunter", xp)

        if (!jarred) {
            // The wiki's own words for this branch are about the boost, which is not modelled; this
            // says what actually happens instead. The live server string is not recoverable offline.
            mes("You have no empty jar, so the butterfly flies away.")
        }
        return true
    }

    /**
     * Swaps one empty jar for this creature's filled one.
     *
     * **Never needs a free slot**, which is why there is no space check here and no
     * `hunterInvSlotsNeeded` call: both jars are non-stackable, so the delete frees exactly the slot
     * the add takes and a player with a completely full inventory can still jar a catch. That is the
     * behaviour the wiki implies - jars are the consumable, and running out of *space* is not how
     * you run out of jars.
     *
     * The delete has to succeed before the add runs, for the reason [HunterFalconry]'s glove swap
     * does: an add-then-delete ordering that failed halfway would mint a filled jar out of nothing.
     *
     * @return false when the player is carrying no empty jar, which is a legal catch, not an error.
     */
    private fun ProtectedAccess.jarCatch(creature: ButterflyCreature): Boolean {
        if (!inv.contains(BUTTERFLY_JAR)) {
            return false
        }
        if (invDel(inv, BUTTERFLY_JAR, 1).failure) {
            return false
        }
        for (reward in creature.caught) {
            invAdd(inv, reward.obj, rollQuantity(reward.quantity))
        }
        return true
    }

    /**
     * Whether either net is *worn*, which is what "wielding" means: both nets are
     * `wearpos=righthand` with `iop2=Wield`, so a net sitting in the inventory is not being used and
     * the attempt is barehanded.
     */
    private fun ProtectedAccess.isHoldingNet(): Boolean =
        worn.contains(BUTTERFLY_NET) || worn.contains(MAGIC_BUTTERFLY_NET)

    /**
     * Whether this attempt gets [NET_BONUS] - true for the magic net and for barehanded, false for
     * the plain net.
     *
     * Barehanded is on the faster curve on the strength of two of three sources. The black warlock's
     * chart labels its two series "Butterfly net" and "Barehanded or Magic butterfly net", and void
     * applies its own `+20` to "Barehanded or magic net" from an unrelated derivation. The sunlight
     * moth's chart disagrees, labelling them "Barehanded or butterfly net" and "Magic butterfly
     * net" - but the two charts are otherwise pointwise identical, so one of the two labels is
     * simply stale, and it is the one with no corroboration.
     */
    private fun ProtectedAccess.usesFasterCurve(): Boolean =
        worn.contains(MAGIC_BUTTERFLY_NET) || !isHoldingNet()

    /** A fixed quantity costs no random draw; only a real range consumes one. */
    private fun rollQuantity(quantity: IntRange): Int =
        if (quantity.first == quantity.last) quantity.first else gameRandom.of(quantity)

    companion object {
        /**
         * How far above a butterfly's own requirement a barehanded catch sits.
         *
         * "They can also be caught barehanded, which requires a Hunter level 10 above the normal
         * requirement." (*Butterfly (Hunter)*, oldid=15242004.) Note that void's table says 80/85/
         * 90/95 for these same four creatures rather than 25/35/45/55 - that is the RS3 rule, and
         * this server is OSRS, so the wiki's flat +10 is what ships.
         */
        const val BAREHANDED_LEVELS: Int = 10

        /**
         * What the magic net, or a barehanded catch, adds to both coefficients.
         *
         * Twice sourced and exact. Both published charts carry a second series which fits
         * `(40, 316)` against the plain net's `(20, 296)` - `+20` on each - and void reaches the
         * same `+20` from its own derivation. A constant rather than a second column pair because it
         * is the same offset for every creature, and because a per-row pair would force a second
         * guess onto each of the three rows that already carry one.
         */
        const val NET_BONUS: Int = 20
    }
}
