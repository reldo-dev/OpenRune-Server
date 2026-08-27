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

const val BUTTERFLY_NET: String = "obj.hunting_butterfly_net"

/** A separate item, not a variant: the wiki charts it as its own catch-rate series. */
const val MAGIC_BUTTERFLY_NET: String = "obj.ii_magic_butterfly_net"

const val BUTTERFLY_JAR: String = "obj.butterfly_jar"

const val BUTTERFLY_NET_SWING: String = "seq.human_butterflynet_swing"

/**
 * Butterfly netting: click the creature, roll once, done - no loc, no controller, no varcon, no
 * cap. The reward depends on carrying an empty jar, not on the net; barehanded is a level gate and
 * nothing else. The jarless-catch stat boost is a stated gap. See docs/hunter.md.
 */
class HunterButterfly
@Inject
constructor(
    private val npcRepo: NpcRepository,
    // Named `gameRandom`, not `random`: the `ProtectedAccess` receiver has a `random` of its own
    // that silently shadows a field of that name at every use site.
    private val gameRandom: GameRandom,
    private val xpMods: XpModifiers,
) {
    // No delay and no lock: a net swing lands where the player stands and no source describes a
    // wait, so nothing can change between the op and the roll.
    fun ProtectedAccess.catchButterfly(target: Npc): Boolean {
        val creature = ButterflyCreatures.byNpcId(target.visType.id) ?: return false

        val barehanded = !isHoldingNet()
        val required = creature.level + if (barehanded) BAREHANDED_LEVELS else 0

        // Gated before the roll so an under-levelled attempt never consumes a draw.
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
            // A miss leaves the butterfly where it is; it is not consumed by being tried for.
            mes("You fail to catch the butterfly.")
            return false
        }

        // Jarred *before* the creature is removed, so a failed jar swap leaves it on the map.
        val jarred = jarCatch(creature)

        npcRepo.despawn(target, target.visType.respawnRate)

        // Stored x10. Awarded on the catch: there is nothing to collect.
        val xp = (creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
        statAdvance("stat.hunter", xp)

        if (!jarred) {
            // The string is ours; it states what happens in place of the unmodelled boost.
            mes("You have no empty jar, so the butterfly flies away.")
        }
        return true
    }

    // Never needs a free slot: both jars are non-stackable, so the delete frees exactly the slot
    // the add takes. Delete before add, or a halfway failure mints a filled jar from nothing.
    private fun ProtectedAccess.jarCatch(creature: ButterflyCreature): Boolean {
        if (!inv.contains(BUTTERFLY_JAR)) {
            return false
        }
        if (invDel(inv, BUTTERFLY_JAR, 1).failure) {
            return false
        }
        for (reward in creature.caught) {
            invAdd(inv, reward.obj, rollQuantity(gameRandom, reward.quantity))
        }
        return true
    }

    // "Wielding" means *worn*: a net sitting in the backpack is a barehanded attempt.
    private fun ProtectedAccess.isHoldingNet(): Boolean =
        worn.contains(BUTTERFLY_NET) || worn.contains(MAGIC_BUTTERFLY_NET)

    // Barehanded rides the faster curve on two of three sources; the third's label is read as
    // stale (docs/hunter.md).
    private fun ProtectedAccess.usesFasterCurve(): Boolean =
        worn.contains(MAGIC_BUTTERFLY_NET) || !isHoldingNet()

    companion object {
        /** "caught barehanded ... requires a Hunter level 10 above the normal requirement." */
        const val BAREHANDED_LEVELS: Int = 10

        /**
         * What the magic net, or a barehanded catch, adds to both coefficients. Twice sourced and
         * exact; one constant so the three guessed rows need no second guess (docs/hunter.md).
         */
        const val NET_BONUS: Int = 20
    }
}
