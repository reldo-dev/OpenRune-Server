package org.rsmod.content.skills.hunter

import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertEquals(setOf("XpModifiers"), collaborators)
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
}
