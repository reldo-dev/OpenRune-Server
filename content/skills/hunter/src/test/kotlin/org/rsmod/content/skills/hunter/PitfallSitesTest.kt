package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.cache.MAPS
import dev.openrune.filesystem.Cache
import dev.openrune.map.loc.MapLocDefinition
import dev.openrune.map.loc.MapLocListDecoder
import dev.openrune.map.tile.MapTileDecoder
import dev.openrune.map.tile.MapTileSimpleDefinition
import dev.openrune.map.util.InlineByteBuf
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.map.square.MapSquareKey

/**
 * The twenty-five pitfall sites, checked against the packed cache rather than against themselves.
 *
 * Every column in [PitfallSites] is hand-transcribed from a survey document, and a transposed digit
 * in any of them fails silently: the loc still resolves, the varbit still reads, and the site
 * simply is not where the player is standing. So almost nothing here compares the table to a
 * literal. The cache is asked instead, four ways:
 * - the **base loc's own `multiVarBit`** must be the varbit the row names, which is what pairs a
 *   site to its state field without trusting that two columns of the same table row agree;
 * - the **`_animal` loc's `multiVarBit`** must be that same varbit, which is the only check that
 *   catches a companion transcribed off the wrong site - and the companion ids are deliberately not
 *   in site order, so nothing may pair them by sorting;
 * - the **packed `VarBitType`** must carry the measured bits, including site 19's start at 25
 *   rather than the 24 that any `3 * field` arithmetic would produce;
 * - the **packed map** must hold that exact loc id on that exact tile, and that tile must carry
 *   `LINK_BELOW` at plane 1, which is the whole reason every coordinate here is authored at level 0
 *   while the cache authors the placement at level 1.
 *
 * Serialised like the rest of the cache-touching suite: `ServerCacheManager` is a singleton and
 * `RSCM` memoises into a plain `HashMap`.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class PitfallSitesTest {
    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
    }

    @Test
    fun `twenty-five sites ship, indexed one through twenty-five in order`() {
        assertEquals(25, PitfallSites.all.size, "every pit ships, not only the nine antelope ones")
        assertEquals((1..25).toList(), PitfallSites.all.map { it.index })
    }

    /** Six kyatt, then five larupia, five graahk, five sunlight and four moonlight: 6/5/5/5/4. */
    @Test
    fun `the creature split is six, five, five, five, four in site order`() {
        val expected =
            mapOf(
                PitfallCreatures.kyatt to (1..6).toList(),
                PitfallCreatures.larupia to (7..11).toList(),
                PitfallCreatures.graahk to (12..16).toList(),
                PitfallCreatures.sunlight to (17..21).toList(),
                PitfallCreatures.moonlight to (22..25).toList(),
            )
        val actual = PitfallSites.all.groupBy({ it.creature }, { it.index })
        assertEquals(expected, actual)
        assertEquals(listOf(6, 5, 5, 5, 4), expected.values.map { it.size })
    }

    /**
     * Every gameval on every site resolves and has a packed definition behind it.
     *
     * Resolution alone proves only that a name maps to an id: a name can resolve and still have
     * nothing packed behind it, which throws at first use rather than at boot. `asRSCM` throws on
     * an unmapped name and the `getObject`/`getVarbit` lookup covers the other half.
     */
    @Test
    fun `every base loc, varbit and animal companion has a packed definition`() {
        for (site in PitfallSites.all) {
            assertNotNull(
                ServerCacheManager.getObject(site.baseLoc.asRSCM(RSCMType.LOC)),
                "${site.baseLoc} resolves but has no packed loc definition",
            )
            assertNotNull(
                ServerCacheManager.getVarbit(site.varbit.asRSCM(RSCMType.VARBIT)),
                "${site.varbit} resolves but has no packed varbit definition",
            )
            val animal = site.animalLoc ?: continue
            assertNotNull(
                ServerCacheManager.getObject(animal.asRSCM(RSCMType.LOC)),
                "$animal resolves but has no packed loc definition",
            )
        }
    }

    /** Nine companions, on sites 17-25 and nowhere else; the other sixteen carry null. */
    @Test
    fun `only sites seventeen through twenty-five carry an animal companion`() {
        val withCompanion = PitfallSites.all.filter { it.animalLoc != null }
        assertEquals(9, withCompanion.size, "nine `_animal` companions ship")
        assertEquals((17..25).toList(), withCompanion.map { it.index })
        for (site in PitfallSites.all.filter { it.index <= 16 }) {
            assertNull(site.animalLoc, "site ${site.index} has no companion loc in the cache")
        }
    }

    /**
     * Both indices keep all twenty-five rows.
     *
     * `associateBy` drops a duplicate key silently, so two sites transcribed onto the same loc or
     * the same varbit would shrink the map and lose one of them with no error anywhere.
     */
    @Test
    fun `every site is still reachable by its own base loc and its own varbit`() {
        assertEquals(25, PitfallSites.byBaseLoc.size, "a duplicate base loc would shrink this")
        assertEquals(25, PitfallSites.byVarbit.size, "a duplicate varbit would shrink this")
        for (site in PitfallSites.all) {
            assertSame(site, PitfallSites.byBaseLoc[site.baseLoc], "byBaseLoc[${site.baseLoc}]")
            assertSame(site, PitfallSites.byVarbit[site.varbit], "byVarbit[${site.varbit}]")
        }
    }

    /**
     * Each site's varbit is the one its own base loc renders from.
     *
     * The strongest pairing check available: `multiVarBit` comes out of the cache, so a row that
     * named site 8's varbit beside site 7's loc fails here rather than showing a player one pit's
     * state on another pit.
     */
    @Test
    fun `every site's varbit is the base loc's own multiVarBit`() {
        for (site in PitfallSites.all) {
            val type =
                checkNotNull(ServerCacheManager.getObject(site.baseLoc.asRSCM(RSCMType.LOC))) {
                    "Missing packed loc: ${site.baseLoc}"
                }
            assertEquals(
                site.varbit.asRSCM(RSCMType.VARBIT),
                type.multiVarBit,
                "${site.baseLoc} renders from a different varbit than ${site.varbit}",
            )
        }
    }

    /**
     * Each companion renders from its own site's varbit, and the companion ids are not in site
     * order.
     *
     * The `_animal` loc ids run 53036 (site **19**), 53037 (site **22**), 53038 (site **24**)
     * before settling into 53039..53044, so anything that pairs a companion to a site by sorting
     * ids, by arithmetic, or by zipping two ordered lists is wrong. The second assertion pins that
     * disorder, so a future refactor that "tidies" the pairing into a formula fails here.
     */
    @Test
    fun `every animal companion renders from its own site's varbit`() {
        for (site in PitfallSites.all) {
            val animal = site.animalLoc ?: continue
            val type =
                checkNotNull(ServerCacheManager.getObject(animal.asRSCM(RSCMType.LOC))) {
                    "Missing packed loc: $animal"
                }
            assertEquals(
                site.varbit.asRSCM(RSCMType.VARBIT),
                type.multiVarBit,
                "$animal belongs to a different site than ${site.index}",
            )
        }

        val ids = PitfallSites.all.mapNotNull { it.animalLoc }.map { it.asRSCM(RSCMType.LOC) }
        assertNotEquals(ids.sorted(), ids, "companion ids ascend with the site index after all")
        assertEquals(53036, "loc.hunting_pitfall_19_animal".asRSCM(RSCMType.LOC))
        assertEquals(53037, "loc.hunting_pitfall_22_animal".asRSCM(RSCMType.LOC))
        assertEquals(53038, "loc.hunting_pitfall_24_animal".asRSCM(RSCMType.LOC))
    }

    /**
     * Each site's creature is the one the packed multiloc children name.
     *
     * For sites 1-16 the base loc's own children carry the creature (`hunter_pitfall_full_kyatt`
     * and friends). For 17-25 the base loc carries the creature-free `hunter_pitfall_full` at both
     * full states and the `_animal` companion is the sole carrier, so the companion is read
     * instead. That split is the reason the creature column cannot be checked one way for all
     * twenty-five.
     */
    @Test
    fun `each site's creature is the one its packed multiloc children name`() {
        for (site in PitfallSites.all) {
            val carrier = site.animalLoc ?: site.baseLoc
            val type =
                checkNotNull(ServerCacheManager.getObject(carrier.asRSCM(RSCMType.LOC))) {
                    "Missing packed loc: $carrier"
                }
            val children = type.multiLoc.map { it and 0xFFFF }
            val full =
                checkNotNull(FULL_LOCS[site.creature]) { "No full loc listed for ${site.creature}" }
            assertTrue(
                full.asRSCM(RSCMType.LOC) in children,
                "site ${site.index} is authored as ${site.creature.npc}, but $carrier does not " +
                    "carry $full among its children",
            )
        }
    }

    /**
     * The varbit layout has a hole at site 19, so no bit position may ever be computed.
     *
     * Site 18 ends at bit 23 of `varp.hunt_pitfall_states_basevar2` and site 19 starts at **25**:
     * bit 24 of varp 918 is unused. Site 19 is the ninth field on that varp, so the obvious `3 *
     * field` arithmetic yields 24 - one bit into site 18's field - and 27 for site 20 rather than
     * 28. Both would read and write across a neighbour's state.
     */
    @Test
    fun `the varbit layout has a hole at site nineteen`() {
        val nineteen = PitfallSites.all.single { it.index == 19 }
        val packed = bitsOf(nineteen)
        assertEquals(25, packed.first, "site 19 starts at bit 25 of basevar2")
        assertEquals(27, packed.second, "site 19 ends at bit 27")
        assertNotEquals(24, packed.first, "24 is what `3 * field` would give, and it is wrong")

        val twenty = PitfallSites.all.single { it.index == 20 }
        assertEquals(28, bitsOf(twenty).first, "site 20 is shifted by the same hole")
    }

    /**
     * Every site's packed bits are the surveyed ones, base varp included.
     *
     * The full layout rather than the hole alone, because the hole is only visible as an
     * irregularity against the twenty-four regular fields around it. Read out of the cache and
     * compared to the survey, so this is code against an independent source in both directions.
     */
    @Test
    fun `every site's packed varbit carries its surveyed varp and bits`() {
        for (site in PitfallSites.all) {
            val type =
                checkNotNull(ServerCacheManager.getVarbit(site.varbit.asRSCM(RSCMType.VARBIT))) {
                    "Missing packed varbit: ${site.varbit}"
                }
            val surveyed =
                checkNotNull(SURVEYED_BITS[site.index]) { "Site ${site.index} was not surveyed" }
            assertEquals(
                surveyed,
                Triple(type.varp, type.startBit, type.endBit),
                "${site.varbit} (varp, startBit, endBit)",
            )
            assertEquals(3, type.endBit - type.startBit + 1, "${site.varbit} is a three-bit field")
        }
    }

    /**
     * Every authored coordinate is at level 0, the plane a player actually stands on.
     *
     * The cache authors all 134 pitfall placements at level 1 on `LINK_BELOW` tiles, and
     * `GameMapDecoder.bridgeLevel` resolves them a plane down before the loc is added to its zone.
     * A site authored here at level 1 would exist, resolve, and never fire an op - with nothing in
     * any log. That the tiles really are `LINK_BELOW` is proved below rather than assumed.
     */
    @Test
    fun `every authored coordinate is at level zero`() {
        for (site in PitfallSites.all) {
            assertEquals(0, site.coords.level, "${site.baseLoc} is not on the resolved plane")
        }
    }

    @Test
    fun `no two sites share a coordinate`() {
        assertEquals(25, PitfallSites.all.map { it.coords }.toSet().size)
    }

    /**
     * Every site's loc is placed on exactly that tile in the packed map.
     *
     * The one check that can contradict a transposed digit. The map square is derived from the
     * authored coordinate rather than transcribed, so a coordinate in the wrong square finds no
     * placement at all and a coordinate in the right square with the wrong tile fails on the local
     * offsets. The authored level is asserted as 1 - the placement really is a plane above the
     * coordinate this table ships.
     */
    @Test
    fun `every site's loc is placed at its authored tile in the packed map`() {
        for (site in PitfallSites.all) {
            val id = site.baseLoc.asRSCM(RSCMType.LOC)
            val placements = placementsIn(MapSquareKey.from(site.coords)).filter { it.id == id }
            assertEquals(
                1,
                placements.size,
                "${site.baseLoc} (id $id) placements in the square holding ${site.coords}",
            )
            val placement = placements.single()
            assertEquals(site.coords.lx, placement.localX, "${site.baseLoc} local x")
            assertEquals(site.coords.lz, placement.localZ, "${site.baseLoc} local z")
            assertEquals(1, placement.level, "${site.baseLoc} is authored a plane above level 0")
        }
    }

    @Test
    fun `every animal companion is placed in its own site's map square`() {
        for (site in PitfallSites.all) {
            val animal = site.animalLoc ?: continue
            val id = animal.asRSCM(RSCMType.LOC)
            val placements = placementsIn(MapSquareKey.from(site.coords)).filter { it.id == id }
            assertEquals(1, placements.size, "$animal placements in site ${site.index}'s square")
        }
    }

    /**
     * Every site's tile carries `LINK_BELOW` at plane 1, which is what puts the site on plane 0.
     *
     * This is `GameMapDecoder.bridgeLevel`'s own expression, evaluated on the site's own tile:
     * `mapDef[x, z, 1] and LINK_BELOW`. It is the proof behind the level-0 column, not a
     * restatement of it - if a cache update ever flattened these squares the coordinates would need
     * reauthoring, and this is the assertion that would say so.
     */
    @Test
    fun `every site sits on a LINK_BELOW tile, which is why the resolved plane is zero`() {
        for (site in PitfallSites.all) {
            val tiles = tilesIn(MapSquareKey.from(site.coords))
            val flags = tiles[site.coords.lx, site.coords.lz, BRIDGE_FLAG_LEVEL].toInt()
            assertTrue(
                (flags and MapTileSimpleDefinition.LINK_BELOW) != 0,
                "${site.baseLoc} at ${site.coords} is not on a bridged tile (flags=$flags), so " +
                    "GameMapDecoder would leave it on plane 1",
            )
        }
    }

    private fun bitsOf(site: PitfallSite): Pair<Int, Int> {
        val type =
            checkNotNull(ServerCacheManager.getVarbit(site.varbit.asRSCM(RSCMType.VARBIT))) {
                "Missing packed varbit: ${site.varbit}"
            }
        return type.startBit to type.endBit
    }

    private companion object {
        /**
         * The plane the `LINK_BELOW` flag is authored on. `GameMapDecoder` reads plane 1 whichever
         * plane it is resolving, so this is 1 and not the placement's own level.
         */
        private const val BRIDGE_FLAG_LEVEL = 1

        /** The full-trap child loc that names each creature; see the multiloc test. */
        private val FULL_LOCS: Map<PitfallCreature, String> =
            mapOf(
                PitfallCreatures.kyatt to "loc.hunter_pitfall_full_kyatt",
                PitfallCreatures.larupia to "loc.hunter_pitfall_full_larupia",
                PitfallCreatures.graahk to "loc.hunter_pitfall_full_graahk",
                PitfallCreatures.sunlight to "loc.hunter_pitfall_full_antelope_sunlight",
                PitfallCreatures.moonlight to "loc.hunter_pitfall_full_antelope_moonlight",
            )

        /**
         * `site index to (varp, startBit, endBit)`, read off the packed cache during the survey and
         * retyped here from `.data/cache/wiki-hunter/pitfall-site-table.md` section 2.
         *
         * Three base varps: 917 holds sites 1-10, 918 holds 11-20, 4123 holds 21-25. The only
         * discontinuity in the whole layout is bit 24 of 918.
         */
        private val SURVEYED_BITS: Map<Int, Triple<Int, Int, Int>> =
            mapOf(
                1 to Triple(917, 0, 2),
                2 to Triple(917, 3, 5),
                3 to Triple(917, 6, 8),
                4 to Triple(917, 9, 11),
                5 to Triple(917, 12, 14),
                6 to Triple(917, 15, 17),
                7 to Triple(917, 18, 20),
                8 to Triple(917, 21, 23),
                9 to Triple(917, 24, 26),
                10 to Triple(917, 27, 29),
                11 to Triple(918, 0, 2),
                12 to Triple(918, 3, 5),
                13 to Triple(918, 6, 8),
                14 to Triple(918, 9, 11),
                15 to Triple(918, 12, 14),
                16 to Triple(918, 15, 17),
                17 to Triple(918, 18, 20),
                18 to Triple(918, 21, 23),
                19 to Triple(918, 25, 27),
                20 to Triple(918, 28, 30),
                21 to Triple(4123, 0, 2),
                22 to Triple(4123, 3, 5),
                23 to Triple(4123, 6, 8),
                24 to Triple(4123, 9, 11),
                25 to Triple(4123, 12, 14),
            )

        /**
         * A second read handle on the packed cache, for the two map files `ServerCacheManager` does
         * not decode.
         *
         * It loads configs only; maps stay on disk as raw archives, which is how the boot decoder
         * reads them too. Opened once per JVM and never written to.
         */
        private val cache: Cache by lazy {
            Cache.load(Paths.get(HunterTestCache.repoRoot.absolutePath, ".data", "cache", "SERVER"))
        }

        private val locLists = HashMap<Int, List<MapLocDefinition>>()

        private val tileDefs = HashMap<Int, MapTileSimpleDefinition>()

        /**
         * Every loc placement in one map square, the same path `GameMapDecoder` takes at boot: file
         * 1 of the square's map group is the loc list, and each packed long unpacks to an id, a
         * shape, an angle and a local tile.
         */
        private fun placementsIn(square: MapSquareKey): List<MapLocDefinition> =
            locLists.getOrPut(square.id) {
                val data =
                    checkNotNull(cache.data(MAPS, square.id, 1)) {
                        "Map group ${square.id} ($square) has no loc list"
                    }
                MapLocListDecoder.decode(InlineByteBuf(data)).spawns.map(::MapLocDefinition)
            }

        /** File 0 of the square's map group: the tile list, with the flags every plane reads. */
        private fun tilesIn(square: MapSquareKey): MapTileSimpleDefinition =
            tileDefs.getOrPut(square.id) {
                val data =
                    checkNotNull(cache.data(MAPS, square.id, 0)) {
                        "Map group ${square.id} ($square) has no tile list"
                    }
                MapTileDecoder.decode(InlineByteBuf(data))
            }
    }
}
