package org.rsmod.content.skills.hunter

import org.rsmod.api.random.GameRandom

/**
 * Which impling a spawn point produces, and how often.
 *
 * **All four tables are published**, in *Impling* (oldid=15303398) under *Types of spawn*, as
 * explicit numerators over an explicit denominator - so nothing here is inferred from observed
 * rarity or borrowed from another server. Each table's weights sum to its own total, which is
 * asserted rather than assumed: the low and mid tiers are out of 100, the overworld high tier out
 * of 101, and the Puro-Puro high tier out of 301. Those three different denominators are the
 * wiki's, not a normalisation of ours.
 *
 * The high tier is the only one that differs between the overworld and Puro-Puro, and it differs
 * sharply: a Puro-Puro high spawn is a nature impling half the time, where an overworld one is a
 * magpie half the time. Only two of Puro-Puro's spawn points are high tier, which is what stops
 * that being generous.
 *
 * Entries name the **overworld** npc id, because it is the one identity every impling has - the
 * crystal impling has no Puro-Puro form. [ImplingSpawner] maps that to whichever form the anchor
 * calls for.
 */
enum class ImplingTier {
    Low,
    Mid,

    /** The overworld's high tier; see [HighPuroPuro] for the one Puro-Puro uses instead. */
    High,
    HighPuroPuro,

    /**
     * Prifddinas, which is not a weighted tier at all.
     *
     * *Impetuous Impulses* (oldid=15169162): "The sole exception is the crystal impling, which may
     * only be caught within the walls of Prifddinas." Its single spawn point always produces one,
     * so this "table" has one entry at certainty and exists only so the spawner has one shape for
     * every anchor rather than a special case beside it.
     */
    Crystal,
}

/** One creature's share of a tier, as the wiki prints it. */
data class ImplingSpawnChance(val npc: String, val weight: Int)

object ImplingSpawns {
    private val low =
        listOf(
            ImplingSpawnChance("npc.ii_impling_type_1", 20),
            ImplingSpawnChance("npc.ii_impling_type_2", 20),
            ImplingSpawnChance("npc.ii_impling_type_3", 20),
            ImplingSpawnChance("npc.ii_impling_type_4", 20),
            ImplingSpawnChance("npc.ii_impling_type_5", 10),
            ImplingSpawnChance("npc.ii_impling_type_6", 10),
        )

    private val mid =
        listOf(
            ImplingSpawnChance("npc.ii_impling_type_3", 10),
            ImplingSpawnChance("npc.ii_impling_type_4", 10),
            ImplingSpawnChance("npc.ii_impling_type_5", 20),
            ImplingSpawnChance("npc.ii_impling_type_6", 37),
            ImplingSpawnChance("npc.ii_impling_type_7", 20),
            ImplingSpawnChance("npc.ii_impling_type_8", 2),
            ImplingSpawnChance("npc.ii_impling_type_9", 1),
        )

    private val high =
        listOf(
            ImplingSpawnChance("npc.ii_impling_type_7", 10),
            ImplingSpawnChance("npc.ii_impling_type_8", 50),
            ImplingSpawnChance("npc.ii_impling_type_9", 30),
            ImplingSpawnChance("npc.ii_impling_type_10", 10),
            ImplingSpawnChance("npc.ii_impling_type_11", 1),
        )

    private val highPuroPuro =
        listOf(
            ImplingSpawnChance("npc.ii_impling_type_7", 150),
            ImplingSpawnChance("npc.ii_impling_type_8", 114),
            ImplingSpawnChance("npc.ii_impling_type_9", 27),
            ImplingSpawnChance("npc.ii_impling_type_10", 9),
            ImplingSpawnChance("npc.ii_impling_type_11", 1),
        )

    private val crystal = listOf(ImplingSpawnChance("npc.ii_impling_type_12_johnny", 1))

    /** The published denominator for each table, which is **not** the same for all of them. */
    val totals: Map<ImplingTier, Int> =
        mapOf(
            ImplingTier.Low to 100,
            ImplingTier.Mid to 100,
            ImplingTier.High to 101,
            ImplingTier.HighPuroPuro to 301,
            ImplingTier.Crystal to 1,
        )

    fun table(tier: ImplingTier): List<ImplingSpawnChance> =
        when (tier) {
            ImplingTier.Low -> low
            ImplingTier.Mid -> mid
            ImplingTier.High -> high
            ImplingTier.HighPuroPuro -> highPuroPuro
            ImplingTier.Crystal -> crystal
        }

    /**
     * Rolls one creature's overworld npc symbol out of [tier].
     *
     * Walks the entries accumulating weight, which is the same shape the drop tables roll with. The
     * trailing return cannot be reached while the weights sum to the total - which
     * `ImplingSpawnsTest` asserts against the published numbers - but a table edited to sum short
     * would otherwise fall out of the loop and spawn nothing at all, silently.
     */
    fun roll(tier: ImplingTier, random: GameRandom): String {
        val entries = table(tier)
        val roll = random.of(totals.getValue(tier))
        var accumulated = 0
        for (entry in entries) {
            accumulated += entry.weight
            if (roll < accumulated) {
                return entry.npc
            }
        }
        return entries.last().npc
    }
}
