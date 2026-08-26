package org.rsmod.content.skills.hunter

/**
 * A single tracking creature. Deliberately not a [HunterCreature] for the same reason
 * [FalconryCreature] is not: no trap family, no loc states, no trap cap - and unlike every
 * other family, no success pair at all. The catch is deterministic: the wiki technique page
 * awards loot unconditionally and no tracking page carries a Hunting-chance chart.
 *
 * [xp] is stored x10 like [HunterCreature.xp]. [fur] is the biome-named cache symbol,
 * which diverges from the wiki creature name - "common kebbit fur" is `huntingbeast_woodland_fur`.
 */
data class TrackingCreature(
    val name: String,
    val level: Int,
    val xp: Int,
    val fur: String,
    val catchSeq: String,
)

/** Values derived from the wiki technique table. */
object TrackingCreatures {
    val polar = TrackingCreature(
        name = "polar kebbit",
        level = 1,
        xp = 300,
        fur = "obj.huntingbeast_polar_fur",
        catchSeq = "seq.hunting_noose_polar",
    )

    val common = TrackingCreature(
        name = "common kebbit",
        level = 3,
        xp = 360,
        fur = "obj.huntingbeast_woodland_fur",
        catchSeq = "seq.hunting_noose_wood",
    )

    val feldipWeasel = TrackingCreature(
        name = "feldip weasel",
        level = 7,
        xp = 480,
        fur = "obj.huntingbeast_jungle_fur",
        catchSeq = "seq.hunting_noose_jungle",
    )

    val desertDevil = TrackingCreature(
        name = "desert devil",
        level = 13,
        xp = 660,
        fur = "obj.huntingbeast_desert_fur",
        catchSeq = "seq.hunting_noose_desert",
    )

    val razorBacked = TrackingCreature(
        name = "razor-backed kebbit",
        level = 49,
        xp = 3480,
        fur = "obj.huntingbeast_bigspike",
        catchSeq = "seq.hunting_noose_razorback",
    )

    val all: List<TrackingCreature> = listOf(polar, common, feldipWeasel, desertDevil, razorBacked)
}
