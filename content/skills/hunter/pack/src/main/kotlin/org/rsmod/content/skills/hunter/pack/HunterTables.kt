package org.rsmod.content.skills.hunter.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType

/**
 * The bird snare and box trap creature tables.
 *
 * Every `npc` and caught `obj` here is a cache symbol confirmed to exist via `config/npc` /
 * `config/obj` lookups - never a wiki name transcribed directly, since the two frequently differ
 * (e.g. the wiki's "Crimson swift" is `npc.hunting_bird_jungle` in the cache).
 *
 * XP is stored x10 so that the fractional values the wiki quotes survive an int column; the
 * content side divides by ten once, at the point it awards.
 *
 * Row order is load-bearing: a sprung trap persists its creature as an index into the combined
 * snare-then-box list, so rows are read back sorted by dbrow id and the ids below are assigned in
 * that order.
 */
object HunterTables {
    const val COL_NPC = 0
    const val COL_LEVEL = 1
    const val COL_XP = 2
    const val COL_SUCCESS_LOW = 3
    const val COL_SUCCESS_HIGH = 4
    const val COL_CAUGHT_ITEMS = 5
    const val COL_CAUGHT_MIN = 6
    const val COL_CAUGHT_MAX = 7
    const val COL_BAIT = 8

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
            column("npc", COL_NPC, VarType.NPC)
            column("level", COL_LEVEL, VarType.INT)
            // Stored x10.
            column("xp", COL_XP, VarType.INT)
            column("success_low", COL_SUCCESS_LOW, VarType.INT)
            column("success_high", COL_SUCCESS_HIGH, VarType.INT)
            column("caught_items", COL_CAUGHT_ITEMS, VarType.OBJ)
            column("caught_min", COL_CAUGHT_MIN, VarType.INT)
            column("caught_max", COL_CAUGHT_MAX, VarType.INT)

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
            column("npc", COL_NPC, VarType.NPC)
            column("level", COL_LEVEL, VarType.INT)
            // Stored x10.
            column("xp", COL_XP, VarType.INT)
            column("success_low", COL_SUCCESS_LOW, VarType.INT)
            column("success_high", COL_SUCCESS_HIGH, VarType.INT)
            column("caught_items", COL_CAUGHT_ITEMS, VarType.OBJ)
            column("caught_min", COL_CAUGHT_MIN, VarType.INT)
            column("caught_max", COL_CAUGHT_MAX, VarType.INT)
            column("bait", COL_BAIT, VarType.OBJ)

            row("dbrow.hunter_chinchompa") {
                columnRSCM(COL_NPC, "npc.hunting_chinchompa")
                column(COL_LEVEL, 53)
                column(COL_XP, 1984)
                column(COL_SUCCESS_LOW, 6)
                column(COL_SUCCESS_HIGH, 268)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.chinchompa_captured")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                columnRSCM(COL_BAIT, "obj.bowl_spicytomato")
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
                columnRSCM(COL_BAIT, "obj.bowl_spicymeat")
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
                columnRSCM(COL_BAIT, "obj.bowl_spicymeat")
            }
        }
}
