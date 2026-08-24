package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.table.hunter.HunterBoxCreaturesRow
import org.rsmod.api.table.hunter.HunterDeadfallCreaturesRow
import org.rsmod.api.table.hunter.HunterMagicboxCreaturesRow
import org.rsmod.api.table.hunter.HunterNettrapCreaturesRow
import org.rsmod.api.table.hunter.HunterSnareCreaturesRow

/**
 * The bird snare, box trap, deadfall, net trap and magic box creature tables, read back from the
 * packed dbtables.
 *
 * The values and their provenance live in `HunterTables.kt` in the `hunter-pack` module; this is
 * only the adapter that reshapes a generated row into the runtime record. Rows are sorted by dbrow
 * id because a sprung trap persists its creature as an index into [all].
 *
 * XP is stored x10 in the packed table; the mappers below pass `row.xp` through unscaled, and
 * `HunterTrap` divides by ten once, at the point it awards.
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
    /**
     * **Append only.** A sprung trap persists its creature as an index into this list, so a
     * technique's block has to be concatenated after every block already shipped. Inserting the two
     * slice-2 blocks anywhere earlier would shift every index already written into a player's save
     * and turn their caught chinchompa into a salamander on the next login.
     */
    val all: List<HunterCreature> by lazy {
        HunterSnareCreaturesRow.all().sortedBy(HunterSnareCreaturesRow::rowId).map(::snare) +
            HunterBoxCreaturesRow.all().sortedBy(HunterBoxCreaturesRow::rowId).map(::box) +
            HunterDeadfallCreaturesRow.all()
                .sortedBy(HunterDeadfallCreaturesRow::rowId)
                .map(::deadfall) +
            HunterNettrapCreaturesRow.all()
                .sortedBy(HunterNettrapCreaturesRow::rowId)
                .map(::netTrap) +
            HunterMagicboxCreaturesRow.all()
                .sortedBy(HunterMagicboxCreaturesRow::rowId)
                .map(::magicBox)
    }

    /** The deadfall subset, one of the two families whose loc states are per-creature data. */
    val deadfall: List<HunterCreature> by lazy {
        all.filter { it.family == TrapFamily.DEADFALL }
    }

    /** The net trap subset, the other. */
    val netTrap: List<HunterCreature> by lazy { all.filter { it.family == TrapFamily.NETTRAP } }

    /**
     * The imp, and the whole of its family. Read for the lay-time level gate, which is the
     * creature's own requirement rather than a constant retyped in the script - with one creature
     * in the family the two cannot disagree.
     */
    val magicBox: HunterCreature by lazy {
        all.single { it.family == TrapFamily.MAGICBOX }
    }

    private val byNpc: Map<String, HunterCreature> by lazy { all.associateBy { it.npc } }

    /**
     * Every net trap loc id, tree half and net half alike, to the creature it belongs to.
     *
     * This is how a net trap's creature is recovered *without* a catch: the family's failure and
     * collapse paths need to know which salamander's net to show while `trapCreature` still reads
     * "nothing caught", and the loc the player clicked is the only thing that says.
     */
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

    fun byNetTrapLoc(locId: Int): HunterCreature? = byNetTrapLoc[locId]

    /**
     * A bird snare catch awards three items in one go, so `caught_items` and its two quantity
     * columns are parallel lists: entry `i` of each describes the same reward line.
     */
    private fun snare(row: HunterSnareCreaturesRow): HunterCreature {
        val itemCount = row.caughtItems.size
        require(row.caughtMin.size == itemCount && row.caughtMax.size == itemCount) {
            "Row ${row.rowId} has mismatched caught reward sizes: items=$itemCount, " +
                "min=${row.caughtMin.size}, max=${row.caughtMax.size}"
        }
        return HunterCreature(
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
    }

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

    /**
     * Like [snare], the deadfall's reward columns are parallel lists - but a ragged set of them:
     * wild kebbit, barb-tailed kebbit and pyre fox award three lines, prickly and sabre-toothed
     * award two, because neither drops meat. Nothing here may assume a fixed width; the guard is
     * what turns a column edit that drops one entry into a named failure at boot instead of an
     * `IndexOutOfBounds` on the tick that catches that one creature.
     */
    private fun deadfall(row: HunterDeadfallCreaturesRow): HunterCreature {
        val itemCount = row.caughtItems.size
        require(row.caughtMin.size == itemCount && row.caughtMax.size == itemCount) {
            "Row ${row.rowId} has mismatched caught reward sizes: items=$itemCount, " +
                "min=${row.caughtMin.size}, max=${row.caughtMax.size}"
        }
        return HunterCreature(
            family = TrapFamily.DEADFALL,
            npc = row.npc.internalName,
            level = row.level,
            xp = row.xp,
            caught =
                row.caughtItems.mapIndexed { i, obj ->
                    HunterCatch(obj.internalName, row.caughtMin[i]..row.caughtMax[i])
                },
            successLow = row.successLow,
            successHigh = row.successHigh,
            trappingLoc = row.trappingLoc.internalName,
            trappingLocM = row.trappingLocM.internalName,
            fullLoc = row.fullLoc.internalName,
        )
    }

    /**
     * The net trap's eight loc columns, split across its two tiles: `up`/`setting`/`set` are the
     * young tree, `net_set`/`catching`/`full`/`failing`/`failed` are the net beside it.
     *
     * `catching_loc` lands in [HunterCreature.trappingLoc] and `full_loc` in
     * [HunterCreature.fullLoc], the same two fields the deadfall uses, because they mean the same
     * thing; only the cache's word for the mid-catch state differs.
     *
     * Unlike [snare] and [deadfall] there is no parallel-list guard to write: the packed table
     * gives every salamander exactly one reward line, so the generated row exposes `caught_items`,
     * `caught_min` and `caught_max` as scalars rather than lists and there is no width to disagree
     * about.
     */
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

    /**
     * The magic box, whose table is the shared 0-7 block and nothing else: one creature means every
     * loc state is shared by construction, so all four live as constants in [HunterTrapStates].
     *
     * No `bait` either, unlike [box] - the magic box takes beads rather than a spicy stew, and bait
     * is out of scope for both.
     */
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
