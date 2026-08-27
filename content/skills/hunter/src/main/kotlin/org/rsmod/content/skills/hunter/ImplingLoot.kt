package org.rsmod.content.skills.hunter

import dtx.rs.RSDropTable
import org.rsmod.api.droptable.DropRollItem
import org.rsmod.api.droptable.nothing
import org.rsmod.api.droptable.rsPlayerTertiaryTable
import org.rsmod.api.droptable.rsPlayerWeightedTable
import org.rsmod.content.drops.clueScrollTransformObj
import org.rsmod.game.entity.Player

/**
 * What each filled impling jar pays out. Every rate is published ("provided by Jagex"), and
 * `ImplingLootTest` asserts every row against the checked-in extract - the only reason a
 * transcription this size is trustworthy. The baby table's `nothing()` is a real 1/10 entry, the
 * Grubby key is a pre-roll (folding it in would break the sum), and clue scrolls are tertiaries.
 * See docs/hunter.md.
 */
private val babyImplingLoot: RSDropTable<Player, DropRollItem> =
    RSDropTable(
        tableIdentifier = "Baby impling jar",
        mainTable =
            rsPlayerWeightedTable(total = 100) {
                name("Baby impling jar")
                10 weight "obj.anchovies" count 1
                10 weight "obj.ball_of_wool" count 1
                10 weight "obj.cheese" count 1
                10 weight "obj.chisel" count 1
                10 weight "obj.hammer" count 1
                10 weight "obj.knife" count 1
                10 weight "obj.needle" count 1
                10 weight nothing()
                10 weight "obj.thread" count 1
                1 weight "obj.air_talisman" count 1
                1 weight "obj.flax" count 1
                1 weight "obj.hard_leather" count 1
                1 weight "obj.lobster" count 1
                1 weight "obj.mud_pie" count 1
                1 weight "obj.sapphire" count 1
                1 weight "obj.seaweed" count 1
                1 weight "obj.silver_bar" count 1
                1 weight "obj.softclay" count 1
                1 weight "obj.spicespot" count 1
            },
        tertiaries =
            rsPlayerTertiaryTable {
                1 outOf 50 weight "obj.trail_clue_beginner" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_clue_beginner")
                }
                1 outOf 100 weight "obj.trail_clue_easy_simple001" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_clue_easy_simple001")
                }
            },
    )

private val youngImplingLoot: RSDropTable<Player, DropRollItem> =
    RSDropTable(
        tableIdentifier = "Young impling jar",
        mainTable =
            rsPlayerWeightedTable(total = 100) {
                name("Young impling jar")
                10 weight "obj.bow_string" count 1
                10 weight "obj.chocolate_slice" count 1
                10 weight "obj.coal" count 1
                10 weight "obj.lockpick" count 1
                10 weight "obj.meat_pizza" count 1
                10 weight "obj.blankrune_high" count 1
                10 weight "obj.steel_axe" count 1
                10 weight "obj.nails" count 5
                10 weight "obj.tuna" count 1
                1 weight "obj.3dose1defense" count 1
                1 weight "obj.garden_pie" count 1
                1 weight "obj.jangerberries" count 1
                1 weight "obj.mithril_bar" count 1
                1 weight "obj.plank_oak" count 1
                1 weight "obj.snape_grass" count 1
                1 weight "obj.softclay" count 1
                1 weight "obj.steel_full_helm" count 1
                1 weight "obj.studded_chaps" count 1
                1 weight "obj.yew_longbow" count 1
            },
        tertiaries =
            rsPlayerTertiaryTable {
                1 outOf 25 weight "obj.trail_clue_beginner" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_clue_beginner")
                }
                1 outOf 50 weight "obj.trail_clue_easy_simple001" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_clue_easy_simple001")
                }
            },
    )

private val gourmetImplingLoot: RSDropTable<Player, DropRollItem> =
    RSDropTable(
        tableIdentifier = "Gourmet impling jar",
        preRoll =
            rsPlayerTertiaryTable {
                1 outOf 500 weight "obj.hosdun_grubby_key" count 1
            },
        mainTable =
            rsPlayerWeightedTable(total = 100) {
                name("Gourmet impling jar")
                20 weight "obj.tuna" count 1
                10 weight "obj.bass" count 1
                10 weight "obj.chocolate_cake" count 1
                10 weight "obj.curry" count 1
                10 weight "obj.curry_leaf" count 1
                10 weight "obj.giant_frogspawn" count 1
                10 weight "obj.meat_pie" count 1
                10 weight "obj.spicespot" count 1
                1 weight "obj.chefs_delight" count 1
                1 weight "obj.cert_tbwt_cooked_karambwan" count 2
                1 weight "obj.fish_pie" count 1
                1 weight "obj.cert_garden_pie" count 6
                1 weight "obj.cert_lobster" count 4
                1 weight "obj.cert_hunting_fish_special" count 5
                1 weight "obj.cert_shark" count 3
                1 weight "obj.basket_strawberry_5" count 1
                1 weight "obj.cert_swordfish" count 3
                1 weight "obj.ugthanki_kebab" count 1
            },
        tertiaries =
            rsPlayerTertiaryTable {
                1 outOf 25 weight "obj.trail_clue_easy_simple001" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_clue_easy_simple001")
                }
            },
    )

private val earthImplingLoot: RSDropTable<Player, DropRollItem> =
    RSDropTable(
        tableIdentifier = "Earth impling jar",
        mainTable =
            rsPlayerWeightedTable(total = 100) {
                name("Earth impling jar")
                10 weight "obj.cert_bucket_sand" count 4
                10 weight "obj.cert_bucket_compost" count 6
                10 weight "obj.earthrune" count 32
                10 weight "obj.earth_talisman" count 1
                10 weight "obj.tiara_earth" count 1
                10 weight "obj.fire_talisman" count 1
                10 weight "obj.gold_ore" count 1
                10 weight "obj.cert_mithril_ore" count 1
                10 weight "obj.unicorn_horn" count 1
                1 weight "obj.cert_coal" count 6
                1 weight "obj.cert_emerald" count 2
                1 weight "obj.harralander_seed" count 2
                1 weight "obj.jangerberry_bush_seed" count 2
                1 weight "obj.cert_mithril_ore" count 3
                1 weight "obj.mithril_pickaxe" count 1
                1 weight "obj.ruby" count 1
                1 weight "obj.steel_bar" count 1
                1 weight "obj.cert_bucket_supercompost" count 2
                1 weight "obj.wildblood_hop_seed" count 2
            },
        tertiaries =
            rsPlayerTertiaryTable {
                1 outOf 100 weight "obj.trail_clue_medium_sextant001" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_clue_medium_sextant001")
                }
            },
    )

private val essenceImplingLoot: RSDropTable<Player, DropRollItem> =
    RSDropTable(
        tableIdentifier = "Essence impling jar",
        mainTable =
            rsPlayerWeightedTable(total = 100) {
                name("Essence impling jar")
                10 weight "obj.airrune" count 30
                10 weight "obj.bodyrune" count 28
                10 weight "obj.chaosrune" count 4
                10 weight "obj.cosmicrune" count 4
                10 weight "obj.firerune" count 50
                10 weight "obj.mindrune" count 25
                10 weight "obj.mind_talisman" count 1
                10 weight "obj.cert_blankrune_high" count 20
                10 weight "obj.waterrune" count 30
                1 weight "obj.bloodrune" count 7
                1 weight "obj.deathrune" count 13
                1 weight "obj.lavarune" count 4
                1 weight "obj.lawrune" count 13
                1 weight "obj.mudrune" count 4
                1 weight "obj.naturerune" count 13
                1 weight "obj.cert_blankrune_high" count 35
                1 weight "obj.smokerune" count 4
                1 weight "obj.soulrune" count 11
                1 weight "obj.steamrune" count 4
            },
        tertiaries =
            rsPlayerTertiaryTable {
                1 outOf 50 weight "obj.trail_clue_medium_sextant001" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_clue_medium_sextant001")
                }
            },
    )

private val eclecticImplingLoot: RSDropTable<Player, DropRollItem> =
    RSDropTable(
        tableIdentifier = "Eclectic impling jar",
        mainTable =
            rsPlayerWeightedTable(total = 100) {
                name("Eclectic impling jar")
                10 weight "obj.airrune" count 30..58
                10 weight "obj.curry_leaf" count 1
                10 weight "obj.candle_lantern_empty" count 1
                10 weight "obj.cert_gold_bar" count 5
                10 weight "obj.gold_ore" count 1
                10 weight "obj.mithril_pickaxe" count 1
                10 weight "obj.cert_plank_oak" count 4
                10 weight "obj.snape_grass" count 1
                10 weight "obj.unicorn_horn" count 1
                1 weight "obj.adamant_kiteshield" count 1
                1 weight "obj.cert_adamantite_ore" count 10
                1 weight "obj.battlestaff" count 1
                1 weight "obj.blue_dragonhide_chaps" count 1
                1 weight "obj.diamond" count 1
                1 weight "obj.hunting_red_spiked_vambraces" count 1
                1 weight "obj.rune_dagger" count 1
                1 weight "obj.cert_slayers_respite" count 2
                1 weight "obj.watermelon_seed" count 3
                1 weight "obj.wild_pie" count 1
            },
        tertiaries =
            rsPlayerTertiaryTable {
                1 outOf 25 weight "obj.trail_clue_medium_sextant001" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_clue_medium_sextant001")
                }
            },
    )

private val natureImplingLoot: RSDropTable<Player, DropRollItem> =
    RSDropTable(
        tableIdentifier = "Nature impling jar",
        mainTable =
            rsPlayerWeightedTable(total = 100) {
                name("Nature impling jar")
                10 weight "obj.belladonna_seed" count 1
                10 weight "obj.cactus_spine" count 1
                10 weight "obj.cert_tarromin" count 4
                10 weight "obj.coconut" count 1
                10 weight "obj.harralander_seed" count 1
                10 weight "obj.irit_seed" count 1
                10 weight "obj.jangerberry_bush_seed" count 1
                10 weight "obj.limpwurt_seed" count 1
                10 weight "obj.magic_logs" count 1
                1 weight "obj.avantoe_seed" count 5
                1 weight "obj.cert_torstol" count 2
                1 weight "obj.curry_tree_seed" count 1
                1 weight "obj.dwarf_weed_seed" count 1
                1 weight "obj.kwuarm_seed" count 1
                1 weight "obj.orange_tree_seed" count 1
                1 weight "obj.ranarr_seed" count 1
                1 weight "obj.snapdragon" count 1
                1 weight "obj.torstol_seed" count 1
                1 weight "obj.willow_seed" count 1
            },
        tertiaries =
            rsPlayerTertiaryTable {
                1 outOf 100 weight "obj.trail_clue_hard_map001" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_clue_hard_map001")
                }
            },
    )

private val magpieImplingLoot: RSDropTable<Player, DropRollItem> =
    RSDropTable(
        tableIdentifier = "Magpie impling jar",
        mainTable =
            rsPlayerWeightedTable(total = 21) {
                name("Magpie impling jar")
                2 weight "obj.cert_dragonhide_black" count 6
                1 weight "obj.cert_amulet_of_power" count 3
                1 weight "obj.cert_diamond" count 4
                1 weight "obj.cert_ring_of_forging" count 3
                1 weight "obj.cert_ring_of_life" count 4
                1 weight "obj.cert_ring_of_recoil" count 3
                1 weight "obj.cert_runite_bar" count 2
                1 weight "obj.cert_strung_diamond_amulet" count 3
                1 weight "obj.dragon_dagger" count 1
                1 weight "obj.keyhalf1" count 1
                1 weight "obj.keyhalf2" count 1
                1 weight "obj.mystic_boots" count 1
                1 weight "obj.mystic_gloves" count 1
                1 weight "obj.pineapple_tree_seed" count 1
                1 weight "obj.rune_sq_shield" count 1
                1 weight "obj.rune_warhammer" count 1
                1 weight "obj.sinister_key" count 1
                1 weight "obj.snapdragon_seed" count 1
                1 weight "obj.splitbark_gauntlets" count 1
                1 weight "obj.tiara_nature" count 1
            },
        tertiaries =
            rsPlayerTertiaryTable {
                1 outOf 50 weight "obj.trail_clue_hard_map001" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_clue_hard_map001")
                }
            },
    )

private val ninjaImplingLoot: RSDropTable<Player, DropRollItem> =
    RSDropTable(
        tableIdentifier = "Ninja impling jar",
        mainTable =
            rsPlayerWeightedTable(total = 19) {
                name("Ninja impling jar")
                1 weight "obj.cert_3doseprayerrestore" count 4
                1 weight "obj.cert_dagganoth_hide" count 3
                1 weight "obj.cert_dragonhide_black" count 10
                1 weight "obj.cert_weapon_poison+" count 4
                1 weight "obj.dragon_dagger_p+" count 1
                1 weight "obj.machette_opal" count 1
                1 weight "obj.mystic_boots" count 1
                1 weight "obj.mystic_gloves" count 1
                1 weight "obj.rune_arrow" count 70
                1 weight "obj.rune_chainbody" count 1
                1 weight "obj.rune_claws" count 1
                1 weight "obj.rune_dart" count 70
                1 weight "obj.rune_knife" count 40
                1 weight "obj.rune_scimitar" count 1
                1 weight "obj.rune_thrownaxe" count 50
                1 weight "obj.snakeskin_boots" count 1
                1 weight "obj.splitbark_helm" count 1
                1 weight "obj.xbows_bolt_tips_onyx" count 4
                1 weight "obj.xbows_crossbow_bolts_runite_tipped_onyx" count 2
            },
        tertiaries =
            rsPlayerTertiaryTable {
                1 outOf 25 weight "obj.trail_clue_hard_map001" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_clue_hard_map001")
                }
            },
    )

private val crystalImplingLoot: RSDropTable<Player, DropRollItem> =
    RSDropTable(
        tableIdentifier = "Crystal impling jar",
        mainTable =
            rsPlayerWeightedTable(total = 18) {
                name("Crystal impling jar")
                1 weight "obj.cert_amulet_of_power" count 5..7
                1 weight "obj.cert_babydragon_bones" count 75..125
                1 weight "obj.cert_dragon_dagger" count 2
                1 weight "obj.cert_dragonstone" count 2
                1 weight "obj.cert_rune_scimitar" count 3..6
                1 weight "obj.crystal_tree_seed" count 1
                1 weight "obj.dragon_dart_tip" count 10..15
                1 weight "obj.prif_crystal_shard" count 30..40
                1 weight "obj.ranarr_seed" count 3..8
                1 weight "obj.rune_arrow" count 400..750
                1 weight "obj.rune_arrowheads" count 150..250
                1 weight "obj.rune_dart" count 50..100
                1 weight "obj.rune_dart_tip" count 25..75
                1 weight "obj.rune_javelin_head" count 20..60
                1 weight "obj.strung_dragonstone_amulet" count 1
                1 weight "obj.xbows_bolt_tips_onyx" count 6..10
                1 weight "obj.xbows_bolt_tips_ruby" count 50..125
                1 weight "obj.yew_seed" count 1
            },
        tertiaries =
            rsPlayerTertiaryTable {
                1 outOf 50 weight "obj.trail_elite_emote_exp1" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_elite_emote_exp1")
                }
                1 outOf 128 weight "obj.elven_signet" count 1
            },
    )

private val dragonImplingLoot: RSDropTable<Player, DropRollItem> =
    RSDropTable(
        tableIdentifier = "Dragon impling jar",
        mainTable =
            rsPlayerWeightedTable(total = 19) {
                name("Dragon impling jar")
                1 weight "obj.cert_amulet_of_glory" count 3
                1 weight "obj.cert_babydragon_bones" count 100..300
                1 weight "obj.cert_dragon_bones" count 50..100
                1 weight "obj.cert_dragon_dagger_p++" count 3
                1 weight "obj.cert_dragonstone" count 3
                1 weight "obj.cert_strung_dragonstone_amulet" count 2
                1 weight "obj.cert_summer_pie" count 15
                1 weight "obj.dragon_arrow" count 100..250
                1 weight "obj.dragon_arrowheads" count 100..350
                1 weight "obj.dragon_dart" count 100..250
                1 weight "obj.dragon_dart_tip" count 100..350
                1 weight "obj.dragon_javelin_head" count 25..35
                1 weight "obj.dragon_longsword" count 1
                1 weight "obj.magic_tree_seed" count 1
                1 weight "obj.mystic_robe_bottom" count 1
                1 weight "obj.snapdragon_seed" count 6
                1 weight "obj.xbows_bolt_tips_dragonstone" count 10..30
                1 weight "obj.xbows_bolt_tips_dragonstone" count 36
                1 weight "obj.xbows_crossbow_bolts_runite_tipped_dragonstone" count 10..40
            },
        tertiaries =
            rsPlayerTertiaryTable {
                1 outOf 50 weight "obj.trail_elite_emote_exp1" count 1 transformObj { player ->
                    player.clueScrollTransformObj("obj.trail_elite_emote_exp1")
                }
            },
    )

// Keyed by the obj the player actually clicks.
private val lootByJar: Map<String, RSDropTable<Player, DropRollItem>> =
    mapOf(
        "obj.ii_captured_impling_1" to babyImplingLoot,
        "obj.ii_captured_impling_2" to youngImplingLoot,
        "obj.ii_captured_impling_3" to gourmetImplingLoot,
        "obj.ii_captured_impling_4" to earthImplingLoot,
        "obj.ii_captured_impling_5" to essenceImplingLoot,
        "obj.ii_captured_impling_6" to eclecticImplingLoot,
        "obj.ii_captured_impling_7" to natureImplingLoot,
        "obj.ii_captured_impling_8" to magpieImplingLoot,
        "obj.ii_captured_impling_9" to ninjaImplingLoot,
        "obj.ii_captured_impling_10" to dragonImplingLoot,
        "obj.ii_captured_impling_12" to crystalImplingLoot,
    )

/**
 * The lucky impling's jar has no table and cannot be opened: its published loot is a clue-tier
 * *reward* roll, and this server has no casket rewards to roll (docs/hunter.md).
 * `ImplingLootTest` names it as the single permitted exception.
 */
const val LUCKY_IMPLING_JAR: String = "obj.ii_captured_impling_11"

/**
 * Held here rather than in `DropTableRegistry`, which keys on npcs and locs only; the key here is
 * an obj. These tables would slot into such a registry unchanged if it grew an obj key.
 */
object ImplingLoot {
    val jars: Set<String> = lootByJar.keys

    fun forJar(obj: String): RSDropTable<Player, DropRollItem>? = lootByJar[obj]
}
