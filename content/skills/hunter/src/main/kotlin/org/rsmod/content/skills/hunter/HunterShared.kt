package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.random.GameRandom
import org.rsmod.game.inv.Inventory

/**
 * The handful of rules every hunter technique shares.
 *
 * Each of these was written once per technique before it lived here, which is the shape this module
 * keeps growing into: a slice adds a script, copies the three or four lines it needs out of the last
 * one, and the copies drift. They are top-level rather than members of any one technique because no
 * technique owns them - [HunterTrap], [HunterFalconry], [HunterButterfly] and [HunterCrabTrap] are
 * deliberately independent of each other, and the alternative to a shared file is one class reaching
 * into another's private surface.
 */

/**
 * The level [org.rsmod.api.utils.skills.SkillingSuccessRate] interpolates a creature's `(low, high)`
 * pair against.
 *
 * Not a general "max hunter level" constant: it is the `maxLevel` argument of that formula, and it
 * is 99 because the published catch-rate curves every creature's pair was fitted to are charted from
 * level 1 to level 99. A hunter above 99 is not something this scale describes.
 */
internal const val MAX_HUNTER_LEVEL: Int = 99

/**
 * How many of a reward line a single catch awards.
 *
 * The `first == last` short-circuit is load-bearing rather than an optimisation: it means a fixed
 * quantity consumes **no random draw at all**. Every technique's tests script the RNG as a fixed
 * sequence of draws, so a flat `1..1` reward that quietly consumed one would shift every subsequent
 * draw and silently change what the next roll returns.
 *
 * This is why [GameRandom.of] is not called unconditionally, and why this is not the same thing as
 * `random.of(quantity)`.
 */
internal fun rollQuantity(random: GameRandom, quantity: IntRange): Int =
    if (quantity.first == quantity.last) quantity.first else random.of(quantity)

/**
 * How many free slots awarding [count] of [internal] to [inv] costs. A stackable item already
 * present just grows its existing stack and needs none whatever the count; a stackable item not yet
 * held at all needs exactly one; anything else needs one per item.
 *
 * Shared because the trap collect and falconry's retrieve need the identical rule, and a second copy
 * would be a second place for "a stackable award the player already holds costs no slot" to drift.
 * Getting that wrong over-rejects a legitimate collect, which is exactly the bug this function
 * exists to prevent.
 */
internal fun hunterInvSlotsNeeded(inv: Inventory, internal: String, count: Int): Int {
    val stackable = ServerCacheManager.getItem(internal.asRSCM(RSCMType.OBJ))?.isStackable == true
    return when {
        !stackable -> count
        inv.contains(internal) -> 0
        else -> 1
    }
}
