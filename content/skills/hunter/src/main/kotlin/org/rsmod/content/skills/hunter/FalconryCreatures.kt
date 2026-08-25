package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.table.hunter.HunterFalconryCreaturesRow

/**
 * A single falconry kebbit.
 *
 * Deliberately **not** a [HunterCreature]. That record carries a [TrapFamily] and a set of loc-state
 * columns, and falconry has neither: nothing is laid, nothing is transformed, and there is no trap
 * to cap. Widening [HunterCreature] to make room would have meant either a sixth [TrapFamily] entry -
 * which corrupts the trap cap and the controller-per-trap model, since `HunterTrap` reads
 * `TrapFamily.entries` to decide how to find and change a tile's loc - or a nullable family that
 * every existing `when` would have to grow a branch for. A separate record keeps the trap engine
 * exactly as it was.
 *
 * [successLow] and [successHigh] are the same engine coefficients [HunterCreature] documents, fed to
 * the same `SkillingSuccessRate.successRate`. The spotted kebbit's [successHigh] is above 256 on
 * purpose; see `HunterTables.falconryCreatures`.
 *
 * [falconNpc] is what makes this family need no side-channel: it is the "falcon holding prey" npc a
 * successful catch spawns, and because OSRS ships one per kebbit rather than a single generic bird,
 * the npc the player later clicks is enough to recover the whole reward on its own.
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
 * The falconry creature table, read back from the packed dbtable.
 *
 * The values and their provenance live in `HunterTables.kt` in the `hunter-pack` module; this is
 * only the adapter, exactly as [HunterCreatures] is for the five trap tables.
 *
 * Sorted by dbrow id like [HunterCreatures.all], and for a weaker reason: a live falcon persists its
 * creature as an index into [all], so the order has to be stable across a restart. It does **not**
 * have to be stable against the trap tables - this is a separate list indexed by a separate varcon
 * on a separate controller type - so appending a falconry creature later cannot disturb a caught
 * chinchompa, and vice versa.
 */
object FalconryCreatures {
    /**
     * **Append only**, for the same reason [HunterCreatures.all] is: an unretrieved falcon standing
     * in the world holds an index into this list, and inserting a row ahead of it would hand its
     * owner a different kebbit's loot after a restart.
     */
    val all: List<FalconryCreature> by lazy {
        HunterFalconryCreaturesRow.all()
            .sortedBy(HunterFalconryCreaturesRow::rowId)
            .map(::creature)
    }

    private val byNpc: Map<String, FalconryCreature> by lazy { all.associateBy { it.npc } }

    private val byFalconNpc: Map<String, FalconryCreature> by lazy {
        all.associateBy { it.falconNpc }
    }

    /**
     * The two tables again, keyed by resolved npc id for callers holding a live [Npc].
     *
     * `RSCM.getReverseMapping` scans the whole ~14.5k-entry npc table and memoises nothing, and
     * `falconAt` runs it against every npc on a tile every tick while a falcon is out. Resolving
     * each row's symbol once here makes that a hash lookup.
     */
    private val byNpcId: Map<Int, FalconryCreature> by lazy {
        all.associateBy { it.npc.asRSCM(RSCMType.NPC) }
    }

    private val byFalconNpcId: Map<Int, FalconryCreature> by lazy {
        all.associateBy { it.falconNpc.asRSCM(RSCMType.NPC) }
    }

    /** The kebbit a `Catch` op landed on, or null if it is not a falconry creature. */
    fun byNpc(npc: String): FalconryCreature? = byNpc[npc]

    /** [byNpc] for a live npc, without the reverse-mapping scan. */
    fun byNpcId(npc: Int): FalconryCreature? = byNpcId[npc]

    /** [byFalconNpc] for a live npc, without the reverse-mapping scan. */
    fun byFalconNpcId(npc: Int): FalconryCreature? = byFalconNpcId[npc]

    /**
     * The kebbit a falcon is holding, recovered from the falcon's own npc.
     *
     * This is the whole reason the table carries `falcon_npc`: a retrieve knows what it is owed
     * without consulting the controller, so the controller only has to remember *who* owns the
     * catch, not *what* it is.
     */
    fun byFalconNpc(npc: String): FalconryCreature? = byFalconNpc[npc]

    /**
     * Like [HunterCreatures.snare] and its deadfall twin, and ragged the same way: the dashing
     * kebbit awards three reward lines where the other two award two. See [parallelCatches].
     */
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
