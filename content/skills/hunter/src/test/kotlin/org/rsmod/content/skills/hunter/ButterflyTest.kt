package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * Butterfly netting, driven in-process.
 *
 * Cases are chosen the way the trap and falconry suites' are: **what a live client cannot force.**
 * A failed catch is a rare event at a sensible level and impossible to reproduce on demand; the
 * barehanded gate needs an account parked at exactly the wrong level with nothing wielded; and the
 * two branches that matter most - [jarlessCatchStillAwardsXpButNoJar] and
 * [aFullInventoryStillJarsTheCatch] - both assert something whose *absence* looks identical in game
 * to its presence unless you are watching the xp counter to the decimal.
 */
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class ButterflyTest {
    /* Catching */

    @Test
    fun successfulCatchJarsTheButterflyAndAwardsXp() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, BUTTERFLY_JAR)
        val butterfly = world.addNpc("npc.butterfly_warlock")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val before = player.statMap.getXP("stat.hunter")

        val caught = world.run(player) { with(it) { catchButterfly(butterfly) } }

        assertTrue(caught)
        assertTrue(player.inv.contains("obj.butterfly_jar_warlock"), "should hold the filled jar")
        assertFalse(player.inv.contains(BUTTERFLY_JAR), "the empty jar is consumed")
        assertFalse(world.npcIsSpawned(butterfly), "the butterfly should be gone")
        // Stored x10, awarded once divided. Unlike every trap, xp lands on the catch itself -
        // there is nothing left to collect it from.
        assertEquals(54, player.statMap.getXP("stat.hunter") - before, "black warlock awards 54 xp")
    }

    /**
     * The Hunter xp modifier is *applied*, not merely injected.
     *
     * Every world in this suite built its `XpModifiers` from an empty set, which is a flat 1.0, so
     * the `* xpMods.get(player, "stat.hunter")` on the award site could be deleted with all 481
     * tests still green. Running the same catch twice, once in a doubled world, is what makes the
     * multiplication load-bearing.
     */
    @Test
    fun theXpModifierScalesTheButterflyAward() {
        val plain = nettedWarlockFineXp(hunterXpBonus = 0.0)
        val doubled = nettedWarlockFineXp(hunterXpBonus = DOUBLE_HUNTER_XP)

        // Fine xp is tenths of a point, so the black warlock's pinned 54 reads as 540 here.
        assertEquals(540, plain, "unmodified, the black warlock is 54.0 xp")
        assertEquals(1080, doubled, "a +100% modifier makes it 108.0")
    }

    /** One successful black warlock catch, in tenths of a point. */
    private fun nettedWarlockFineXp(hunterXpBonus: Double): Int {
        val world = HunterButterflyTestWorld(hunterXpBonus = hunterXpBonus)
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, BUTTERFLY_JAR)
        val butterfly = world.addNpc("npc.butterfly_warlock")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        assertTrue(world.run(player) { with(it) { catchButterfly(butterfly) } })

        return player.statMap.getFineXP("stat.hunter")
    }

    /**
     * The failure branch: nothing is consumed and the butterfly stays put.
     *
     * Set at the creature's own requirement level, where the rate is a real fraction - at 99 the
     * curve is above any legal draw and cannot miss.
     */
    @Test
    fun failedCatchConsumesNothingAndLeavesTheButterfly() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 45)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, BUTTERFLY_JAR)
        val butterfly = world.addNpc("npc.butterfly_warlock")
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW
        val before = player.statMap.getXP("stat.hunter")

        val caught = world.run(player) { with(it) { catchButterfly(butterfly) } }

        assertFalse(caught, "highest draw should miss at the requirement level")
        assertTrue(player.inv.contains(BUTTERFLY_JAR), "a miss must not eat the jar")
        assertFalse(player.inv.contains("obj.butterfly_jar_warlock"))
        assertEquals(before, player.statMap.getXP("stat.hunter"), "no xp for a miss")
        // "Multiple players may attempt to catch a given butterfly at the same time" - a failed
        // attempt does not consume the creature.
        assertTrue(world.npcIsSpawned(butterfly), "the butterfly stays")
    }

    /**
     * A jarless catch is a *successful* catch: xp is awarded, the creature is taken, no item.
     *
     * This is the branch the wiki describes as applying the butterfly's combat boost, which is not
     * modelled. What must not happen is the catch being refused, or silently paying out an item the
     * player had no jar for. Both would look plausible in game.
     */
    @Test
    fun jarlessCatchStillAwardsXpButNoJar() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        val butterfly = world.addNpc("npc.butterfly_ruby")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val before = player.statMap.getXP("stat.hunter")

        val caught = world.run(player) { with(it) { catchButterfly(butterfly) } }

        assertTrue(caught, "no jar is not a refusal")
        assertFalse(player.inv.contains("obj.butterfly_jar_ruby"), "nothing to put it in")
        assertEquals(24, player.statMap.getXP("stat.hunter") - before, "ruby harvest awards 24 xp")
        assertFalse(world.npcIsSpawned(butterfly))
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
        world.giveItem(player, BUTTERFLY_JAR)
        world.fillInventory(player)
        assertEquals(0, player.inv.freeSpace(), "the case under test needs a full inventory")
        val butterfly = world.addNpc("npc.butterfly_snowy")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.run(player) { with(it) { catchButterfly(butterfly) } }

        assertTrue(caught)
        assertTrue(player.inv.contains("obj.butterfly_jar_snowy"), "the swap needs no free slot")
        assertFalse(player.inv.contains(BUTTERFLY_JAR))
        assertEquals(0, player.inv.freeSpace(), "and it must not have created one either")
    }

    /**
     * A full inventory and *no* jar is still a catch, and must not drop anything on the floor.
     *
     * The one case where a full inventory could plausibly have produced a ground obj; it does not,
     * because there is no reward to award.
     */
    @Test
    fun aFullInventoryWithNoJarIsStillACatch() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        world.fillInventory(player)
        val butterfly = world.addNpc("npc.butterfly_glacialis")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val before = player.statMap.getXP("stat.hunter")

        val caught = world.run(player) { with(it) { catchButterfly(butterfly) } }

        assertTrue(caught)
        assertEquals(34, player.statMap.getXP("stat.hunter") - before)
        assertFalse(player.inv.contains("obj.butterfly_jar_glacialis"))
    }

    /* Level gates - the netted one and the barehanded one */

    /** Under the creature's level with a net, refused before the roll, costing no draw. */
    @Test
    fun nettedLevelGateRefusesBeforeTheRoll() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 44)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, BUTTERFLY_JAR)
        val butterfly = world.addNpc("npc.butterfly_warlock")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.run(player) { with(it) { catchButterfly(butterfly) } }

        assertFalse(caught, "level 44 cannot net a level-45 black warlock")
        assertEquals(0, world.random.doubleDraws, "an under-levelled attempt must not roll")
        assertTrue(world.npcIsSpawned(butterfly), "the butterfly should be untouched")
        assertTrue(player.inv.contains(BUTTERFLY_JAR))
    }

    /**
     * Barehanded costs ten levels, and 44 is not the only number that fails.
     *
     * The interesting level is 45-to-54: high enough to net the creature, still too low to take it
     * barehanded. A gate that forgot the `+10` would pass every one of them, and no netted test
     * would notice.
     */
    @Test
    fun barehandedLevelGateCostsTenLevels() {
        for (level in listOf(45, 50, 54)) {
            val world = HunterButterflyTestWorld()
            val player = world.addPlayer(hunterLvl = level)
            world.giveItem(player, BUTTERFLY_JAR)
            val butterfly = world.addNpc("npc.butterfly_warlock")
            world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

            val caught = world.run(player) { with(it) { catchButterfly(butterfly) } }

            assertFalse(caught, "level $level should not catch a black warlock barehanded")
            assertEquals(0, world.random.doubleDraws, "level $level must not roll")
            assertTrue(world.npcIsSpawned(butterfly))
        }
    }

    /** And at requirement + 10 it works, with no net wielded at all. */
    @Test
    fun barehandedSucceedsAtTenLevelsAbove() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 55)
        world.giveItem(player, BUTTERFLY_JAR)
        val butterfly = world.addNpc("npc.butterfly_warlock")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.run(player) { with(it) { catchButterfly(butterfly) } }

        assertTrue(caught)
        assertEquals(1, world.random.doubleDraws, "it should have reached the roll")
        assertTrue(player.inv.contains("obj.butterfly_jar_warlock"))
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
        val player = world.addPlayer(hunterLvl = 45)
        world.giveItem(player, BUTTERFLY_NET)
        world.giveItem(player, BUTTERFLY_JAR)
        val butterfly = world.addNpc("npc.butterfly_warlock")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.run(player) { with(it) { catchButterfly(butterfly) } }

        assertFalse(caught, "a carried net does not net anything")
        assertEquals(0, world.random.doubleDraws)
    }

    /* The faster curve */

    /**
     * The magic net catches where the plain net misses, at the same level and on the same draw.
     *
     * The `+20` is worth 0.078 of a draw at the black warlock's requirement - 165/256 against
     * 145/256 - so a draw between the two separates them exactly. This is the only assertion that
     * proves the bonus is wired to the roll rather than merely defined.
     */
    @Test
    fun theMagicNetCatchesWhereThePlainNetMisses() {
        val betweenTheTwoCurves = 155.0 / 256.0

        val plain = HunterButterflyTestWorld()
        val plainPlayer = plain.addPlayer(hunterLvl = 45)
        plain.wield(plainPlayer, BUTTERFLY_NET)
        plain.random.nextDouble = betweenTheTwoCurves
        val plainCaught =
            plain.run(plainPlayer) { with(it) { catchButterfly(plain.addNpc("npc.butterfly_warlock")) } }

        val magic = HunterButterflyTestWorld()
        val magicPlayer = magic.addPlayer(hunterLvl = 45)
        magic.wield(magicPlayer, MAGIC_BUTTERFLY_NET)
        magic.random.nextDouble = betweenTheTwoCurves
        val magicCaught =
            magic.run(magicPlayer) { with(it) { catchButterfly(magic.addNpc("npc.butterfly_warlock")) } }

        assertFalse(plainCaught, "145/256 loses to a 155/256 draw")
        assertTrue(magicCaught, "165/256 beats it")
    }

    /** Barehanded is on the faster curve too - the same draw, no net at all, ten levels higher. */
    @Test
    fun barehandedIsOnTheFasterCurve() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 45)
        val butterfly = world.addNpc("npc.butterfly_snowy")
        // Snowy knight requires 35, so level 45 is exactly its barehanded requirement. Its plain-net
        // chance there is 145/256 and its barehanded chance 165/256, the same split as above.
        world.random.nextDouble = 155.0 / 256.0

        val caught = world.run(player) { with(it) { catchButterfly(butterfly) } }

        assertTrue(caught, "barehanded should be on the +20 curve")
    }

    /* Not a butterfly */

    /** An npc with no butterfly row is simply not handled, and nothing is consumed. */
    @Test
    fun aNonButterflyNpcIsNotCaught() {
        val world = HunterButterflyTestWorld()
        val player = world.addPlayer(hunterLvl = 99)
        world.wield(player, BUTTERFLY_NET)
        world.giveItem(player, BUTTERFLY_JAR)
        // The moonlight moth: a real butterfly npc with a real jar and a published chart, and no
        // row here, because it has zero spawns. It must be inert rather than half-working.
        val moth = world.addNpc("npc.moth_moonlight")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val caught = world.run(player) { with(it) { catchButterfly(moth) } }

        assertFalse(caught)
        assertEquals(0, world.random.doubleDraws)
        assertTrue(world.npcIsSpawned(moth))
        assertTrue(player.inv.contains(BUTTERFLY_JAR))
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }
    }
}
