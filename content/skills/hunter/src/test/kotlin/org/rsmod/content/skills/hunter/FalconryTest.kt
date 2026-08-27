package org.rsmod.content.skills.hunter

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.map.CoordGrid

/**
 * Falconry, driven in-process - the cases a live client cannot force. The two that matter most,
 * [falconTimesOutAndLosesTheCatch] and [falconTimesOutWithOwnerLoggedOut], assert the *absence* of
 * a reward, which a quietly-paying timeout would make invisible in play.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class FalconryTest {
    /* Rates and table integrity - code against an independent source, not against code. */

    /**
     * The fitted pairs reproduce the wiki's own charted points - expected values taken from each
     * creature's published chart, not from `HunterTables` (the 112-point extract is in
     * `wiki-charts/falconry-chance.tsv`).
     */
    @Test
    fun fittedRatesReproduceTheWikiCharts() {
        // creature npc -> (level, expected chance out of 256) sampled across each charted range,
        // including both endpoints.
        val samples =
            mapOf(
                "npc.huntingbeast_speedy" to
                    listOf(43 to 149, 44 to 152, 60 to 198, 79 to 253, 80 to 256),
                "npc.huntingbeast_silent" to
                    listOf(57 to 146, 58 to 148, 75 to 192, 98 to 251, 99 to 254),
                "npc.huntingbeast_speedy2" to
                    listOf(69 to 143, 70 to 145, 84 to 175, 98 to 204, 99 to 206),
            )

        for ((npc, points) in samples) {
            val creature = checkNotNull(FalconryCreatures.byNpc(npc)) { "No falconry row for $npc" }
            for ((level, expected) in points) {
                val rate =
                    org.rsmod.api.utils.skills.SkillingSuccessRate.successRate(
                        low = creature.successLow,
                        high = creature.successHigh,
                        level = level,
                        maxLevel = 99,
                    )
                assertEquals(
                    expected,
                    Math.round(rate * 256).toInt(),
                    "$npc at level $level: chart says $expected/256",
                )
            }
        }
    }

    /**
     * The spotted kebbit's curve is certain from level 80, not 99: the guard that fails loudly if
     * anyone "fixes" the deliberately out-of-range `high` of 310.
     */
    @Test
    fun spottedKebbitRateIsUnclamped() {
        val creature = checkNotNull(FalconryCreatures.byNpc("npc.huntingbeast_speedy"))
        assertTrue(creature.successHigh > 256, "high must stay above 256")

        val at80 = rateOf(creature, 80)
        val at99 = rateOf(creature, 99)
        assertTrue(at80 >= 1.0, "spotted kebbit should be a certain catch from level 80, got $at80")
        assertTrue(at99 > 1.0, "level 99 rate should exceed 1.0 unclamped, got $at99")
    }

    /** Levels, xp (x10) and the falcon npc pairing, against the wiki's Creatures table. */
    @Test
    fun tableCarriesTheChartedLevelsXpAndFalconNpcs() {
        val expected =
            listOf(
                Triple("npc.huntingbeast_speedy", 43, 1040) to "npc.hunting_falcon_onspeedy",
                Triple("npc.huntingbeast_silent", 57, 1320) to "npc.hunting_falcon_onsilent",
                Triple("npc.huntingbeast_speedy2", 69, 1560) to "npc.hunting_falcon_onspeedy2",
            )
        assertEquals(expected.size, FalconryCreatures.all.size)

        for ((triple, falcon) in expected) {
            val (npc, level, xp) = triple
            val creature = checkNotNull(FalconryCreatures.byNpc(npc))
            assertEquals(level, creature.level, "$npc level")
            assertEquals(xp, creature.xp, "$npc xp (stored x10)")
            assertEquals(falcon, creature.falconNpc, "$npc falcon npc")
            // The falcon npc has to round-trip, or the retrieve path cannot recover the reward.
            assertEquals(creature, FalconryCreatures.byFalconNpc(falcon))
        }
    }

    /**
     * The three falcon npcs are pinned in `.data/raw-cache/server/npcs.toml` - the half of the
     * wander fix that lives in data, not code (docs/hunter.md). Asserted against the TOML, not the
     * packed def, so a fresh checkout passes; single-block because duplicate raw-cache TOML ids
     * are last-wins.
     */
    @Test
    fun falconNpcsAreDeclaredStationary() {
        val toml = File(HunterTestCache.repoRoot, ".data/raw-cache/server/npcs.toml").readText()
        val blocks = toml.split("[[npc]]")

        for (creature in FalconryCreatures.all) {
            val declaring =
                blocks.filter { block ->
                    block.lineSequence().any { it.trim() == "id = \"${creature.falconNpc}\"" }
                }
            assertEquals(1, declaring.size, "${creature.falconNpc} should be declared once")

            val lines = declaring.single().lines().map(String::trim)
            assertTrue(
                "moveRestrict = \"NoMove\"" in lines,
                "${creature.falconNpc} must not walk off its prey",
            )
            assertTrue("wanderRange = 0" in lines, "${creature.falconNpc} must not wander")
        }
    }

    /** "Always" drops only; Kebbity tuft is rumour-conditional and excluded from all three. */
    @Test
    fun rewardsAreTheAlwaysDropsOnly() {
        fun rewardsOf(npc: String) =
            checkNotNull(FalconryCreatures.byNpc(npc)).caught.map { it.obj }

        assertEquals(
            listOf("obj.bones", "obj.huntingbeast_speedy_fur"),
            rewardsOf("npc.huntingbeast_speedy"),
        )
        assertEquals(
            listOf("obj.bones", "obj.huntingbeast_silent_fur"),
            rewardsOf("npc.huntingbeast_silent"),
        )
        // The only three-line falconry reward: dashing kebbits always drop meat as well.
        assertEquals(
            listOf(
                "obj.bones",
                "obj.huntingbeast_speedy2_fur",
                "obj.huntingbeast_speedy2_meat",
            ),
            rewardsOf("npc.huntingbeast_speedy2"),
        )

        // Every falconry reward is a flat one, so no catch consumes a random draw for quantity.
        for (creature in FalconryCreatures.all) {
            for (reward in creature.caught) {
                assertEquals(1..1, reward.quantity, "${creature.npc} ${reward.obj}")
            }
        }
    }

    /**
     * Falconry must never appear in the trap engine's world - the regression guard on the whole
     * "not a trap" decision.
     */
    @Test
    fun falconryIsNotPartOfTheTrapTables() {
        val falconryNpcs = FalconryCreatures.all.map { it.npc }.toSet()
        val trapNpcs = HunterCreatures.all.map { it.npc }.toSet()
        assertTrue(
            (falconryNpcs intersect trapNpcs).isEmpty(),
            "Falconry creatures must not appear in HunterCreatures.all",
        )
        assertEquals(21, HunterCreatures.all.size, "trap creature count must not change")
        assertEquals(5, TrapFamily.entries.size, "falconry must not add a TrapFamily entry")
    }

    /* Renting */

    @Test
    fun rentingSwapsCoinsForTheGloveAndBird() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 43)
        world.giveCoins(player, FALCONRY_RENTAL_FEE)

        val rented = world.runProtected(player) { with(it) { rentFalcon() } }

        assertTrue(rented)
        assertTrue(player.inv.contains(FALCON_GLOVE_WITH_BIRD), "should hold the glove with bird")
        assertEquals(0, player.inv.count("obj.coins"), "fee should be taken")
    }

    @Test
    fun rentingRefusedWithoutTheFee() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 43)
        world.giveCoins(player, FALCONRY_RENTAL_FEE - 1)

        val rented = world.runProtected(player) { with(it) { rentFalcon() } }

        assertFalse(rented)
        assertFalse(player.inv.contains(FALCON_GLOVE_WITH_BIRD))
        assertEquals(FALCONRY_RENTAL_FEE - 1, player.inv.count("obj.coins"), "no partial charge")
    }

    /** Renting twice would mint a second glove; both glove states have to block it. */
    @Test
    fun rentingRefusedWhileAlreadyHoldingEitherGlove() {
        for (held in listOf(FALCON_GLOVE, FALCON_GLOVE_WITH_BIRD)) {
            val world = HunterFalconryTestWorld()
            val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 43)
            world.giveCoins(player, FALCONRY_RENTAL_FEE)
            world.giveItem(player, held)

            val rented = world.runProtected(player) { with(it) { rentFalcon() } }

            assertFalse(rented, "should refuse while holding $held")
            assertEquals(FALCONRY_RENTAL_FEE, player.inv.count("obj.coins"), "no charge for $held")
        }
    }

    /**
     * The glove is *equipment*, and every check reads the worn slot as well as the backpack -
     * reading only `inv` let a player who wore the glove rent a second one for another 500 coins.
     */
    @Test
    fun rentingRefusedWhileWearingEitherGlove() {
        for (worn in listOf(FALCON_GLOVE, FALCON_GLOVE_WITH_BIRD)) {
            val world = HunterFalconryTestWorld()
            val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 43)
            world.giveCoins(player, FALCONRY_RENTAL_FEE)
            world.wear(player, worn)

            val rented = world.runProtected(player) { with(it) { rentFalcon() } }

            assertFalse(rented, "should refuse while wearing $worn")
            assertEquals(FALCONRY_RENTAL_FEE, player.inv.count("obj.coins"), "no charge for $worn")
            assertFalse(player.inv.contains(FALCON_GLOVE_WITH_BIRD), "no second glove for $worn")
        }
    }

    /* Catching */

    /**
     * The whole technique with the glove worn: the catch used to refuse while the player visibly
     * held a falcon, and the bird has to leave and return on the hand it was on.
     */
    @Test
    fun catchAndRetrieveWorkWithTheGloveWorn() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        world.wear(player, FALCON_GLOVE_WITH_BIRD)
        val kebbitTile = FALCONRY_TILE.translateX(2)
        val kebbit = world.addNpc("npc.huntingbeast_speedy", kebbitTile)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.runProtected(player) { with(it) { catchKebbit(kebbit) } }

        assertTrue(caught, "a worn glove is a held glove")
        assertTrue(player.worn.contains(FALCON_GLOVE), "the empty glove stays on the hand")
        assertFalse(player.inv.contains(FALCON_GLOVE), "and does not fall into the backpack")

        // The despawned kebbit is still on its tile, hidden, so the visible npc is the falcon.
        val falcon = checkNotNull(world.npcRepo.findAll(kebbitTile).firstOrNull { it.isVisible })
        val retrieved = world.runProtected(player) { with(it) { retrieveFalcon(falcon) } }

        assertTrue(retrieved)
        assertTrue(player.worn.contains(FALCON_GLOVE_WITH_BIRD), "the bird comes back to the hand")
        assertTrue(player.inv.contains("obj.huntingbeast_speedy_fur"), "and the catch is paid out")
    }

    @Test
    fun successfulCatchDespawnsKebbitAndSpawnsTheMatchingFalcon() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        world.giveItem(player, FALCON_GLOVE_WITH_BIRD)
        val kebbitTile = FALCONRY_TILE.translateX(3)
        val kebbit = world.addNpc("npc.huntingbeast_speedy", kebbitTile)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.runProtected(player) { with(it) { catchKebbit(kebbit) } }

        assertTrue(caught)
        assertFalse(world.npcIsSpawned(kebbit), "kebbit should be gone")
        assertEquals(
            "npc.hunting_falcon_onspeedy",
            world.npcNameAt(kebbitTile),
            "the spotted kebbit's own falcon npc should stand on its tile",
        )
        assertTrue(player.inv.contains(FALCON_GLOVE), "glove should be empty while the bird is out")
        assertFalse(player.inv.contains(FALCON_GLOVE_WITH_BIRD))

        val controller = checkNotNull(world.falconControllerAt(kebbitTile))
        assertEquals(player.uid.packed, controller.falconOwner)
    }

    /** No xp at the catch - every shipped technique awards on collect, and falconry matches. */
    @Test
    fun catchAwardsNoXpUntilRetrieval() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        world.giveItem(player, FALCON_GLOVE_WITH_BIRD)
        val kebbit = world.addNpc("npc.huntingbeast_speedy", FALCONRY_TILE.translateX(2))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val before = player.statMap.getXP("stat.hunter")

        world.runProtected(player) { with(it) { catchKebbit(kebbit) } }

        assertEquals(before, player.statMap.getXP("stat.hunter"), "xp is awarded at retrieval")
    }

    /**
     * The failure branch: the bird comes back to the glove and nothing is spawned.
     *
     * Set at the creature's own requirement level, where the rate is a real fraction - at 99 the
     * spotted kebbit's unclamped rate exceeds any legal draw and cannot miss.
     */
    @Test
    fun failedCatchReturnsTheFalconToTheGlove() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 43)
        world.giveItem(player, FALCON_GLOVE_WITH_BIRD)
        val kebbitTile = FALCONRY_TILE.translateX(2)
        val kebbit = world.addNpc("npc.huntingbeast_speedy", kebbitTile)
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW

        val caught = world.runProtected(player) { with(it) { catchKebbit(kebbit) } }

        assertFalse(caught, "highest draw should miss at the requirement level")
        assertTrue(
            player.inv.contains(FALCON_GLOVE_WITH_BIRD),
            "the falcon should be back on the glove",
        )
        assertFalse(player.inv.contains(FALCON_GLOVE))
        assertNull(world.falconControllerAt(kebbitTile), "a miss spawns no falcon")
        assertEquals("npc.huntingbeast_speedy", world.npcNameAt(kebbitTile), "kebbit stays")
    }

    /** Under the creature's level the attempt is refused before the roll, costing no draw. */
    @Test
    fun levelGateRefusesBeforeTheRoll() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 68)
        world.giveItem(player, FALCON_GLOVE_WITH_BIRD)
        val kebbit = world.addNpc("npc.huntingbeast_speedy2", FALCONRY_TILE.translateX(2))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.runProtected(player) { with(it) { catchKebbit(kebbit) } }

        assertFalse(caught, "level 68 cannot catch a level-69 dashing kebbit")
        assertEquals(0, world.random.doubleDraws, "an under-levelled attempt must not roll")
        assertTrue(world.npcIsSpawned(kebbit), "the kebbit should be untouched")
        assertTrue(player.inv.contains(FALCON_GLOVE_WITH_BIRD), "the bird never left")
    }

    /** No bird on the glove means no catch. */
    @Test
    fun catchRefusedWithoutTheFalcon() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        world.giveItem(player, FALCON_GLOVE)
        val kebbit = world.addNpc("npc.huntingbeast_speedy", FALCONRY_TILE.translateX(2))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.runProtected(player) { with(it) { catchKebbit(kebbit) } }

        assertFalse(caught)
        assertEquals(0, world.random.doubleDraws)
        assertTrue(world.npcIsSpawned(kebbit))
    }

    /* Retrieval */

    @Test
    fun retrievingAwardsLootAndXpAndReturnsTheBird() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        world.giveItem(player, FALCON_GLOVE)
        val tile = FALCONRY_TILE.translateX(2)
        val creature = checkNotNull(FalconryCreatures.byNpc("npc.huntingbeast_speedy2"))
        val falcon = world.placeCaughtFalcon(tile, player, creature)
        val before = player.statMap.getXP("stat.hunter")

        val retrieved = world.runProtected(player) { with(it) { retrieveFalcon(falcon) } }

        assertTrue(retrieved)
        for (reward in creature.caught) {
            assertTrue(player.inv.contains(reward.obj), "should hold ${reward.obj}")
        }
        // Stored x10, awarded once divided.
        assertEquals(
            creature.xp / 10,
            player.statMap.getXP("stat.hunter") - before,
            "dashing kebbit awards 156 xp",
        )
        assertTrue(player.inv.contains(FALCON_GLOVE_WITH_BIRD), "the bird comes back")
        assertFalse(player.inv.contains(FALCON_GLOVE))
        assertFalse(world.npcIsSpawned(falcon), "the falcon npc should be gone")
        assertNull(world.falconControllerAt(tile), "its controller should be gone")
    }

    /**
     * The Hunter xp modifier is *applied*, not merely injected.
     *
     * Every world in this suite built its `XpModifiers` from an empty set, which is a flat 1.0, so
     * the `* xpMods.get(player, "stat.hunter")` on the award site could be deleted with the suite
     * still green. Running the same catch twice, once in a doubled world, is what makes the
     * multiplication load-bearing.
     */
    @Test
    fun theXpModifierScalesTheFalconryAward() {
        val plain = retrievedKebbitFineXp(hunterXpBonus = 0.0)
        val doubled = retrievedKebbitFineXp(hunterXpBonus = DOUBLE_HUNTER_XP)

        // The dashing kebbit's pinned 156 xp, in the stat map's tenths.
        assertEquals(1560, plain, "unmodified, the dashing kebbit is 156.0 xp")
        assertEquals(3120, doubled, "a +100% modifier makes it 312.0")
    }

    /** One retrieved dashing kebbit, in tenths of a point. */
    private fun retrievedKebbitFineXp(hunterXpBonus: Double): Int {
        val world = HunterFalconryTestWorld(hunterXpBonus = hunterXpBonus)
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        world.giveItem(player, FALCON_GLOVE)
        val creature = checkNotNull(FalconryCreatures.byNpc("npc.huntingbeast_speedy2"))
        val falcon = world.placeCaughtFalcon(FALCONRY_TILE.translateX(2), player, creature)

        assertTrue(world.runProtected(player) { with(it) { retrieveFalcon(falcon) } })

        return player.statMap.getFineXP("stat.hunter")
    }

    /**
     * A falcon that has walked off its prey's tile is still that catch's falcon - the tile-keyed
     * bug silently voided the catch and left an `Int.MAX_VALUE` npc in the world (docs/hunter.md).
     * No other test moves a falcon: this world has no `NpcMainProcess`.
     */
    @Test
    fun falconStaysItsOwnAfterWanderingOffThePreyTile() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        world.giveItem(player, FALCON_GLOVE)
        val tile = FALCONRY_TILE.translateX(2)
        val creature = checkNotNull(FalconryCreatures.byNpc("npc.huntingbeast_speedy"))
        val falcon = world.placeCaughtFalcon(tile, player, creature)

        world.moveNpc(falcon, tile.translateX(1))
        world.advanceFalconCycle(tile)

        assertNotNull(
            world.falconControllerAt(tile),
            "A bird that walked one tile has not gone anywhere.",
        )
        assertTrue(world.npcIsSpawned(falcon), "and it is certainly not despawned")

        val retrieved = world.runProtected(player) { with(it) { retrieveFalcon(falcon) } }

        assertTrue(retrieved, "the catch is still the player's to collect")
        for (reward in creature.caught) {
            assertTrue(player.inv.contains(reward.obj), "should hold ${reward.obj}")
        }
        assertTrue(player.inv.contains(FALCON_GLOVE_WITH_BIRD), "the bird comes back")
        assertFalse(world.npcIsSpawned(falcon), "and the npc is cleaned up, not leaked")
        assertNull(world.falconControllerAt(tile))
    }

    /** Somebody else's falcon is not yours to take. */
    @Test
    fun retrievingRefusedForANonOwner() {
        val world = HunterFalconryTestWorld()
        val owner = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        val stranger = world.addPlayer(FALCONRY_TILE.translateX(1), hunterLvl = 99)
        world.giveItem(stranger, FALCON_GLOVE)
        val tile = FALCONRY_TILE.translateX(2)
        val creature = checkNotNull(FalconryCreatures.byNpc("npc.huntingbeast_speedy"))
        val falcon = world.placeCaughtFalcon(tile, owner, creature)

        val retrieved = world.runProtected(stranger) { with(it) { retrieveFalcon(falcon) } }

        assertFalse(retrieved)
        assertFalse(stranger.inv.contains("obj.huntingbeast_speedy_fur"), "no loot for a stranger")
        assertTrue(world.npcIsSpawned(falcon), "the falcon should still be there for its owner")
        assertNotNull(world.falconControllerAt(tile))
    }

    /** A full inventory refuses the retrieve outright rather than dropping half the catch. */
    @Test
    fun retrievingRefusedWhenTheInventoryIsFull() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        world.giveItem(player, FALCON_GLOVE)
        world.fillInventory(player)
        val tile = FALCONRY_TILE.translateX(2)
        val creature = checkNotNull(FalconryCreatures.byNpc("npc.huntingbeast_speedy"))
        val falcon = world.placeCaughtFalcon(tile, player, creature)
        val before = player.statMap.getXP("stat.hunter")

        val retrieved = world.runProtected(player) { with(it) { retrieveFalcon(falcon) } }

        assertFalse(retrieved)
        assertFalse(player.inv.contains("obj.huntingbeast_speedy_fur"))
        assertEquals(before, player.statMap.getXP("stat.hunter"), "no xp for a refused retrieve")
        assertTrue(world.npcIsSpawned(falcon), "the catch waits for space")
        assertNotNull(world.falconControllerAt(tile))
    }

    /* Timeout - the headline case */

    /**
     * "If the falcon is not retrieved within a short time... no experience is given for the lost
     * prey." (wiki.) Asserts the *absence* of a reward, which a live client cannot show.
     */
    @Test
    fun falconTimesOutAndLosesTheCatch() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        world.giveItem(player, FALCON_GLOVE)
        val tile = FALCONRY_TILE.translateX(2)
        val creature = checkNotNull(FalconryCreatures.byNpc("npc.huntingbeast_speedy"))
        val falcon = world.placeCaughtFalcon(tile, player, creature)
        val before = player.statMap.getXP("stat.hunter")

        world.tickFalconUntilGone(tile)

        assertNull(world.falconControllerAt(tile), "the controller should be gone")
        assertFalse(world.npcIsSpawned(falcon), "the falcon should have flown off")
        assertFalse(player.inv.contains("obj.huntingbeast_speedy_fur"), "the catch is lost")
        assertFalse(player.inv.contains("obj.bones"), "the catch is lost")
        assertEquals(before, player.statMap.getXP("stat.hunter"), "no xp for the lost prey")
        // The bird goes back to the falconer, not onto the player's glove.
        assertFalse(player.inv.contains(FALCON_GLOVE_WITH_BIRD), "the bird returns to Matthias")
        assertTrue(player.inv.contains(FALCON_GLOVE), "the player keeps the empty glove")
    }

    /**
     * The timer is scoped to the falcon, not the player, and runs with the owner logged out.
     *
     * A trap's tick collapses immediately when its owner is gone, because the roll needs their live
     * Hunter level. A falcon has already rolled, so it has no such dependency - and a falcon that
     * only expired while its owner was watching would sit on the map forever after a logout.
     */
    @Test
    fun falconTimesOutWithOwnerLoggedOut() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        val tile = FALCONRY_TILE.translateX(2)
        val creature = checkNotNull(FalconryCreatures.byNpc("npc.huntingbeast_silent"))
        val falcon = world.placeCaughtFalcon(tile, player, creature)

        world.removePlayer(player)
        world.tickFalconUntilGone(tile)

        assertNull(world.falconControllerAt(tile))
        assertFalse(world.npcIsSpawned(falcon))
    }

    /**
     * The falcon lasts ninety-eight cycles and is gone on the ninety-ninth.
     *
     * [FALCON_TIMEOUT_CYCLES] is pinned nowhere: [falconSurvivesUntilItsTimeoutElapses] counts
     * `FALCON_TIMEOUT_CYCLES - 2` cycles and [HunterFalconryTestWorld.tickFalconUntilGone] bounds
     * itself at `FALCON_TIMEOUT_CYCLES + 5`, so both move with the constant and neither notices a
     * falcon that now gives up after five seconds. Same vacuous shape as bird house `NEST_ROLLS`.
     *
     * There is no wiki figure to pin it to - the page says only "a short time" - so what is pinned
     * instead is the thing a player can actually observe: how many cycles a falcon really sits on
     * its prey. Ninety-eight and ninety-nine are literals, and they are not the constant: a duration
     * of a hundred is decremented once per cycle and [HunterFalconry.falconTick] gives up at one
     * remaining, so the two ends of the lifetime differ from the constant by two and by one. That is
     * exactly the arithmetic a test reading the constant back cannot check, and the pair fails from
     * both sides - a lifetime raised by a cycle fails the second assertion, one lowered fails the
     * first.
     *
     * The direct comparison is kept alongside, because it says which of the two is wrong when they
     * disagree: the constant, or what the controller does with it.
     */
    @Test
    fun falconLastsNinetyEightCyclesAndIsGoneOnTheNinetyNinth() {
        assertEquals(100, FALCON_TIMEOUT_CYCLES, "a minute at 0.6s a cycle")

        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        val tile = FALCONRY_TILE.translateX(2)
        val creature = checkNotNull(FalconryCreatures.byNpc("npc.huntingbeast_speedy"))
        val falcon = world.placeCaughtFalcon(tile, player, creature)

        repeat(98) { world.advanceFalconCycle(tile) }

        assertNotNull(world.falconControllerAt(tile), "still waiting after ninety-eight")
        assertTrue(world.npcIsSpawned(falcon), "and the bird is still on its prey")

        world.advanceFalconCycle(tile)

        assertNull(world.falconControllerAt(tile), "gone on the ninety-ninth")
        assertFalse(world.npcIsSpawned(falcon), "and the prey with it")
    }

    /** Before the timeout lands, the falcon is still there to be retrieved. */
    @Test
    fun falconSurvivesUntilItsTimeoutElapses() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        val tile = FALCONRY_TILE.translateX(2)
        val creature = checkNotNull(FalconryCreatures.byNpc("npc.huntingbeast_speedy"))
        val falcon = world.placeCaughtFalcon(tile, player, creature)

        // One short of the lifetime: the controller gives up a cycle early, so stop two short.
        repeat(FALCON_TIMEOUT_CYCLES - 2) { world.advanceFalconCycle(tile) }

        assertNotNull(world.falconControllerAt(tile), "should still be waiting")
        assertTrue(world.npcIsSpawned(falcon))
    }

    /* Leaving the enclosure */

    @Test
    fun leavingTheAreaStripsBothGloveStates() {
        for (held in listOf(FALCON_GLOVE, FALCON_GLOVE_WITH_BIRD)) {
            val world = HunterFalconryTestWorld()
            val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
            world.giveItem(player, held)
            world.giveItem(player, "obj.bones")

            val stripped = world.runProtected(player) { with(it) { stripFalconGloves() } }

            assertTrue(stripped, "should strip $held")
            assertFalse(player.inv.contains(held), "$held should be gone")
            assertTrue(player.inv.contains("obj.bones"), "the catch is kept, only the glove goes")
        }
    }

    /**
     * A glove on the hand leaves with Matthias too.
     *
     * "When attempting to leave the area with the glove equipped, the player will state 'I should
     * return the glove I borrowed from the falconer before leaving.'... Teleporting bypasses this
     * restriction with the text *As you leave, Matthias' falcon flies back to him.*" (wiki,
     * *Falconer's glove*.) A strip that walked `inv` alone let a worn glove out of the enclosure for
     * good - it is untradeable and unobtainable outside the area, so nothing would ever take it
     * back.
     */
    @Test
    fun leavingTheAreaStripsAWornGlove() {
        for (worn in listOf(FALCON_GLOVE, FALCON_GLOVE_WITH_BIRD)) {
            val world = HunterFalconryTestWorld()
            val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
            world.wear(player, worn)
            world.giveItem(player, "obj.bones")

            val stripped = world.runProtected(player) { with(it) { stripFalconGloves() } }

            assertTrue(stripped, "should strip a worn $worn")
            assertFalse(player.worn.contains(worn), "$worn should be off the hand")
            assertFalse(player.inv.contains(worn), "and not moved into the backpack")
            assertTrue(player.inv.contains("obj.bones"), "the catch is kept, only the glove goes")
        }
    }

    /** Nothing to strip is not a failure, and must not send a message. */
    @Test
    fun leavingTheAreaWithoutAGloveIsANoOp() {
        val world = HunterFalconryTestWorld()
        val player = world.addPlayer(FALCONRY_TILE, hunterLvl = 99)
        world.giveItem(player, "obj.bones")

        val stripped = world.runProtected(player) { with(it) { stripFalconGloves() } }

        assertFalse(stripped)
        assertTrue(player.inv.contains("obj.bones"))
    }

    private fun rateOf(creature: FalconryCreature, level: Int): Double =
        org.rsmod.api.utils.skills.SkillingSuccessRate.successRate(
            low = creature.successLow,
            high = creature.successHigh,
            level = level,
            maxLevel = 99,
        )

    companion object {
        /** Inside the Piscatoris falconry enclosure, and clear of the instanced-region range. */
        val FALCONRY_TILE: CoordGrid = CoordGrid(2376, 3590, 0)

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }
    }
}
