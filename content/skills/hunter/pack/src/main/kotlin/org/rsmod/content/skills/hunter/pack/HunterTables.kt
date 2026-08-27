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
 * already shipped, never between them. The wagtail, ferret and jerboa rows carry deliberately late
 * ids for the same reason (docs/hunter.md). [falconryCreatures] sits outside that combined list
 * (`FalconryCreatures.all` is its own list); its ids sort last only to keep one ascending run.
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

    /**
     * Net trap only, and unlike the other three techniques *every* state is a column: see
     * [netTrapCreatures] for why none of the eight can be a shared constant.
     */
    private object NetTrap {
        const val COL_UP_LOC = 8
        const val COL_SETTING_LOC = 9
        const val COL_SET_LOC = 10
        const val COL_NET_SET_LOC = 11
        const val COL_CATCHING_LOC = 12
        const val COL_FULL_LOC = 13
        const val COL_FAILING_LOC = 14
        const val COL_FAILED_LOC = 15
    }

    // The magic box, a one-creature technique, needs no nested block: its loc states are shared by
    // construction and live content-side.

    /**
     * Falconry only, and the one family with no loc column at all: nothing is laid, nothing is
     * transformed. What a falconry row needs instead is the npc the successful catch *becomes*.
     */
    private object Falconry {
        const val COL_FALCON_NPC = 8
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

            // The bird a `hunting_bird_*` search does not find: `npc.multicoloured_bird`. The
            // chart alone does not pin the pair; the page's prose endpoints decide (75, 370) over
            // (74, 371). See docs/hunter.md.
            row("dbrow.hunter_tropical_wagtail") {
                columnRSCM(COL_NPC, "npc.multicoloured_bird")
                column(COL_LEVEL, 19)
                column(COL_XP, 952)
                column(COL_SUCCESS_LOW, 75)
                column(COL_SUCCESS_HIGH, 370)
                columnRSCM(
                    COL_CAUGHT_ITEMS,
                    "obj.bones",
                    "obj.spit_raw_bird_meat",
                    "obj.hunting_stripy_bird_feather",
                )
                column(COL_CAUGHT_MIN, 1, 1, 5)
                column(COL_CAUGHT_MAX, 1, 1, 10)
                // Not "multicoloured_bird": the trap states are the `_coloured` set, 9347/9348.
                column(LocKeyed.COL_LOC_KEY, "coloured")
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

            // The two rate-blocked creatures: no chart or prose endpoints exist anywhere, so both
            // pairs are derived guesses - the chinchompa's curve translated to each creature's own
            // requirement. Derivation and anchors: docs/hunter.md.
            row("dbrow.hunter_ferret") {
                columnRSCM(COL_NPC, "npc.hunting_ferret")
                column(COL_LEVEL, 27)
                column(COL_XP, 1152)
                // Derived guess, not published - see docs/hunter.md.
                column(COL_SUCCESS_LOW, 75)
                column(COL_SUCCESS_HIGH, 338)
                // The item Ferret; `npc.hunting_ferret` is the creature (the claws pattern).
                columnRSCM(COL_CAUGHT_ITEMS, "obj.hunting_ferret")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                column(LocKeyed.COL_LOC_KEY, "ferret")
            }

            row("dbrow.hunter_embertailed_jerboa") {
                // Not `npc.varlamore_jerboa`, which is ambient scenery named plain "Jerboa".
                columnRSCM(COL_NPC, "npc.varlamore_hunterjerboa01")
                column(COL_LEVEL, 39)
                column(COL_XP, 1370)
                // Derived guess, not published - see docs/hunter.md.
                column(COL_SUCCESS_LOW, 43)
                column(COL_SUCCESS_HIGH, 306)
                // Large jerboa tail is rumour-conditional, omitted like Kebbity tuft.
                columnRSCM(COL_CAUGHT_ITEMS, "obj.hunting_jerboa_tail")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                column(LocKeyed.COL_LOC_KEY, "jerboa")
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

    /**
     * The net trap drives two locs per creature and *every* state is a column - the cache holds a
     * full 5x8 grid of `hunting_sapling_*` locs with no unsuffixed member of any state. All forty
     * were resolved individually against `config/loc`, never derived from a suffix: the swamp
     * lizard's are inconsistent. The five `(low, high)` pairs are published outright and each is
     * pinned to a single integer pair. No bait column. See docs/hunter.md.
     */
    fun netTrapCreatures(): DBTable =
        dbTable("dbtable.hunter_nettrap_creatures", serverOnly = true) {
            creatureColumns()
            column("up_loc", NetTrap.COL_UP_LOC, VarType.LOC)
            column("setting_loc", NetTrap.COL_SETTING_LOC, VarType.LOC)
            column("set_loc", NetTrap.COL_SET_LOC, VarType.LOC)
            column("net_set_loc", NetTrap.COL_NET_SET_LOC, VarType.LOC)
            column("catching_loc", NetTrap.COL_CATCHING_LOC, VarType.LOC)
            column("full_loc", NetTrap.COL_FULL_LOC, VarType.LOC)
            column("failing_loc", NetTrap.COL_FAILING_LOC, VarType.LOC)
            column("failed_loc", NetTrap.COL_FAILED_LOC, VarType.LOC)

            // The mixed-suffix creature. Six `_swamp` states, two `_green` ones.
            row("dbrow.hunter_swamp_lizard") {
                columnRSCM(COL_NPC, "npc.salamander_green")
                column(COL_LEVEL, 29)
                column(COL_XP, 1520)
                column(COL_SUCCESS_LOW, 52)
                column(COL_SUCCESS_HIGH, 360)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.green_salamander")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                columnRSCM(NetTrap.COL_UP_LOC, "loc.hunting_sapling_up_swamp")
                columnRSCM(NetTrap.COL_SETTING_LOC, "loc.hunting_sapling_setting_swamp")
                columnRSCM(NetTrap.COL_SET_LOC, "loc.hunting_sapling_set_swamp")
                columnRSCM(NetTrap.COL_NET_SET_LOC, "loc.hunting_sapling_net_set_swamp")
                columnRSCM(NetTrap.COL_CATCHING_LOC, "loc.hunting_sapling_catching_green")
                columnRSCM(NetTrap.COL_FULL_LOC, "loc.hunting_sapling_full_green")
                columnRSCM(NetTrap.COL_FAILING_LOC, "loc.hunting_sapling_failing_swamp")
                columnRSCM(NetTrap.COL_FAILED_LOC, "loc.hunting_sapling_failed_swamp")
            }

            row("dbrow.hunter_orange_salamander") {
                columnRSCM(COL_NPC, "npc.salamander_orange")
                column(COL_LEVEL, 47)
                column(COL_XP, 2240)
                column(COL_SUCCESS_LOW, 16)
                column(COL_SUCCESS_HIGH, 288)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.orange_salamander")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                columnRSCM(NetTrap.COL_UP_LOC, "loc.hunting_sapling_up_orange")
                columnRSCM(NetTrap.COL_SETTING_LOC, "loc.hunting_sapling_setting_orange")
                columnRSCM(NetTrap.COL_SET_LOC, "loc.hunting_sapling_set_orange")
                columnRSCM(NetTrap.COL_NET_SET_LOC, "loc.hunting_sapling_net_set_orange")
                columnRSCM(NetTrap.COL_CATCHING_LOC, "loc.hunting_sapling_catching_orange")
                columnRSCM(NetTrap.COL_FULL_LOC, "loc.hunting_sapling_full_orange")
                columnRSCM(NetTrap.COL_FAILING_LOC, "loc.hunting_sapling_failing_orange")
                columnRSCM(NetTrap.COL_FAILED_LOC, "loc.hunting_sapling_failed_orange")
            }

            row("dbrow.hunter_red_salamander") {
                columnRSCM(COL_NPC, "npc.salamander_red")
                column(COL_LEVEL, 59)
                column(COL_XP, 2720)
                column(COL_SUCCESS_LOW, 0)
                column(COL_SUCCESS_HIGH, 240)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.red_salamander")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                columnRSCM(NetTrap.COL_UP_LOC, "loc.hunting_sapling_up_red")
                columnRSCM(NetTrap.COL_SETTING_LOC, "loc.hunting_sapling_setting_red")
                columnRSCM(NetTrap.COL_SET_LOC, "loc.hunting_sapling_set_red")
                columnRSCM(NetTrap.COL_NET_SET_LOC, "loc.hunting_sapling_net_set_red")
                columnRSCM(NetTrap.COL_CATCHING_LOC, "loc.hunting_sapling_catching_red")
                columnRSCM(NetTrap.COL_FULL_LOC, "loc.hunting_sapling_full_red")
                columnRSCM(NetTrap.COL_FAILING_LOC, "loc.hunting_sapling_failing_red")
                columnRSCM(NetTrap.COL_FAILED_LOC, "loc.hunting_sapling_failed_red")
            }

            // 319.2 xp - the row that makes the x10 column mandatory rather than defensive.
            row("dbrow.hunter_black_salamander") {
                columnRSCM(COL_NPC, "npc.salamander_black")
                column(COL_LEVEL, 67)
                column(COL_XP, 3192)
                column(COL_SUCCESS_LOW, 0)
                column(COL_SUCCESS_HIGH, 212)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.black_salamander")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                columnRSCM(NetTrap.COL_UP_LOC, "loc.hunting_sapling_up_black")
                columnRSCM(NetTrap.COL_SETTING_LOC, "loc.hunting_sapling_setting_black")
                columnRSCM(NetTrap.COL_SET_LOC, "loc.hunting_sapling_set_black")
                columnRSCM(NetTrap.COL_NET_SET_LOC, "loc.hunting_sapling_net_set_black")
                columnRSCM(NetTrap.COL_CATCHING_LOC, "loc.hunting_sapling_catching_black")
                columnRSCM(NetTrap.COL_FULL_LOC, "loc.hunting_sapling_full_black")
                columnRSCM(NetTrap.COL_FAILING_LOC, "loc.hunting_sapling_failing_black")
                columnRSCM(NetTrap.COL_FAILED_LOC, "loc.hunting_sapling_failed_black")
            }

            row("dbrow.hunter_tecu_salamander") {
                columnRSCM(COL_NPC, "npc.salamander_mountain")
                column(COL_LEVEL, 79)
                column(COL_XP, 3440)
                // Not (0, 212): the two pairs differ at exactly L83 (docs/hunter.md).
                column(COL_SUCCESS_LOW, 1)
                column(COL_SUCCESS_HIGH, 212)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.mountain_salamander")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                columnRSCM(NetTrap.COL_UP_LOC, "loc.hunting_sapling_up_mountain")
                columnRSCM(NetTrap.COL_SETTING_LOC, "loc.hunting_sapling_setting_mountain")
                columnRSCM(NetTrap.COL_SET_LOC, "loc.hunting_sapling_set_mountain")
                columnRSCM(NetTrap.COL_NET_SET_LOC, "loc.hunting_sapling_net_set_mountain")
                columnRSCM(NetTrap.COL_CATCHING_LOC, "loc.hunting_sapling_catching_mountain")
                columnRSCM(NetTrap.COL_FULL_LOC, "loc.hunting_sapling_full_mountain")
                columnRSCM(NetTrap.COL_FAILING_LOC, "loc.hunting_sapling_failing_mountain")
                columnRSCM(NetTrap.COL_FAILED_LOC, "loc.hunting_sapling_failed_mountain")
            }
        }

    /**
     * The magic box's one creature, the imp. Its own table because the *family* decides the laid
     * obj and every loc state, and the imp cannot participate in the box trap's naming from either
     * end - filing it under BOX would resolve to loc names that do not exist. The rate is the
     * creature page's own published parameter. See docs/hunter.md.
     */
    fun magicBoxCreatures(): DBTable =
        dbTable("dbtable.hunter_magicbox_creatures", serverOnly = true) {
            creatureColumns()

            row("dbrow.hunter_imp") {
                columnRSCM(COL_NPC, "npc.imp")
                column(COL_LEVEL, 71)
                column(COL_XP, 4500)
                column(COL_SUCCESS_LOW, 0)
                column(COL_SUCCESS_HIGH, 197)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.magic_imp_box_full")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }
        }

    /**
     * The three falconry kebbits - not a trap table: the one extra column is the per-kebbit
     * "falcon holding prey" npc a catch spawns, which is what lets a retrieve recover the whole
     * reward from the npc it clicked. All three pairs are pinned to a single integer solution,
     * cross-checked twice (chart + prose endpoints). The spotted kebbit's `high` of 310 exceeds
     * 256 on purpose and must not be clamped. Sourcing and extraction notes: docs/hunter.md.
     */
    fun falconryCreatures(): DBTable =
        dbTable("dbtable.hunter_falconry_creatures", serverOnly = true) {
            creatureColumns()
            column("falcon_npc", Falconry.COL_FALCON_NPC, VarType.NPC)

            row("dbrow.hunter_spotted_kebbit") {
                columnRSCM(COL_NPC, "npc.huntingbeast_speedy")
                column(COL_LEVEL, 43)
                column(COL_XP, 1040)
                column(COL_SUCCESS_LOW, 26)
                // Above 256, and correct - do not clamp (docs/hunter.md).
                column(COL_SUCCESS_HIGH, 310)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.bones", "obj.huntingbeast_speedy_fur")
                column(COL_CAUGHT_MIN, 1, 1)
                column(COL_CAUGHT_MAX, 1, 1)
                columnRSCM(Falconry.COL_FALCON_NPC, "npc.hunting_falcon_onspeedy")
            }

            row("dbrow.hunter_dark_kebbit") {
                columnRSCM(COL_NPC, "npc.huntingbeast_silent")
                column(COL_LEVEL, 57)
                column(COL_XP, 1320)
                column(COL_SUCCESS_LOW, 0)
                column(COL_SUCCESS_HIGH, 253)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.bones", "obj.huntingbeast_silent_fur")
                column(COL_CAUGHT_MIN, 1, 1)
                column(COL_CAUGHT_MAX, 1, 1)
                // The falcon npc ids do not ascend with creature level.
                columnRSCM(Falconry.COL_FALCON_NPC, "npc.hunting_falcon_onsilent")
            }

            // The only three-reward falconry row: dashing kebbits always drop meat as well.
            row("dbrow.hunter_dashing_kebbit") {
                columnRSCM(COL_NPC, "npc.huntingbeast_speedy2")
                column(COL_LEVEL, 69)
                column(COL_XP, 1560)
                column(COL_SUCCESS_LOW, 0)
                column(COL_SUCCESS_HIGH, 205)
                columnRSCM(
                    COL_CAUGHT_ITEMS,
                    "obj.bones",
                    "obj.huntingbeast_speedy2_fur",
                    "obj.huntingbeast_speedy2_meat",
                )
                column(COL_CAUGHT_MIN, 1, 1, 1)
                column(COL_CAUGHT_MAX, 1, 1, 1)
                columnRSCM(Falconry.COL_FALCON_NPC, "npc.hunting_falcon_onspeedy2")
            }
        }
}
