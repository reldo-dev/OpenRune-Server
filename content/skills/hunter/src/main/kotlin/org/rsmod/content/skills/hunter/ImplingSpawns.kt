package org.rsmod.content.skills.hunter

import org.rsmod.api.random.GameRandom

/**
 * Which impling a spawn point produces. All four tables are published with explicit numerators;
 * the differing denominators (100/100/101/301) are the wiki's, not ours. Entries name the
 * overworld npc id - the one identity every impling has (docs/hunter.md).
 */
enum class ImplingTier {
    Low,
    Mid,

    // The overworld's high tier; [HighPuroPuro] is Puro-Puro's.
    High,
    HighPuroPuro,

    /**
     * Prifddinas: one entry at certainty, so the spawner has one shape for every anchor rather
     * than a special case beside it.
     */
    Crystal,
}

// One creature's share of a tier, as the wiki prints it.
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

    // The published denominator, which is *not* the same for all tables.
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

    // The trailing return is unreachable while the weights sum to the total (asserted by
    // `ImplingSpawnsTest`); a table edited to sum short must not silently spawn nothing.
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
