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
 * What a harvested bird house pays out. Two rolls on two different curves: the seed nest is the
 * published (0, 200) skilling curve; the ten nest-table rolls are *not* a /256 curve - the
 * endpoints and the halving-below-50 are Jagex's, the linear 50-99 ramp is the wiki's own model
 * and the largest piece of unsourced arithmetic here. A successful roll pre-rolls a clue first,
 * then the ring/egg/empty table. Full derivation and sources: docs/hunter.md.
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
     * `half*(99-L) + high*(L-50)` over 49 simplifies to `high*(L-1)/2`, so the whole curve - flat
     * region included - is `high * (max(L, 50) - 1) / 98_000`, both endpoints falling out of the
     * identity rather than being special-cased.
     */
    const val NEST_RATE_DENOMINATOR: Int = 98_000

    /* The nest type table. */

    /**
     * Rolled as an *index*, not as weights - Mod Ash's own description of the woodcutting
     * original. That also makes the rabbit foot honest: it removes the five highest (empty)
     * slots; "+5% ring/egg" would be slightly wrong for every row. See docs/hunter.md.
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

    /** Twitcher's gloves are deliberately *not* checked: published as not applying here. */
    const val STRUNG_RABBIT_FOOT: String = "obj.hunting_strung_rabbit_foot"

    /* Clue nests. */

    /**
     * The five clue nests, rarest first (the pre-roll priority), at the published ~N/1500 rates -
     * a section the wiki itself marks unconfirmed (docs/hunter.md).
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
     * The only confirmed statement about clue mechanics (Mod Ash, 19 June 2020); the dated
     * developer statement wins over the wiki's self-contradicting prose.
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
