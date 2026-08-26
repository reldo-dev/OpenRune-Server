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
import org.rsmod.api.repo.loc.LocRepository
import org.rsmod.content.skills.hunter.HunterBirdHouseTestWorld.Companion.FIRST_SPACE
import org.rsmod.content.skills.hunter.HunterBirdHouseTestWorld.Companion.HIGH_SEED
import org.rsmod.content.skills.hunter.HunterBirdHouseTestWorld.Companion.LAST_SPACE
import org.rsmod.content.skills.hunter.HunterBirdHouseTestWorld.Companion.LOW_SEED
import org.rsmod.content.skills.hunter.HunterBirdHouseTestWorld.Companion.NORMAL
import org.rsmod.content.skills.hunter.HunterBirdHouseTestWorld.Companion.OTHER_LOW_SEED
import org.rsmod.content.skills.hunter.HunterBirdHouseTestWorld.Companion.REDWOOD

/**
 * The bird house lifecycle: build, seed, fill, harvest.
 *
 * The interesting half of this technique is the **timer**, and it is the first thing on this branch
 * that runs on wall-clock time rather than on the map clock. Every assertion about it therefore winds
 * [WoundBirdHouseClock] rather than stepping cycles, which is also the only way to test the case that
 * matters most: fifty minutes passing while the player is not there.
 *
 * Serialised for the reason the rest of the suite is - `ServerCacheManager` is a singleton and `RSCM`
 * memoises into a plain `HashMap`.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class BirdHouseTest {
    /* Build */

    @Test
    fun `building places the carried bird house and leaves the space unseeded`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, NORMAL.obj)

        assertTrue(world.build(player, FIRST_SPACE))

        assertEquals(NORMAL.builtState, world.stateOf(player, FIRST_SPACE))
        assertEquals(0, world.itemCount(player, NORMAL.obj), "the house leaves the inventory")
        assertEquals(0, world.seedUnits(player, FIRST_SPACE), "a fresh house holds no seeds")
        assertEquals(0, world.readyAt(player, FIRST_SPACE), "an unseeded house has no deadline")
    }

    /**
     * "Bird houses now have a left click/tap 'build' option which will erect the **best** bird house
     * in your inventory" (18 April 2019), where "best" is bounded by the Hunter level - published,
     * and the only level check in the whole technique.
     */
    @Test
    fun `building picks the best carried house the hunter level allows`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer(hunterLvl = NORMAL.hunterLevel)
        world.giveItem(player, NORMAL.obj)
        world.giveItem(player, REDWOOD.obj)

        assertTrue(world.build(player, FIRST_SPACE))

        assertEquals(NORMAL.builtState, world.stateOf(player, FIRST_SPACE), "the redwood is skipped")
        assertEquals(1, world.itemCount(player, REDWOOD.obj), "and stays in the inventory")
    }

    @Test
    fun `a redwood house goes up once the hunter level reaches it`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer(hunterLvl = REDWOOD.hunterLevel)
        world.giveItem(player, NORMAL.obj)
        world.giveItem(player, REDWOOD.obj)

        assertTrue(world.build(player, FIRST_SPACE))

        assertEquals(REDWOOD.builtState, world.stateOf(player, FIRST_SPACE))
        assertEquals(1, world.itemCount(player, NORMAL.obj), "the lesser house is untouched")
    }

    @Test
    fun `building refuses with no house carried, and refuses a second house on one space`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()

        assertFalse(world.build(player, FIRST_SPACE), "nothing to place")
        assertEquals(BirdHouseSpaces.BARE, world.stateOf(player, FIRST_SPACE))

        world.giveItem(player, NORMAL.obj, 2)
        assertTrue(world.build(player, FIRST_SPACE))
        assertFalse(world.build(player, FIRST_SPACE), "the space is taken")
        assertEquals(1, world.itemCount(player, NORMAL.obj), "and the second house is not consumed")
    }

    /* Seeds */

    @Test
    fun `ten low-value seeds fill a house and start its fifty minutes`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, NORMAL.obj)
        world.build(player, FIRST_SPACE)
        world.giveItem(player, LOW_SEED, 10)

        assertTrue(world.seed(player, FIRST_SPACE))

        assertEquals(NORMAL.fullState, world.stateOf(player, FIRST_SPACE))
        assertEquals(0, world.itemCount(player, LOW_SEED), "all ten go in")
        assertEquals(
            world.clock.minute + HunterBirdHouse.BIRDHOUSE_FILL_MINUTES,
            world.readyAt(player, FIRST_SPACE),
        )
    }

    /**
     * "10 low level **or 5 high level**" seeds. Five ranarrs is the same ten units as ten barleys,
     * which is the inferred half of the capacity model - see [BirdHouseSeeds].
     */
    @Test
    fun `five high-value seeds fill a house`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, NORMAL.obj)
        world.build(player, FIRST_SPACE)
        world.giveItem(player, HIGH_SEED, 5)

        assertTrue(world.seed(player, FIRST_SPACE))

        assertEquals(NORMAL.fullState, world.stateOf(player, FIRST_SPACE))
        assertEquals(0, world.itemCount(player, HIGH_SEED))
    }

    /**
     * The wiki's own worked example: "it's possible to use a stack of 7 Barley seeds and fill the
     * remaining 3 with Hammerstone".
     */
    @Test
    fun `seed types mix, seven of one and three of another`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, NORMAL.obj)
        world.build(player, FIRST_SPACE)
        world.giveItem(player, LOW_SEED, 7)
        world.giveItem(player, OTHER_LOW_SEED, 8)

        assertTrue(world.seed(player, FIRST_SPACE))

        assertEquals(NORMAL.fullState, world.stateOf(player, FIRST_SPACE))
        assertEquals(0, world.itemCount(player, LOW_SEED), "all seven go in")
        assertEquals(5, world.itemCount(player, OTHER_LOW_SEED), "and exactly three of the other")
    }

    /**
     * A partial insert is remembered and can be finished later.
     *
     * "Birdhouses no longer ask how many seeds you wish to insert; they simply assume you wish to
     * insert as many as possible" (3 May 2018) - which only makes sense if fewer than ten is a state
     * the house can sit in.
     */
    @Test
    fun `a partial fill is remembered and finished on a later click`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, NORMAL.obj)
        world.build(player, FIRST_SPACE)
        world.giveItem(player, LOW_SEED, 6)

        assertTrue(world.seed(player, FIRST_SPACE))
        assertEquals(NORMAL.builtState, world.stateOf(player, FIRST_SPACE), "still unseeded")
        assertEquals(6, world.seedUnits(player, FIRST_SPACE))
        assertEquals(0, world.readyAt(player, FIRST_SPACE), "and no deadline yet")

        world.giveItem(player, LOW_SEED, 4)
        assertTrue(world.seed(player, FIRST_SPACE))
        assertEquals(NORMAL.fullState, world.stateOf(player, FIRST_SPACE))
        assertEquals(0, world.seedUnits(player, FIRST_SPACE), "the counter resets when it starts")
    }

    /**
     * A high-value seed cannot half-fill the last unit, so it is left alone.
     *
     * Only reachable from a partial fill, because high-value seeds are inserted first: a player
     * offering nine barleys and a ranarr in one click gets the ranarr plus eight barleys, which is
     * the same ten units. The odd case is an odd *remaining* capacity, and it is worth pinning
     * because the alternative - a seed worth two units going into a house with one left - would
     * either overfill the house or silently destroy half a ranarr.
     */
    @Test
    fun `a high-value seed will not go into a house with one unit left`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, NORMAL.obj)
        world.build(player, FIRST_SPACE)
        world.giveItem(player, LOW_SEED, 9)
        assertTrue(world.seed(player, FIRST_SPACE))
        assertEquals(9, world.seedUnits(player, FIRST_SPACE), "nine units, one to go")

        world.giveItem(player, HIGH_SEED, 1)
        assertFalse(world.seed(player, FIRST_SPACE), "the ranarr does not fit")

        assertEquals(NORMAL.builtState, world.stateOf(player, FIRST_SPACE), "one unit short")
        assertEquals(9, world.seedUnits(player, FIRST_SPACE))
        assertEquals(1, world.itemCount(player, HIGH_SEED), "the ranarr is not wasted on it")
    }

    /** High-value seeds are inserted first, so one ranarr plus eight barleys fills the house. */
    @Test
    fun `a mixed handful spends the high-value seeds first`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, NORMAL.obj)
        world.build(player, FIRST_SPACE)
        world.giveItem(player, LOW_SEED, 9)
        world.giveItem(player, HIGH_SEED, 1)

        assertTrue(world.seed(player, FIRST_SPACE))

        assertEquals(NORMAL.fullState, world.stateOf(player, FIRST_SPACE))
        assertEquals(0, world.itemCount(player, HIGH_SEED), "the ranarr is worth two units")
        assertEquals(1, world.itemCount(player, LOW_SEED), "so only eight barleys were needed")
    }

    @Test
    fun `seeding a bare space or a filling house does nothing`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, LOW_SEED, 10)

        assertFalse(world.seed(player, FIRST_SPACE), "there is nothing to seed")
        assertEquals(10, world.itemCount(player, LOW_SEED))

        world.giveItem(player, NORMAL.obj)
        world.build(player, FIRST_SPACE)
        world.seed(player, FIRST_SPACE)
        world.giveItem(player, LOW_SEED, 10)

        assertFalse(world.seed(player, FIRST_SPACE), "a filling house takes no more")
        assertEquals(10, world.itemCount(player, LOW_SEED))
    }

    @Test
    fun `a seed the wiki does not list is refused`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, NORMAL.obj)
        world.build(player, FIRST_SPACE)
        // A tree seed. Published as not accepted - the seed table is hops, herbs, flowers,
        // allotments and bushes, and nothing else.
        world.giveItem(player, "obj.magic_tree_seed", 10)

        assertFalse(world.seed(player, FIRST_SPACE))
        assertEquals(10, world.itemCount(player, "obj.magic_tree_seed"))
        assertEquals(0, world.seedUnits(player, FIRST_SPACE))
    }

    /* The timer */

    @Test
    fun `a house does not mature before its fifty minutes are up`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.seededHouse()

        world.clock.advance(HunterBirdHouse.BIRDHOUSE_FILL_MINUTES - 1)
        world.fillArrives(player, FIRST_SPACE)

        assertEquals(NORMAL.fullState, world.stateOf(player, FIRST_SPACE), "still filling")
    }

    @Test
    fun `a house matures exactly on its deadline`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.seededHouse()

        world.clock.advance(HunterBirdHouse.BIRDHOUSE_FILL_MINUTES)
        world.fillArrives(player, FIRST_SPACE)

        assertEquals(NORMAL.birdState, world.stateOf(player, FIRST_SPACE))
        assertEquals(0, world.readyAt(player, FIRST_SPACE), "and the deadline is cleared")
    }

    /**
     * The whole reason the deadline is a saved varp rather than a queue.
     *
     * Nothing runs while the player is logged out - no queue, no controller, no timer. The house is
     * still full when they come back, because the *clock* moved and the deadline was written down.
     */
    @Test
    fun `a house fills while its owner is logged out`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.seededHouse()

        world.clock.advance(HunterBirdHouse.BIRDHOUSE_FILL_MINUTES * 4)
        // No queue body runs here: this is the login re-arm and nothing else.
        world.newSession(player)
        world.login(player)
        world.fillArrives(player, FIRST_SPACE)

        assertEquals(NORMAL.birdState, world.stateOf(player, FIRST_SPACE))
    }

    /**
     * Logging back in mid-fill re-queues the **remaining** time, not the full fifty minutes.
     *
     * Restarting the clock would make logging out a punishment, and is exactly what the crab trap
     * does - correctly, because a crab trap's timer is not wall-clock. The two are deliberately
     * different and this is where that difference is pinned.
     */
    @Test
    fun `logging in mid-fill re-queues only the time left`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.seededHouse()
        val elapsed = 30

        world.clock.advance(elapsed)
        world.newSession(player)
        world.login(player)

        val pending = world.pendingFills(player)
        assertEquals(1, pending.size, "one space is filling, so one queue")
        val remaining = HunterBirdHouse.BIRDHOUSE_FILL_MINUTES - elapsed
        assertEquals(
            remaining * HunterBirdHouse.BIRDHOUSE_CYCLES_PER_MINUTE,
            pending.single().remainingCycles,
        )
    }

    @Test
    fun `logging in after the deadline queues the maturation for the next cycle`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.seededHouse()

        world.clock.advance(HunterBirdHouse.BIRDHOUSE_FILL_MINUTES * 2)
        world.newSession(player)
        world.login(player)

        // Deliberately queued rather than written: `VarPlayerIntMapSetter` skips its transmit branch
        // while `processedMapClock` is still zero, which is the state during the login event, so a
        // direct write would leave the client drawing a house full of seeds.
        val pending = world.pendingFills(player)
        assertEquals(1, pending.size)
        assertEquals(1, pending.single().remainingCycles, "next cycle, not fifty minutes")
        assertEquals(NORMAL.fullState, world.stateOf(player, FIRST_SPACE), "not written at login")
    }

    @Test
    fun `login queues nothing for a space that is bare, unseeded or already full`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, NORMAL.obj)
        world.build(player, FIRST_SPACE)
        world.setState(player, LAST_SPACE, NORMAL.birdState)

        world.newSession(player)
        world.login(player)

        assertEquals(emptyList<Any>(), world.pendingFills(player))
    }

    /**
     * A matured fill landing on a space that was dismantled in the meantime does nothing.
     *
     * The queue outlives the state that scheduled it, so this is the branch that stops a bird house
     * appearing on a space nobody built - the same re-check `HunterCrabTrap.crabTrapCatchArrives`
     * makes, and the one a live client cannot be made to exercise.
     */
    @Test
    fun `a matured fill on a dismantled space does nothing`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.seededHouse()

        world.dismantle(player, FIRST_SPACE)
        world.clock.advance(HunterBirdHouse.BIRDHOUSE_FILL_MINUTES)
        world.fillArrives(player, FIRST_SPACE)

        assertEquals(BirdHouseSpaces.BARE, world.stateOf(player, FIRST_SPACE))
    }

    /* Dismantle */

    /**
     * "Emptying the birdhouse early will save the clockwork but lose the birdhouse, the remaining
     * seeds, and any loot that might have accumulated."
     */
    @Test
    fun `dismantling a filling house returns the clockwork and nothing else`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.seededHouse()
        val xpBefore = player.statMap.getXP("stat.hunter")

        assertTrue(world.dismantle(player, FIRST_SPACE))

        assertEquals(BirdHouseSpaces.BARE, world.stateOf(player, FIRST_SPACE))
        assertEquals(1, world.itemCount(player, HunterBirdHouse.CLOCKWORK))
        assertEquals(0, world.itemCount(player, LOW_SEED), "the seeds are gone")
        assertEquals(0, world.itemCount(player, NORMAL.obj), "and so is the house")
        assertEquals(xpBefore, player.statMap.getXP("stat.hunter"), "and no experience is paid")
        assertEquals(emptyList<String>(), world.objNamesAt(), "and nothing hits the ground")
        assertEquals(0, world.readyAt(player, FIRST_SPACE))
    }

    @Test
    fun `dismantling is refused on a full house, which has its own op`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.setState(player, FIRST_SPACE, NORMAL.birdState)

        assertFalse(world.dismantle(player, FIRST_SPACE))
        assertEquals(NORMAL.birdState, world.stateOf(player, FIRST_SPACE))
    }

    /* Empty */

    @Test
    fun `emptying a full house pays the guaranteed drops and the tier experience`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.setState(player, FIRST_SPACE, NORMAL.birdState)
        // Every rate roll misses, so this is exactly the guaranteed half of the payout.
        world.random.fallbackDouble = 1.0

        assertTrue(world.empty(player, FIRST_SPACE))

        assertEquals(BirdHouseSpaces.BARE, world.stateOf(player, FIRST_SPACE))
        assertEquals(1, world.itemCount(player, HunterBirdHouse.CLOCKWORK))
        assertEquals(
            NORMAL.hunterXp / 10,
            player.statMap.getXP("stat.hunter"),
            "xp is the x10 column divided once",
        )
        // Published twice: "Always dropped to the ground, even if there is space in the inventory."
        assertEquals(0, world.itemCount(player, HunterBirdHouse.RAW_BIRD_MEAT))
        assertEquals(
            HunterBirdHouse.RAW_BIRD_MEAT_COUNT,
            world.groundCount(HunterBirdHouse.RAW_BIRD_MEAT),
        )
        assertTrue(world.itemCount(player, HunterBirdHouse.FEATHER) in listOf(30, 40, 50, 60))
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
    fun `the xp modifier scales the bird house award`() {
        val plain = emptiedNormalHouseFineXp(hunterXpBonus = 0.0)
        val doubled = emptiedNormalHouseFineXp(hunterXpBonus = DOUBLE_HUNTER_XP)

        // The wiki's 280 xp for a normal bird house, in the stat map's tenths.
        assertEquals(2800, plain, "unmodified, a normal bird house is 280.0 xp")
        assertEquals(5600, doubled, "a +100% modifier makes it 560.0")
    }

    /** One emptied normal bird house, in tenths of a point. */
    private fun emptiedNormalHouseFineXp(hunterXpBonus: Double): Int {
        val world = HunterBirdHouseTestWorld(hunterXpBonus = hunterXpBonus)
        val player = world.addPlayer()
        world.setState(player, FIRST_SPACE, NORMAL.birdState)
        world.random.fallbackDouble = 1.0

        assertTrue(world.empty(player, FIRST_SPACE))

        return player.statMap.getFineXP("stat.hunter")
    }

    @Test
    fun `emptying is refused on a filling house`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.seededHouse()

        assertFalse(world.empty(player, FIRST_SPACE))
        assertEquals(NORMAL.fullState, world.stateOf(player, FIRST_SPACE))
    }

    /* Reset */

    @Test
    fun `reset empties the house and puts a fresh one in its place`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.setState(player, FIRST_SPACE, NORMAL.birdState)
        world.giveItem(player, NORMAL.obj)

        assertTrue(world.empty(player, FIRST_SPACE, rebuild = true))

        assertEquals(NORMAL.builtState, world.stateOf(player, FIRST_SPACE), "unseeded, not filling")
        assertEquals(0, world.itemCount(player, NORMAL.obj), "the carried house goes up")
        assertEquals(1, world.itemCount(player, HunterBirdHouse.CLOCKWORK), "the old one comes back")
        assertEquals(0, world.seedUnits(player, FIRST_SPACE), "and it needs seeds again")
    }

    @Test
    fun `reset with no house to place still empties, leaving the space bare`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        world.setState(player, FIRST_SPACE, NORMAL.birdState)

        assertTrue(world.empty(player, FIRST_SPACE, rebuild = true))

        assertEquals(BirdHouseSpaces.BARE, world.stateOf(player, FIRST_SPACE))
        assertEquals(1, world.itemCount(player, HunterBirdHouse.CLOCKWORK), "the payout still happens")
    }

    /* Independence */

    /**
     * The four spaces are four independent bird houses.
     *
     * They share one class and one set of ops, and everything about them is keyed off the space, so
     * a single misplaced index would make all four the same house. That would be invisible to every
     * other test here, which all use the first space.
     */
    @Test
    fun `the four spaces keep separate state`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.addPlayer()
        // One house at a time: `Build` erects the *best* one carried, so holding both would put the
        // redwood on the first space.
        world.giveItem(player, NORMAL.obj)
        world.build(player, FIRST_SPACE)
        world.giveItem(player, LOW_SEED, 10)
        world.seed(player, FIRST_SPACE)

        assertEquals(NORMAL.fullState, world.stateOf(player, FIRST_SPACE))
        assertEquals(BirdHouseSpaces.BARE, world.stateOf(player, LAST_SPACE))
        assertNotEquals(0, world.readyAt(player, FIRST_SPACE))
        assertEquals(0, world.readyAt(player, LAST_SPACE))

        world.giveItem(player, REDWOOD.obj)
        world.build(player, LAST_SPACE)
        assertEquals(REDWOOD.builtState, world.stateOf(player, LAST_SPACE), "a different tier")
        assertEquals(NORMAL.fullState, world.stateOf(player, FIRST_SPACE), "and the first is intact")
    }

    /**
     * The type holds no loc repository, so it could not delete a map-placed loc if it tried.
     *
     * The same structural argument [CrabTrapTest] makes: a test that watches a repository stay
     * untouched is weaker than one showing the collaborator does not exist. The four spaces are
     * permanent map locs and every other hunter family needs a hard `check` to protect them.
     */
    @Test
    fun `the bird house type has no loc repository to break the map with`() {
        val fields = HunterBirdHouse::class.java.declaredFields.map { it.type }
        assertFalse(LocRepository::class.java in fields, "bird houses must never touch locRepo")
    }

    /** A house built, seeded and left filling, with the clock still at its starting minute. */
    private fun HunterBirdHouseTestWorld.seededHouse(): org.rsmod.game.entity.Player {
        val player = addPlayer()
        giveItem(player, NORMAL.obj)
        build(player, FIRST_SPACE)
        giveItem(player, LOW_SEED, 10)
        seed(player, FIRST_SPACE)
        return player
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }
    }
}
