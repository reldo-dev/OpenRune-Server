package org.rsmod.content.skills.hunter.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.definition.dbtables.DBTableBuilder
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType

/**
 * The bird snare, box trap, deadfall, net trap and magic box creature tables.
 *
 * Every `npc` and caught `obj` here is a cache symbol confirmed to exist via `config/npc` /
 * `config/obj` lookups - never a wiki name transcribed directly, since the two frequently differ
 * (e.g. the wiki's "Crimson swift" is `npc.hunting_bird_jungle` in the cache).
 *
 * XP is stored x10 so that the fractional values the wiki quotes survive an int column; the
 * content side divides by ten once, at the point it awards.
 *
 * Row order is load-bearing: a sprung trap persists its creature as an index into the combined
 * snare-then-box-then-deadfall-then-nettrap-then-magicbox list, so rows are read back sorted by
 * dbrow id and the ids below are assigned in that order. Existing saves hold indices into the first
 * three tables, so a new technique's rows must sort after every row already shipped - never between
 * them.
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

    // The magic box needs no nested block: it is a one-creature technique, so its four loc states
    // are shared by construction and live on the content side as constants, the same split the
    // deadfall's unset boulder and armed trap get.

    /**
     * Columns 0-7, shared verbatim by every creature table. Per-technique columns are declared by
     * the caller on top of these: box trap adds `bait`, deadfall adds three loc columns, net trap
     * adds eight, and the magic box adds none.
     *
     * The column *ids* are shared as well as the shapes, so a creature row means the same thing
     * whichever table it came from and `HunterCreatures` can map all five through one record.
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

    /**
     * The net trap is a fixed map loc like the deadfall, but it drives *two* locs: the young tree
     * the player sets, and a second "Net trap" loc that appears on an adjacent tile. Both, and every
     * frame between them, are per-creature - so all eight states are columns here and none is a
     * shared constant. That is the same rule the deadfall follows, applied to a family where nothing
     * happens to be shared: the deadfall's unset boulder and armed trap are one loc for all five of
     * its creatures, whereas the cache holds a full 5x8 grid of `hunting_sapling_*` locs and there
     * is no unsuffixed member of any state.
     *
     * **Every one of the 40 was resolved individually against `config/loc`, not derived from a
     * suffix**, because the swamp lizard's are inconsistent: it is `hunting_sapling_*_swamp` for
     * six states but `hunting_sapling_{catching,full}_green` for the other two. Its npc is
     * `salamander_green` (2906) while the wiki calls the creature a Swamp lizard, and its caught obj
     * is `green_salamander` (10149) named "Swamp lizard" - three different words for one creature.
     * The tecu is the mirror case: npc `salamander_mountain`, locs `_mountain`, obj
     * `mountain_salamander` (28834) named "Tecu salamander".
     *
     * Which states carry an op, and so which get a `contentGroup` in `loc.toml`: `up` (Set-trap),
     * `set` and `net_set` (Dismantle/Investigate), `full` (Check/Reset) and `failed`
     * (Dismantle/Reset). `setting`, `catching` and `failing` are transient frames with no ops.
     *
     * Levels and xp are from the `Net trap` page's Creatures table (oldid=15272929). The black
     * salamander's 319.2 is the first fractional value the x10 storage convention is actually load-
     * bearing for.
     *
     * Unlike the birds and the deadfall creatures, these `(low, high)` pairs did not have to be
     * fit: the wiki's `{{Skilling success chart}}` template is parameterised on exactly the `low`
     * and `high` the engine takes, and the consolidated "Salamander catch chance" chart on the
     * `Net trap` page states all five outright. They were fit anyway, against the 174 server-
     * rendered chart points, and **each of the five is pinned to a single integer pair** - no member
     * of a wider fit set, as three of the deadfall rows are. Tecu is `(1, 212)`, *not* the `(0, 212)`
     * that black salamander uses and that its near-identical curve suggests: the two differ at
     * exactly one charted level, L83, where the chart reads 179/256 and `(0, 212)` yields 178.
     *
     * No `bait` column, for the reason the deadfall has none: bait is out of scope, and an unread
     * column invites a half-wired implementation. It would also be a ragged one - the tecu is the
     * only Hunter creature that accepts no bait and cannot be smoked at all.
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
                // Not (0, 212). See the class doc: L83 separates them.
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
     * The magic box, whose one creature is the imp.
     *
     * Its own table rather than a sixth row in [boxCreatures], even though both are portable traps
     * laid from an inventory item. The technique, not the column shape, is what a table decides
     * here: `HunterCreatures` derives the trap family from which table a row came out of, and the
     * family is what picks the laid obj and every loc state. A box-trap row builds its states from
     * the npc's own name (`npc.hunting_chinchompa_big` -> `loc.hunting_boxtrap_full_chinchompa_big`),
     * which the imp cannot participate in from either end: its npc is the bare `npc.imp` (5007) with
     * no `hunting_` prefix to strip, its states are the unsuffixed `hunting_imptrap_*` set (19223-
     * 19226), and it is laid from `obj.magic_imp_box` (10025) rather than `obj.hunting_boxtrap`.
     * Filing it under `BOX` would resolve to loc names that do not exist. The `bait` column settles
     * it independently: the box mapper reads it unconditionally, and the magic box takes no bait, so
     * sharing the table would mean inventing a filler obj for a column nothing reads.
     *
     * That leaves a table with only the shared 0-7 block, which is the honest shape - one creature
     * means every loc state is shared by construction, so all four live as constants on the content
     * side, exactly like the deadfall's unset boulder and armed trap.
     *
     * Level 71 / 450 xp from the imp's own Hunter infobox (`Imp`, oldid=15271036), which agrees with
     * the `Magic box` page's Creatures table (oldid=15185581).
     *
     * **The rate is published, and needed no guess.** The design spec recorded the imp as rate-
     * blocked because the `Magic box` page carries no chart - but the creature page does:
     * `{{Skilling success chart}}` "Imp catch chance", `low=0 high=197 req=71`, with 29 server-
     * rendered points that fit exactly. The fit is not unique - `(0, 197)`, `(1, 197)` and
     * `(2, 197)` all reproduce every charted level over 71-99 - so the published `low` is shipped
     * rather than the fitter's median.
     *
     * The catch yields the 2-charge `obj.magic_imp_box_full` (10027, "Imp-in-a-box(2)"). The
     * 1-charge `obj.magic_imp_box_half` (10028) is what *using* the box leaves behind, not what
     * catching an imp produces, and the banking mechanic that consumes it is out of scope.
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
}
