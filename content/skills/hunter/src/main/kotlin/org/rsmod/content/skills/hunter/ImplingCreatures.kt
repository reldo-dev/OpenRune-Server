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
 * Every impling has **two** npc ids for one creature: [npc] is the Puro-Puro `_maze` one and
 * [npcOverworld] the overworld one. They share a level, a rate and a reward, and differ only in the
 * experience a catch awards - [xpPuro] against [xp], both stored x10 as every hunter experience
 * column is. The gap widens sharply with tier: the magpie is 44 against 216.
 *
 * **Which one applies is decided by the id that was caught, not by where the player is standing.**
 * *Eclectic impling*: "30 Hunter experience (if a Puro-Puro spawn) or 32 Hunter experience (if an
 * overworld spawn) (note - overworld spawn versions can spawn in Puro-Puro)". So an area check would
 * be the wrong question; [experienceFor] asks the right one.
 *
 * [caught] is the filled jar, exactly as it is for a butterfly: one non-stackable jar swapped for
 * the empty one. It is a list of [HunterCatch] rather than a bare obj so the reward shape matches
 * every other hunter technique's, but every impling row is one line of one item.
 */
data class ImplingCreature(
    val npc: String,
    val npcOverworld: String,
    val level: Int,
    val xp: Int,
    val xpPuro: Int,
    val caught: List<HunterCatch>,
    val successLow: Int,
    val successHigh: Int,
) {
    /**
     * The experience a catch on [caughtNpc] awards, in tenths.
     *
     * Anything that is not the overworld id is treated as the Puro-Puro spawn, so a caller that
     * somehow passes an unrelated id gets the lower value rather than the higher one.
     */
    fun experienceFor(caughtNpc: Int): Int =
        if (caughtNpc == npcOverworld.asRSCM(RSCMType.NPC)) xp else xpPuro
}

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
        buildMap {
            for (creature in all) {
                put(creature.npc.asRSCM(RSCMType.NPC), creature)
                put(creature.npcOverworld.asRSCM(RSCMType.NPC), creature)
            }
        }
    }

    /** The impling a `Catch` op landed on, or null if it is not one. */
    fun byNpc(npc: String): ImplingCreature? = byNpc[npc]

    /** [byNpc] for a live npc, without the reverse-mapping scan. */
    fun byNpcId(npc: Int): ImplingCreature? = byNpcId[npc]

    private fun creature(row: HunterImplingCreaturesRow): ImplingCreature =
        ImplingCreature(
            npc = row.npc.internalName,
            npcOverworld = row.npcOverworld.internalName,
            level = row.level,
            xp = row.xp,
            xpPuro = row.xpPuro,
            caught = listOf(HunterCatch(row.caughtItems.internalName, row.caughtMin..row.caughtMax)),
            successLow = row.successLow,
            successHigh = row.successHigh,
        )
}
