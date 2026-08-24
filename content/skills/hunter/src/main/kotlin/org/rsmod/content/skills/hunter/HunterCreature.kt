package org.rsmod.content.skills.hunter

enum class TrapFamily {
    SNARE,
    BOX,

    /**
     * The deadfall, which is not a trap the player carries at all: the boulder is a permanent map
     * loc, armed in place with a log and a knife. Nothing is laid, nothing is picked back up, and
     * there is no collectible failed state - so the portable-only paths ([HunterTrap.layTrap],
     * [HunterTrap.takeTrap]) reject it, and every state it moves through is a `locRepo.change` on
     * the boulder rather than a spawn and a delete.
     */
    DEADFALL;

    /**
     * True for the two families laid from an inventory item onto an empty tile, false for the
     * deadfall.
     *
     * The distinction is not cosmetic: deleting a deadfall boulder the way a portable trap's tile
     * is cleared would take that boulder out of the world permanently, because `LocRepository` only
     * schedules a respawn for a delete with a finite duration.
     */
    val portable: Boolean
        get() = this != DEADFALL
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
 * `SkillingSuccessRate.successRate(low, high, level, maxLevel)`, which computes
 * `(1 + floor(low * (99 - L) / 98 + high * (L - 1) / 98 + 0.5)) / 256` - a 1/256 scale with a +1
 * bias, *not* the `/255` scale the wiki's own `P(L)` formulas are written on. They are the
 * coefficients of that engine formula, fit to reproduce each creature's charted per-level success
 * chance, not the formula evaluated to a probability. A negative [successLow]
 * (black/carnivorous chinchompa, four of the five deadfall creatures) reproduces "if the player's
 * Hunter level is too low, the trap will always fail" on its own, with no guard code. Creatures
 * with a non-negative [successLow] - regular chinchompa's is +6 - do not get that guard implicitly;
 * [HunterTrap.hunterTrapTick] gates the roll explicitly on `owner.hunterLvl >= level`.
 *
 * [caught] is a list because a single catch can award more than one item - a bird snare catch
 * always awards bones, raw bird meat, and a species feather in one go - and each entry carries its
 * own quantity, because the feather's is not one.
 *
 * [bait] is recorded but unread in v1.
 *
 * [trappingLoc], [trappingLocM] and [fullLoc] are deadfall-only and null for every other family:
 * the boulder's mid-catch and caught states are per-creature locs that live in the packed table,
 * where the portable families build theirs from a name suffix instead.
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
    val trappingLoc: String? = null,
    val trappingLocM: String? = null,
    val fullLoc: String? = null,
)
