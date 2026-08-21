package org.rsmod.content.skills.hunter

enum class TrapFamily {
    SNARE,
    BOX,
}

/**
 * One reward line of a catch: an obj and how many of it a single catch awards.
 *
 * A range rather than a count because the bird snare needs one: every bird's wiki infobox lists
 * bones and raw bird meat at "Quantity: 1 | Rarity: Always" but its feather at "Quantity: 5-10 |
 * Rarity: Always", so the quantity is genuinely per-item and genuinely rolled. Chinchompas award a
 * flat one, which is the default here.
 */
data class HunterCatch(val obj: String, val quantity: IntRange = 1..1)

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
 * always awards bones, raw bird meat, and a species feather in one go - and each entry carries its
 * own quantity, because the feather's is not one.
 *
 * [bait] is recorded but unread in v1.
 */
data class HunterCreature(
    val family: TrapFamily,
    val npc: String,
    val level: Int,
    val xp: Int,
    val caught: List<HunterCatch>,
    val successLow: Int,
    val successHigh: Int,
    val bait: String? = null,
)
