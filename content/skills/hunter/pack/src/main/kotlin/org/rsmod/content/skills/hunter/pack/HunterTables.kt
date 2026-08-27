package org.rsmod.content.skills.hunter.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.definition.dbtables.DBTableBuilder
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType

/**
 * The hunter creature dbtables. Every `npc` and `obj` is a cache symbol confirmed against the
 * cache, never a wiki name transcribed directly. XP is stored x10 so fractional wiki values
 * survive an int column. See docs/hunter.md.
 *
 * Row order is load-bearing: a sprung trap persists its creature as an index into the combined
 * creature list, read back sorted by dbrow id - a new technique's rows must sort after every row
 * already shipped, never between them.
 */
object HunterTables {
    // Column ids must form a dense 0..n-1 set per table: the encoder writes columns sorted by id
    // without the id itself, so a gap silently shifts every later column and drops the last, with
    // no pack-time diagnostic (docs/hunter.md). Ids 0-7 are shared; per-technique columns start
    // at 8, nested per table so one table's column cannot be typed into another's builder.
    const val COL_NPC = 0
    const val COL_LEVEL = 1
    const val COL_XP = 2
    const val COL_SUCCESS_LOW = 3
    const val COL_SUCCESS_HIGH = 4
    const val COL_CAUGHT_ITEMS = 5
    const val COL_CAUGHT_MIN = 6
    const val COL_CAUGHT_MAX = 7

    /**
     * The loc-state name suffix is authored data, never derived from the npc symbol - not every
     * creature's npc and loc names share a derivable stem. See docs/hunter.md.
     */
    private object LocKeyed {
        const val COL_LOC_KEY = 8
    }

    /** Deadfall only. */
    private object Deadfall {
        const val COL_TRAPPING_LOC = 8
        const val COL_TRAPPING_LOC_M = 9
        const val COL_FULL_LOC = 10
    }

    /** Columns 0-7, shared verbatim by every creature table. */
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
     * Each pair was fit against the creature's charted per-level success curve and verified to
     * reproduce every non-capped point exactly - see docs/hunter.md. A catch awards bones, meat
     * and feathers in one go; only the feather count is rolled.
     */
    fun snareCreatures(): DBTable =
        dbTable("dbtable.hunter_snare_creatures", serverOnly = true) {
            creatureColumns()
            column("loc_key", LocKeyed.COL_LOC_KEY, VarType.STRING)

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
                column(LocKeyed.COL_LOC_KEY, "jungle")
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
                column(LocKeyed.COL_LOC_KEY, "desert")
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
                column(LocKeyed.COL_LOC_KEY, "woodland")
            }

            row("dbrow.hunter_polar_bird") {
                columnRSCM(COL_NPC, "npc.hunting_bird_polar")
                column(COL_LEVEL, 11)
                // The infobox states 64.5 xp, the parent summary table 64.6; the infobox ships.
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
                column(LocKeyed.COL_LOC_KEY, "polar")
            }
        }

    /**
     * All three chinchompas state their success formula directly on the wiki, so those pairs are
     * read off rather than fit. No `bait` column: nothing would read it (docs/hunter.md).
     */
    fun boxCreatures(): DBTable =
        dbTable("dbtable.hunter_box_creatures", serverOnly = true) {
            creatureColumns()
            column("loc_key", LocKeyed.COL_LOC_KEY, VarType.STRING)

            row("dbrow.hunter_chinchompa") {
                columnRSCM(COL_NPC, "npc.hunting_chinchompa")
                column(COL_LEVEL, 53)
                column(COL_XP, 1984)
                column(COL_SUCCESS_LOW, 6)
                column(COL_SUCCESS_HIGH, 268)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.chinchompa_captured")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                column(LocKeyed.COL_LOC_KEY, "chinchompa")
            }

            row("dbrow.hunter_carnivorous_chinchompa") {
                columnRSCM(COL_NPC, "npc.hunting_chinchompa_big")
                column(COL_LEVEL, 63)
                column(COL_XP, 2650)
                // "Carnivorous and Black Chinchompas have the same catch rate" - both wiki pages.
                column(COL_SUCCESS_LOW, -78)
                column(COL_SUCCESS_HIGH, 228)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.chinchompa_big_captured")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                column(LocKeyed.COL_LOC_KEY, "chinchompa_big")
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
                column(LocKeyed.COL_LOC_KEY, "chinchompa_black")
            }
        }

    /**
     * Each deadfall creature also names its three per-creature boulder states; the shared states
     * are content-side constants. Wild and prickly kebbit pairs are uniquely-pinned chart fits;
     * the other three are the wiki template's own published parameters. Negative lows are the
     * honest fit and must not be clamped. Sourcing, suffix map and reward scope: docs/hunter.md.
     */
    fun deadfallCreatures(): DBTable =
        dbTable("dbtable.hunter_deadfall_creatures", serverOnly = true) {
            creatureColumns()
            column("trapping_loc", Deadfall.COL_TRAPPING_LOC, VarType.LOC)
            column("trapping_loc_m", Deadfall.COL_TRAPPING_LOC_M, VarType.LOC)
            column("full_loc", Deadfall.COL_FULL_LOC, VarType.LOC)

            // `obj.huntingbeast_claws` is the item Kebbit claws; `npc.huntingbeast_claws` is the
            // creature - the cache reuses the symbol across the two namespaces.
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
                // Published parameters (oldid=15196228): six charted levels, a fit cannot pin.
                column(COL_SUCCESS_LOW, -220)
                column(COL_SUCCESS_HIGH, 1037)
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
                // Published parameters (oldid=15196422): five charted levels, a fit cannot pin.
                column(COL_SUCCESS_LOW, -434)
                column(COL_SUCCESS_HIGH, 820)
                // Kebbit teeth - not the near-name `_dust`, which is Kebbit teeth dust.
                columnRSCM(COL_CAUGHT_ITEMS, "obj.bones", "obj.huntingbeast_sabreteeth")
                column(COL_CAUGHT_MIN, 1, 1)
                column(COL_CAUGHT_MAX, 1, 1)
                columnRSCM(Deadfall.COL_TRAPPING_LOC, "loc.hunting_deadfall_trapping_sabre")
                columnRSCM(Deadfall.COL_TRAPPING_LOC_M, "loc.hunting_deadfall_trapping_sabre_m")
                columnRSCM(Deadfall.COL_FULL_LOC, "loc.hunting_deadfall_full_sabre")
            }

            // The pyre fox's cache symbols all still read "fennec".
            row("dbrow.hunter_pyre_fox") {
                columnRSCM(COL_NPC, "npc.varlamore_fennecfox")
                column(COL_LEVEL, 57)
                column(COL_XP, 2220)
                // Published parameters; four charted levels, a fit cannot pin.
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
