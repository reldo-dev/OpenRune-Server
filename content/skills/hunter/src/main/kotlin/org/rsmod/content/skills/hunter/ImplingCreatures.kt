package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.table.hunter.HunterImplingCreaturesRow

/**
 * A single impling - a [ButterflyCreature] with a second experience value, and not a
 * [HunterCreature] for the reason that one is not. All twelve pairs are published parameters.
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
    // An unrelated id gets the lower Puro-Puro value rather than the higher one.
    fun experienceFor(caughtNpc: Int): Int =
        if (caughtNpc == npcOverworld.asRSCM(RSCMType.NPC)) xp else xpPuro
}

/**
 * The impling creature table, read back from the packed dbtable. Nothing persists an index into
 * this list; sorted by dbrow id anyway.
 */
object ImplingCreatures {
    val all: List<ImplingCreature> by lazy {
        HunterImplingCreaturesRow.all().sortedBy(HunterImplingCreaturesRow::rowId).map(::creature)
    }

    private val byNpc: Map<String, ImplingCreature> by lazy { all.associateBy { it.npc } }

    // `RSCM.getReverseMapping` is an unmemoised linear scan - far too slow for a per-click op.
    private val byNpcId: Map<Int, ImplingCreature> by lazy {
        buildMap {
            for (creature in all) {
                put(creature.npc.asRSCM(RSCMType.NPC), creature)
                put(creature.npcOverworld.asRSCM(RSCMType.NPC), creature)
            }
        }
    }

    fun byNpc(npc: String): ImplingCreature? = byNpc[npc]

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
