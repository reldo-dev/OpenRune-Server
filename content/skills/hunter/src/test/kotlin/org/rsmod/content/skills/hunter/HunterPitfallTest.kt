package org.rsmod.content.skills.hunter

import dev.openrune.types.NpcMode
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * Building and dismantling a spiked pit, in the branches a live client cannot be made to take.
 *
 * The valuable half of this file is the refusals: the per-creature level gate, the two logs the
 * *Pitfall* page rules out by name, the lowest-tier-first pick that only shows itself when several
 * kinds of log are carried at once, the cap counted across all twenty-five sites, and a dismantle
 * refused for want of a free slot *before* anything is awarded. A player can demonstrate a
 * successful build in ten seconds; nothing but a test can demonstrate that a pit at the cap refuses
 * whichever of twenty-five sites is clicked.
 *
 * Every figure is pinned to a **literal**. Nothing here reads the constant it is testing back as
 * its own expected value - this module has already shipped two vacuous tests of exactly that shape
 * (`CLAUDE.md`, bird house `NEST_ROLLS` and `Controller.duration`).
 *
 * Serialised for the reason the rest of the suite is: `ServerCacheManager` is a singleton and
 * `RSCM` memoises into a plain `HashMap`.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class HunterPitfallTest {
    private lateinit var world: HunterPitfallTestWorld

    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
        world = HunterPitfallTestWorld()
    }

    /* The never-touch-a-map-loc invariant. */

    /**
     * Pitfall trapping cannot delete a map loc, because it cannot reach one.
     *
     * A pit is permanent map scenery whose rendered child every player picks from their own varbit.
     * A `locRepo.del` here would take the pit out of the world for *everyone* until the next
     * restart, which is the single worst thing this feature could do. Asserting on the constructor
     * is the only way to say the failure is unrepresentable rather than merely unexercised - a test
     * that watched a repository stay untouched would keep passing the day somebody injected one.
     *
     * The chase table is an `IdentityHashMap` and is named here on purpose: teasing needed
     * somewhere to record who a creature is chasing, and the cheapest wrong answer would have been
     * an `NpcRepository` to look creatures up through. It holds nothing but npcs the caller already
     * handed it. `PlayerList` is the engine's own player array, which is what
     * `NpcPlayerFollowModeProcessor` resolves a follow target through and what
     * [HunterPitfall.tickChases] resolves one through in turn; it is not a repository and cannot
     * add, delete or move anything.
     */
    @Test
    fun `the pitfall engine has no way to reach a loc, controller or npc repository`() {
        val collaborators =
            HunterPitfall::class
                .java
                .declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.type.simpleName }
                .toSet()
        for (forbidden in
            listOf("LocRepository", "ControllerRepository", "NpcRepository", "ObjRepository")) {
            assertFalse(forbidden in collaborators, "HunterPitfall must not hold a $forbidden")
        }
        assertEquals(setOf("XpModifiers", "PlayerList", "IdentityHashMap"), collaborators)
    }

    /* Build: the happy path and the varbit it writes. */

    @Test
    fun `setting a pit with the level, a knife and a log leaves a spiked pit`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveTrapKit(player)

        assertTrue(world.trap(player, site))
        assertEquals(PitState.Set, world.stateOf(player, site))
        assertEquals(1, world.varbitOf(player, site), "state 1 is the spiked pit")
    }

    /**
     * Site 18 and site 19 do not share bits, and this is the test that would catch it if they did.
     *
     * `varp.hunt_pitfall_states_basevar2` has a hole: site 18 ends at bit 23 and site 19 starts at
     * bit **25**, so `3 * field` - the obvious arithmetic - is right for twenty-three of the
     * twenty-five sites and reads into a neighbour for these two. Writing by gameval name lets the
     * cache place the bits; setting one of the pair and reading the other back is what proves it.
     */
    @Test
    fun `setting one pit leaves every other pit alone, across the varbit layout's hole`() {
        val site18 = PitfallSites.all.first { it.index == 18 }
        val site19 = PitfallSites.all.first { it.index == 19 }
        val player = world.addPlayer(hunterLvl = 72)
        world.giveTrapKit(player, logs = 2)

        assertTrue(world.trap(player, site18))
        assertEquals(1, world.varbitOf(player, site18))
        assertEquals(0, world.varbitOf(player, site19), "site 19 must not move with site 18")

        world.clearPits(player)
        assertTrue(world.trap(player, site19))
        assertEquals(1, world.varbitOf(player, site19))
        assertEquals(0, world.varbitOf(player, site18), "site 18 must not move with site 19")
    }

    /* Build: the level gate, per creature. */

    /**
     * The five requirements, each asserted one level below and at the requirement itself.
     *
     * Pinned as literals rather than read off `site.creature.level`, which is the value under test.
     */
    @Test
    fun `each creature's own level gates the pits that hold it`() {
        val requirements =
            listOf(
                31 to HunterPitfallTestWorld.LARUPIA_SITES,
                41 to HunterPitfallTestWorld.GRAAHK_SITES,
                55 to HunterPitfallTestWorld.KYATT_SITES,
                72 to HunterPitfallTestWorld.SUNLIGHT_SITES,
                91 to HunterPitfallTestWorld.MOONLIGHT_SITES,
            )
        for ((level, sites) in requirements) {
            val site = sites.first()

            val under = world.addPlayer(hunterLvl = level - 1)
            world.giveTrapKit(under)
            assertFalse(world.trap(under, site), "level ${level - 1} must not set a $level pit")
            assertEquals(PitState.Empty, world.stateOf(under, site))
            assertEquals(1, world.itemCount(under, HunterPitfallTestWorld.LOGS), "no log spent")

            val exact = world.addPlayer(hunterLvl = level)
            world.giveTrapKit(exact)
            assertTrue(world.trap(exact, site), "level $level must set a $level pit")
        }
    }

    /** The five levels the table ships, so the gate above is gating the right numbers. */
    @Test
    fun `the shipped creature levels are the wiki's own`() {
        assertEquals(31, PitfallCreatures.larupia.level)
        assertEquals(41, PitfallCreatures.graahk.level)
        assertEquals(55, PitfallCreatures.kyatt.level)
        assertEquals(72, PitfallCreatures.sunlight.level)
        assertEquals(91, PitfallCreatures.moonlight.level)
    }

    /* Build: the knife. */

    @Test
    fun `a pit cannot be set without a knife, and the log is not spent trying`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveItem(player, HunterPitfallTestWorld.LOGS, 3)

        assertFalse(world.trap(player, site))
        assertEquals(PitState.Empty, world.stateOf(player, site))
        assertEquals(3, world.itemCount(player, HunterPitfallTestWorld.LOGS))
    }

    @Test
    fun `a knife in the inventory sets a pit and is not consumed`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveTrapKit(player, knife = HunterPitfallTestWorld.KNIFE)

        assertTrue(world.trap(player, site))
        val knives = world.itemCount(player, HunterPitfallTestWorld.KNIFE)
        assertEquals(1, knives, "the knife is a tool")
    }

    @Test
    fun `a fletching knife in the inventory sets a pit and is not consumed`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveTrapKit(player, knife = HunterPitfallTestWorld.FLETCHING_KNIFE)

        assertTrue(world.trap(player, site))
        assertEquals(1, world.itemCount(player, HunterPitfallTestWorld.FLETCHING_KNIFE))
    }

    /**
     * A fletching knife is a weapon, so the ordinary way to carry one is on the hand.
     *
     * Unreachable from the inventory check alone, and the branch most likely to be dropped: a
     * player who wields their fletching knife would otherwise be told to fetch one they hold.
     */
    @Test
    fun `a worn fletching knife sets a pit and stays on the hand`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.wearItem(player, HunterPitfallTestWorld.FLETCHING_KNIFE)
        world.giveItem(player, HunterPitfallTestWorld.LOGS, 1)

        assertTrue(world.trap(player, site))
        assertTrue(player.worn.contains(HunterPitfallTestWorld.FLETCHING_KNIFE))
        assertEquals(0, world.itemCount(player, HunterPitfallTestWorld.FLETCHING_KNIFE))
    }

    @Test
    fun `a worn knife sets a pit`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.wearItem(player, HunterPitfallTestWorld.KNIFE)
        world.giveItem(player, HunterPitfallTestWorld.LOGS, 1)

        assertTrue(world.trap(player, site))
    }

    /* Build: the log. */

    @Test
    fun `setting a pit spends exactly one log`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveTrapKit(player, logs = 5)

        assertTrue(world.trap(player, site))
        assertEquals(4, world.itemCount(player, HunterPitfallTestWorld.LOGS))
    }

    @Test
    fun `a pit cannot be set with no logs at all`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveItem(player, HunterPitfallTestWorld.KNIFE)

        assertFalse(world.trap(player, site))
        assertEquals(PitState.Empty, world.stateOf(player, site))
    }

    /**
     * "If players have multiple types of logs in their inventory, the lowest tier of logs will be
     * used first." (wiki, *Pitfall*, oldid=15201220)
     *
     * The maple logs go in the first slot on purpose: a first-by-slot-order pick - which is what
     * the deadfall does, and what this was most likely to be copied from - would spend them.
     */
    @Test
    fun `the lowest tier of log is spent first, whatever order they are carried in`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveItem(player, HunterPitfallTestWorld.KNIFE)
        world.giveItem(player, HunterPitfallTestWorld.MAPLE_LOGS, 1)
        world.giveItem(player, HunterPitfallTestWorld.WILLOW_LOGS, 1)
        world.giveItem(player, HunterPitfallTestWorld.OAK_LOGS, 1)
        world.giveItem(player, HunterPitfallTestWorld.LOGS, 1)

        assertTrue(world.trap(player, site))

        val normal = world.itemCount(player, HunterPitfallTestWorld.LOGS)
        assertEquals(0, normal, "normal logs go first")
        assertEquals(1, world.itemCount(player, HunterPitfallTestWorld.OAK_LOGS))
        assertEquals(1, world.itemCount(player, HunterPitfallTestWorld.WILLOW_LOGS))
        assertEquals(1, world.itemCount(player, HunterPitfallTestWorld.MAPLE_LOGS))
    }

    /** With the bottom tier gone the next one up is spent, not the one in the first slot. */
    @Test
    fun `the next tier up is spent once the lowest is gone`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveItem(player, HunterPitfallTestWorld.KNIFE)
        world.giveItem(player, HunterPitfallTestWorld.MAPLE_LOGS, 1)
        world.giveItem(player, HunterPitfallTestWorld.OAK_LOGS, 1)

        assertTrue(world.trap(player, site))

        assertEquals(0, world.itemCount(player, HunterPitfallTestWorld.OAK_LOGS))
        assertEquals(1, world.itemCount(player, HunterPitfallTestWorld.MAPLE_LOGS))
    }

    /**
     * Normal logs and achey tree logs both require Firemaking 1, so this is the one pair the
     * lowest-tier rule cannot settle by level alone - it is a stated tie-break, not the packed
     * table's incidental row order, that must decide it.
     *
     * Achey tree logs are a Big Chompy Bird Hunting quest item; a pit free to spend either would
     * otherwise be one packed-table repack away from quietly destroying one to arm a trap.
     */
    @Test
    fun `normal logs are spent before achey tree logs, which tie at Firemaking level 1`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveItem(player, HunterPitfallTestWorld.KNIFE)
        world.giveItem(player, "obj.achey_tree_logs", 1)
        world.giveItem(player, HunterPitfallTestWorld.LOGS, 1)

        assertTrue(world.trap(player, site))

        val normal = world.itemCount(player, HunterPitfallTestWorld.LOGS)
        assertEquals(0, normal, "normal logs go first")
        assertEquals(1, world.itemCount(player, "obj.achey_tree_logs"), "achey logs are kept")
    }

    /**
     * "Redwood logs and arctic pine logs cannot be used for pitfall traps." (wiki, *Pitfall*)
     *
     * Both are ordinary rows of the packed firemaking logs table - redwood at Firemaking 90, arctic
     * pine at 42 - so reading "logs" off that table sweeps them in unless excluded by name.
     */
    @Test
    fun `redwood logs cannot set a pit`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveTrapKit(player, log = HunterPitfallTestWorld.REDWOOD_LOGS, logs = 2)

        assertFalse(world.trap(player, site))
        assertEquals(PitState.Empty, world.stateOf(player, site))
        assertEquals(2, world.itemCount(player, HunterPitfallTestWorld.REDWOOD_LOGS))
    }

    @Test
    fun `arctic pine logs cannot set a pit`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveTrapKit(player, log = HunterPitfallTestWorld.ARCTIC_PINE_LOG, logs = 2)

        assertFalse(world.trap(player, site))
        assertEquals(PitState.Empty, world.stateOf(player, site))
        assertEquals(2, world.itemCount(player, HunterPitfallTestWorld.ARCTIC_PINE_LOG))
    }

    /**
     * A refused log is refused outright, not merely ranked last: a player holding redwood *and*
     * normal logs spends the normal ones, and one holding only redwood is told to fetch logs rather
     * than quietly charged a 90-Firemaking log.
     */
    @Test
    fun `a refused log is skipped rather than ranked, when an accepted one is also held`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveItem(player, HunterPitfallTestWorld.KNIFE)
        world.giveItem(player, HunterPitfallTestWorld.ARCTIC_PINE_LOG, 1)
        world.giveItem(player, HunterPitfallTestWorld.LOGS, 1)

        assertTrue(world.trap(player, site))
        assertEquals(0, world.itemCount(player, HunterPitfallTestWorld.LOGS))
        assertEquals(1, world.itemCount(player, HunterPitfallTestWorld.ARCTIC_PINE_LOG))
    }

    /* Build: the cap. */

    /**
     * The cap, at every rung of the ladder a pit can actually be set at.
     *
     * The wiki's table starts at Hunter 1 (one trap) and 20 (two), but the lowest pitfall creature
     * is the larupia at 31, so no player who can set a pit at all is on either of those rungs. The
     * reachable rungs are 31-39 (two traps, the level-20 rung), 40, 60 and 80.
     */
    @Test
    fun `the cap refuses the trap past the last one the ladder allows`() {
        val rungs = listOf(31 to 2, 40 to 3, 60 to 4, 80 to 5)
        for ((level, cap) in rungs) {
            val player = world.addPlayer(hunterLvl = level)
            world.giveTrapKit(player, logs = cap + 1)
            // Only larupia sites are reachable at 31, and there are five of them - one more than
            // the largest cap this loop reaches with a larupia-only player.
            val sites = HunterPitfallTestWorld.LARUPIA_SITES + HunterPitfallTestWorld.GRAAHK_SITES

            for (i in 0 until cap) {
                assertTrue(world.trap(player, sites[i]), "trap ${i + 1} of $cap at level $level")
            }
            assertFalse(world.trap(player, sites[cap]), "trap ${cap + 1} at level $level")
            assertEquals(PitState.Empty, world.stateOf(player, sites[cap]))
            assertEquals(1, world.itemCount(player, HunterPitfallTestWorld.LOGS), "no log spent")
        }
    }

    /** The ladder itself, pinned, so the cap above is counting against the right numbers. */
    @Test
    fun `the trap ladder is the wiki's own`() {
        assertEquals(2, PitfallLogic.maxTraps(31), "the lowest level a pit can be set at")
        assertEquals(3, PitfallLogic.maxTraps(40))
        assertEquals(4, PitfallLogic.maxTraps(60))
        assertEquals(5, PitfallLogic.maxTraps(80))
    }

    /** A pit holding a catch is still one of your traps, so it counts against the cap. */
    @Test
    fun `a full pit counts towards the cap just as a set one does`() {
        val sites = HunterPitfallTestWorld.LARUPIA_SITES
        val player = world.addPlayer(hunterLvl = 31)
        world.giveTrapKit(player, logs = 4)
        world.setState(player, sites[0], PitState.Full)
        world.setState(player, sites[1], PitState.FullRotated)

        assertFalse(world.trap(player, sites[2]), "two full pits already fill a cap of two")
        assertEquals(4, world.itemCount(player, HunterPitfallTestWorld.LOGS))
    }

    /** And so does a pit mid-collapse, which is a trap of yours the world has not finished with. */
    @Test
    fun `a collapsing pit counts towards the cap`() {
        val sites = HunterPitfallTestWorld.LARUPIA_SITES
        val player = world.addPlayer(hunterLvl = 31)
        world.giveTrapKit(player, logs = 4)
        world.setState(player, sites[0], PitState.Catching)
        world.setState(player, sites[1], PitState.Catching)

        assertFalse(world.trap(player, sites[2]))
    }

    /* Build: only from an empty pit. */

    @Test
    fun `a pit that is already set cannot be set again`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveTrapKit(player, logs = 2)
        world.setState(player, site, PitState.Set)

        assertFalse(world.trap(player, site))
        assertEquals(PitState.Set, world.stateOf(player, site))
        assertEquals(2, world.itemCount(player, HunterPitfallTestWorld.LOGS))
    }

    /**
     * A full pit refuses a fresh trap, and its catch survives the attempt.
     *
     * The wiki's Trivia section reports the opposite as a live *glitch* - "Using logs on a
     * collapsed trap will cause the player to trap the pit again. No hunter experience or loot
     * will be given."
     * - which destroys the catch. The cache declares no `Trap` op on states 2, 3 or 4 at all, so
     * that path is not reachable here, and reproducing a bug the wiki files under Trivia is not
     * something this refusal should bend for.
     */
    @Test
    fun `a full pit cannot be trapped over, and keeps its catch`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveTrapKit(player, logs = 2)

        for (state in listOf(PitState.Catching, PitState.Full, PitState.FullRotated)) {
            world.setState(player, site, state)
            assertFalse(world.trap(player, site), "a pit in $state cannot be trapped")
            assertEquals(state, world.stateOf(player, site))
        }
        assertEquals(2, world.itemCount(player, HunterPitfallTestWorld.LOGS))
    }

    /* Dismantle. */

    @Test
    fun `dismantling a set pit returns it to an empty pit and gives nothing`() {
        val site = HunterPitfallTestWorld.SUNLIGHT_SITE
        val player = world.addPlayer(hunterLvl = 72)
        world.setState(player, site, PitState.Set)
        val slotsBefore = player.inv.freeSpace()

        assertTrue(world.dismantle(player, site))
        assertEquals(PitState.Empty, world.stateOf(player, site))
        assertEquals(0, world.varbitOf(player, site))
        assertEquals(0, world.hunterFineXp(player), "no xp for taking a trap back apart")
        assertEquals(slotsBefore, player.inv.freeSpace(), "and nothing handed back")
    }

    /**
     * The sunlight antelope's 380 xp, pinned as a literal in the engine's own tenths.
     *
     * `PitfallCreature.xp` stores 3800 because every experience column in this module is stored x10
     * so fractional awards survive the table; `statAdvance` takes the divided figure and the stat
     * map holds tenths again, so 3800 fine xp is the number to look for either way.
     */
    @Test
    fun `dismantling a full pit awards the creature's xp and its whole catch`() {
        val site = HunterPitfallTestWorld.SUNLIGHT_SITE
        val player = world.addPlayer(hunterLvl = 72)
        world.setState(player, site, PitState.Full)

        assertTrue(world.dismantle(player, site))

        assertEquals(380, world.hunterXp(player), "a sunlight antelope is 380 Hunter xp")
        assertEquals(3800, world.hunterFineXp(player))
        assertEquals(1, world.itemCount(player, "obj.big_bones"))
        assertEquals(1, world.itemCount(player, "obj.hunting_antelopesun_meat"))
        assertEquals(1, world.itemCount(player, "obj.hunting_antelopesun_fur"))
        assertEquals(1, world.itemCount(player, "obj.hunting_antelopesun_horn"))
        assertEquals(PitState.Empty, world.stateOf(player, site))
    }

    /** The rotated full state is the same pit facing the other way, and pays out identically. */
    @Test
    fun `the rotated full state pays out exactly as the unrotated one does`() {
        val site = HunterPitfallTestWorld.SUNLIGHT_SITE
        val player = world.addPlayer(hunterLvl = 72)
        world.setState(player, site, PitState.FullRotated)

        assertTrue(world.dismantle(player, site))
        assertEquals(3800, world.hunterFineXp(player))
        assertEquals(1, world.itemCount(player, "obj.hunting_antelopesun_horn"))
        assertEquals(PitState.Empty, world.stateOf(player, site))
    }

    /** The other four creatures' awards, each pinned to the wiki's own experience figure. */
    @Test
    fun `every creature pays its own experience`() {
        val awards =
            listOf(
                1800 to HunterPitfallTestWorld.LARUPIA_SITES.first(),
                2400 to HunterPitfallTestWorld.GRAAHK_SITES.first(),
                3000 to HunterPitfallTestWorld.KYATT_SITES.first(),
                3800 to HunterPitfallTestWorld.SUNLIGHT_SITES.first(),
                4500 to HunterPitfallTestWorld.MOONLIGHT_SITES.first(),
            )
        for ((fineXp, site) in awards) {
            val player = world.addPlayer(hunterLvl = 99)
            world.setState(player, site, PitState.Full)

            assertTrue(world.dismantle(player, site))
            assertEquals(fineXp, world.hunterFineXp(player), "site ${site.index}")
        }
    }

    @Test
    fun `an empty pit has nothing to dismantle`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 99)

        assertFalse(world.dismantle(player, site))
        assertEquals(PitState.Empty, world.stateOf(player, site))
    }

    /**
     * A pit mid-collapse is refused rather than emptied.
     *
     * The cache gives state 2 no ops at all, so no click can reach this; it is here because the
     * *choice* matters if anything ever does. Returning the pit to empty would destroy a catch that
     * is still landing, and paying out would mint one that has not landed yet. Refusing does
     * neither, and [HunterPitfall.clearPits] is the escape hatch if a pit is ever left there.
     */
    @Test
    fun `a collapsing pit cannot be dismantled, and is left exactly as it was`() {
        val site = HunterPitfallTestWorld.SUNLIGHT_SITE
        val player = world.addPlayer(hunterLvl = 99)
        world.setState(player, site, PitState.Catching)

        assertFalse(world.dismantle(player, site))
        assertEquals(PitState.Catching, world.stateOf(player, site))
        assertEquals(0, world.hunterFineXp(player))
    }

    /**
     * A full inventory refuses *before* anything is awarded, rather than dropping the catch.
     *
     * The falconry idiom, and the reason it matters here: the pit stays full, so the catch is still
     * there once a slot is freed. Awarding first and spilling the remainder on the floor would put
     * a rare fur under a three-minute despawn timer instead.
     */
    @Test
    fun `a full inventory refuses the dismantle before any of it is awarded`() {
        val site = HunterPitfallTestWorld.SUNLIGHT_SITE
        val player = world.addPlayer(hunterLvl = 72)
        world.setState(player, site, PitState.Full)
        world.fillInventory(player)

        assertFalse(world.dismantle(player, site))

        assertEquals(PitState.Full, world.stateOf(player, site), "the catch is still in the pit")
        assertEquals(0, world.hunterFineXp(player), "and no xp was paid for it")
        assertEquals(0, world.itemCount(player, "obj.big_bones"), "and nothing was awarded")
        assertEquals(0, player.inv.freeSpace())
    }

    /* clearPits. */

    @Test
    fun `clearing returns every one of a player's pits to empty`() {
        val player = world.addPlayer(hunterLvl = 99)
        world.setState(player, PitfallSites.all[0], PitState.Set)
        world.setState(player, PitfallSites.all[12], PitState.Full)
        world.setState(player, PitfallSites.all[24], PitState.Catching)

        world.clearPits(player)

        for (site in PitfallSites.all) {
            assertEquals(PitState.Empty, world.stateOf(player, site), "site ${site.index}")
            assertEquals(0, world.varbitOf(player, site))
        }
    }

    @Test
    fun `clearing one player's pits leaves another player's alone`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val first = world.addPlayer(hunterLvl = 99)
        val second = world.addPlayer(hunterLvl = 99)
        world.setState(first, site, PitState.Set)
        world.setState(second, site, PitState.Set)

        world.clearPits(first)

        assertEquals(PitState.Empty, world.stateOf(first, site))
        assertEquals(PitState.Set, world.stateOf(second, site), "pits are private, per player")
    }

    /* The invariant the award path relies on. */

    /**
     * Every pitfall reward line is a flat quantity, which is why the award path draws no random
     * number and [HunterPitfall] holds no `GameRandom` at all.
     *
     * If a ranged line is ever added this fails here rather than silently awarding its minimum, and
     * whoever adds it has to bring an RNG - named `gameRandom`, never `random` - with it.
     */
    @Test
    fun `every pitfall reward line is a fixed quantity`() {
        for (creature in PitfallCreatures.all) {
            for (line in creature.loot) {
                assertEquals(
                    line.quantity.first,
                    line.quantity.last,
                    "${creature.npc} awards a range of ${line.obj}",
                )
            }
        }
    }

    /* Teasing: the tool, the chase, and who owns it. */

    /**
     * A teased creature chases the teaser, in the two pieces the engine actually reads.
     *
     * `NpcPlayerFollowModeProcessor.process` does exactly two lookups each cycle -
     * `npc.mode == PlayerFollow` to get there at all, and `npc.facingTarget(playerList)` for the
     * target - so both are asserted rather than only the engine's own record of who teased it. A
     * test that checked the record alone would keep passing the day the mode stopped being set and
     * the creature stopped moving.
     */
    @Test
    fun `teasing with a teasing stick sets the creature chasing the teaser`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        assertEquals(NpcMode.PlayerFollow, npc.mode)
        assertSame(player, world.chaseTarget(npc))
        assertEquals(player.uid, world.teasedBy(npc))
    }

    /**
     * A worn teasing stick works, for the reason a worn knife sets a pit.
     *
     * The wiki names only the inventory, but `obj.hunting_teasing_stick` is `wearpos=righthand`, so
     * the ordinary way to carry one is on the hand. Refusing a player who is visibly holding the
     * stick is the reading that tells them to go and fetch what they already have.
     */
    @Test
    fun `a worn teasing stick teases just as a held one does`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.wearItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        assertSame(player, world.chaseTarget(npc))
    }

    @Test
    fun `an equipped hunter's spear teases`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.wearItem(player, HunterPitfallTestWorld.HUNTERS_SPEAR)

        assertTrue(world.tease(player, npc))
        assertSame(player, world.chaseTarget(npc))
    }

    /**
     * A hunter's spear in the backpack is not enough, and this one is sourced rather than inferred.
     *
     * "The spear must be equipped before being able to tease creatures." (wiki, *Hunter's spear*,
     * oldid=15264550). That is the asymmetry with the teasing stick above: the stick counts held or
     * worn, the spear counts only worn.
     */
    @Test
    fun `a hunter's spear in the inventory does not tease`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.HUNTERS_SPEAR)

        assertFalse(world.tease(player, npc))
        assertNull(world.chaseTarget(npc))
        assertNull(world.teasedBy(npc))
    }

    /**
     * With neither tool the creature is left exactly as the world spawned it.
     *
     * `NpcMode.Wander` is the packed default: neither `npc.hunting_jaguar` nor any other pitfall
     * creature declares `defaultmode` in the cache, and none is overridden in
     * `.data/raw-cache/server/npcs.toml`, so `NpcServerType`'s own default stands.
     */
    @Test
    fun `teasing with neither tool is refused and leaves the creature alone`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)

        assertFalse(world.tease(player, npc))
        assertEquals(NpcMode.Wander, npc.mode)
        assertNull(world.chaseTarget(npc))
        assertNull(world.teasedBy(npc))
    }

    /** "Teasing creatures does not consume the spear." (wiki, *Hunter's spear*, oldid=15264550). */
    @Test
    fun `neither the spear nor the teasing stick is consumed by teasing`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)
        world.wearItem(player, HunterPitfallTestWorld.HUNTERS_SPEAR)

        repeat(3) { assertTrue(world.tease(player, npc)) }

        assertEquals(1, world.itemCount(player, HunterPitfallTestWorld.TEASING_STICK))
        assertEquals(1, world.wornCount(player, HunterPitfallTestWorld.HUNTERS_SPEAR))
    }

    /**
     * The spear and the stick tease **identically**, which is this branch's decision, not an
     * oversight.
     *
     * The hunter's spear's published +5% is a *relative* modifier on a base tease rate that is
     * published nowhere, and no source describes a failed tease at all. This server therefore
     * models the tease as certain, which leaves the +5% nothing to modify. See
     * [HunterPitfall.teaseCreature] for the whole argument; this test is here so the decision
     * cannot drift into a silent difference between the two tools.
     */
    @Test
    fun `the hunter's spear teases no better than the teasing stick`() {
        val stickUser = world.addPlayer()
        val spearUser = world.addPlayer()
        world.giveItem(stickUser, HunterPitfallTestWorld.TEASING_STICK)
        world.wearItem(spearUser, HunterPitfallTestWorld.HUNTERS_SPEAR)

        repeat(20) {
            val stickTarget = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
            val spearTarget =
                world.addNpc(
                    HunterPitfallTestWorld.LARUPIA_NPC,
                    HunterPitfallTestWorld.SECOND_CREATURE_TILE,
                )
            assertTrue(world.tease(stickUser, stickTarget))
            assertTrue(world.tease(spearUser, spearTarget))
            world.removeNpc(stickTarget)
            world.removeNpc(spearTarget)
        }
    }

    /**
     * Teasing carries no Hunter requirement of its own.
     *
     * The *Pitfall* page gates only the trap - "With the required Hunter level, a knife or fletching
     * knife and logs in the inventory, clicking on a pit will set the trap" - and says nothing about
     * a level to tease. A level 1 player can therefore make a moonlight antelope, whose pit needs
     * 91, chase them; they simply cannot build the pit to catch it in.
     */
    @Test
    fun `teasing needs no Hunter level`() {
        val player = world.addPlayer(hunterLvl = 1)
        val npc = world.addNpc(HunterPitfallTestWorld.MOONLIGHT_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        assertSame(player, world.chaseTarget(npc))
    }

    @Test
    fun `every pitfall creature can be teased`() {
        val player = world.addPlayer()
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertEquals(5, HunterPitfallTestWorld.CREATURE_NPCS.size)
        for (internal in HunterPitfallTestWorld.CREATURE_NPCS) {
            val npc = world.addNpc(internal)
            assertTrue(world.tease(player, npc), "$internal refused a tease")
            assertSame(player, world.chaseTarget(npc), "$internal did not chase")
            world.removeNpc(npc)
        }
    }

    /**
     * A second teaser takes the creature over, rather than being refused.
     *
     * This is the branch's call, and the reasoning is worth stating because no source describes it.
     * These npcs carry no `Attack` op and no combat target of their own, so there is nothing in the
     * packed data that reserves one to a player. Refusing the second tease would let anyone park a
     * creature on themselves indefinitely and lock every other hunter out of it, and it would break
     * the wiki's own two-creatures-one-trap procedure, which depends on teasing being cheap and
     * repeatable. The newest teaser therefore wins, and the previous one simply loses the chase.
     */
    @Test
    fun `a creature already chasing someone switches to whoever teases it next`() {
        val first = world.addPlayer()
        val second = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(first, HunterPitfallTestWorld.TEASING_STICK)
        world.giveItem(second, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(first, npc))
        assertTrue(world.tease(second, npc))

        assertSame(second, world.chaseTarget(npc))
        assertEquals(second.uid, world.teasedBy(npc))
        assertNotEquals(first.uid, world.teasedBy(npc))
    }

    @Test
    fun `re-teasing a creature already chasing you keeps it on you`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        assertTrue(world.tease(player, npc))

        assertSame(player, world.chaseTarget(npc))
        assertEquals(player.uid, world.teasedBy(npc))
    }

    /**
     * A creature with no pitfall in it is not teasable, whatever the player is carrying.
     *
     * The op is declared on five npcs and the table holds five rows, but nothing stops a later op
     * registration from being pointed at the wrong npc set. `npc.hunting_chinchompa` is a hunter
     * creature caught by an entirely different technique, and it must come out of this unchanged.
     */
    @Test
    fun `a creature that is not a pitfall creature cannot be teased`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.CHINCHOMPA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertFalse(world.tease(player, npc))
        assertEquals(NpcMode.Wander, npc.mode)
        assertNull(world.chaseTarget(npc))
        assertNull(world.teasedBy(npc))
    }

    /**
     * A creature that has left the world cannot be teased.
     *
     * Nothing in this feature deletes a pitfall creature, but the op layer resolves a click to an
     * npc and the click can land a cycle after the creature went. Setting `PlayerFollow` on a
     * slotless npc would leave the mode written on an object no processor will ever visit again.
     */
    @Test
    fun `a creature that has left the world cannot be teased`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)
        world.removeNpc(npc)

        assertFalse(world.tease(player, npc))
        assertNull(world.teasedBy(npc))
    }

    /**
     * Ending a chase puts the creature back to wandering and forgets who teased it.
     *
     * This is the hook the catch belongs on: a creature that has fallen into a pit must stop
     * following, and the record of who teased it is what says whose catch it is. The player-left
     * half needs no code of ours - `NpcPlayerFollowModeProcessor` resets the mode itself the first
     * cycle `facingTarget` comes back null, which is what a logout leaves behind.
     */
    @Test
    fun `ending a chase returns the creature to its default mode`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        world.stopChasing(npc)

        assertEquals(NpcMode.Wander, npc.mode)
        assertNull(world.chaseTarget(npc))
        assertNull(world.teasedBy(npc))
    }

    /* The leash: the three ways a chase ends without anybody clicking anything. */

    /**
     * The longest lure the authored map actually asks for does **not** break the chase.
     *
     * This is the test that protects players from an over-tight bound, and 41 is measured rather
     * than picked: the six kyatt spawns in `.data/raw-cache/map/npcs/rellekka_cold_water.toml` and
     * the six kyatt pits in [PitfallSites] fall into two clusters a map apart, and a hunter whose
     * only set pit is in the far cluster legitimately walks one from `0_42_59_8_14` (2696, 3790)
     * to site 5 (2737, 3784) - 41 Chebyshev tiles. Every other creature's worst run is shorter:
     * larupia 30, graahk 19, sunlight 17, moonlight 9.
     *
     * A creature is not required to be a kyatt to be lured 41 tiles, so the cheapest creature to
     * build is used; the figure is what is under test, not the species.
     */
    @Test
    fun `the longest lure the map asks for does not break the chase`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        world.lureNpc(npc, 41)
        world.tickChases()

        assertEquals(NpcMode.PlayerFollow, npc.mode)
        assertSame(player, world.chaseTarget(npc))
        assertEquals(player.uid, world.teasedBy(npc))
    }

    /**
     * The leash reaches sixty-four tiles and stops at sixty-five.
     *
     * Both figures are literals rather than the constant they are testing, which is the whole
     * point of pinning them: a bound raised or lowered by one tile has to fail here. The pair also
     * pins the comparison itself - a `<` where the code says `<=` would fail the first half and
     * leave the second passing.
     */
    @Test
    fun `a creature sixty-four tiles from its spawn is still chasing`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        world.lureNpc(npc, 64)
        world.tickChases()

        assertSame(player, world.chaseTarget(npc))
        assertEquals(player.uid, world.teasedBy(npc))
    }

    /**
     * A creature lured off the map gives up and goes home, which is the bug this leash exists for.
     *
     * `NpcMode.PlayerFollow` has no timeout, no leash and no give-up condition of its own, and
     * `NpcPlayerFollowModeProcessor` teleports the creature onto the player's exact tile past
     * fifteen. Without [HunterPitfall.tickChases] a teased larupia follows its hunter to Varrock
     * and stands on them there until one of them logs out.
     */
    @Test
    fun `a creature lured past the leash gives up the chase`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        world.lureNpc(npc, 65)
        world.tickChases()

        assertEquals(NpcMode.Wander, npc.mode)
        assertNull(world.chaseTarget(npc))
        assertNull(world.teasedBy(npc))
    }

    /** Far past the leash rather than one tile past it: the same answer, from the other side. */
    @Test
    fun `a creature lured a thousand tiles away gives up the chase`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        world.lureNpc(npc, 1000)
        world.tickChases()

        assertEquals(NpcMode.Wander, npc.mode)
        assertNull(world.teasedBy(npc))
    }

    /**
     * The chase ends when the player who started it leaves the world.
     *
     * Half of this is the engine's: `chaseTarget` reads `playerList[faceEntity.playerSlot]`, and a
     * logged-out player is no longer in that array, so `NpcPlayerFollowModeProcessor` would reset
     * the mode on its own next cycle. The half that is **ours** is [HunterPitfall.teasedBy] coming
     * back null - the record of who to pay for the catch has to go with the chase, or a creature
     * teased by somebody who has since logged out keeps pointing at them forever.
     */
    @Test
    fun `a chase ends when the teaser leaves the world`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        world.removePlayer(player)
        world.tickChases()

        assertEquals(NpcMode.Wander, npc.mode)
        assertNull(world.chaseTarget(npc))
        assertNull(world.teasedBy(npc))
    }

    /**
     * A chase does not pass to whoever inherits the teaser's slot.
     *
     * This is the case a slot check alone gets wrong, and it is why the chase is recorded as a
     * [org.rsmod.game.entity.player.PlayerUid] rather than a slot: `PlayerRegistry` hands a freed
     * slot straight to the next player to log in, and `facingTarget` resolves a slot. A creature
     * left facing slot 0 would silently start chasing - and paying out to - a stranger.
     */
    @Test
    fun `a chase does not transfer to the next player in the teaser's slot`() {
        val teaser = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(teaser, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(teaser, npc))
        val slot = teaser.slotId
        world.removePlayer(teaser)

        val stranger = world.addPlayer(slot = slot)
        assertEquals(slot, stranger.slotId, "the test needs the freed slot to be reused")

        world.tickChases()

        assertEquals(NpcMode.Wander, npc.mode)
        assertNull(world.teasedBy(npc))
    }

    /**
     * A creature that has left the world is forgotten, and is not written to on the way out.
     *
     * `defaultMode` on a slotless npc would set a mode no processor will ever visit again, so the
     * record is dropped and the npc left exactly as the despawn left it. The mode assertion is the
     * one that says "left alone" rather than merely "not crashed".
     */
    @Test
    fun `a chase is forgotten when the creature leaves the world`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        world.removeNpc(npc)
        world.tickChases()

        assertNull(world.teasedBy(npc))
        assertEquals(NpcMode.PlayerFollow, npc.mode)
    }

    /** Two creatures teased by two players do not share a chase. */
    @Test
    fun `each teased creature remembers its own teaser`() {
        val first = world.addPlayer()
        val second = world.addPlayer()
        val firstNpc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        val secondNpc =
            world.addNpc(
                HunterPitfallTestWorld.GRAAHK_NPC,
                HunterPitfallTestWorld.SECOND_CREATURE_TILE,
            )
        world.giveItem(first, HunterPitfallTestWorld.TEASING_STICK)
        world.giveItem(second, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(first, firstNpc))
        assertTrue(world.tease(second, secondNpc))

        assertSame(first, world.chaseTarget(firstNpc))
        assertSame(second, world.chaseTarget(secondNpc))
        assertEquals(first.uid, world.teasedBy(firstNpc))
        assertEquals(second.uid, world.teasedBy(secondNpc))
    }
}
