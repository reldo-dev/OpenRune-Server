package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.table.hunter.HunterButterflyCreaturesRow

/**
 * A single butterfly or moth - not a [HunterCreature], for the reason [FalconryCreature] is not
 * one. Every shipped row carries the same pair; that is a finding, not a shortcut
 * (docs/hunter.md). [caught] is the filled jar, awarded only when an empty one is carried.
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
 * The butterfly creature table, read back from the packed dbtable. Nothing persists an index into
 * this list - a catch resolves within one op - so the order is free; sorted by dbrow id anyway.
 */
object ButterflyCreatures {
    val all: List<ButterflyCreature> by lazy {
        HunterButterflyCreaturesRow.all()
            .sortedBy(HunterButterflyCreaturesRow::rowId)
            .map(::creature)
    }

    private val byNpc: Map<String, ButterflyCreature> by lazy { all.associateBy { it.npc } }

    // `RSCM.getReverseMapping` is an unmemoised linear scan - far too slow for a per-click op.
    private val byNpcId: Map<Int, ButterflyCreature> by lazy {
        all.associateBy { it.npc.asRSCM(RSCMType.NPC) }
    }

    fun byNpc(npc: String): ButterflyCreature? = byNpc[npc]

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
