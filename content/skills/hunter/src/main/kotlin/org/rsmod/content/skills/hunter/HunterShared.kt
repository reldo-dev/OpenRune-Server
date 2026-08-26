package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.random.GameRandom
import org.rsmod.game.entity.Controller
import org.rsmod.game.inv.Inventory
import org.rsmod.game.loc.BoundLocInfo

/**
 * The handful of rules every hunter technique shares.
 *
 * Top-level rather than members of any one technique because no technique owns them - [HunterTrap],
 * [HunterFalconry], [HunterButterfly] and [HunterCrabTrap] are deliberately independent of each
 * other, and the alternative to a shared file is one class reaching into another's private surface.
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
 * Getting that last rule wrong over-rejects a legitimate collect - a chinchompa catch when the
 * player already holds that chinchompa type, say - which is exactly the bug this function exists to
 * prevent.
 */
internal fun hunterInvSlotsNeeded(inv: Inventory, internal: String, count: Int): Int {
    val stackable = ServerCacheManager.getItem(internal.asRSCM(RSCMType.OBJ))?.isStackable == true
    return when {
        !stackable -> count
        inv.contains(internal) -> 0
        else -> 1
    }
}

/**
 * `Investigate` on an armed trap: walk to it, then say who owns it and whether it has caught.
 *
 * All five trap families carry this op and all five answer it identically bar the noun: the
 * collapsed message, the not-yours message and the ownership test are the same three things every
 * time.
 *
 * The two things that genuinely vary are parameters. [armed] is the family's own test for "is this
 * loc even in a state worth investigating", which the two fixed-loc families answer differently
 * from the three portable ones and which the net trap answers against a whole id set. [controller]
 * is deferred rather than resolved by the caller because [arriveDelay] suspends: the trap has to be
 * looked up *after* the player finishes walking to it, or a trap that collapsed during the walk
 * still reports as set.
 */
internal suspend fun ProtectedAccess.investigateTrap(
    loc: BoundLocInfo,
    noun: String,
    armed: (BoundLocInfo) -> Boolean = { true },
    controller: (BoundLocInfo) -> Controller?,
) {
    arriveDelay()

    if (!armed(loc)) {
        mes("Nothing interesting happens.")
        return
    }

    val found = controller(loc)
    when {
        found == null -> mes("This trap has collapsed.")
        found.trapOwner != player.uid.packed -> mes("This isn't your trap.")
        else -> mes("The $noun is set. Nothing has sprung it yet.")
    }
}
