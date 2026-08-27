package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.table.hunter.HunterBoxCreaturesRow
import org.rsmod.api.table.hunter.HunterDeadfallCreaturesRow
import org.rsmod.api.table.hunter.HunterMagicboxCreaturesRow
import org.rsmod.api.table.hunter.HunterNettrapCreaturesRow
import org.rsmod.api.table.hunter.HunterSnareCreaturesRow

/**
 * The creature tables read back from the packed dbtables; values and provenance live in
 * `HunterTables.kt` and docs/hunter.md. XP passes through unscaled (stored x10).
 */
object HunterCreatures {
    /**
     * **Append only.** A sprung trap persists its creature as an index into this list, so a new
     * row has to land after every row already in it. Sorted by dbrow id across **all tables at
     * once**, not per table and concatenated - global order is what makes "give a new row an id
     * above everything" the entire rule (docs/hunter.md).
     */
    val all: List<HunterCreature> by lazy {
        val rows =
            HunterSnareCreaturesRow.all().map { it.rowId to snare(it) } +
                HunterBoxCreaturesRow.all().map { it.rowId to box(it) } +
                HunterDeadfallCreaturesRow.all().map { it.rowId to deadfall(it) } +
                HunterNettrapCreaturesRow.all().map { it.rowId to netTrap(it) } +
                HunterMagicboxCreaturesRow.all().map { it.rowId to magicBox(it) }
        rows.sortedBy { it.first }.map { it.second }
    }

    val deadfall: List<HunterCreature> by lazy {
        all.filter { it.family == TrapFamily.DEADFALL }
    }

    val netTrap: List<HunterCreature> by lazy { all.filter { it.family == TrapFamily.NETTRAP } }

    // Read for the lay-time level gate: with one creature in the family, the lay requirement and
    // the catch requirement are the same number by construction.
    val magicBox: HunterCreature by lazy {
        all.single { it.family == TrapFamily.MAGICBOX }
    }

    private val byNpc: Map<String, HunterCreature> by lazy { all.associateBy { it.npc } }

    // `RSCM.getReverseMapping` is an unmemoised linear scan - far too slow for the trap tick.
    private val byNpcId: Map<Int, HunterCreature> by lazy {
        all.associateBy { it.npc.asRSCM(RSCMType.NPC) }
    }

    // How a net trap's creature is recovered *without* a catch: the failure and collapse paths
    // need the salamander while `trapCreature` still reads "nothing caught".
    private val byNetTrapLoc: Map<Int, HunterCreature> by lazy {
        buildMap {
            for (creature in netTrap) {
                val states =
                    listOfNotNull(
                        creature.upLoc,
                        creature.settingLoc,
                        creature.setLoc,
                        creature.netSetLoc,
                        creature.trappingLoc,
                        creature.fullLoc,
                        creature.failingLoc,
                        creature.failedLoc,
                    )
                for (state in states) {
                    put(state.asRSCM(RSCMType.LOC), creature)
                }
            }
        }
    }

    fun byNpc(npc: String): HunterCreature? = byNpc[npc]

    fun byNpcId(npc: Int): HunterCreature? = byNpcId[npc]

    fun byNetTrapLoc(locId: Int): HunterCreature? = byNetTrapLoc[locId]

    private fun snare(row: HunterSnareCreaturesRow): HunterCreature =
        HunterCreature(
            family = TrapFamily.SNARE,
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
            locKey = row.locKey,
        )

    private fun box(row: HunterBoxCreaturesRow): HunterCreature =
        HunterCreature(
            family = TrapFamily.BOX,
            npc = row.npc.internalName,
            level = row.level,
            xp = row.xp,
            caught =
                listOf(HunterCatch(row.caughtItems.internalName, row.caughtMin..row.caughtMax)),
            successLow = row.successLow,
            successHigh = row.successHigh,
            locKey = row.locKey,
        )

    // The raggedest table: two creatures drop no meat - [parallelCatches] guards the width.
    private fun deadfall(row: HunterDeadfallCreaturesRow): HunterCreature =
        HunterCreature(
            family = TrapFamily.DEADFALL,
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
            trappingLoc = row.trappingLoc.internalName,
            trappingLocM = row.trappingLocM.internalName,
            fullLoc = row.fullLoc.internalName,
        )

    // `catching_loc` lands in [HunterCreature.trappingLoc]: the cache's word for the mid-catch
    // state differs, the meaning does not.
    private fun netTrap(row: HunterNettrapCreaturesRow): HunterCreature =
        HunterCreature(
            family = TrapFamily.NETTRAP,
            npc = row.npc.internalName,
            level = row.level,
            xp = row.xp,
            caught =
                listOf(HunterCatch(row.caughtItems.internalName, row.caughtMin..row.caughtMax)),
            successLow = row.successLow,
            successHigh = row.successHigh,
            trappingLoc = row.catchingLoc.internalName,
            fullLoc = row.fullLoc.internalName,
            upLoc = row.upLoc.internalName,
            settingLoc = row.settingLoc.internalName,
            setLoc = row.setLoc.internalName,
            netSetLoc = row.netSetLoc.internalName,
            failingLoc = row.failingLoc.internalName,
            failedLoc = row.failedLoc.internalName,
        )

    private fun magicBox(row: HunterMagicboxCreaturesRow): HunterCreature =
        HunterCreature(
            family = TrapFamily.MAGICBOX,
            npc = row.npc.internalName,
            level = row.level,
            xp = row.xp,
            caught =
                listOf(HunterCatch(row.caughtItems.internalName, row.caughtMin..row.caughtMax)),
            successLow = row.successLow,
            successHigh = row.successHigh,
        )
}
