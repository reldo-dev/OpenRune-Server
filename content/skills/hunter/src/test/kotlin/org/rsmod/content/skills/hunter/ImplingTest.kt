package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * Impling catching, driven in-process and checked against the wiki rather than against the table.
 * The two tests that matter most are [theExperienceAwardedIsThePuroPuroValue] (invisible in game
 * until the magpie's 44-vs-216 arrives) and [theSameCreatureNeedsAJarInPuroPuroAndNotOutsideIt] -
 * a rule with two halves, and the default test tile is not Puro-Puro, which is exactly how a
 * one-map-square refusal once got asserted across the whole of Gielinor.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class ImplingTest {
    /* The table, against the wiki. */

    /**
     * Every shipped row carries the wiki's levels and *both* of its experience values.
     *
     * Levels and experience are read from each creature's `Hunter info` infobox, which publishes
     * "Level required", "Puro Puro XP" and "Overworld XP" as three separate rows - see [WIKI] for
     * the oldids. Asserting both experience columns is what pins which is which: a table that had
     * them the wrong way round would still award a plausible-looking number.
     */
    @Test
    fun everyShippedRowCarriesTheWikisLevelsAndBothExperienceValues() {
        assertEquals(WIKI.size, ImplingCreatures.all.size, "all twelve implings ship")
        assertEquals(WIKI.map { it.npc }, ImplingCreatures.all.map { it.npc }, "in tier order")

        for (entry in WIKI) {
            val creature = checkNotNull(ImplingCreatures.byNpc(entry.npc)) { "No row for ${entry.npc}" }
            assertEquals(entry.level, creature.level, "${entry.npc} level")
            // Both stored x10, as every hunter experience column is.
            assertEquals(entry.puroXp * 10, creature.xpPuro, "${entry.npc} Puro-Puro xp")
            assertEquals(entry.overworldXp * 10, creature.xp, "${entry.npc} overworld xp")
            // The crystal impling is the one row whose two experience values are equal, because it
            // is Prifddinas-only and its infobox publishes a single figure - it can never be a
            // Puro-Puro spawn, so the two columns have nothing to disagree about.
            assertEquals(listOf(entry.jar), creature.caught.map { it.obj }, "${entry.npc} jar")
            assertEquals(1..1, creature.caught.single().quantity, "${entry.npc} jar quantity")
        }
    }

    /**
     * Every shipped pair is the chart template's own published parameter, and its level requirement
     * is the published one too.
     *
     * Strictly stronger than reproducing a chart, for the reason `HunterRateTablesTest` gives:
     * reproducing a few plotted points does not pin a pair. These are read out of
     * `published-params.tsv`, which holds the `{{Skilling success chart}}` transclusion's own
     * arguments.
     */
    @Test
    fun everyShippedPairAndLevelIsThePublishedParameter() {
        val params = readParams()
        for (entry in WIKI) {
            val published = checkNotNull(params["${entry.page}|Butterfly net"]) { entry.page }
            val creature = checkNotNull(ImplingCreatures.byNpc(entry.npc))
            assertEquals(
                Triple(published.low, published.high, published.req),
                Triple(creature.successLow, creature.successHigh, creature.level),
                "${entry.npc}: ${entry.page} publishes (${published.low}, ${published.high}) req=${published.req}",
            )
        }
    }

    /**
     * The faster curve really is `+20` on both coefficients, on every row.
     *
     * The claim [HunterImpling.usesFasterCurve] rests on, checked against the published parameters
     * of the *second* series on each page rather than against the constant. If the wiki ever
     * published a per-creature bonus, this fails rather than the flat constant quietly being wrong
     * for one row.
     */
    @Test
    fun theFasterCurveIsThePublishedBarehandedAndMagicNetSeries() {
        val params = readParams()
        for (entry in WIKI) {
            val net = params.getValue("${entry.page}|Butterfly net")
            val faster = params.getValue("${entry.page}|Barehanded or magic butterfly net")
            assertEquals(
                net.low + HunterButterfly.NET_BONUS to net.high + HunterButterfly.NET_BONUS,
                faster.low to faster.high,
                "${entry.page}: the two series should differ by exactly the net bonus",
            )
            // And the level requirement is shared - barehanded costs levels through the gate, not
            // through a second curve with a higher start.
            assertEquals(net.req, faster.req, "${entry.page}: both series start at the same level")
        }
    }

    /**
     * Every impling carries both of its npc ids, paired by the `_maze` suffix.
     *
     * "In Puro-Puro" is answered by which npc was clicked rather than by an area check, so the two
     * ids have to be recognisably one creature rather than two rows that happen to agree. The
     * crystal impling is the one row with no Puro-Puro form, and it is named rather than filtered
     * out, so a second such row fails here.
     */
    @Test
    fun everyImplingCarriesBothOfItsNpcIds() {
        for (creature in ImplingCreatures.all) {
            if (creature.npc.endsWith("_maze")) {
                // The Puro-Puro id is the overworld one plus the suffix, which is what makes them
                // recognisably one creature rather than two rows that happen to agree.
                assertEquals(
                    creature.npc,
                    creature.npcOverworld + "_maze",
                    "${creature.npc} is not the maze form of ${creature.npcOverworld}",
                )
            } else {
                // The crystal impling is the exception and the only one: it is Prifddinas-only and
                // has no Puro-Puro form at all, so both columns hold the same overworld id.
                assertEquals("npc.ii_impling_type_12_johnny", creature.npc)
                assertEquals(creature.npc, creature.npcOverworld)
            }
        }
        assertEquals(
            1,
            ImplingCreatures.all.count { !it.npc.endsWith("_maze") },
            "Exactly one impling should lack a Puro-Puro form.",
        )
    }

    /** Implings must never appear in the trap engine's world, or in butterfly netting's. */
    @Test
    fun implingsAreNotPartOfAnyOtherHunterTable() {
        val implings = ImplingCreatures.all.map { it.npc }.toSet()
        assertTrue((implings intersect HunterCreatures.all.map { it.npc }.toSet()).isEmpty())
        assertTrue((implings intersect ButterflyCreatures.all.map { it.npc }.toSet()).isEmpty())
        assertEquals(5, TrapFamily.entries.size, "impling catching must not add a TrapFamily entry")
    }

    /* Catching */

    @Test
    fun successfulCatchSwapsTheEmptyJarForTheFilledOne() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, IMPLING_JAR)
        val impling = world.addNpc(BABY)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.runImpling(player) { with(it) { catchImpling(impling) } }

        assertTrue(caught)
        assertTrue(player.inv.contains(BABY_JAR), "should hold the baby impling jar")
        assertFalse(player.inv.contains(IMPLING_JAR), "the empty jar is consumed")
        assertFalse(world.npcIsSpawned(impling), "the impling should be gone")
    }

    /**
     * The experience awarded is the Puro-Puro value, not the overworld one.
     *
     * Both are published per creature and both are packed, two experience apart at this tier, so
     * reading the wrong column produces a number that looks entirely reasonable. Asserted against
     * the wiki's own figures - baby 18 (not 20), lucky 80 (not 380) - and against the *absence* of
     * the overworld value, which is the half that actually fails if the columns are swapped.
     */
    @Test
    fun theExperienceAwardedIsThePuroPuroValue() {
        for (entry in listOf(WIKI.first(), WIKI.last())) {
            val world = HunterButterflyTestWorld()
            val player = world.addPlayer(hunterLvl = 99)
            world.wield(player, BUTTERFLY_NET)
            world.giveItem(player, IMPLING_JAR)
            val impling = world.addNpc(entry.npc)
            world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
            val before = player.statMap.getXP("stat.hunter")

            assertTrue(world.runImpling(player) { with(it) { catchImpling(impling) } })

            val gained = player.statMap.getXP("stat.hunter") - before
            assertEquals(entry.puroXp, gained, "${entry.npc} awards ${entry.puroXp} xp in Puro-Puro")
            assertNotEquals(entry.overworldXp, gained, "${entry.npc} must not award the overworld xp")
        }
    }

    /**
     * The Hunter xp modifier is *applied*, not merely injected.
     *
     * Every other world in this suite builds its `XpModifiers` from an empty set, which is a flat
     * 1.0, so the `* xpMods.get(player, "stat.hunter")` on the award site could be deleted with the
     * rest of the suite still green. Running the same catch twice, once in a doubled world, is what
     * makes the multiplication load-bearing.
     */
    @Test
    fun theXpModifierScalesTheImplingAward() {
        val plain = caughtBabyImplingFineXp(hunterXpBonus = 0.0)
        val doubled = caughtBabyImplingFineXp(hunterXpBonus = DOUBLE_HUNTER_XP)

        // The wiki's 18 Puro-Puro xp for a baby impling, in the stat map's tenths.
        assertEquals(180, plain, "unmodified, the baby impling is 18.0 xp")
        assertEquals(360, doubled, "a +100% modifier makes it 36.0")
    }

    /** One successful baby impling catch in Puro-Puro, in tenths of a point. */
    private fun caughtBabyImplingFineXp(hunterXpBonus: Double): Int {
        val world = HunterButterflyTestWorld(hunterXpBonus = hunterXpBonus)
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, IMPLING_JAR)
        val impling = world.addNpc(BABY)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        assertTrue(world.runImpling(player) { with(it) { catchImpling(impling) } })

        return player.statMap.getFineXP("stat.hunter")
    }

    /**
     * The failure branch: nothing is consumed and the impling stays put.
     *
     * Set at the creature's own requirement, where the rate is a real fraction - the baby impling's
     * published curve reads 133/256 at level 17, so the highest legal draw misses it.
     */
    @Test
    fun failedRollConsumesNothingAndLeavesTheImpling() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 17)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, IMPLING_JAR)
        val impling = world.addNpc(BABY)
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW
        val before = player.statMap.getXP("stat.hunter")

        val caught = world.runImpling(player) { with(it) { catchImpling(impling) } }

        assertFalse(caught, "the highest draw should miss 133/256")
        assertEquals(1, world.random.doubleDraws, "it should have reached the roll")
        assertTrue(player.inv.contains(IMPLING_JAR), "a miss must not eat the jar")
        assertFalse(player.inv.contains(BABY_JAR))
        assertEquals(before, player.statMap.getXP("stat.hunter"), "no xp for a miss")
        assertTrue(world.npcIsSpawned(impling), "the impling stays")
    }

    /**
     * A completely full inventory still jars a catch, because the swap is net-zero.
     *
     * Both jars are non-stackable, so deleting the empty one frees exactly the slot the filled one
     * takes. A space check copied from the falconry retrieve would have refused this, and the player
     * would have lost catches for a reason the game never states.
     */
    @Test
    fun aFullInventoryStillJarsTheCatch() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, IMPLING_JAR)
        world.fillInventory(player)
        assertEquals(0, player.inv.freeSpace(), "the case under test needs a full inventory")
        val impling = world.addNpc(BABY)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.runImpling(player) { with(it) { catchImpling(impling) } }

        assertTrue(caught)
        assertTrue(player.inv.contains(BABY_JAR), "the swap needs no free slot")
        assertFalse(player.inv.contains(IMPLING_JAR))
        assertEquals(0, player.inv.freeSpace(), "and it must not have created one either")
    }

    /* The jar rule, which is the one thing that is not butterfly netting */

    /**
     * Inside Puro-Puro, no empty jar refuses the attempt before the roll, net or no net - and the
     * refusal must consume no draw.
     */
    @Test
    fun noEmptyJarRefusesTheAttemptInsidePuroPuroBeforeAnyRoll() {
        for (net in listOf(BUTTERFLY_NET, MAGIC_BUTTERFLY_NET, null)) {
            val world = HunterButterflyTestWorld()
            val player = world.addPlayer(HunterButterflyTestWorld.PURO_PURO_TILE, hunterLvl = 99)
            net?.let { world.wield(player, it) }
            val impling = world.addNpc(BABY, HunterButterflyTestWorld.PURO_PURO_TILE.translateX(1))
            world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
            val before = player.statMap.getXP("stat.hunter")

            val caught = world.runImpling(player) { with(it) { catchImpling(impling) } }

            val how = net ?: "barehanded"
            assertFalse(caught, "$how with no jar is not a catch")
            assertEquals(0, world.random.doubleDraws, "$how with no jar must not roll")
            assertTrue(world.npcIsSpawned(impling), "$how: the impling should be untouched")
            assertFalse(player.inv.contains(BABY_JAR), "$how: nothing to put it in")
            assertEquals(before, player.statMap.getXP("stat.hunter"), "$how: no xp")
        }
    }

    /**
     * Outside Puro-Puro a jarless catch is a catch, and it pays the impling's loot on the spot.
     *
     * *Baby impling* (oldid=15297388): "In Gielinor, they can also be caught barehanded or while
     * wielding any item, with or without an empty impling jar. Without an empty jar the player will
     * immediately receive the loot from the impling, instead of the impling itself." *Impling*
     * (oldid=15303398) says it in one line: "Implings caught without an impling jar will be looted
     * immediately."
     *
     * The eclectic rather than the baby, because the baby's is the one shipped table carrying a
     * `nothing()` slot and "the inventory gained something" would then be a one-in-ten flake. The
     * eclectic pays on every roll, so an empty inventory afterwards means the loot branch never ran.
     *
     * The jar assertions are the pair that says *which* reward arrived: a filled eclectic jar would
     * mean the impling itself was kept, and an empty impling jar could only have come from nowhere.
     */
    @Test
    fun aJarlessCatchOutsidePuroPuroIsLootedOnTheSpot() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        val impling = world.addNpc(ECLECTIC_OVERWORLD)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val before = player.statMap.getXP("stat.hunter")
        val freeBefore = player.inv.freeSpace()

        val caught = world.runImpling(player) { with(it) { catchImpling(impling) } }

        assertTrue(caught, "no jar is no obstacle outside Puro-Puro")
        assertEquals(1, world.random.doubleDraws, "the catch is rolled for like any other")
        assertFalse(world.npcIsSpawned(impling), "the impling is taken")
        assertFalse(player.inv.contains(ECLECTIC_JAR), "the impling is looted, not kept")
        assertFalse(player.inv.contains(IMPLING_JAR), "no jar was involved at either end")
        assertTrue(player.inv.freeSpace() < freeBefore, "the loot must actually arrive")
        assertNotEquals(before, player.statMap.getXP("stat.hunter"), "a catch pays experience")
    }

    /**
     * The whole rule in one test: one creature, one draw, two places, two outcomes.
     *
     * Everything is held fixed except the tile the player is standing on - same npc, same level,
     * same net, same empty inventory, same scripted draw - so the only thing either assertion can
     * be reading is the location. That is the bug this pair exists for: the refusal is Puro-Puro's,
     * and applying it to the rest of Gielinor made every overworld impling uncatchable without a
     * jar the wiki never asked for.
     *
     * The overworld npc form is used on both sides deliberately. *Impling* (oldid=15303398) notes
     * overworld spawns can appear inside Puro-Puro, so this is a creature that really can be met in
     * both places, and it keeps the npc id - which is what decides the **experience** - identical
     * across the two halves.
     */
    @Test
    fun theSameCreatureNeedsAJarInPuroPuroAndNotOutsideIt() {
        val inside = HunterButterflyTestWorld()
        val insidePlayer = inside.addPlayer(HunterButterflyTestWorld.PURO_PURO_TILE, hunterLvl = 99)
        inside.wield(insidePlayer, BUTTERFLY_NET)
        val insideImpling =
            inside.addNpc(
                ECLECTIC_OVERWORLD,
                HunterButterflyTestWorld.PURO_PURO_TILE.translateX(1),
            )
        inside.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val outside = HunterButterflyTestWorld()
        val outsidePlayer = outside.addPlayer(hunterLvl = 99)
        outside.wield(outsidePlayer, BUTTERFLY_NET)
        val outsideImpling = outside.addNpc(ECLECTIC_OVERWORLD)
        outside.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caughtInside =
            inside.runImpling(insidePlayer) { with(it) { catchImpling(insideImpling) } }
        val caughtOutside =
            outside.runImpling(outsidePlayer) { with(it) { catchImpling(outsideImpling) } }

        assertFalse(caughtInside, "Puro-Puro requires the jar")
        assertTrue(inside.npcIsSpawned(insideImpling), "and leaves the impling alone")
        assertTrue(caughtOutside, "the rest of Gielinor does not")
        assertFalse(outside.npcIsSpawned(outsideImpling), "and the impling is taken")
    }

    /**
     * The Prifddinas crystal impling, jarless, which is the case no Puro-Puro rule can ever reach.
     *
     * `npc.ii_impling_type_12_johnny` has a single spawn marker and it is in Prifddinas; there is no
     * maze form of this creature and no maze marker for it. So a jar rule keyed on the player's
     * location leaves it catchable, and a jar rule applied everywhere made the one impling that
     * cannot be caught in Puro-Puro require Puro-Puro's item.
     */
    @Test
    fun theCrystalImplingIsCatchableWithNoJar() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, MAGIC_BUTTERFLY_NET)
        val impling = world.addNpc(CRYSTAL_PRIFDDINAS)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.runImpling(player) { with(it) { catchImpling(impling) } }

        assertTrue(caught, "Prifddinas is not Puro-Puro")
        assertFalse(world.npcIsSpawned(impling))
        assertFalse(player.inv.contains(IMPLING_JAR))
    }

    /**
     * A *filled* jar is not an empty one.
     *
     * The obvious way to get the jar check wrong, and the one that would only show up on the second
     * catch of a trip: `inv.contains` against the wrong obj passes for a player carrying nothing but
     * the jars they have already filled. Asked inside Puro-Puro, because that is the only place the
     * empty jar is a requirement at all.
     */
    @Test
    fun aFilledJarDoesNotCountAsAnEmptyOne() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(HunterButterflyTestWorld.PURO_PURO_TILE, hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, BABY_JAR)
        val impling = world.addNpc(BABY, HunterButterflyTestWorld.PURO_PURO_TILE.translateX(1))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.runImpling(player) { with(it) { catchImpling(impling) } }

        assertFalse(caught)
        assertEquals(0, world.random.doubleDraws)
        assertTrue(player.inv.contains(BABY_JAR), "the filled jar must be untouched")
    }

    /* Level gates - the netted one and the barehanded one */

    /** Under the creature's own level with a net, refused before the roll, costing no draw. */
    @Test
    fun anUnderLevelledAttemptIsRefusedBeforeTheRoll() {
        for (creature in ImplingCreatures.all) {
            val world = HunterButterflyTestWorld()
            val player = world.addPlayer(hunterLvl = creature.level - 1)
            world.wield(player, BUTTERFLY_NET)
            world.giveItem(player, IMPLING_JAR)
            val impling = world.addNpc(creature.npc)
            world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

            val caught = world.runImpling(player) { with(it) { catchImpling(impling) } }

            assertFalse(caught, "${creature.level - 1} cannot net a level-${creature.level} impling")
            assertEquals(0, world.random.doubleDraws, "${creature.npc}: no draw below the gate")
            assertTrue(world.npcIsSpawned(impling))
            assertTrue(player.inv.contains(IMPLING_JAR))
        }
    }

    /**
     * Barehanded costs exactly [HunterButterfly.BAREHANDED_LEVELS] more, on every row.
     *
     * A relationship, asserted against the table rather than against a transcribed number, so it
     * still holds if a level is ever corrected. The interesting level is the one *just* below the
     * threshold: high enough to net the creature, still too low to take it barehanded. A gate that
     * forgot the offset entirely would pass every one of them, and no netted test would notice.
     */
    @Test
    fun barehandedCostsTenLevelsOnEveryImpling() {
        for (creature in ImplingCreatures.all) {
            val threshold = creature.level + HunterButterfly.BAREHANDED_LEVELS

            val below = attemptBarehanded(creature.npc, threshold - 1)
            assertFalse(below.caught, "${creature.npc} at ${threshold - 1} is one level short")
            assertEquals(0, below.draws, "${creature.npc}: a refusal below the gate costs no draw")

            val at = attemptBarehanded(creature.npc, threshold)
            assertTrue(at.caught, "${creature.npc} at $threshold should catch barehanded")
            assertEquals(1, at.draws, "${creature.npc}: it should have reached the roll")
        }
    }

    /**
     * And the threshold itself is the wiki's own number, not merely ten above whatever we packed.
     *
     * *Baby impling* (oldid=15297388): "at least 17 Hunter to capture this type of impling with a
     * butterfly net or a magic butterfly net, or 27 Hunter barehanded." *Eclectic impling*
     * (oldid=15297390): "at least 50 Hunter ... or at least 60 Hunter to capture it barehanded."
     * [barehandedCostsTenLevelsOnEveryImpling] proves the offset is applied; this proves the pair of
     * numbers it lands on is the published one.
     */
    @Test
    fun theBarehandedThresholdIsTheWikisOwnNumber() {
        for ((npc, published) in listOf(BABY to 27, ECLECTIC to 60)) {
            assertFalse(attemptBarehanded(npc, published - 1).caught, "$npc at ${published - 1}")
            assertTrue(attemptBarehanded(npc, published).caught, "$npc at $published")
        }
    }

    /**
     * A net in the *backpack* is not a wielded net.
     *
     * Both nets are `wearpos=righthand` with `iop2=Wield`, so carrying one is barehanded and pays
     * the ten levels. Reading the wrong inventory is the single most likely way to get this wrong,
     * and it is invisible in game to anyone who always wields their net.
     */
    @Test
    fun aNetInTheBackpackIsStillBarehanded() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 17)
        world.giveItem(player, BUTTERFLY_NET)
        world.giveItem(player, IMPLING_JAR)
        val impling = world.addNpc(BABY)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.runImpling(player) { with(it) { catchImpling(impling) } }

        assertFalse(caught, "a carried net does not net anything")
        assertEquals(0, world.random.doubleDraws)
    }

    /* The faster curve, at the roll */

    /**
     * The magic net catches where the plain net misses, at the same level and on the same draw.
     *
     * The baby impling's two published series read 133/256 and 153/256 at its requirement, so a draw
     * between them separates the curves exactly. This is the only assertion that proves the bonus is
     * wired to the roll rather than merely defined.
     */
    @Test
    fun theMagicNetCatchesWhereThePlainNetMisses() {
        val betweenTheTwoCurves = 143.0 / 256.0

        val plain = attemptWithNet(BUTTERFLY_NET, level = 17, draw = betweenTheTwoCurves)
        val magic = attemptWithNet(MAGIC_BUTTERFLY_NET, level = 17, draw = betweenTheTwoCurves)

        assertFalse(plain, "133/256 loses to a 143/256 draw")
        assertTrue(magic, "153/256 beats it")
    }

    /**
     * Barehanded is on the faster curve too - the same draw, no net at all.
     *
     * *Impling* (oldid=15303398): "Grabbing an impling with your bare hands has the same success
     * rate as attempting to catch one with a magic butterfly net." At level 27, the baby impling's
     * two series read 166/256 and 186/256, so a 176/256 draw separates them.
     */
    @Test
    fun barehandedIsOnTheFasterCurve() {
        val betweenTheTwoCurves = 176.0 / 256.0

        val plain = attemptWithNet(BUTTERFLY_NET, level = 27, draw = betweenTheTwoCurves)
        val barehanded = attemptWithNet(null, level = 27, draw = betweenTheTwoCurves)

        assertFalse(plain, "166/256 loses to a 176/256 draw")
        assertTrue(barehanded, "186/256 beats it")
    }

    /* Not a shipped impling */

    /**
     * An impling npc with no row is simply not handled, and nothing is consumed.
     *
     * It has to be inert rather than half-working: no swing, no draw, no jar taken.
     */
    @Test
    fun theWanderingImplingIsNotCatchable() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, IMPLING_JAR)
        // `ii_lost_impling` (5737) is the "Wandering impling" in the Zanaris wheat field. It is
        // an impling by name and model and carries `Talk-to`/`Check-gates`, not `Catch` - the wiki
        // says outright that he cannot be caught. The nearest thing to a false positive this table
        // can produce.
        val wandering = world.addNpc("npc.ii_lost_impling")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.runImpling(player) { with(it) { catchImpling(wandering) } }

        assertFalse(caught)
        assertEquals(0, world.random.doubleDraws)
        assertTrue(world.npcIsSpawned(wandering))
        assertTrue(player.inv.contains(IMPLING_JAR))
    }

    /** A butterfly is not an impling, and neither technique may claim the other's creatures. */
    @Test
    fun aButterflyIsNotCaughtByTheImplingHandler() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, IMPLING_JAR)
        val warlock = world.addNpc("npc.butterfly_warlock")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        assertFalse(world.runImpling(player) { with(it) { catchImpling(warlock) } })
        assertEquals(0, world.random.doubleDraws)
        assertTrue(world.npcIsSpawned(warlock))
    }

    /* Fixtures. */

    private data class Attempt(val caught: Boolean, val draws: Int)

    /** One barehanded attempt at [level], with an empty jar and the kindest possible draw. */
    private fun attemptBarehanded(npc: String, level: Int): Attempt {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = level)
        world.giveItem(player, IMPLING_JAR)
        val impling = world.addNpc(npc)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val caught = world.runImpling(player) { with(it) { catchImpling(impling) } }
        return Attempt(caught, world.random.doubleDraws)
    }

    /** One baby-impling attempt with [net] wielded (or nothing, for barehanded) on a fixed [draw]. */
    private fun attemptWithNet(net: String?, level: Int, draw: Double): Boolean {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = level)
        net?.let { world.wield(player, it) }
        world.giveItem(player, IMPLING_JAR)
        val impling = world.addNpc(BABY)
        world.random.nextDouble = draw
        return world.runImpling(player) { with(it) { catchImpling(impling) } }
    }

    /** The `{{Skilling success chart}}` arguments, keyed by page and series label. */
    private fun readParams(): Map<String, PublishedParams> =
        checkNotNull(javaClass.getResourceAsStream("/wiki-charts/published-params.tsv")) {
                "Missing /wiki-charts/published-params.tsv"
            }
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val f = line.split("\t")
                require(f.size == 6) { "Malformed params row: $line" }
                "${f[0]}|${f[2]}" to PublishedParams(f[3].toInt(), f[4].toInt(), f[5].toInt())
            }

    private data class PublishedParams(val low: Int, val high: Int, val req: Int)

    /** One creature's published figures: its wiki page, its level and its two experience values. */
    private data class WikiImpling(
        val npc: String,
        val page: String,
        val level: Int,
        val puroXp: Int,
        val overworldXp: Int,
        val jar: String,
    )

    companion object {
        private const val BABY = "npc.ii_impling_type_1_maze"
        private const val BABY_JAR = "obj.ii_captured_impling_1"
        private const val ECLECTIC = "npc.ii_impling_type_6_maze"

        /** The eclectic's overworld form, and the filled jar both forms swap for. */
        private const val ECLECTIC_OVERWORLD = "npc.ii_impling_type_6"

        private const val ECLECTIC_JAR = "obj.ii_captured_impling_6"

        /** The Prifddinas crystal impling, which has no Puro-Puro form at all. */
        private const val CRYSTAL_PRIFDDINAS = "npc.ii_impling_type_12_johnny"

        /**
         * The six shipped implings as the wiki publishes them.
         *
         * Levels and both experience values are the `Hunter info` infobox's "Level required", "Puro
         * Puro XP" and "Overworld XP" rows, read from the offline snapshot 20260817. Oldids, in
         * order: 15297388, 15297391, 15297393, 15297396, 15297398, 15297390. The page titles are
         * also the keys into `published-params.tsv`.
         */
        private val WIKI =
            listOf(
                WikiImpling(BABY, "Baby impling", 17, 18, 20, BABY_JAR),
                WikiImpling(
                    "npc.ii_impling_type_2_maze",
                    "Young impling",
                    22,
                    20,
                    22,
                    "obj.ii_captured_impling_2",
                ),
                WikiImpling(
                    "npc.ii_impling_type_3_maze",
                    "Gourmet impling",
                    28,
                    22,
                    24,
                    "obj.ii_captured_impling_3",
                ),
                WikiImpling(
                    "npc.ii_impling_type_4_maze",
                    "Earth impling",
                    36,
                    25,
                    27,
                    "obj.ii_captured_impling_4",
                ),
                WikiImpling(
                    "npc.ii_impling_type_5_maze",
                    "Essence impling",
                    42,
                    27,
                    29,
                    "obj.ii_captured_impling_5",
                ),
                WikiImpling(ECLECTIC, "Eclectic impling", 50, 30, 32, "obj.ii_captured_impling_6"),
                WikiImpling(
                    "npc.ii_impling_type_7_maze",
                    "Nature impling",
                    58,
                    34,
                    36,
                    "obj.ii_captured_impling_7",
                ),
                WikiImpling(
                    "npc.ii_impling_type_8_maze",
                    "Magpie impling",
                    65,
                    44,
                    216,
                    "obj.ii_captured_impling_8",
                ),
                WikiImpling(
                    "npc.ii_impling_type_9_maze",
                    "Ninja impling",
                    74,
                    50,
                    240,
                    "obj.ii_captured_impling_9",
                ),
                WikiImpling(
                    "npc.ii_impling_type_12_johnny",
                    "Crystal impling",
                    80,
                    280,
                    280,
                    "obj.ii_captured_impling_12",
                ),
                WikiImpling(
                    "npc.ii_impling_type_10_maze",
                    "Dragon impling",
                    83,
                    65,
                    300,
                    "obj.ii_captured_impling_10",
                ),
                WikiImpling(
                    "npc.ii_impling_type_11_maze",
                    "Lucky impling",
                    89,
                    80,
                    380,
                    "obj.ii_captured_impling_11",
                ),
            )

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }
    }
}
