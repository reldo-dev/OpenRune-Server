package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.table.hunter.HunterFalconryCreaturesRow

/**
 * A single falconry kebbit - deliberately *not* a [HunterCreature], which would have meant a sixth
 * [TrapFamily] entry or a nullable family (docs/hunter.md). [falconNpc] is the per-kebbit "falcon
 * holding prey" npc a catch spawns, which is enough to recover the whole reward on its own.
 */
data class FalconryCreature(
    val npc: String,
    val level: Int,
    val xp: Int,
    val caught: List<HunterCatch>,
    val successLow: Int,
    val successHigh: Int,
    val falconNpc: String,
)

/**
 * The falconry creature table, read back from the packed dbtable. Sorted by dbrow id but *not* for
 * [HunterCreatures.all]'s reason: nothing indexes into this list, so a row may be inserted
 * anywhere without consequence.
 */
object FalconryCreatures {
    val all: List<FalconryCreature> by lazy {
        HunterFalconryCreaturesRow.all()
            .sortedBy(HunterFalconryCreaturesRow::rowId)
            .map(::creature)
    }

    private val byNpc: Map<String, FalconryCreature> by lazy { all.associateBy { it.npc } }

    private val byFalconNpc: Map<String, FalconryCreature> by lazy {
        all.associateBy { it.falconNpc }
    }

    // `RSCM.getReverseMapping` is an unmemoised linear scan - far too slow for a per-click op.
    private val byNpcId: Map<Int, FalconryCreature> by lazy {
        all.associateBy { it.npc.asRSCM(RSCMType.NPC) }
    }

    private val byFalconNpcId: Map<Int, FalconryCreature> by lazy {
        all.associateBy { it.falconNpc.asRSCM(RSCMType.NPC) }
    }

    fun byNpc(npc: String): FalconryCreature? = byNpc[npc]

    fun byNpcId(npc: Int): FalconryCreature? = byNpcId[npc]

    fun byFalconNpcId(npc: Int): FalconryCreature? = byFalconNpcId[npc]

    // A retrieve knows what it is owed without consulting the controller.
    fun byFalconNpc(npc: String): FalconryCreature? = byFalconNpc[npc]

    private fun creature(row: HunterFalconryCreaturesRow): FalconryCreature =
        FalconryCreature(
            npc = row.npc.internalName,
            level = row.level,
            xp = row.xp,
            caught =
                parallelCatches(
                    row.rowId,
                    row.caughtItems.map { it.internalName },
                    row.caughtMin,
                    row.caughtMax,
                ),
            successLow = row.successLow,
            successHigh = row.successHigh,
            falconNpc = row.falconNpc.internalName,
        )
}
