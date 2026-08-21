package org.rsmod.content.skills.hunter

enum class TrapFamily {
    SNARE,
    BOX,
}

/**
 * A single laid-trap creature.
 *
 * [successLow] and [successHigh] are the `(low, high)` pair fed into
 * `SkillingSuccessRate.successRate(low, high, level, maxLevel)`. They are the raw `c` and `m + c`
 * coefficients read off each creature's wiki formula `P(L) = (floor(m * (L - 1) / 98) + c) / 255`
 * at level 1 and level 99 respectively - not the formula evaluated to a probability. A negative
 * [successLow] (black/carnivorous chinchompa) reproduces "if the player's Hunter level is too low,
 * the trap will always fail" on its own, with no guard code. Every other creature here has a
 * non-negative [successLow] - regular chinchompa's is +6 - so that guard is *not* implicit for
 * them; [HunterTrap.hunterTrapTick] gates the roll explicitly on `owner.hunterLvl >= level`.
 *
 * [caught] is a list because a single catch can award more than one item - a bird snare catch
 * always awards bones, raw bird meat, and a species feather in one go.
 *
 * [bait] is recorded but unread in v1.
 */
data class HunterCreature(
    val family: TrapFamily,
    val npc: String,
    val level: Int,
    val xp: Int,
    val caught: List<String>,
    val successLow: Int,
    val successHigh: Int,
    val bait: String? = null,
)
