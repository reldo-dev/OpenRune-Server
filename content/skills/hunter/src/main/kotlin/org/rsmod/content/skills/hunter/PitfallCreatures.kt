package org.rsmod.content.skills.hunter

/**
 * A single pitfall creature.
 *
 * Deliberately **not** a [HunterCreature], for the same reason [FalconryCreature] and
 * [TrackingCreature] are not: that record carries a [TrapFamily] and a set of loc-state columns,
 * and a pitfall lays nothing. A pitfall is per-player varbit state on permanent map scenery - the
 * client picks each site's rendered child from a `hunt_pitfall_state<N>` varbit, exactly as the
 * crab trap's sites do - never a `locRepo`-owned world object with a family of its own to widen.
 *
 * [successLow] and [successHigh] are the same engine coefficients [HunterCreature] documents, fed
 * to the same `SkillingSuccessRate.successRate` - **when non-null**. They are nullable on purpose:
 * [sunlight] and [moonlight] are a documented 100% catch and carry `null`, not `(256, 256)` or any
 * other always-true pair. A null is the honest encoding of "this creature cannot fail," and it is
 * what lets [HunterPitfall] skip the roll entirely for them instead of rolling a rate that always
 * wins.
 *
 * The other three creatures' pairs are **derived guesses, not published or fitted-from-chart
 * ones**. No pitfall creature has a published catch-rate chart at all - the one Hunter technique
 * with none, confirmed by sweeping the whole wiki snapshot for the chart template's own marker - so
 * [larupia], [graahk] and [kyatt] ship pairs derived from this branch's own corpus of 41 published
 * pairs by a stated, reproducible rule. The derivation, and the caveat that anyone who later
 * measures real rates should replace them outright, is recorded in
 * `.data/cache/wiki-hunter/pitfall-rate-derivation.md`.
 *
 * [leapSeq] is the creature's own animation for going into the pit: the three cats share
 * `seq.hunter_bigcat_leap_npc`, and the antelopes carry `seq.unicorn_rework_leap_npc` - the
 * antelope-specific counterpart in the same cache, not a reuse of the cats' sequence.
 *
 * [loot] ships the guaranteed catch only - big bones, raw meat, and the creature's fur or antler.
 * Left out, on purpose: the 1/15 rumour-only trophies (larupia ear, graahk horn spur, kyatt tooth
 * chip, and both antelopes' hoof shards), which are gated by the separate Hunter's Rumours system
 * rather than by this table; the sunlight antelope's "sunfire splinters" line, whose obj gameval
 * this branch's recon did not resolve with confidence; and the tatty-vs-perfect fur split the three
 * cats actually roll for, whose scaling curve is documented as real but unpublished (Mod Ash,
 * 1 Aug 2022) - [loot] carries the clean fur (`_perfect`) as the representative catch, and the
 * tatty variant (e.g. `obj.hunting_fur_jaguar_shabby`) is left for whichever later change
 * implements that roll.
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
