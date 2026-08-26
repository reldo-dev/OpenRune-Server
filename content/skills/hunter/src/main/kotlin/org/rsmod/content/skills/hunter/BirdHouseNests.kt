package org.rsmod.content.skills.hunter

import kotlin.math.max
import org.rsmod.api.random.GameRandom
import org.rsmod.api.utils.skills.SkillingSuccessRate
import org.rsmod.content.drops.easyClueDropDenominator
import org.rsmod.content.drops.eliteClueDropDenominator
import org.rsmod.content.drops.hardClueDropDenominator
import org.rsmod.content.drops.mediumClueDropDenominator
import org.rsmod.game.entity.Player

/**
 * What a harvested bird house pays out, and where every number in it came from.
 *
 * A harvest is not deterministic: there are **two** rolls, on two different curves, and only one
 * of them is an ordinary skilling rate.
 *
 * ## Roll one: the seed nest
 *
 * "Each birdhouse will give **at most 1 seed nest**", at a rate that depends on Hunter level and
 * **not** on tier. The chart on *Bird house trapping > Loot > Seed nest* is a plain
 * `{{Skilling success chart}}` whose own parameters are `low = 0, high = 200, req = 5` - read out of
 * the snapshot's Parsoid `data-mw`, not fitted, so this pair is exact rather than one of a family of
 * fits. That makes the roll literally [SkillingSuccessRate.successRate], the same function every
 * catch in this module runs on, and it needs no column of its own.
 *
 * It checks out against Mod Ash (5 June 2019): "a chance of 0% at level 1 and 78.5% at level 99" -
 * `1/256 = 0.39 %` and `201/256 = 78.52 %`, his 0% being a rounding. `req = 5` only controls where
 * the chart switches from dashed to solid; it is **not** a gate, and the roll is made at every
 * level.
 *
 * Unaffected by tier (published) and unaffected by the strung rabbit foot: "It does not make you
 * more likely to get the seed nest" (Mod Ash, 24 July 2021). That is the opposite of the woodcutting
 * interaction, and the wiki says so explicitly.
 *
 * ## Roll two: ten rolls on the nest table
 *
 * "The nest table will be rolled **10 times**. The chance of a successful roll is based on both type
 * of birdhouse and Hunter level … The rate is constant at levels below 50."
 *
 * This is **not** a `(low, high)/256` curve - 10.0 % at level 99 is not a multiple of 1/256 - so it
 * cannot reuse the engine formula. Mod Ash gives the nine level-99 endpoints (already shipped as the
 * `nest_permille` column) and the rule "these scale down by half at level 50". Flat below 50, then
 * the wiki's chart ramps linearly from half to full between 50 and 99. Those three facts collapse to
 * one exact rational, [nestRollChance], with the knee continuous at 50 by construction.
 *
 * **Say which half is sourced.** The endpoints and the halving are Jagex's. The linear 50-to-99
 * ramp is the **wiki's own model**, with no Jagex statement behind it - it is the single largest
 * piece of unsourced arithmetic in this file. Extrapolating past 99 is likewise unmodelled shape,
 * though that boosting above 99 keeps helping *is* published.
 *
 * ## What a successful roll gives
 *
 * A clue **pre-roll** first, then the ring/egg/empty table. The ordering matters: folding clues into
 * the type table as a sixth slot would drift the ring and empty rates by about 2.7 %.
 */
internal object BirdHouseNests {
    /* Roll one. */

    /** The seed nest's published `low`. */
    const val SEED_NEST_LOW: Int = 0

    /** The seed nest's published `high`. */
    const val SEED_NEST_HIGH: Int = 200

    /** The nest a successful seed roll gives. */
    const val SEED_NEST: String = "obj.bird_nest_seeds_jan2019"

    /* Roll two. */

    /** How many times the nest table is rolled per house. Published, and constant. */
    const val NEST_ROLLS: Int = 10

    /**
     * The level at and below which the ten-roll rate is flat at half the tier's endpoint.
     *
     * Published as a rule ("the rate is constant at levels below 50", "these scale down by half at
     * level 50"), and level 50 itself is inside the flat region - the wiki's chart is flat *through*
     * 50 and rises from 51.
     */
    const val NEST_RATE_KNEE: Int = 50

    /**
     * The denominator that makes the whole ten-roll rate one exact fraction.
     *
     * `nest_permille` is a rate out of 1,000 at level 99, halving at 50, and the ramp between is
     * linear over the 49 levels from 50 to 99. Written out, `half*(99-L) + high*(L-50)` over `49`
     * simplifies to `high*(L-1)/2`, so the whole curve - flat region included, since `max(L, 50) - 1`
     * is 49 throughout it - is `high * (max(L, 50) - 1) / 98_000`. Both endpoints fall out of that
     * identity rather than being special-cased: `high/2000` at 50 and `high/1000` at 99.
     */
    const val NEST_RATE_DENOMINATOR: Int = 98_000

    /* The nest type table. */

    /**
     * The type table's size without a strung rabbit foot.
     *
     * Rolled as an **index**, not as weights, because that is how Mod Ash described the
     * implementation (26 October 2018, for the woodcutting version this one is a copy of): "If
     * you've got the rabbit foot necklace, pick number 0-94 inclusive. Otherwise, pick number 0-99
     * inclusive. 0 = red egg, 1 = blue egg, 2 = green egg, 3-34 = ring, otherwise it's seeds." For
     * bird houses the "otherwise" outcome is the **empty** nest rather than the seed nest, which is
     * the only difference between the two tables.
     *
     * Rolling an index also makes the rabbit foot honest: it does not reweight anything, it removes
     * the five highest slots, which are five *empty* outcomes. Implementing it as "+5 % ring/egg"
     * gives slightly wrong numbers for every other row.
     */
    const val NEST_TYPE_SLOTS: Int = 100

    /** The type table's size with a strung rabbit foot worn. Five fewer empty slots. */
    const val NEST_TYPE_SLOTS_RABBIT_FOOT: Int = 95

    /** The highest index that is a ring nest; `0..2` are the three eggs and the rest are empty. */
    private const val LAST_RING_INDEX: Int = 34

    const val RING_NEST: String = "obj.bird_nest_ring"
    const val RED_EGG_NEST: String = "obj.bird_nest_egg_red"
    const val BLUE_EGG_NEST: String = "obj.bird_nest_egg_blue"
    const val GREEN_EGG_NEST: String = "obj.bird_nest_egg_green"
    const val EMPTY_NEST: String = "obj.bird_nest_empty"

    /**
     * Worn to shift five empty slots onto ring and egg nests.
     *
     * Twitcher's gloves are deliberately **not** checked: "The effect of the gloves does not apply
     * during bird house trapping" is published on the gloves' own page, and a technique that
     * silently honoured them would be wrong in a way no test elsewhere would catch.
     */
    const val STRUNG_RABBIT_FOOT: String = "obj.hunting_strung_rabbit_foot"

    /* Clue nests. */

    /**
     * The five clue nests, rarest first, with the base denominator of each.
     *
     * Published as `~1`, `~2`, `~3`, `~4` and `~30` out of 1,500 *conditional on a successful nest
     * roll*, which are exactly the denominators below. Every one of them is an estimate: the wiki
     * flags this whole section `{{incomplete}}` - "Clue nest mechanics are not confirmed" - and the
     * Combat Achievement magnitudes are absent entirely, so the repo's own 5 %-per-tier convention
     * (`*ClueDropDenominator`) stands in for them, the same as in every drop table here.
     *
     * Rarest first because at most one clue is given per house (below), so the order is the
     * priority.
     */
    val CLUE_NESTS: List<ClueNest> =
        listOf(
            ClueNest("obj.wc_clue_nest_elite", 1_500) { player, base ->
                player.eliteClueDropDenominator(base)
            },
            ClueNest("obj.wc_clue_nest_hard", 750) { player, base ->
                player.hardClueDropDenominator(base)
            },
            ClueNest("obj.wc_clue_nest_medium", 500) { player, base ->
                player.mediumClueDropDenominator(base)
            },
            ClueNest("obj.wc_clue_nest_easy", 375) { player, base ->
                player.easyClueDropDenominator(base)
            },
            // Beginner clues are outside the Combat Achievement reward tiers, so nothing scales this
            // one. 30/1500 reduces exactly.
            ClueNest("obj.wc_clue_nest_beginner", 50, null),
        )

    data class ClueNest(
        val obj: String,
        val denominator: Int,
        val combatAchievementScale: ((Player, Int) -> Int)?,
    ) {
        fun denominatorFor(player: Player): Int =
            combatAchievementScale?.invoke(player, denominator) ?: denominator
    }

    /**
     * At most one clue per bird house.
     *
     * The **only confirmed** statement about this: "If you get a clue nest, the remaining rolls from
     * the 10 will not have a chance of being a clue nest again" (Mod Ash, 19 June 2020). The wiki's
     * current text says both that multiple clues are possible *and* that "once at least one clue has
     * been awarded, further rolls do not attempt to give clues" - two sentences that contradict each
     * other, in a section the wiki itself marks unconfirmed. Where a source contradicts itself and a
     * dated developer statement does not, the developer wins.
     */
    const val MAX_CLUES_PER_HOUSE: Int = 1

    /* The models. */

    /** The chance of this house's single seed nest, at [hunterLevel]. */
    fun seedNestChance(hunterLevel: Int): Double =
        SkillingSuccessRate.successRate(
            SEED_NEST_LOW,
            SEED_NEST_HIGH,
            hunterLevel,
            MAX_HUNTER_LEVEL,
        )

    /**
     * The chance that one of the ten nest rolls succeeds, for a tier whose level-99 endpoint is
     * [nestPermille].
     *
     * See [NEST_RATE_DENOMINATOR] for why the flat region and the ramp are one expression.
     */
    fun nestRollChance(nestPermille: Int, hunterLevel: Int): Double =
        (nestPermille.toDouble() * (max(hunterLevel, NEST_RATE_KNEE) - 1)) / NEST_RATE_DENOMINATOR

    /** How many slots the type table has for a player wearing (or not wearing) a rabbit foot. */
    fun nestTypeSlots(rabbitFoot: Boolean): Int =
        if (rabbitFoot) NEST_TYPE_SLOTS_RABBIT_FOOT else NEST_TYPE_SLOTS

    /** Which nest [index] is, over a table of [nestTypeSlots] slots. */
    fun nestTypeAt(index: Int): String =
        when {
            index == 0 -> RED_EGG_NEST
            index == 1 -> BLUE_EGG_NEST
            index == 2 -> GREEN_EGG_NEST
            index <= LAST_RING_INDEX -> RING_NEST
            else -> EMPTY_NEST
        }

    /** Rolls the ring/egg/empty table once. */
    fun rollNestType(random: GameRandom, rabbitFoot: Boolean): String =
        nestTypeAt(random.of(nestTypeSlots(rabbitFoot)))
}
