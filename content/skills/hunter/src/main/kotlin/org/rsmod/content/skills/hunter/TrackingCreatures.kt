package org.rsmod.content.skills.hunter

/**
 * A single tracking creature. Deliberately not a [HunterCreature] for the same reason
 * [FalconryCreature] is not: no trap family, no loc states, no trap cap - and unlike every
 * other family, no success pair at all. The catch is deterministic: the wiki technique page
 * awards loot unconditionally and no tracking page carries a Hunting-chance chart.
 *
 * [xp] is stored x10 like [HunterCreature.xp]. [fur] is the biome-named cache symbol -
 * "common kebbit fur" is `huntingbeast_woodland_fur`, the third instance of wiki names
 * diverging from cache symbols on this branch.
 */
data class TrackingCreature(
    val name: String,
    val level: Int,
    val xp: Int,
    val fur: String,
    val catchSeq: String,
)

/** Values from the wiki technique table; re-derived independently by `hunterVerify`. */
object TrackingCreatures {
    val polar = TrackingCreature("polar kebbit", 1, 300, "obj.huntingbeast_polar_fur", "seq.hunting_noose_polar")
    val common = TrackingCreature("common kebbit", 3, 360, "obj.huntingbeast_woodland_fur", "seq.hunting_noose_wood")
    val feldipWeasel = TrackingCreature("feldip weasel", 7, 480, "obj.huntingbeast_jungle_fur", "seq.hunting_noose_jungle")
    val desertDevil = TrackingCreature("desert devil", 13, 660, "obj.huntingbeast_desert_fur", "seq.hunting_noose_desert")
    val razorBacked = TrackingCreature("razor-backed kebbit", 49, 3480, "obj.huntingbeast_bigspike", "seq.hunting_noose_razorback")

    val all: List<TrackingCreature> = listOf(polar, common, feldipWeasel, desertDevil, razorBacked)
}
