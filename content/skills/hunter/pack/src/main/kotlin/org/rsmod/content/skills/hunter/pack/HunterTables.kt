package org.rsmod.content.skills.hunter.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.definition.dbtables.DBTableBuilder
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType

/**
 * The bird snare, box trap, deadfall, net trap, magic box, falconry and butterfly creature tables.
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
 *
 * [falconryCreatures] and [butterflyCreatures] are the exceptions to that sentence, and deliberately
 * so: neither technique is a trap, and their rows are read into `FalconryCreatures.all` and
 * `ButterflyCreatures.all` - separate lists indexed by separate things, never `HunterCreatures.all`.
 * Their dbrow ids still sort after every trap row so the hunter block stays one ascending run, but
 * nothing about trap-index stability depends on that.
 *
 * That stability rule is the reason the three rows added last - the tropical wagtail here in
 * [snareCreatures], the ferret and the embertailed jerboa in [boxCreatures] - carry ids in the
 * 56360s rather than ids next to their table-mates. They are the first rows to join a table that
 * already shipped, and `HunterCreatures.all` reads the five trap tables into **one list sorted by
 * dbrow id**, so an id chosen to sit next to the other birds would sort ahead of the chinchompas and
 * shift every index already written into a save.
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

    // Table-specific columns are nested so e.g. `COL_FULL_LOC` cannot be typed inside
    // snareCreatures() by mistake - they all start at 8, and a slip would compile, pack, and
    // silently write the wrong table's column 8.

    /**
     * The two portable families whose loc states are built from a name suffix rather than stored
     * whole: `loc.hunting_ojibway_trap_full_<key>` and `loc.hunting_boxtrap_full_<key>`.
     *
     * The key used to be derived in code by stripping a prefix off the npc's own symbol -
     * `npc.hunting_bird_polar` -> `polar`, `npc.hunting_chinchompa_big` -> `chinchompa_big`. That
     * held for the seven rows shipped first and breaks for all three added here: the tropical
     * wagtail's npc is `npc.multicoloured_bird` against a `_coloured` loc set, the ferret's obj and
     * npc share a symbol but its npc has no `hunting_bird_` prefix to strip, and the embertailed
     * jerboa is `npc.varlamore_hunterjerboa01` against a `_jerboa` loc set. Kotlin's
     * `substringAfter`/`substringAfterLast` return the *whole string* when the delimiter is absent,
     * so each of those would have resolved to a loc name like
     * `loc.hunting_ojibway_trap_full_npc.multicoloured_bird` and thrown at the first catch rather
     * than at boot. Storing the key makes it data, and the derivation cannot silently mismatch.
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

    // The magic box needs no nested block: it is a one-creature technique, so its four loc states
    // are shared by construction and live on the content side as constants, the same split the
    // deadfall's unset boulder and armed trap get.

    /**
     * Falconry only, and the one family with no loc column at all: nothing is laid, nothing is
     * transformed. What a falconry row needs instead is the npc the successful catch *becomes*.
     */
    private object Falconry {
        const val COL_FALCON_NPC = 8
    }

    /**
     * Crab trapping, the one table that shares **none** of [creatureColumns] and starts its own ids
     * at 0.
     *
     * Two of the shared eight do not exist for this technique and the other six would have had to be
     * reordered around them, so a bespoke dense set is the honest shape:
     * - **No `success_low`/`success_high`.** "Unlike other methods, players cannot fail to catch a
     *   crab" (wiki, *Crab trapping*), restated in its own Strategy section - "Because crabs traps
     *   can never fail to catch a crab, the guild hunter outfit has no effect, nor does anti-odour
     *   salt". There is no rate to store and no roll to make, so a `(0, 256)` pair would be a
     *   fabricated coefficient for a formula this technique never evaluates.
     * - **No `npc`.** The three crab npcs (`red_crab` 15089, `blue_crab` 15090,
     *   `rainbow_crab_a/b/c` 15091-15093) carry **no ops at all** and are never touched: a crab trap
     *   is a per-player varbit on a map-placed multiloc, so nothing is lured, found or despawned.
     *   `.data` holds one red-crab spawn, two blue and **zero** rainbow, and the technique works
     *   identically for all three - which is the proof the npc is not load-bearing. A column nothing
     *   reads is the trap [netTrapCreatures] documents for `bait`.
     *
     * [COL_CAUGHT_ITEMS] and [COL_FULL_LOC] are **parallel lists**: entry `i` of each is the same
     * colourway of the same crab. Red and blue have one; the rainbow crab has three.
     */
    /**
     * Implings, whose only per-technique column is the second experience value.
     *
     * A catch awards different experience depending on where the impling **spawned**: the wiki's
     * per-creature infobox publishes a "Puro Puro XP" and an "Overworld XP" for all twelve, and the
     * gap is not cosmetic - the magpie is 44 against 216. The shared [COL_XP] keeps its usual
     * meaning, the overworld value, and this column carries the Puro-Puro one, so neither column is
     * a special case that only this table understands.
     *
     * Both are populated even though this slice ships only the Puro-Puro creatures and reads only
     * [COL_XP_PURO]. That is deliberate and is not the unread-column trap [netTrapCreatures]
     * documents for `bait`: the overworld value is published data with a known consumer - the
     * overworld implings need a spawner before they can ship - and omitting it would leave the
     * shared `xp` column meaning "the Puro-Puro value" in this table alone.
     */
    private object Impling {
        const val COL_XP_PURO = 8
    }

    private object Crab {
        const val COL_LEVEL = 0
        const val COL_XP = 1
        const val COL_BAIT = 2
        const val COL_CAUGHT_ITEMS = 3
        const val COL_FULL_LOC = 4
        const val COL_CATCH_DELAY = 5
    }

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
                column(LocKeyed.COL_LOC_KEY, "polar")
            }

            /*
             * The fifth bird, which the first build wrongly recorded as not existing.
             *
             * That exclusion searched the `hunting_bird_*` symbol prefix, found exactly four, and
             * concluded there was no npc behind the unused `hunting_ojibway_trap_full_coloured`
             * trap state. The wagtail sits outside the prefix: it is `npc.multicoloured_bird`
             * (5548), `name=Tropical wagtail`, and its `model1=model_26839` is the very model that
             * comment cited as belonging to the orphaned trap state - the evidence read as proving
             * absence is what identifies the creature. It is otherwise byte-for-byte the shape of
             * the other four (same `category_651`, same `model2=model_26844`, same six params), and
             * it has 30 spawns in `.data`, comparable to polar's 23.
             *
             * Level 19 / 95.2 xp from its own Hunter info box (oldid=15259195). Its chart is
             * published there too, 43 points over L19-61, and the chart alone does *not* pin the
             * pair: both `(74, 371)` and `(75, 370)` reproduce all 43 points exactly.
             *
             * **The page's prose separates them, and this is the first row in the hunter branch
             * where the cross-check has actually decided something rather than merely agreed.** It
             * states "The catch rate is 29% at lvl 1 and 144% at lvl 99", and the engine evaluates
             * `low + 1` at L1 and `high + 1` at L99: `(75, 370)` gives 76/256 = 29.6% and 371/256 =
             * 144.9%, which truncate to the stated 29 and 144, while `(74, 371)` gives 372/256 =
             * 145.3% and truncates to 145. So `(75, 370)` is pinned after all.
             *
             * It is also the pair that continues the snare family's own sequence: the four birds run
             * (100, 420), (92, 400), (85, 390), (82, 380) as the requirement climbs 1 -> 5 -> 9 ->
             * 11, both coefficients descending, and (75, 370) is the next step in both.
             *
             * Tailfeathers is omitted from the rewards for the reason Kebbity tuft is omitted from
             * the deadfall's: 1/20 *and* conditional on an active Hunter's Rumour.
             */
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
     * All three chinchompas state their success formula directly on the wiki, so unlike the birds
     * those pairs are read off rather than fit. The ferret and the embertailed jerboa state nothing
     * at all - see their rows.
     *
     * **The `bait` column is gone.** It was recorded but never read, and the two rows added here
     * have no bait to record: neither the `Ferret (Hunter)` nor the `Embertailed jerboa` page names
     * one, so keeping the column would have meant inventing a filler obj for something nothing
     * reads. That is the objection the deadfall and net trap tables already state as their reason
     * for having no bait column, applied to the one table that did.
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
                // "Carnivorous and Black Chinchompas have the same catch rate" - stated on both wiki
                // pages, and the two per-level charts are pointwise identical.
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

            /*
             * The two genuinely rate-blocked creatures in the whole hunter branch so far, and the
             * only two rows here whose `(low, high)` is a guess rather than a fit.
             *
             * A content search of the offline wiki snapshot for the `skillingSuccess` chart marker
             * returns nothing on `Ferret`, `Ferret (Hunter)` or `Embertailed jerboa`, and nothing on
             * the `Box trap` technique page either - so the §2a "check the technique page too" rule
             * has been applied and still finds no curve. Neither page states endpoints in prose.
             *
             * Both pairs are therefore **derived from the regular chinchompa's shape**, which is the
             * only published box-trap curve that is not one of the two identical high-level ones.
             * Its `(6, 268)` gives 146/256 at its own requirement of 53 and reaches certainty at
             * L94, i.e. 41 levels above the requirement. Each row below solves the engine formula
             * for the pair that puts 146/256 at *its* requirement and 256/256 forty-one levels
             * later, which reproduces the chinchompa's curve translated down the level axis rather
             * than inventing a new shape. Both are exact: the pair reproduces those two anchors to
             * the integer.
             *
             * Recorded for the record, because a later measurement should be checked against it:
             * void ships no ferret or jerboa pair at all, so unlike the butterflies there was no
             * prior guess to re-derive from.
             */
            row("dbrow.hunter_ferret") {
                columnRSCM(COL_NPC, "npc.hunting_ferret")
                column(COL_LEVEL, 27)
                column(COL_XP, 1152)
                // guessed: chinchompa curve translated to a level-27 requirement, see the block
                // comment above. Not measured, not published.
                column(COL_SUCCESS_LOW, 75)
                column(COL_SUCCESS_HIGH, 338)
                // `obj.hunting_ferret` (10092) is the item Ferret; `npc.hunting_ferret` (1505) is
                // the creature. The cache reuses the symbol across the two namespaces, exactly as
                // it does for `huntingbeast_claws`.
                columnRSCM(COL_CAUGHT_ITEMS, "obj.hunting_ferret")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                column(LocKeyed.COL_LOC_KEY, "ferret")
            }

            row("dbrow.hunter_embertailed_jerboa") {
                // Not `npc.varlamore_jerboa` (12982), which is ambient scenery named plain "Jerboa"
                // with no category; this one is `name=Embertailed jerboa` and carries the same
                // `category_374` as every other box-trap creature.
                columnRSCM(COL_NPC, "npc.varlamore_hunterjerboa01")
                column(COL_LEVEL, 39)
                column(COL_XP, 1370)
                // guessed: chinchompa curve translated to a level-39 requirement, see the block
                // comment above. Not measured, not published.
                column(COL_SUCCESS_LOW, 43)
                column(COL_SUCCESS_HIGH, 306)
                // Large jerboa tail is 1/50 *and* rumour-conditional, so omitted for the reason
                // Kebbity tuft is.
                columnRSCM(COL_CAUGHT_ITEMS, "obj.hunting_jerboa_tail")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
                column(LocKeyed.COL_LOC_KEY, "jerboa")
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
                // The chart template's own published parameters (oldid=15196228), read from the
                // Parsoid transclusion metadata rather than fitted. Only six levels are charted and
                // 25 different pairs reproduce all six, so a fit cannot pin this one - the previous
                // hand-derived (-226, 1048) was a member of that set but not the published pair.
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
                // Published parameters (oldid=15196422); same story as barb-tailed - five charted
                // levels, 19 pairs reproduce them, and the previous fitted (-422, 809) was one of
                // the 19 rather than the published pair.
                column(COL_SUCCESS_LOW, -434)
                column(COL_SUCCESS_HIGH, 820)
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

    /**
     * The three falconry kebbits.
     *
     * **Falconry is not a trap**, and this table is the first that is not one: there is no loc to
     * lay, transform or clear, and no `TrapFamily` entry backs it. The one column it adds instead of
     * loc states is [Falconry.COL_FALCON_NPC] - the "falcon holding prey" npc a successful catch
     * spawns on the kebbit's tile, which the player then clicks to retrieve.
     *
     * That column exists because **our cache is richer than the usual reference implementation's**.
     * OSRS ships three distinct falcon-with-prey npcs, one per kebbit
     * (`hunting_falcon_onspeedy` 1342, `hunting_falcon_onsilent` 1344, `hunting_falcon_onspeedy2`
     * 1343, all `name=Gyr Falcon`, all `op1=Retrieve`), where implementations working from a thinner
     * dump use a single generic falcon and stash the prey type in a side-channel attribute. Encoding
     * it in the npc means the retrieve path recovers the whole reward from the thing it clicked, and
     * there is no second source of truth to desync. Note the id order is *not* the level order:
     * 1343 is the dashing kebbit's and 1344 the dark kebbit's.
     *
     * Levels and xp are from each creature's own "Hunter info" box, which agrees with the `Falconry`
     * page's Creatures table (oldid=14840978).
     *
     * **All three `(low, high)` pairs are pinned to a single integer solution** - the strongest fit
     * any hunter table has had. Each creature's page carries a `{{Skilling success chart}}` whose
     * every y value is an exact 256th, and each *also* states its endpoints in prose, so there are
     * two independent cross-checks rather than one:
     * - Spotted (oldid=15225548), 38 charted points L43-80: `(26, 310)`. Prose: "The catch rate is
     *   10% at lvl 1 and 121% at lvl 99", sourced to Mod Ash. The fit gives 27/256 = 10.5% at L1 and
     *   311/256 = 121.5% at L99.
     * - Dark (oldid=15288973), 43 points L57-99: `(0, 253)`. Prose: "0% at lvl 1 and 99% at lvl 99".
     *   The fit gives 1/256 = 0.4% and 254/256 = 99.2%; 254 is also the charted L99 value exactly.
     * - Dashing (oldid=15225549), 31 points L69-99: `(0, 205)`. Prose: "0% at lvl 1 and 80% at lvl
     *   99". The fit gives 1/256 and 206/256 = 80.5%; 206 is the charted L99 value exactly.
     *
     * **Spotted kebbit's `high` exceeds 256 on purpose and must not be clamped.** 121% at 99 is what
     * the source says, and it is the same unclamped shape the wild kebbit already ships: past L80 the
     * charted curve stops because the roll can no longer fail. `SkillingSuccessRate` is unclamped and
     * reproduces that for free; clamping `high` to 256 would move the certainty point from L80 to L99.
     *
     * A note on the extraction, because the obvious route is wrong: the offline wiki sqlite's
     * `chunks` are truncated at ~1KB, so pulling these charts from sqlite silently yields 2 of the
     * spotted kebbit's 38 points and fits a curve to them without complaint. All 112 points here came
     * through the `osrs-cache` MCP `get_wiki_section`, which returns the section whole.
     *
     * Rewards are the infobox "Always" drops only, exactly as the trap tables do it. Kebbity tuft is
     * omitted from all three: it is 1/10 *and* conditional on an active Hunter's Rumour, which is not
     * implemented. The dashing kebbit is the only one with three reward lines - it always drops
     * `huntingbeast_speedy2_meat` (29107, "Raw dashing kebbit") on top of bones and its fur, where
     * the other two drop bones and fur alone.
     *
     * No `bait` column and no proximity term. Falconry takes no bait at all, and on proximity the
     * wiki is explicit that there is nothing to model: "Although the success rate is supposedly not
     * affected by proximity, running up to the target before catching it may improve success rate" -
     * which it then explains as a timing artefact of the falcon's travel speed, not a rate change.
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
                // Above 256, and correct. See the class doc.
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
                // 1344, not 1343 - the falcon npc ids do not ascend with creature level.
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

    /**
     * The four butterflies and the sunlight moth.
     *
     * Structurally the simplest table here, and the second that is not a trap: butterfly netting
     * lays nothing, transforms nothing and caps nothing, so like [magicBoxCreatures] this is the
     * shared 0-7 block and no more. The empty jar a catch consumes and the two nets are one item
     * each for the whole family, so they live as constants on the content side. No `TrapFamily`
     * entry backs it, and its rows are read into `ButterflyCreatures.all`, never
     * `HunterCreatures.all`.
     *
     * Levels and xp are from the `Butterfly (Hunter)` page's Butterflies table (oldid=15242004):
     * 15/25/35/45/65 and 24/34/44/54/74 xp. `caught_items` is the *filled* jar, which is the reward
     * only when the player is carrying an empty one - see `HunterButterfly` for the other branch.
     *
     * **The moonlight moth is deliberately absent.** Its npc (`npc.moth_moonlight`, 12771) and its
     * jar (`obj.butterfly_jar_moonmoth`, 28893) both exist and its chart is published, but it has
     * **zero spawns** in `.data/raw-cache/map/npcs/`, so a row for it would be unreachable content.
     * Same reason letvek and the stymphike are absent from [boxCreatures].
     *
     * ## The rates, which are the interesting part
     *
     * Only two of these five carry a published chart: black warlock (oldid=15288148) and sunlight
     * moth (oldid=15197088). Ruby harvest, sapphire glacialis and snowy knight carry none, on any
     * page, and the `Butterfly (Hunter)` technique page carries none either - the §2a "check the
     * technique page too" rule applied and still empty.
     *
     * The two that *are* published turn out to be **pointwise identical over the 21 levels they
     * share**, and both fit the same single integer pair, `(20, 296)`, with no other pair
     * reproducing every point. That is the load-bearing observation: the sunlight moth requires 65
     * and starts at 201/256, which is exactly what the black warlock's curve reads at 65 - not the
     * 145/256 the warlock starts at. So for these two the requirement only decides where you join a
     * shared curve; it does not anchor a curve of its own.
     *
     * **That is not the same as catch chance being a function of level alone, and an earlier
     * revision of this comment claimed exactly that.** The moonlight moth is a third published
     * member of the family (oldid=15208105) and does *not* sit on the shared curve: it fits
     * `(0, 276)` plain and `(20, 286)` magic, each a unique exact fit, and reads 209/256 at its own
     * requirement of 75 where the shared curve reads 229/256. It is not shipped - zero spawns - so
     * nothing here depends on it, but it is the counterexample that bounds the claim. Two agreeing
     * members license the guess below; they do not rule species-dependence out.
     * `HunterRateTablesTest` asserts the disagreement so the stronger claim cannot creep back.
     *
     * The three unpublished rows therefore ship `(20, 296)` as well. That is still a guess - none of
     * the three has been charted or measured - and it is an **extrapolation**, not an interpolation:
     * all three sit below the lower of the two agreeing requirements (15, 25 and 35 against the
     * warlock's 45), so no charted point brackets them. It is flagged as a guess on each row.
     *
     * void independently fits `chance = [20, 296]` for its own black warlock, which is the same pair
     * from an unrelated derivation and confirms the engine formula maps onto the wiki template. Its
     * three guesses differ - `[60, 415]`, `[45, 390]`, `[30, 320]`, each annotated "Guessed from
     * warlock curve against implings" - and are **not** adopted: they predate the sunlight moth,
     * which is Varlamore content, so that curve was not available to check them against. They are
     * the same three creatures this file guesses, which is corroboration that the guessing is
     * happening in the right place.
     *
     * ## What is not modelled: the magic net's faster curve
     *
     * Both published charts carry a *second* series for the magic butterfly net, and it fits
     * `(40, 316)` on both - exactly `(low + 20, high + 20)`. void applies the same `+20` from an
     * unrelated derivation. That relationship is therefore well-sourced and cheap, and it is applied
     * on the content side as a constant rather than a second column pair, so that the three guessed
     * rows do not each need a second guess. See `HunterButterfly.NET_BONUS`.
     *
     * The extracted chart points - both series, all four creatures, the moonlight moth included -
     * are checked in as a test resource at `content/skills/hunter/src/test/resources/wiki-charts/`
     * with the oldids in each file's header, and `HunterRateTablesTest` asserts every shipped pair
     * against them. Extraction went through the `osrs-cache` MCP
     * `get_wiki_section` - the sqlite `chunks` route truncates at ~1KB and silently returns a
     * near-empty curve.
     */
    fun butterflyCreatures(): DBTable =
        dbTable("dbtable.hunter_butterfly_creatures", serverOnly = true) {
            creatureColumns()

            row("dbrow.hunter_ruby_harvest") {
                columnRSCM(COL_NPC, "npc.butterfly_ruby")
                column(COL_LEVEL, 15)
                column(COL_XP, 240)
                // guessed: not charted anywhere. The pair the two published members of this family
                // share; see the class doc.
                column(COL_SUCCESS_LOW, 20)
                column(COL_SUCCESS_HIGH, 296)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.butterfly_jar_ruby")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }

            row("dbrow.hunter_sapphire_glacialis") {
                columnRSCM(COL_NPC, "npc.butterfly_glacialis")
                column(COL_LEVEL, 25)
                column(COL_XP, 340)
                // guessed: not charted anywhere. See the class doc.
                column(COL_SUCCESS_LOW, 20)
                column(COL_SUCCESS_HIGH, 296)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.butterfly_jar_glacialis")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }

            row("dbrow.hunter_snowy_knight") {
                columnRSCM(COL_NPC, "npc.butterfly_snowy")
                column(COL_LEVEL, 35)
                column(COL_XP, 440)
                // guessed: not charted anywhere. See the class doc.
                column(COL_SUCCESS_LOW, 20)
                column(COL_SUCCESS_HIGH, 296)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.butterfly_jar_snowy")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }

            // Published, 41 charted points over L45-85, fit pinned to this single pair.
            row("dbrow.hunter_black_warlock") {
                columnRSCM(COL_NPC, "npc.butterfly_warlock")
                column(COL_LEVEL, 45)
                column(COL_XP, 540)
                column(COL_SUCCESS_LOW, 20)
                column(COL_SUCCESS_HIGH, 296)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.butterfly_jar_warlock")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }

            // Published, 21 charted points over L65-85, same pair. The cache name really is
            // `name=Sunlight Moth` with a capital M, and so is the wiki page title - which is why a
            // case-sensitive search for "Sunlight moth" reports no chart and is wrong.
            row("dbrow.hunter_sunlight_moth") {
                columnRSCM(COL_NPC, "npc.moth_sunlight")
                column(COL_LEVEL, 65)
                column(COL_XP, 740)
                column(COL_SUCCESS_LOW, 20)
                column(COL_SUCCESS_HIGH, 296)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.butterfly_jar_sunmoth")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }
        }

    /**
     * The three crabs, and the only technique in the skill with **no catch rate at all**.
     *
     * Levels are twice sourced and agree. The cache's own `skill_features` rows carry them -
     * `skill_feature_hunter_red_crab` (dbrow 11798) reads `data=skill,23,21,9`,
     * `..._blue_crab` (11799) `data=skill,23,48,9` and `..._rainbow_crab` (11800)
     * `data=skill,23,77,9`, where the trailing 9 is the crab-trapping feature group - and the wiki's
     * *Crab trapping* Overview table (oldid=15264574) lists 21 / 48 / 77 for the same three. Its xp
     * column gives 64 / 136 / 216, stored x10 here like every other hunter table.
     *
     * Every one of those three cache rows also carries a second requirement, `data=skill,22,10,-1` -
     * Construction 10, with no feature group of its own - which is the same gate the wiki states in
     * *Setting up*. It is a build-time gate rather than a per-creature one, so it lives on the
     * content side as `HunterCrabTrap.CRAB_TRAP_CONSTRUCTION_LEVEL` rather than as a column repeated
     * identically on all three rows.
     *
     * ## Bait is mandatory, and the cache says which
     *
     * "Red and blue crab traps must be baited with fish offcuts, and rainbow crab traps must be
     * baited with fine fish offcuts, both of which are stackable." The cache agrees independently:
     * the pandemonium and great-conch site locs resolve their baited state to `crab_trap_active`
     * (58906, `model2=model_59784`) and the crown-jewel sites to `crab_trap_active_fine_offcuts`
     * (58907, `model2=model_59785`), so the bait type is baked into the site and visible in the
     * world. `obj.brut_fish_cuts` (11334) really is `name=Fish offcuts` - the plausible-looking
     * `sailing_fish_offcuts` does not exist - and `obj.sailing_fine_fish_offcuts` (32307) is
     * `name=Fine fish offcuts`.
     *
     * ## The delay a baited trap fills in
     *
     * "After a set period of time, crabs will move towards the baited traps: Red and blue crabs: 15
     * ticks (9s); Rainbow crabs: 25 ticks (15s)." Sourced exactly, in cycles, which is why this is a
     * column and not one of the module's flagged guesses.
     *
     * ## The rainbow crab's three colourways
     *
     * There are three rainbow npcs, three `crab_trap_full_rainbow_*` locs and three `rainbow_crab_*`
     * objs, and they are **one creature seen three ways**, not three creatures. The three objs share
     * a name ("Rainbow crab"), a description, a cost (300), a weight, and both processing params
     * (`param_2421,raw_rainbow_crab_meat`, `param_2422,rainbow_crab_paste`); they differ only in
     * their 13-pair recolour table. Each obj's recolour table is **byte-identical** to the loc and
     * npc of the same letter - `rainbow_crab_b` (31680), `crab_trap_full_rainbow_b` (58911) and
     * `rainbow_crab_b` (15092) all read `recol1s=8322 recol1d=2246 … recol13s=22720 recol13d=4578` -
     * which is what pins the a/b/c triples to each other rather than to some other pairing. So the
     * crab is one row whose reward and full-trap loc are parallel three-entry lists, and the
     * colourway is picked once per catch.
     *
     * ## What is not modelled
     *
     * **The automatic re-bait.** "The player will automatically re-bait a trap after 3 ticks (1.8s),
     * but this can be reduced to just one tick by immediately clicking the trap again." Emptying a
     * full trap here returns it to the empty state and the player baits it again by hand. It is an
     * ergonomic accelerator over the two ops this slice does implement, and its second half - the
     * click-to-shorten - has no state to hang off yet; shipping only the first half would be a
     * partial mechanic. The numbers are recorded here so it is a scoped-out gap and not an unknown.
     *
     * **The crab walking to the trap.** The wiki describes the delay as the crab moving towards the
     * bait. Nothing walks here; the trap simply fills. See [Crab] for why the npc is not a column.
     */
    fun crabCreatures(): DBTable =
        dbTable("dbtable.hunter_crab_creatures", serverOnly = true) {
            column("level", Crab.COL_LEVEL, VarType.INT)
            // Stored x10, like every other hunter table.
            column("xp", Crab.COL_XP, VarType.INT)
            column("bait", Crab.COL_BAIT, VarType.OBJ)
            column("caught_items", Crab.COL_CAUGHT_ITEMS, VarType.OBJ)
            column("full_loc", Crab.COL_FULL_LOC, VarType.LOC)
            column("catch_delay", Crab.COL_CATCH_DELAY, VarType.INT)

            row("dbrow.hunter_red_crab") {
                column(Crab.COL_LEVEL, 21)
                column(Crab.COL_XP, 640)
                columnRSCM(Crab.COL_BAIT, "obj.brut_fish_cuts")
                columnRSCM(Crab.COL_CAUGHT_ITEMS, "obj.red_crab")
                columnRSCM(Crab.COL_FULL_LOC, "loc.crab_trap_full_red")
                column(Crab.COL_CATCH_DELAY, 15)
            }

            row("dbrow.hunter_blue_crab") {
                column(Crab.COL_LEVEL, 48)
                column(Crab.COL_XP, 1360)
                columnRSCM(Crab.COL_BAIT, "obj.brut_fish_cuts")
                columnRSCM(Crab.COL_CAUGHT_ITEMS, "obj.blue_crab")
                columnRSCM(Crab.COL_FULL_LOC, "loc.crab_trap_full_blue")
                column(Crab.COL_CATCH_DELAY, 15)
            }

            // The row that makes both reward columns lists. Order is load-bearing: entry `i` of
            // `caught_items` and of `full_loc` are the same colourway, and the pairing is the
            // recolour-table identity described above, not the alphabet.
            row("dbrow.hunter_rainbow_crab") {
                column(Crab.COL_LEVEL, 77)
                column(Crab.COL_XP, 2160)
                columnRSCM(Crab.COL_BAIT, "obj.sailing_fine_fish_offcuts")
                columnRSCM(
                    Crab.COL_CAUGHT_ITEMS,
                    "obj.rainbow_crab_a",
                    "obj.rainbow_crab_b",
                    "obj.rainbow_crab_c",
                )
                columnRSCM(
                    Crab.COL_FULL_LOC,
                    "loc.crab_trap_full_rainbow_a",
                    "loc.crab_trap_full_rainbow_b",
                    "loc.crab_trap_full_rainbow_c",
                )
                column(Crab.COL_CATCH_DELAY, 25)
            }
        }

    /**
     * The six Puro-Puro implings, and only those six.
     *
     * **Every pair here is the chart template's own published parameter**, read from the Parsoid
     * transclusion metadata rather than fitted - so unlike any technique shipped before it, this
     * table contains no guess and no annotated approximation. `HunterRateTablesTest` asserts all six
     * against `published-params.tsv`. The magic-net series is `low + 20` / `high + 20` on all twelve
     * impling pages, which is `HunterButterfly.NET_BONUS` exactly; it is applied on the content side
     * as it is for butterflies, not stored twice.
     *
     * **Why the `_maze` npcs and not the overworld ones.** Each impling has two npc ids, an
     * overworld one and a Puro-Puro `_maze` one. Only the `_maze` ids of types 1-6 have spawns in
     * `.data` - 51 of them, all in map square 40,67 - and every other impling npc, overworld and
     * maze alike, has **zero**. The overworld spawn data is instead five invisible, op-less
     * "precursor" markers that the live game replaces with a rolled impling type, so the overworld
     * six and the high-tier six are blocked on a spawner that does not exist here yet, not on
     * missing content. That is a different blocker from letvek's and Stymphike's, whose npcs have no
     * spawns *and* nothing that would ever produce one.
     *
     * Levels are twice-sourced and agree: the client's own skill guide (`config/dbrow`
     * `skill_feature_hunter_impling_*`, `data=skill,23,<level>,4`) and each creature's wiki infobox.
     *
     * The catch is the filled jar, so the shared `caught_items`/`min`/`max` block carries it exactly
     * as butterfly netting's does - one non-stackable jar swapped for the empty one. No table-
     * specific loc columns, because an impling is caught where it flies and leaves nothing behind.
     */
    fun implingCreatures(): DBTable =
        dbTable("dbtable.hunter_impling_creatures", serverOnly = true) {
            creatureColumns()
            column("xp_puro", Impling.COL_XP_PURO, VarType.INT)

            row("dbrow.hunter_baby_impling") {
                columnRSCM(COL_NPC, "npc.ii_impling_type_1_maze")
                column(COL_LEVEL, 17)
                column(COL_XP, 200)
                column(Impling.COL_XP_PURO, 180)
                column(COL_SUCCESS_LOW, 79)
                column(COL_SUCCESS_HIGH, 402)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.ii_captured_impling_1")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }

            row("dbrow.hunter_young_impling") {
                columnRSCM(COL_NPC, "npc.ii_impling_type_2_maze")
                column(COL_LEVEL, 22)
                column(COL_XP, 220)
                column(Impling.COL_XP_PURO, 200)
                column(COL_SUCCESS_LOW, 69)
                column(COL_SUCCESS_HIGH, 351)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.ii_captured_impling_2")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }

            row("dbrow.hunter_gourmet_impling") {
                columnRSCM(COL_NPC, "npc.ii_impling_type_3_maze")
                column(COL_LEVEL, 28)
                column(COL_XP, 240)
                column(Impling.COL_XP_PURO, 220)
                column(COL_SUCCESS_LOW, 61)
                column(COL_SUCCESS_HIGH, 325)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.ii_captured_impling_3")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }

            row("dbrow.hunter_earth_impling") {
                columnRSCM(COL_NPC, "npc.ii_impling_type_4_maze")
                column(COL_LEVEL, 36)
                column(COL_XP, 270)
                column(Impling.COL_XP_PURO, 250)
                column(COL_SUCCESS_LOW, 51)
                column(COL_SUCCESS_HIGH, 302)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.ii_captured_impling_4")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }

            row("dbrow.hunter_essence_impling") {
                columnRSCM(COL_NPC, "npc.ii_impling_type_5_maze")
                column(COL_LEVEL, 42)
                column(COL_XP, 290)
                column(Impling.COL_XP_PURO, 270)
                column(COL_SUCCESS_LOW, 40)
                column(COL_SUCCESS_HIGH, 275)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.ii_captured_impling_5")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }

            row("dbrow.hunter_eclectic_impling") {
                columnRSCM(COL_NPC, "npc.ii_impling_type_6_maze")
                column(COL_LEVEL, 50)
                column(COL_XP, 320)
                column(Impling.COL_XP_PURO, 300)
                column(COL_SUCCESS_LOW, 30)
                column(COL_SUCCESS_HIGH, 250)
                columnRSCM(COL_CAUGHT_ITEMS, "obj.ii_captured_impling_6")
                column(COL_CAUGHT_MIN, 1)
                column(COL_CAUGHT_MAX, 1)
            }
        }
}
