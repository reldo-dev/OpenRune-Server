package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
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
import org.rsmod.content.skills.hunter.HunterBirdHouse.Companion.CHISEL
import org.rsmod.content.skills.hunter.HunterBirdHouse.Companion.CLOCKWORK
import org.rsmod.content.skills.hunter.HunterBirdHouse.Companion.HAMMER

/**
 * Making a bird house: a clockwork on logs, with a chisel and a hammer.
 *
 * **This is the technique's entry point**: nothing else in the game hands out a bird house, so
 * without a craft `Build` has nothing to place. Every expectation below is pinned to a **literal
 * read off the wiki**, never to the column it is testing: [WIKI] is transcribed from
 * *Crafting#Birdhouses*, and each of its nine rows was cross-checked against a second page that
 * repeats the same figure independently - *Clockwork#Products* for the normal, maple, mahogany
 * and magic tiers and *Chisel#Usage* for willow and yew. A test that read `type.craftingXp` back
 * would move with a mutation instead of catching it.
 *
 * Serialised for the reason the rest of the suite is - `ServerCacheManager` is a singleton and
 * `RSCM` memoises into a plain `HashMap`.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class BirdHouseCraftTest {
    /* The recipe */

    @Test
    fun `every tier is carved from its own logs and one clockwork`() {
        for (entry in WIKI) {
            val world = HunterBirdHouseTestWorld()
            val player = world.addPlayer(craftingLvl = entry.craftingLevel)
            val type = entry.type()
            world.giveCraftingKit(player, type)

            assertTrue(world.craft(player, type), "${entry.obj} at ${entry.craftingLevel}")

            assertEquals(1, world.itemCount(player, entry.obj), "${entry.obj} is in the backpack")
            assertEquals(0, world.itemCount(player, entry.logs), "${entry.logs} was spent")
            assertEquals(0, world.itemCount(player, CLOCKWORK), "the clockwork was spent")
        }
    }

    @Test
    fun `both tools are held and neither is spent`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        val type = NORMAL.type()
        world.giveCraftingKit(player, type, houses = 3)

        repeat(3) { assertTrue(world.craft(player, type)) }

        assertEquals(3, world.itemCount(player, NORMAL.obj), "three houses")
        assertEquals(1, world.itemCount(player, CHISEL), "the chisel survives every craft")
        assertEquals(1, world.itemCount(player, HAMMER), "and so does the hammer")
    }

    @Test
    fun `a craft with no chisel makes nothing and spends nothing`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        val type = NORMAL.type()
        world.giveItem(player, HAMMER)
        world.giveItem(player, type.logs)
        world.giveItem(player, CLOCKWORK)

        assertFalse(world.craft(player, type), "a hammer alone is not enough")

        assertEquals(0, world.itemCount(player, NORMAL.obj))
        assertEquals(1, world.itemCount(player, type.logs), "the logs are untouched")
        assertEquals(1, world.itemCount(player, CLOCKWORK), "and so is the clockwork")
    }

    @Test
    fun `a craft with no hammer makes nothing and spends nothing`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        val type = NORMAL.type()
        world.giveItem(player, CHISEL)
        world.giveItem(player, type.logs)
        world.giveItem(player, CLOCKWORK)

        assertFalse(world.craft(player, type), "a chisel alone is not enough")

        assertEquals(0, world.itemCount(player, NORMAL.obj))
        assertEquals(1, world.itemCount(player, type.logs), "the logs are untouched")
        assertEquals(1, world.itemCount(player, CLOCKWORK), "and so is the clockwork")
    }

    @Test
    fun `a craft with no clockwork makes nothing and spends the logs on nothing`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        val type = NORMAL.type()
        world.giveCraftingTools(player)
        world.giveItem(player, type.logs)

        assertFalse(world.craft(player, type), "logs alone are not enough")

        assertEquals(0, world.itemCount(player, NORMAL.obj))
        assertEquals(1, world.itemCount(player, type.logs), "the logs are untouched")
    }

    @Test
    fun `another tier's logs will not make this tier`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.giveCraftingTools(player)
        world.giveItem(player, CLOCKWORK)
        world.giveItem(player, "obj.oak_logs")

        assertFalse(world.craft(player, NORMAL.type()), "oak logs are not logs")

        assertEquals(1, world.itemCount(player, "obj.oak_logs"), "the oak logs are untouched")
        assertEquals(1, world.itemCount(player, CLOCKWORK), "and so is the clockwork")
    }

    @Test
    fun `the animation and both tools are packed, not merely resolvable`() {
        // An RSCM name resolves to an id whether or not anything is behind it, so each is looked up
        // in the cache as well. The three ids are read off the decoded dump, which is a source
        // outside this module: `[birdhouse_make]` is seq 7057 and names its own materials
        // (`replaceheldright=hammer`, `replaceheldleft=logs`).
        val seq = HunterBirdHouse.BIRDHOUSE_MAKE_SEQ.asRSCM(RSCMType.SEQ)
        assertEquals(7057, seq, "seq.birdhouse_make is 7057")
        assertNotNull(ServerCacheManager.getAnim(seq), "and 7057 is packed")

        assertNotNull(ServerCacheManager.getItem(CHISEL.asRSCM(RSCMType.OBJ)), "chisel is packed")
        assertNotNull(ServerCacheManager.getItem(HAMMER.asRSCM(RSCMType.OBJ)), "hammer is packed")
        assertNotNull(
            ServerCacheManager.getItem(CLOCKWORK.asRSCM(RSCMType.OBJ)),
            "obj.poh_clockwork_mechanism is packed",
        )
    }

    /* The level gate */

    @Test
    fun `every tier refuses one Crafting level below the wiki's requirement`() {
        for (entry in WIKI) {
            val world = HunterBirdHouseTestWorld()
            val player = world.addPlayer(craftingLvl = entry.craftingLevel - 1)
            val type = entry.type()
            world.giveCraftingKit(player, type)

            assertFalse(
                world.craft(player, type),
                "${entry.obj} needs Crafting ${entry.craftingLevel}",
            )
            assertEquals(0, world.itemCount(player, entry.obj), "nothing was made")
            assertEquals(1, world.itemCount(player, entry.logs), "and nothing was spent")
        }
    }

    @Test
    fun `the Hunter level does not gate a craft`() {
        // Only *placing* a bird house reads the Hunter level; the wiki lists Crafting alone as the
        // requirement to make one ("Crafting 5-90 if you wish to make your own houses").
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer(hunterLvl = 1, craftingLvl = REDWOOD.craftingLevel)
        val type = REDWOOD.type()
        world.giveCraftingKit(player, type)

        assertTrue(world.craft(player, type), "Hunter 1 still makes a redwood bird house")
        assertEquals(1, world.itemCount(player, REDWOOD.obj))
    }

    @Test
    fun `the refusal is readable before the menu opens`() {
        // `openSkillMulti` returns silently when nothing is affordable, so the events script has to
        // know why before it opens or the player sees no response at all.
        val world = HunterBirdHouseTestWorld()
        val short = world.addPlayer(craftingLvl = NORMAL.craftingLevel - 1)
        val ready = world.addPlayer(craftingLvl = NORMAL.craftingLevel)
        val type = NORMAL.type()
        world.giveCraftingKit(short, type)
        world.giveCraftingKit(ready, type)

        val refusal = world.craftRefusal(short, type)
        assertNotNull(refusal, "a level-short player is refused")
        assertTrue(
            refusal!!.contains(NORMAL.craftingLevel.toString()),
            "and the message names the level it wants: $refusal",
        )
        assertNull(world.craftRefusal(ready, type), "a ready player is not")
    }

    /* Experience */

    @Test
    fun `every tier awards the wiki's Crafting experience and no Hunter experience`() {
        for (entry in WIKI) {
            val world = HunterBirdHouseTestWorld()
            val player = world.addPlayer(craftingLvl = entry.craftingLevel)
            val type = entry.type()
            world.giveCraftingKit(player, type)

            assertTrue(world.craft(player, type))

            // The stat map stores fine experience in tenths of a point.
            assertEquals(
                entry.craftingXp * 10,
                player.statMap.getFineXP("stat.crafting"),
                "${entry.obj} pays ${entry.craftingXp} Crafting xp",
            )
            assertEquals(
                0,
                player.statMap.getFineXP("stat.hunter"),
                "${entry.obj} pays no Hunter xp - that belongs to the harvest",
            )
        }
    }

    @Test
    fun `the xp modifier scales the crafting award`() {
        // The award site multiplies by `xpMods.get(player, "stat.crafting")`. Every other world in
        // this suite carries a `stat.hunter` bonus only, which leaves that multiply at 1.0 and would
        // let it be deleted with the suite still green.
        val plain = craftedNormalHouseFineXp(craftingXpBonus = 0.0)
        val doubled = craftedNormalHouseFineXp(craftingXpBonus = DOUBLE_HUNTER_XP)

        // The wiki's 15 xp for a normal bird house, in the stat map's tenths.
        assertEquals(150, plain, "unmodified, a normal bird house is 15.0 xp")
        assertEquals(300, doubled, "and twice that with a +100% modifier")
    }

    /* Building out of materials */

    @Test
    fun `building makes a house out of materials when none is carried`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer(craftingLvl = NORMAL.craftingLevel)
        val space = HunterBirdHouseTestWorld.FIRST_SPACE
        val type = NORMAL.type()
        world.giveCraftingKit(player, type)

        assertTrue(world.build(player, space), "the space is built")

        assertEquals(type.builtState, world.stateOf(player, space), "and it shows a normal house")
        assertEquals(0, world.itemCount(player, entryLogs(NORMAL)), "the logs went into it")
        assertEquals(0, world.itemCount(player, CLOCKWORK), "and so did the clockwork")
        assertEquals(0, world.itemCount(player, NORMAL.obj), "nothing is left over")
        assertEquals(150, player.statMap.getFineXP("stat.crafting"), "the craft still paid its xp")
    }

    @Test
    fun `building prefers a carried house and leaves the materials alone`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        val space = HunterBirdHouseTestWorld.FIRST_SPACE
        val type = NORMAL.type()
        world.giveCraftingKit(player, type)
        world.giveItem(player, NORMAL.obj)

        assertTrue(world.build(player, space))

        assertEquals(0, world.itemCount(player, NORMAL.obj), "the ready-made house was placed")
        assertEquals(1, world.itemCount(player, entryLogs(NORMAL)), "the logs stayed in the bag")
        assertEquals(1, world.itemCount(player, CLOCKWORK), "and so did the clockwork")
        assertEquals(0, player.statMap.getFineXP("stat.crafting"), "and no craft happened")
    }

    @Test
    fun `building from materials picks the best tier both levels allow`() {
        // Maple needs Hunter 44 and Crafting 45; mahogany needs Hunter 49. At Hunter 44 the mahogany
        // logs are skipped even though the Crafting level would make them, because a house made here
        // is placed in the same action.
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer(hunterLvl = 44, craftingLvl = 99)
        val space = HunterBirdHouseTestWorld.FIRST_SPACE
        world.giveCraftingTools(player)
        world.giveItem(player, CLOCKWORK)
        world.giveItem(player, "obj.maple_logs")
        world.giveItem(player, "obj.mahogany_logs")

        assertTrue(world.build(player, space))

        val maple = checkNotNull(BirdHouseTypes.byObj("obj.birdhouse_maple"))
        assertEquals(maple.builtState, world.stateOf(player, space), "a maple house was placed")
        assertEquals(0, world.itemCount(player, "obj.maple_logs"), "the maple logs were spent")
        assertEquals(1, world.itemCount(player, "obj.mahogany_logs"), "the mahogany logs were not")
    }

    @Test
    fun `resetting rebuilds out of the clockwork the payout just returned`() {
        // Published: `Reset` places a fresh house "reusing the clockwork mechanism from the previous
        // birdhouse". The player carries logs and tools but *no* clockwork - the only one available
        // is the one the harvest hands back.
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer(craftingLvl = NORMAL.craftingLevel)
        val space = HunterBirdHouseTestWorld.FIRST_SPACE
        val type = NORMAL.type()
        world.setState(player, space, type.birdState)
        world.giveCraftingTools(player)
        world.giveItem(player, type.logs)

        assertTrue(world.empty(player, space, rebuild = true), "the full house pays out")

        assertEquals(type.builtState, world.stateOf(player, space), "and a fresh one replaces it")
        assertEquals(0, world.itemCount(player, type.logs), "the logs went into the replacement")
        assertEquals(0, world.itemCount(player, CLOCKWORK), "and the returned clockwork with them")
    }

    @Test
    fun `building with neither a house nor materials still refuses`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        val space = HunterBirdHouseTestWorld.FIRST_SPACE
        world.giveCraftingTools(player)
        world.giveItem(player, "obj.logs")
        // No clockwork.

        assertFalse(world.build(player, space), "logs and tools alone build nothing")
        assertEquals(BirdHouseSpaces.BARE, world.stateOf(player, space), "the space is still bare")
        assertEquals(1, world.itemCount(player, "obj.logs"), "and the logs are untouched")
    }

    private fun craftedNormalHouseFineXp(craftingXpBonus: Double): Int {
        val world = HunterBirdHouseTestWorld(craftingXpBonus = craftingXpBonus)
        val player = world.addPlayer(craftingLvl = NORMAL.craftingLevel)
        val type = NORMAL.type()
        world.giveCraftingKit(player, type)
        check(world.craft(player, type)) { "The craft should have succeeded." }
        return player.statMap.getFineXP("stat.crafting")
    }

    private fun entryLogs(entry: WikiCraft): String = entry.logs

    private fun WikiCraft.type(): BirdHouseType =
        checkNotNull(BirdHouseTypes.byObj(obj)) { "No row for $obj" }

    /** One row of the wiki's *Crafting#Birdhouses* table. */
    data class WikiCraft(
        val obj: String,
        val logs: String,
        val craftingLevel: Int,
        val craftingXp: Int,
    )

    private companion object {
        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }

        /**
         * *Crafting#Birdhouses*, transcribed: "A clockwork and the appropriate logs with a chisel
         * and hammer are required to craft these items."
         *
         * The nine `(level, xp)` pairs run 5/15 to 90/55. Six of the nine are repeated verbatim on a
         * second page - *Clockwork#Products* lists normal 5/15, maple 45/35, mahogany 50/40 and
         * magic 75/50, and *Chisel#Usage* lists willow 25/25 and yew 60/45 - so most of this table
         * is two sources agreeing rather than one read twice.
         */
        private val WIKI =
            listOf(
                WikiCraft("obj.birdhouse_normal", "obj.logs", 5, 15),
                WikiCraft("obj.birdhouse_oak", "obj.oak_logs", 15, 20),
                WikiCraft("obj.birdhouse_willow", "obj.willow_logs", 25, 25),
                WikiCraft("obj.birdhouse_teak", "obj.teak_logs", 35, 30),
                WikiCraft("obj.birdhouse_maple", "obj.maple_logs", 45, 35),
                WikiCraft("obj.birdhouse_mahogany", "obj.mahogany_logs", 50, 40),
                WikiCraft("obj.birdhouse_yew", "obj.yew_logs", 60, 45),
                WikiCraft("obj.birdhouse_magic", "obj.magic_logs", 75, 50),
                WikiCraft("obj.birdhouse_redwood", "obj.redwood_logs", 90, 55),
            )

        private val NORMAL = WIKI.first()

        private val REDWOOD = WIKI.last()
    }
}
