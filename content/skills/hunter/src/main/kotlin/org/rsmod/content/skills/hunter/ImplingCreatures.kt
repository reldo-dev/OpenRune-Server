package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.table.hunter.HunterImplingCreaturesRow

/**
 * A single impling.
 *
 * Structurally a [ButterflyCreature] with a second experience value, and deliberately **not** a
 * [HunterCreature] for the reason that one is not: there is no [TrapFamily] and no loc state, because
 * an impling is caught where it flies and leaves nothing standing in the world.
 *
 * [successLow] and [successHigh] are the same engine coefficients [HunterCreature] documents, fed to
 * the same `SkillingSuccessRate.successRate`. Unlike every technique shipped before it, no pair here
 * is a fit or a guess: all six are the `{{Skilling success chart}}` template's own published
 * parameters. The magic-net and barehanded curve is those two coefficients plus
 * `HunterButterfly.NET_BONUS`, not a second column pair.
 *
 * [xp] and [xpPuro] are both the *same* creature's experience, stored x10 as every hunter experience
 * column is, and which one applies depends on where the impling spawned - the wiki publishes both
 * per creature and the gap widens with tier (the magpie is 44 against 216). [xpPuro] is what a catch
 * awards today, because every row in this table is a `_maze` npc; [xp] is the overworld value, and
 * it is carried rather than dropped so that the shared `xp` column does not silently mean something
 * different in this table than in the other eight. Its consumer is the overworld impling spawner,
 * which does not exist yet.
 *
 * [caught] is the filled jar, exactly as it is for a butterfly: one non-stackable jar swapped for
 * the empty one. It is a list of [HunterCatch] rather than a bare obj so the reward shape matches
 * every other hunter technique's, but every impling row is one line of one item.
 */
data class ImplingCreature(
    val npc: String,
    val level: Int,
    val xp: Int,
    val xpPuro: Int,
    val caught: List<HunterCatch>,
    val successLow: Int,
    val successHigh: Int,
)

/**
 * The impling creature table, read back from the packed dbtable.
 *
 * The values and their provenance live in `HunterTables.kt` in the `hunter-pack` module; this is
 * only the adapter, exactly as [HunterCreatures], [FalconryCreatures] and [ButterflyCreatures] are
 * for their tables.
 *
 * Like [ButterflyCreatures], and unlike the trap tables, **nothing persists an index into this
 * list**. A catch resolves within one op - there is no trap, no controller and no npc left holding a
 * reward - so no varcon ever refers to a row here and the order is free. It is sorted by dbrow id
 * anyway, so that `all` reads in the order the table is written and a future feature that does need
 * a stable index inherits one.
 */
object ImplingCreatures {
    val all: List<ImplingCreature> by lazy {
        HunterImplingCreaturesRow.all().sortedBy(HunterImplingCreaturesRow::rowId).map(::creature)
    }

    private val byNpc: Map<String, ImplingCreature> by lazy { all.associateBy { it.npc } }

    /**
     * The same table keyed by resolved npc id, so a `Catch` op does not pay
     * `RSCM.getReverseMapping`'s linear scan of the whole npc table to find out what it landed on.
     */
    private val byNpcId: Map<Int, ImplingCreature> by lazy {
        all.associateBy { it.npc.asRSCM(RSCMType.NPC) }
    }

    /** The impling a `Catch` op landed on, or null if it is not one. */
    fun byNpc(npc: String): ImplingCreature? = byNpc[npc]

    /** [byNpc] for a live npc, without the reverse-mapping scan. */
    fun byNpcId(npc: Int): ImplingCreature? = byNpcId[npc]

    private fun creature(row: HunterImplingCreaturesRow): ImplingCreature =
        ImplingCreature(
            npc = row.npc.internalName,
            level = row.level,
            xp = row.xp,
            xpPuro = row.xpPuro,
            caught = listOf(HunterCatch(row.caughtItems.internalName, row.caughtMin..row.caughtMax)),
            successLow = row.successLow,
            successHigh = row.successHigh,
        )
}
