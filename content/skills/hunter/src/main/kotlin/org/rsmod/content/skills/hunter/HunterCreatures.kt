package org.rsmod.content.skills.hunter

/**
 * The bird snare and box trap creature table.
 *
 * Every `npc` and `caught` value here is a cache symbol confirmed to exist via `config/npc` /
 * `config/obj` lookups - never a wiki name transcribed directly, since the two frequently differ
 * (e.g. the wiki's "Crimson swift" is `npc.hunting_bird_jungle` in the cache).
 *
 * Quarantined, not guessed - four candidates from the design spec are deliberately left out:
 * - Letvek (`npc.hunting_letvek`, level 76 box trap) - the npc exists in the cache but has zero
 *   spawns in `.data/raw-cache/map/npcs/`.
 * - Tropical wagtail (level 19 bird snare, "coloured" trap state) - the cache defines a 5th bird
 *   snare biome state (`hunting_ojibway_trap_full_coloured`, model2=model_26839) and a matching
 *   `skill_feature_hunter_coloured_bird` entry, but there is no 5th bird npc: the cache holds
 *   exactly four (`hunting_bird_{jungle,polar,desert,woodland}`, ids 5549-5552), and none of their
 *   `model1` values is `model_26839`.
 * - Embertailed jerboa (level 39 box trap) - the npc is resolved (`npc.varlamore_hunterjerboa01`,
 *   which carries `name=Embertailed jerboa` and the same `category_374` as every other box-trap
 *   creature; the other jerboa spawn, `npc.varlamore_jerboa`, is undecorated decorative fauna with
 *   no category and the generic name "Jerboa"). What is missing is the `(low, high)` source: the
 *   Embertailed jerboa's wiki page has no Hunting technique / Hunting chance section at all, only
 *   Location and a level/xp/drop table.
 * - Ferret (level 27 box trap) - npc and obj both resolve and are spawned, but its wiki page has no
 *   catch-chance formula or chart either. The only documented Ferret percentage is a different
 *   mechanic (the chance of *keeping* a ferret after it flushes a white rabbit, used in the
 *   out-of-scope net-trap rabbit chain), not the box-trap catch chance.
 */
object HunterCreatures {
    val all: List<HunterCreature> =
        listOf(
            // Bird snare. P(L) = (floor(m * (L - 1) / 98) + c) / 255 is not stated on any bird's
            // wiki page - each pair below was fit against that creature's full per-level success
            // chart (all ~48-58 points, level by level) embedded in its "Hunter info" section, and
            // verified to reproduce every non-capped point exactly.
            HunterCreature(
                family = TrapFamily.SNARE,
                npc = "npc.hunting_bird_jungle",
                level = 1,
                xp = 340,
                caught =
                    listOf("obj.bones", "obj.spit_raw_bird_meat", "obj.hunting_jungle_feather"),
                successLow = 100,
                successHigh = 420,
            ),
            HunterCreature(
                family = TrapFamily.SNARE,
                npc = "npc.hunting_bird_desert",
                level = 5,
                xp = 470,
                caught =
                    listOf("obj.bones", "obj.spit_raw_bird_meat", "obj.hunting_desert_feather"),
                successLow = 92,
                successHigh = 400,
            ),
            HunterCreature(
                family = TrapFamily.SNARE,
                npc = "npc.hunting_bird_woodland",
                level = 9,
                xp = 612,
                caught =
                    listOf("obj.bones", "obj.spit_raw_bird_meat", "obj.hunting_woodland_feather"),
                successLow = 85,
                successHigh = 390,
            ),
            HunterCreature(
                family = TrapFamily.SNARE,
                npc = "npc.hunting_bird_polar",
                level = 11,
                // The creature's own infobox states 64.5 xp; the parent "Bird snare" summary
                // table states 64.6 xp. Used the creature-page value as primary.
                xp = 645,
                caught = listOf("obj.bones", "obj.spit_raw_bird_meat", "obj.hunting_polar_feather"),
                successLow = 82,
                successHigh = 380,
            ),

            // Box trap. All three below state their formula directly on the wiki.
            HunterCreature(
                family = TrapFamily.BOX,
                npc = "npc.hunting_chinchompa",
                level = 53,
                xp = 1984,
                caught = listOf("obj.chinchompa_captured"),
                successLow = 6,
                successHigh = 268,
                bait = "obj.bowl_spicytomato",
            ),
            HunterCreature(
                family = TrapFamily.BOX,
                npc = "npc.hunting_chinchompa_big",
                level = 63,
                xp = 2650,
                caught = listOf("obj.chinchompa_big_captured"),
                // "Carnivorous and Black Chinchompas have the same catch rate" - stated on both
                // wiki pages, and the two per-level charts are pointwise identical.
                successLow = -78,
                successHigh = 228,
                bait = "obj.bowl_spicymeat",
            ),
            HunterCreature(
                family = TrapFamily.BOX,
                npc = "npc.hunting_chinchompa_black",
                level = 73,
                xp = 3150,
                caught = listOf("obj.chinchompa_black"),
                successLow = -78,
                successHigh = 228,
                bait = "obj.bowl_spicymeat",
            ),
        )

    private val byNpc: Map<String, HunterCreature> = all.associateBy { it.npc }

    fun byNpc(npc: String): HunterCreature? = byNpc[npc]
}
