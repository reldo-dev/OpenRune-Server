package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.table.hunter.HunterButterflyCreaturesRow

/**
 * A single butterfly or moth.
 *
 * Deliberately **not** a [HunterCreature], for the reason [FalconryCreature] is not one: that record
 * carries a [TrapFamily] and a set of loc-state columns, and butterfly netting has neither. It is
 * thinner than falconry's record too - there is no falcon npc, no controller, no timeout and no cap,
 * because a catch resolves on the cycle it is asked for and leaves nothing standing in the world.
 *
 * [successLow] and [successHigh] are the same engine coefficients [HunterCreature] documents, fed to
 * the same `SkillingSuccessRate.successRate`. Every butterfly ships the *same* pair; see
 * `HunterTables.butterflyCreatures` for why that is a finding rather than a shortcut.
 *
 * [caught] is the filled jar, and is awarded only when the player is carrying an empty one. It is
 * still a list of [HunterCatch] rather than a bare obj so the reward shape matches every other
 * hunter technique's, but every butterfly row is one line of one item.
 */
data class ButterflyCreature(
    val npc: String,
    val level: Int,
    val xp: Int,
    val caught: List<HunterCatch>,
    val successLow: Int,
    val successHigh: Int,
)

/**
 * The butterfly creature table, read back from the packed dbtable.
 *
 * The values and their provenance live in `HunterTables.kt` in the `hunter-pack` module; this is
 * only the adapter, exactly as [HunterCreatures] and [FalconryCreatures] are for their tables.
 *
 * Unlike both of those, **nothing persists an index into this list**. A butterfly catch resolves
 * within one op - there is no trap, no controller and no npc left holding a reward - so no varcon
 * ever refers to a row here and the order is free. It is sorted by dbrow id anyway, so that
 * `all` reads in the order the table is written and a future feature that does need a stable index
 * inherits one.
 */
object ButterflyCreatures {
    val all: List<ButterflyCreature> by lazy {
        HunterButterflyCreaturesRow.all()
            .sortedBy(HunterButterflyCreaturesRow::rowId)
            .map(::creature)
    }

    private val byNpc: Map<String, ButterflyCreature> by lazy { all.associateBy { it.npc } }

    /**
     * The same table keyed by resolved npc id, so a `Catch` op does not pay
     * `RSCM.getReverseMapping`'s linear scan of the whole npc table to find out what it landed on.
     */
    private val byNpcId: Map<Int, ButterflyCreature> by lazy {
        all.associateBy { it.npc.asRSCM(RSCMType.NPC) }
    }

    /** The butterfly a `Catch` op landed on, or null if it is not one. */
    fun byNpc(npc: String): ButterflyCreature? = byNpc[npc]

    /** [byNpc] for a live npc, without the reverse-mapping scan. */
    fun byNpcId(npc: Int): ButterflyCreature? = byNpcId[npc]

    private fun creature(row: HunterButterflyCreaturesRow): ButterflyCreature =
        ButterflyCreature(
            npc = row.npc.internalName,
            level = row.level,
            xp = row.xp,
            caught =
                listOf(HunterCatch(row.caughtItems.internalName, row.caughtMin..row.caughtMax)),
            successLow = row.successLow,
            successHigh = row.successHigh,
        )
}
