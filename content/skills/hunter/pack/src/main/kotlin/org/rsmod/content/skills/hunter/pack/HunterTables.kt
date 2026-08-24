package org.rsmod.content.skills.hunter.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.definition.dbtables.DBTableBuilder
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType

/**
 * The bird snare, box trap and deadfall creature tables.
 *
 * Every `npc` and caught `obj` here is a cache symbol confirmed to exist via `config/npc` /
 * `config/obj` lookups - never a wiki name transcribed directly, since the two frequently differ
 * (e.g. the wiki's "Crimson swift" is `npc.hunting_bird_jungle` in the cache).
 *
 * XP is stored x10 so that the fractional values the wiki quotes survive an int column; the
 * content side divides by ten once, at the point it awards.
 *
 * Row order is load-bearing: a sprung trap persists its creature as an index into the combined
 * snare-then-box-then-deadfall list, so rows are read back sorted by dbrow id and the ids below are
 * assigned in that order.
 */
object HunterTables {
    // Column ids must form a dense 0..n-1 *set* per table - declaration order does not matter, only
    // which ids are used. Why: the gameval encoder writes a table's columns sorted by id and never
    // writes the id itself, just a name per column; on read, each `dbcol.<table>:<col>` is assigned
    // its ordinal purely from read position - a counter starting at 0, incremented per column - with
    // no relation to the id passed to `column(name, id, type)` here. The packed *row* bytes, decoded
    // separately, keep the original id explicit per column. Leave a gap and the two numbering
    // schemes desync: every gameval ordinal past the gap resolves one row-id too low, so the
    // generated accessor silently reads the next column's data, and the highest id has no ordinal
    // left to reach it - dropped with no error, since the codegen just skips a column it cannot find
    // in any sample row. So the per-technique columns below all start at 8: ids are shared across
    // tables only for the common block, 0-7.
    const val COL_NPC = 0
    const val COL_LEVEL = 1
    const val COL_XP = 2
    const val COL_SUCCESS_LOW = 3
    const val COL_SUCCESS_HIGH = 4
    const val COL_CAUGHT_ITEMS = 5
    const val COL_CAUGHT_MIN = 6
    const val COL_CAUGHT_MAX = 7

    // Table-specific columns are nested so e.g. `COL_BAIT` cannot be typed inside deadfallCreatures()
    // by mistake - both start at 8, and a slip would compile, pack, and silently write the wrong
    // table's column 8.

    /** Box trap only. */
    private object Box {
        const val COL_BAIT = 8
    }

    /** Deadfall only. */
    private object Deadfall {
        const val COL_TRAPPING_LOC = 8
        const val COL_TRAPPING_LOC_M = 9
        const val COL_FULL_LOC = 10
    }

    /**
     * Columns 0-7, shared verbatim by all three creature tables. Per-technique columns are declared
     * by the caller on top of these: box trap adds `bait`, deadfall adds the three loc columns.
     *
     * The column *ids* are shared as well as the shapes, so a creature row means the same thing
     * whichever table it came from and `HunterCreatures` can map all three through one record.
     */
    private fun DBTableBuilder.creatureColumns() {
        column("npc", COL_NPC, VarType.NPC)
        column("level", COL_LEVEL, VarType.INT)
        // Stored x10.
        column("xp", COL_XP, VarType.INT)
        column("success_low", COL_SUCCESS_LOW, VarType.INT)
        column("success_high", COL_SUCCESS_HIGH, VarType.INT)
        column("caught_items", COL_CAUGHT_ITEMS, VarType.OBJ)
        column("caught_min", COL_CAUGHT_MIN, VarType.INT)
        column("caught_max", COL_CAUGHT_MAX, VarType.INT)
    }

    /**
     * `P(L) = (floor(m * (L - 1) / 98) + c) / 255` is not stated on any bird's wiki page - each
     * `success_low`/`success_high` pair below was fit against that creature's full per-level success
     * chart (all ~48-58 points, level by level) embedded in its "Hunter info" section, and verified
     * to reproduce every non-capped point exactly.
     *
     * Each catch awards three items in one go, and their quantities differ: every bird's infobox
     * reads "Bones | Quantity: 1 | Rarity: Always", "Raw bird meat | Quantity: 1 | Rarity: Always",
     * "<colour> feather | Quantity: 5-10 | Rarity: Always". Only the feather is rolled.
     */
    fun snareCreatures(): DBTable =
        dbTable("dbtable.hunter_snare_creatures", serverOnly = true) {
            creatureColumns()

            row("dbrow.hunter_jungle_bird") {
                columnRSCM(COL_NPC, "npc.hunting_bird_jungle")
                column(COL_LEVEL, 1)
                column(COL_XP, 340)
                column(COL_SUCCESS_LOW, 100)
                column(COL_SUCCESS_HIGH, 420)
                columnRSCM(
                    COL_CAUGHT_ITEMS,
                    "obj.bones",
                    "obj.spit_raw_bird_meat",
                    "obj.hunting_jungle_feather",
                )
                column(COL_CAUGHT_MIN, 1, 1, 5)
                column(COL_CAUGHT_MAX, 1, 1, 10)
            }

            row("dbrow.hunter_desert_bird") {
                columnRSCM(COL_NPC, "npc.hunting_bird_desert")
                column(COL_LEVEL, 5)
                column(COL_XP, 470)
                column(COL_SUCCESS_LOW, 92)
                column(COL_SUCCESS_HIGH, 400)
                columnRSCM(
                    COL_CAUGHT_ITEMS,
                    "obj.bones",
                    "obj.spit_raw_bird_meat",
                    "obj.hunting_desert_feather",
                )
                column(COL_CAUGHT_MIN, 1, 1, 5)
                column(COL_CAUGHT_MAX, 1, 1, 10)
            }

            row("dbrow.hunter_woodland_bird") {
                columnRSCM(COL_NPC, "npc.hunting_bird_woodland")
                column(COL_LEVEL, 9)
                column(COL_XP, 612)
                column(COL_SUCCESS_LOW, 85)
                column(COL_SUCCESS_HIGH, 390)
                columnRSCM(
                    COL_CAUGHT_ITEMS,
                    "obj.bones",
                    "obj.spit_raw_bird_meat",
                    "obj.hunting_woodland_feather",
                )
                column(COL_CAUGHT_MIN, 1, 1, 5)
                column(COL_CAUGHT_MAX, 1, 1, 10)
            }

            row("dbrow.hunter_polar_bird") {
                columnRSCM(COL_NPC, "npc.hunting_bird_polar")
                column(COL_LEVEL, 11)
                // The creature's own infobox states 64.5 xp; the parent "Bird snare" summary table
                // states 64.6 xp. Used the creature-page value as primary.
                column(COL_XP, 645)
                column(COL_SUCCESS_LOW, 82)
                column(COL_SUCCESS_HIGH, 380)
                columnRSCM(
                    COL_CAUGHT_ITEMS,
                    "obj.bones",
                    "obj.spit_raw_bird_meat",
                    "obj.hunting_polar_feather",
                )
                column(COL_CAUGHT_MIN, 1, 1, 5)
                column(COL_CAUGHT_MAX, 1, 1, 10)
            }
        }

    /**
     * All three box trap creatures state their success formula directly on the wiki, so unlike the
     * birds these pairs are read off rather than fit.
     *
     * `bait` is recorded but unread in v1.
     */
    fun boxCreatures(): DBTable =
        dbTable("dbtable.hunter_box_creatures", serverOnly = true) {
            creatureColumns()
            column("bait", Box.COL_BAIT, VarType.OBJ)

            row("dbrow.hunter_chinchompa") {
                columnRSCM(COL_NPC, "npc.hunting_chinchompa")
                column(COL_LEVEL, 53)
                column(COL_XP, 1984)
                column(COL_SUCCESS_LOW, 6)
                column(COL_SUCCESS_HIGH, 268)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.chinchompa_captured")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                columnRSCM(Box.COL_BAIT, "obj.bowl_spicytomato")
            }

            row("dbrow.hunter_carnivorous_chinchompa") {
                columnRSCM(COL_NPC, "npc.hunting_chinchompa_big")
                column(COL_LEVEL, 63)
                column(COL_XP, 2650)
                // "Carnivorous and Black Chinchompas have the same catch rate" - stated on both wiki
                // pages, and the two per-level charts are pointwise identical.
                column(COL_SUCCESS_LOW, -78)
                column(COL_SUCCESS_HIGH, 228)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.chinchompa_big_captured")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                columnRSCM(Box.COL_BAIT, "obj.bowl_spicymeat")
            }

            row("dbrow.hunter_black_chinchompa") {
                columnRSCM(COL_NPC, "npc.hunting_chinchompa_black")
                column(COL_LEVEL, 73)
                column(COL_XP, 3150)
                column(COL_SUCCESS_LOW, -78)
                column(COL_SUCCESS_HIGH, 228)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.chinchompa_black")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                columnRSCM(Box.COL_BAIT, "obj.bowl_spicymeat")
            }
        }

    /**
     * Deadfall is a fixed map loc rather than a carried trap, so each creature also names the three
     * boulder states that are specific to it: the two mid-catch `trapping` locs (the base and its
     * `_m` mirror, which the client picks between by the side the creature walked in from) and the
     * `full` loc that carries the Check op. The states shared by every creature - the unset boulder,
     * the armed trap, and the transient setting/failing frames - are plain constants on the content
     * side, not columns.
     *
     * Suffix map, read off `config/loc` and cross-checked against each npc's own recolours: claw =
     * wild kebbit (both `recol1s=8288 recol1d=10468`), barbed = barb-tailed (`10468 -> 8420`), sabre
     * = sabre-toothed (`8288 -> 25403`), fennec = pyre fox, spike = prickly by elimination.
     *
     * No deadfall creature states its `P(L)` formula on the wiki, so as with the birds each
     * `success_low`/`success_high` pair was fit against that creature's full per-level chart under
     * *Drops > Hunting chance*, against the exact formula `SkillingSuccessRate` implements
     * (`api/utils/utils-skills/.../SkillingSuccessRate.kt`): chance = `(1 + floor(low*(99-L)/98 +
     * high*(L-1)/98 + 0.5)) / 256`. Every charted point is reproduced exactly - 102 points across the
     * five creatures. For wild kebbit (42 points) and prickly kebbit (45 points) the fit is
     * mathematically pinned: exactly one integer pair satisfies every charted point. Barb-tailed,
     * sabre-toothed and pyre fox chart far fewer levels each, so multiple integer pairs fit exactly;
     * the pair shipped here is one member of that fit set, not the unique solution. Wiki revisions
     * fitted against: wild kebbit oldid=15196478, barb-tailed oldid=15196228, prickly
     * oldid=15196260, sabre-toothed oldid=15196422, pyre fox oldid=15197087. (A local, gitignored
     * copy of the chart extract and fitting script lives in `.data/cache/wiki-hunter/` for
     * convenience - it is not checked in and is not the source of truth; the oldids above are.)
     * Negative lows are the honest fit for the four steeper curves and must not be clamped:
     * `SkillingSuccessRate` interpolates low..high across levels 1..99, and these creatures are only
     * catchable from level 23+, so the sub-requirement end of the line is never evaluated.
     *
     * Rewards are the infobox "Always" drops only. Kebbity tuft and Fox fluff are omitted on
     * purpose: both are 1/15 *and* conditional on an active Hunter's Rumour, which is not
     * implemented. Bait (+3/256) and smoke (+2/256) are real but out of scope, and deliberately get
     * no column here - an unread column invites a half-wired implementation.
     */
    fun deadfallCreatures(): DBTable =
        dbTable("dbtable.hunter_deadfall_creatures", serverOnly = true) {
            creatureColumns()
            column("trapping_loc", Deadfall.COL_TRAPPING_LOC, VarType.LOC)
            column("trapping_loc_m", Deadfall.COL_TRAPPING_LOC_M, VarType.LOC)
            column("full_loc", Deadfall.COL_FULL_LOC, VarType.LOC)

            // Level and xp from the creature's own wiki infobox ("Hunter XP: 128"), which agrees
            // with the parent Deadfall page (oldid=15201193). `obj.huntingbeast_claws` is the item
            // Kebbit claws (10113) - not to be confused with `npc.huntingbeast_claws` (1349), the
            // creature; the cache reuses the symbol across the two namespaces.
            row("dbrow.hunter_wild_kebbit") {
                columnRSCM(COL_NPC, "npc.huntingbeast_claws")
                column(COL_LEVEL, 23)
                column(COL_XP, 1280)
                column(COL_SUCCESS_LOW, 29)
                column(COL_SUCCESS_HIGH, 385)
                columnRSCM(
                    COL_CAUGHT_ITEMS,
                    "obj.bones",
                    "obj.huntingbeast_claws",
                    "obj.huntingbeast_wild_meat",
                )
                column(COL_CAUGHT_MIN, 1, 1, 1)
                column(COL_CAUGHT_MAX, 1, 1, 1)
                columnRSCM(Deadfall.COL_TRAPPING_LOC, "loc.hunting_deadfall_trapping_claw")
                columnRSCM(Deadfall.COL_TRAPPING_LOC_M, "loc.hunting_deadfall_trapping_claw_m")
                columnRSCM(Deadfall.COL_FULL_LOC, "loc.hunting_deadfall_full_claw")
            }

            row("dbrow.hunter_barbtailed_kebbit") {
                columnRSCM(COL_NPC, "npc.huntingbeast_barbedtail")
                column(COL_LEVEL, 33)
                column(COL_XP, 1680)
                // Only six levels of this creature's curve are charted, so the fit is not pinned to
                // a single pair; this one was derived by hand from the endpoints and then confirmed
                // to be a member of the script's exact-fit set.
                column(COL_SUCCESS_LOW, -226)
                column(COL_SUCCESS_HIGH, 1048)
                columnRSCM(
                    COL_CAUGHT_ITEMS,
                    "obj.bones",
                    "obj.hunting_barbed_harpoon",
                    "obj.huntingbeast_barbed_meat",
                )
                column(COL_CAUGHT_MIN, 1, 1, 1)
                column(COL_CAUGHT_MAX, 1, 1, 1)
                columnRSCM(Deadfall.COL_TRAPPING_LOC, "loc.hunting_deadfall_trapping_barbed")
                columnRSCM(Deadfall.COL_TRAPPING_LOC_M, "loc.hunting_deadfall_trapping_barbed_m")
                columnRSCM(Deadfall.COL_FULL_LOC, "loc.hunting_deadfall_full_barbed")
            }

            // Prickly and sabre-toothed drop no meat - two reward lines, not three.
            row("dbrow.hunter_prickly_kebbit") {
                columnRSCM(COL_NPC, "npc.huntingbeast_spiky")
                column(COL_LEVEL, 37)
                column(COL_XP, 2040)
                column(COL_SUCCESS_LOW, -70)
                column(COL_SUCCESS_HIGH, 331)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.bones", "obj.huntingbeast_spike")
                column(COL_CAUGHT_MIN, 1, 1)
                column(COL_CAUGHT_MAX, 1, 1)
                columnRSCM(Deadfall.COL_TRAPPING_LOC, "loc.hunting_deadfall_trapping_spike")
                columnRSCM(Deadfall.COL_TRAPPING_LOC_M, "loc.hunting_deadfall_trapping_spike_m")
                columnRSCM(Deadfall.COL_FULL_LOC, "loc.hunting_deadfall_full_spike")
            }

            row("dbrow.hunter_sabretoothed_kebbit") {
                columnRSCM(COL_NPC, "npc.huntingbeast_sabreteeth")
                column(COL_LEVEL, 51)
                column(COL_XP, 2000)
                // Five charted levels; same caveat as barb-tailed.
                column(COL_SUCCESS_LOW, -422)
                column(COL_SUCCESS_HIGH, 809)
                // Kebbit teeth, `obj.huntingbeast_sabreteeth` (10109) - not the near-name
                // `obj.huntingbeast_sabreteeth_dust` (10111), which is Kebbit teeth dust.
                columnRSCM(COL_CAUGHT_ITEMS, "obj.bones", "obj.huntingbeast_sabreteeth")
                column(COL_CAUGHT_MIN, 1, 1)
                column(COL_CAUGHT_MAX, 1, 1)
                columnRSCM(Deadfall.COL_TRAPPING_LOC, "loc.hunting_deadfall_trapping_sabre")
                columnRSCM(Deadfall.COL_TRAPPING_LOC_M, "loc.hunting_deadfall_trapping_sabre_m")
                columnRSCM(Deadfall.COL_FULL_LOC, "loc.hunting_deadfall_full_sabre")
            }

            // The pyre fox's cache symbols all read "fennec": the npc is `varlamore_fennecfox`
            // (12980), whose cache name is still "Fennec fox" even though the wiki, the drops and
            // the loc set all call it a Pyre fox.
            row("dbrow.hunter_pyre_fox") {
                columnRSCM(COL_NPC, "npc.varlamore_fennecfox")
                column(COL_LEVEL, 57)
                column(COL_XP, 2220)
                // Four charted levels; same caveat as barb-tailed.
                column(COL_SUCCESS_LOW, -475)
                column(COL_SUCCESS_HIGH, 750)
                columnRSCM(
                    COL_CAUGHT_ITEMS,
                    "obj.bones",
                    "obj.hunting_fennecfox_fur",
                    "obj.hunting_fennecfox_meat",
                )
                column(COL_CAUGHT_MIN, 1, 1, 1)
                column(COL_CAUGHT_MAX, 1, 1, 1)
                columnRSCM(Deadfall.COL_TRAPPING_LOC, "loc.hunting_deadfall_trapping_fennec")
                columnRSCM(Deadfall.COL_TRAPPING_LOC_M, "loc.hunting_deadfall_trapping_fennec_m")
                columnRSCM(Deadfall.COL_FULL_LOC, "loc.hunting_deadfall_full_fennec")
            }
        }
}
