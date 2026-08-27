package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.content.drops.ClueScrollTier
import org.rsmod.content.drops.clueScrollBoxObj
import org.rsmod.content.quest.manager.QuestRequirementMode
import org.rsmod.content.quest.manager.QuestRequirementPolicy
import org.rsmod.content.quest.manager.QuestRequirements
import org.rsmod.content.skills.hunter.HunterBirdHouseTestWorld.Companion.FIRST_SPACE
import org.rsmod.content.skills.hunter.HunterBirdHouseTestWorld.Companion.NORMAL
import org.rsmod.game.entity.Player

/**
 * The *shape* of the payout - roll counts and ordering, the half a rate model cannot see; the
 * draw ordering is itself load-bearing. The draw order, so every test below can be read: one int
 * for the feather quantity, one double for the seed nest, then ten doubles for the nest rolls -
 * each success taking up to five ints for the clue pre-roll and one int for the type table.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class BirdHouseLootTest {
    /* The rolls that are made. */

    /**
     * One seed-nest roll and exactly ten nest rolls, published as those exact counts.
     *
     * "Each birdhouse will give at most 1 seed nest, followed by 10 chances to get nests off the
     * nest table." Counting the draws is the only way to see this: a house that rolled nine times, or
     * eleven, would still hand over a plausible pile of nests.
     */
    @Test
    fun `a harvest makes one seed roll and exactly ten nest rolls`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.fullHouse()
        world.random.fallbackDouble = 1.0

        world.empty(player, FIRST_SPACE)

        // The literal 11 on purpose. Writing it as `1 + NEST_ROLLS` would move with the constant,
        // so a house that quietly started rolling nine times would still pass.
        assertEquals(11, world.random.doubleDraws, "one seed roll plus ten nest rolls")
        assertEquals(10, BirdHouseNests.NEST_ROLLS, "and ten is the published count")
    }

    @Test
    fun `a house whose every roll misses still pays the guaranteed drops`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.fullHouse()
        world.random.fallbackDouble = 1.0

        world.empty(player, FIRST_SPACE)

        assertEquals(1, world.itemCount(player, HunterBirdHouse.CLOCKWORK))
        assertEquals(0, world.itemCount(player, BirdHouseNests.SEED_NEST), "no seed nest")
        assertEquals(0, nestCount(world, player), "and no other nests")
    }

    /* The seed nest. */

    @Test
    fun `the seed nest lands on its own roll and at most once`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.fullHouse()
        // The seed roll is the first double; every nest roll after it misses.
        world.random.queueDoubles(0.0)
        world.random.fallbackDouble = 1.0

        world.empty(player, FIRST_SPACE)

        assertEquals(1, world.itemCount(player, BirdHouseNests.SEED_NEST), "at most one per house")
    }

    /**
     * The seed nest is `obj.bird_nest_seeds_jan2019` (22798), not `obj.bird_nest_seeds` (5073).
     *
     * Worth its own assertion because **every** bird nest variant shares the display name
     * `"Bird nest"`, so nothing about the wrong item would look wrong: 5073 is the discontinued
     * pre-2019 nest, kept in the cache and replaced on the day bird houses released precisely so
     * that older nests would not grant the new Farming Guild seeds.
     */
    @Test
    fun `the seed nest is the post-2019 item`() {
        assertEquals("obj.bird_nest_seeds_jan2019", BirdHouseNests.SEED_NEST)
    }

    /* The nest table. */

    @Test
    fun `every nest roll landing gives ten nests`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.fullHouse()
        // Seed nest misses; all ten nest rolls land. `fallbackInt = 1` makes every clue roll fail
        // (they need a 0) and picks index 1 from the type table, which is the blue egg nest.
        world.random.queueDoubles(1.0)
        world.random.fallbackDouble = 0.0
        world.random.fallbackInt = 1

        world.empty(player, FIRST_SPACE)

        assertEquals(10, nestCount(world, player))
        assertEquals(10, world.itemCount(player, BirdHouseNests.BLUE_EGG_NEST), "index 1 is blue")
    }

    /**
     * The strung rabbit foot is read from what is **worn**, not from the backpack.
     *
     * It shortens the table to 95 slots, so index 96 - an empty nest without the foot - is out of
     * range with it and the roll is coerced back inside. Asserting the slot count through the op is
     * what proves the equipment check is wired at all; [BirdHouseNestsTest] proves the table itself.
     */
    @Test
    fun `the strung rabbit foot shortens the table, and only when worn`() {
        val carried = HunterBirdHouseTestWorld()
        val carrying = carried.fullHouse()
        carried.giveItem(carrying, BirdHouseNests.STRUNG_RABBIT_FOOT)
        assertEquals(BirdHouseNests.NEST_TYPE_SLOTS, slotsSeenByHarvest(carried, carrying))

        val worn = HunterBirdHouseTestWorld()
        val wearing = worn.fullHouse()
        worn.wearItem(wearing, BirdHouseNests.STRUNG_RABBIT_FOOT)
        assertEquals(BirdHouseNests.NEST_TYPE_SLOTS_RABBIT_FOOT, slotsSeenByHarvest(worn, wearing))
    }

    /* Clue nests. */

    /**
     * A clue replaces that roll's ring/egg/empty outcome rather than adding to it. What arrives is
     * a scroll box (the default quest policy reads X Marks the Spot as done) plus an empty nest on
     * the ground - both halves asserted, including the floor.
     */
    @Test
    fun `a clue replaces that roll's ordinary nest, as a scroll box on this server`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.fullHouse()
        // Feather index, then the first clue tier - elite - hits on its first draw.
        world.random.queueInts(0, 0)
        world.random.queueDoubles(1.0)
        world.random.fallbackDouble = 0.0
        world.random.fallbackInt = 1

        world.empty(player, FIRST_SPACE)

        assertEquals(1, world.itemCount(player, clueScrollBoxObj(ClueScrollTier.Elite)))
        assertEquals(0, world.itemCount(player, "obj.wc_clue_nest_elite"), "transformed, not raw")
        assertEquals(
            1,
            world.groundCount(BirdHouseNests.EMPTY_NEST),
            "the substitute empty nest is published as landing on the ground",
        )
        assertEquals(
            BirdHouseNests.NEST_ROLLS - 1,
            world.itemCount(player, BirdHouseNests.BLUE_EGG_NEST),
            "the clue took one of the ten rolls, it did not come on top of it",
        )
    }

    /**
     * Without *X Marks the Spot*, the raw clue nest is what arrives.
     *
     * The other branch of the same transform, reached by installing the policy that respects real
     * quest progress. Worth covering because the default policy hides it entirely, and a server
     * running `respect-progress` would otherwise be exercising untested code.
     */
    @Test
    fun `without X Marks the Spot the raw clue nest is given`() {
        val previous = QuestRequirements.activePolicy()
        QuestRequirements.install(QuestRequirementPolicy(QuestRequirementMode.RespectProgress))
        try {
            val world = HunterBirdHouseTestWorld()
            val player = world.fullHouse()
            world.random.queueInts(0, 0)
            world.random.queueDoubles(1.0)
            world.random.fallbackDouble = 0.0
            world.random.fallbackInt = 1

            world.empty(player, FIRST_SPACE)

            assertEquals(1, world.itemCount(player, "obj.wc_clue_nest_elite"))
            assertEquals(0, world.groundCount(BirdHouseNests.EMPTY_NEST), "no substitute nest")
        } finally {
            QuestRequirements.install(previous)
        }
    }

    /**
     * At most one clue per house, whatever the other nine rolls do.
     *
     * The only *confirmed* statement about clue nests - Mod Ash, 19 June 2020 - in a wiki section
     * that is flagged incomplete and contradicts itself. See [BirdHouseNests.MAX_CLUES_PER_HOUSE].
     */
    @Test
    fun `only one clue comes out of a house`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.fullHouse()
        // Every int draw is a 0, so every clue roll would hit if it were made.
        world.random.queueDoubles(1.0)
        world.random.fallbackDouble = 0.0
        world.random.fallbackInt = 0

        world.empty(player, FIRST_SPACE)

        val boxes = ClueScrollTier.entries.sumOf { world.itemCount(player, clueScrollBoxObj(it)) }
        assertEquals(BirdHouseNests.MAX_CLUES_PER_HOUSE, boxes)
        // The other nine rolls fell through to the type table, whose index 0 is the red egg.
        assertEquals(
            BirdHouseNests.NEST_ROLLS - 1,
            world.itemCount(player, BirdHouseNests.RED_EGG_NEST),
        )
    }

    /** Rarest first, which is what makes the elite tier reachable at all. */
    @Test
    fun `the clue tiers are rolled rarest first`() {
        val denominators = BirdHouseNests.CLUE_NESTS.map { it.denominator }
        assertEquals(listOf(1_500, 750, 500, 375, 50), denominators)
        assertTrue(denominators.zipWithNext().all { it.first > it.second }, "rarest first")
    }

    /* Overflow. */

    /**
     * A payout that will not fit lands on the floor rather than being refused.
     *
     * The space is cleared before anything is awarded, so a refusal here would destroy the loot
     * outright. Which of the two live does is unstated; the floor is the recoverable direction, and
     * the raw bird meat is published as going there regardless.
     */
    @Test
    fun `a full inventory sends the payout to the ground instead of losing it`() {
        val world = HunterBirdHouseTestWorld()
        val player = world.fullHouse()
        world.fillInventory(player)
        world.random.fallbackDouble = 1.0

        assertTrue(world.empty(player, FIRST_SPACE))

        assertEquals(0, world.itemCount(player, HunterBirdHouse.CLOCKWORK), "no room for it")
        assertEquals(1, world.groundCount(HunterBirdHouse.CLOCKWORK), "so it is on the floor")
        assertEquals(
            HunterBirdHouse.RAW_BIRD_MEAT_COUNT,
            world.groundCount(HunterBirdHouse.RAW_BIRD_MEAT),
        )
        assertEquals(BirdHouseSpaces.BARE, world.stateOf(player, FIRST_SPACE), "and it still emptied")
    }

    /** How many ring, egg and empty nests the player is holding. */
    private fun nestCount(world: HunterBirdHouseTestWorld, player: Player): Int =
        listOf(
                BirdHouseNests.RING_NEST,
                BirdHouseNests.RED_EGG_NEST,
                BirdHouseNests.BLUE_EGG_NEST,
                BirdHouseNests.GREEN_EGG_NEST,
                BirdHouseNests.EMPTY_NEST,
            )
            .sumOf { world.itemCount(player, it) }

    /**
     * The table size the harvest actually rolled against.
     *
     * Recovered rather than asserted directly: index 95 is inside the plain table and outside the
     * rabbit-foot one, so scripting that draw and reading back which nest arrived says which
     * denominator was used. Index 95 is an empty nest without the foot; with the foot the draw is
     * coerced to 94, which is also empty - so the distinguishing draw is 99 against 94, and the
     * ring band is what separates them.
     */
    private fun slotsSeenByHarvest(world: HunterBirdHouseTestWorld, player: Player): Int {
        world.random.queueDoubles(1.0)
        world.random.fallbackDouble = 0.0
        // Feather index first, then every type-table draw asks for index 34 - the last ring slot -
        // which is inside both tables. The clue rolls need a 0 and get 34, so they all miss.
        world.random.fallbackInt = 34
        world.empty(player, FIRST_SPACE)
        assertEquals(
            BirdHouseNests.NEST_ROLLS,
            world.itemCount(player, BirdHouseNests.RING_NEST),
            "index 34 is the last ring slot in both tables",
        )
        // Now the real question, asked through the model with the same worn state the op read.
        return BirdHouseNests.nestTypeSlots(
            player.worn.contains(BirdHouseNests.STRUNG_RABBIT_FOOT)
        )
    }

    /** A player standing at a bird house that is full of birds. */
    private fun HunterBirdHouseTestWorld.fullHouse(): Player {
        val player = addPlayer()
        setState(player, FIRST_SPACE, NORMAL.birdState)
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
