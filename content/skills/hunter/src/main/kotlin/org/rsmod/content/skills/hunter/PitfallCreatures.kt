package org.rsmod.content.skills.hunter

/**
 * A single pitfall creature - not a [HunterCreature]: no trap family, no loc states, no cap
 * shared with the tile traps. A null success pair means the catch is published as *certain* and
 * no draw is taken; the three cats' pairs are derived, not published (docs/hunter.md).
 */
data class PitfallCreature(
    val npc: String,
    val level: Int,
    val xp: Int,
    val loot: List<HunterCatch>,
    val leapSeq: String,
    val successLow: Int?,
    val successHigh: Int?,
)

/** Values sourced in [PitfallCreature]'s own KDoc; see there for every figure's provenance. */
object PitfallCreatures {
    val larupia =
        PitfallCreature(
            npc = "npc.hunting_jaguar",
            level = 31,
            xp = 1800,
            loot =
                listOf(
                    HunterCatch("obj.big_bones"),
                    HunterCatch("obj.hunting_larupia_meat"),
                    HunterCatch("obj.hunting_fur_jaguar_perfect"),
                ),
            leapSeq = "seq.hunter_bigcat_leap_npc",
            successLow = 53,
            successHigh = 325,
        )

    val graahk =
        PitfallCreature(
            npc = "npc.hunting_leopard",
            level = 41,
            xp = 2400,
            loot =
                listOf(
                    HunterCatch("obj.big_bones"),
                    HunterCatch("obj.hunting_graahk_meat"),
                    HunterCatch("obj.hunting_fur_leopard_perfect"),
                ),
            leapSeq = "seq.hunter_bigcat_leap_npc",
            successLow = 41,
            successHigh = 289,
        )

    val kyatt =
        PitfallCreature(
            npc = "npc.hunting_snow_tiger",
            level = 55,
            xp = 3000,
            loot =
                listOf(
                    HunterCatch("obj.big_bones"),
                    HunterCatch("obj.hunting_kyatt_meat"),
                    HunterCatch("obj.hunting_fur_tiger_perfect"),
                ),
            leapSeq = "seq.hunter_bigcat_leap_npc",
            successLow = 24,
            successHigh = 237,
        )

    val sunlight =
        PitfallCreature(
            npc = "npc.sunlight_antelope",
            level = 72,
            xp = 3800,
            loot =
                listOf(
                    HunterCatch("obj.big_bones"),
                    HunterCatch("obj.hunting_antelopesun_meat"),
                    HunterCatch("obj.hunting_antelopesun_fur"),
                    HunterCatch("obj.hunting_antelopesun_horn"),
                ),
            leapSeq = "seq.unicorn_rework_leap_npc",
            successLow = null,
            successHigh = null,
        )

    val moonlight =
        PitfallCreature(
            npc = "npc.moonlight_antelope",
            level = 91,
            xp = 4500,
            loot =
                listOf(
                    HunterCatch("obj.big_bones"),
                    HunterCatch("obj.hunting_antelopemoon_meat"),
                    HunterCatch("obj.hunting_antelopemoon_fur"),
                    HunterCatch("obj.hunting_antelopemoon_horn"),
                ),
            leapSeq = "seq.unicorn_rework_leap_npc",
            successLow = null,
            successHigh = null,
        )

    /** Ascending by level, which is also wiki order and dbrow order once this table is packed. */
    val all: List<PitfallCreature> = listOf(larupia, graahk, kyatt, sunlight, moonlight)
}
