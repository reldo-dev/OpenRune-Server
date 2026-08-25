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
 * Quarantined - one candidate from the design spec is still deliberately left out. Letvek
 * (`npc.hunting_letvek`, level 76 box trap) exists in the cache but has zero spawns in
 * `.data/raw-cache/map/npcs/`, so a row for it would be unreachable content.
 *
 * The tropical wagtail used to be listed here too, and that exclusion was **wrong**. It claimed the
 * cache held "exactly four" bird-snare npcs and therefore nothing to put behind the orphaned
 * `hunting_ojibway_trap_full_coloured` state. That search was by `hunting_bird_` symbol prefix and
 * the wagtail sits outside it: `npc.multicoloured_bird` (5548), `name=Tropical wagtail`, whose
 * `model1=model_26839` is precisely the model the old comment cited as belonging to the orphaned
 * state. The evidence read as proving absence was in fact the identification. A prefix search
 * returning nothing is not absence - search by `name=`.
 *
 * The ferret and the embertailed jerboa were quarantined for a different and still-true reason:
 * neither has a published catch-chance curve anywhere. They ship anyway, on annotated guesses
 * derived from the regular chinchompa's shape, under the rates policy that took effect after they
 * were first excluded. `HunterTables.boxCreatures` records the derivation.
 */
object HunterCreatures {
    /**
     * **Append only.** A sprung trap persists its creature as an index into this list, so a new row
     * has to land after every row already shipped or it turns someone's caught chinchompa into a
     * salamander on their next login.
     *
     * Sorted by dbrow id across **all five tables at once**, not per table and concatenated. Those
     * two orderings agreed for as long as each technique arrived as a whole block numbered above the
     * last - snare 56300-03, box 56304-06, deadfall 56310-14, and so on - so this is a no-op for
     * every row shipped so far. They stop agreeing the moment a row joins a table that has already
     * shipped, which is what the tropical wagtail, the ferret and the embertailed jerboa do: under a
     * per-table concatenation the wagtail would land at index 4 whatever id it was given, because
     * it sorts within the snare block, and the three chinchompas behind it would each shift one
     * place. Sorting globally makes "give it an id above everything" mean what it says, and is what
     * lets the append-only rule be enforced by choosing an id rather than by choosing a table.
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
     * The same table keyed by resolved npc id, for the callers that start from a live [Npc].
     *
     * `RSCM.getReverseMapping` is a linear scan of the whole npc table - ~14.5k entries at this
     * revision, with no memoisation - so recovering a symbol per candidate npc is the most
     * expensive thing the trap tick does. Resolving each row's symbol once at class load and
     * comparing ids turns that scan into a hash lookup. Built the same way as [byNetTrapLoc], which
     * already keys on a resolved id for the same reason.
     */
    private val byNpcId: Map<Int, HunterCreature> by lazy {
        all.associateBy { it.npc.asRSCM(RSCMType.NPC) }
    }

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

    /** [byNpc] for a live npc, without the reverse-mapping scan. */
    fun byNpcId(npc: Int): HunterCreature? = byNpcId[npc]

    fun byNetTrapLoc(locId: Int): HunterCreature? = byNetTrapLoc[locId]

    /** A bird snare catch awards three items in one go; see [parallelCatches]. */
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

    /**
     * Like [snare], and the raggedest of the three: wild kebbit, barb-tailed kebbit and pyre fox
     * award three reward lines, prickly and sabre-toothed award two, because neither drops meat.
     * [parallelCatches] carries the guard that keeps that legitimate and a dropped column not.
     */
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
     * No `loc_key` either, unlike [box]: with one creature in the family there is no suffix to vary,
     * so all four states are constants in [HunterTrapStates].
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
