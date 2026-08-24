package org.rsmod.content.skills.hunter

import org.rsmod.api.table.hunter.HunterBoxCreaturesRow
import org.rsmod.api.table.hunter.HunterSnareCreaturesRow

/**
 * The bird snare and box trap creature table, read back from the packed dbtables.
 *
 * The values and their provenance live in `HunterTables.kt` in the `hunter-pack` module; this is
 * only the adapter that reshapes a generated row into the runtime record. Rows are sorted by dbrow
 * id because a sprung trap persists its creature as an index into [all].
 *
 * Quarantined, not guessed - four candidates from the design spec are deliberately left out:
 * - Letvek (`npc.hunting_letvek`, level 76 box trap) - the npc exists in the cache but has zero
 *   spawns in `.data/raw-cache/map/npcs/`.
 * - Tropical wagtail (level 19 bird snare, "coloured" trap state) - the cache defines a 5th bird
 *   snare biome state (`hunting_ojibway_trap_full_coloured`, model2=model_26839) and a matching
 *   `skill_feature_hunter_coloured_bird` entry, but there is no 5th bird npc: the cache holds
 *   exactly four (`hunting_bird_{jungle,polar,desert,woodland}`, ids 5549-5552), and none of their
 *   `model1` values is `model_26839`.
 * - Embertailed jerboa (level 39 box trap) - the npc is resolved (`npc.varlamore_hunterjerboa01`,
 *   which carries `name=Embertailed jerboa` and the same `category_374` as every other box-trap
 *   creature; the other jerboa spawn, `npc.varlamore_jerboa`, is undecorated decorative fauna with
 *   no category and the generic name "Jerboa"). What is missing is the `(low, high)` source: the
 *   Embertailed jerboa's wiki page has no Hunting technique / Hunting chance section at all, only
 *   Location and a level/xp/drop table.
 * - Ferret (level 27 box trap) - npc and obj both resolve and are spawned, but its wiki page has no
 *   catch-chance formula or chart either. The only documented Ferret percentage is a different
 *   mechanic (the chance of *keeping* a ferret after it flushes a white rabbit, used in the
 *   out-of-scope net-trap rabbit chain), not the box-trap catch chance.
 */
object HunterCreatures {
    val all: List<HunterCreature> by lazy {
        HunterSnareCreaturesRow.all().sortedBy(HunterSnareCreaturesRow::rowId).map(::snare) +
            HunterBoxCreaturesRow.all().sortedBy(HunterBoxCreaturesRow::rowId).map(::box)
    }

    private val byNpc: Map<String, HunterCreature> by lazy { all.associateBy { it.npc } }

    fun byNpc(npc: String): HunterCreature? = byNpc[npc]

    /**
     * A bird snare catch awards three items in one go, so `caught_items` and its two quantity
     * columns are parallel lists: entry `i` of each describes the same reward line.
     */
    private fun snare(row: HunterSnareCreaturesRow): HunterCreature =
        HunterCreature(
            family = TrapFamily.SNARE,
            npc = row.npc.internalName,
            level = row.level,
            xp = row.xp,
            caught =
                row.caughtItems.mapIndexed { i, obj ->
                    HunterCatch(obj.internalName, row.caughtMin[i]..row.caughtMax[i])
                },
            successLow = row.successLow,
            successHigh = row.successHigh,
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
            bait = row.bait.internalName,
        )
}
