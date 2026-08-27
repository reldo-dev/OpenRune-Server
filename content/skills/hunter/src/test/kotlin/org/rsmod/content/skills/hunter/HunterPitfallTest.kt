package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.NpcMode
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.map.CoordGrid

/**
 * Pitfall trapping in the branches a live client cannot be made to take - the refusals, the
 * two-creature window, the leash, the draw counts. Every figure is pinned to a literal, never to
 * the constant it is testing read back as its own expected value. Serialised like the rest of the
 * cache-touching suite.
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
     * The two `IdentityHashMap`s are named here on purpose: teasing needed somewhere to record who
     * a creature is chasing and which pit it last refused, and the cheapest wrong answer to either
     * would have been a repository to look creatures up through. They hold nothing but npcs the
     * caller already handed them. The `HashMap` is the per-arming catch count, and it is named for
     * the same reason: "how many creatures has this log taken" is per-player state, and the wrong
     * answer to it would have been twenty-five new server-side varps. `PlayerList` is the engine's
     * own player array, which is what `NpcPlayerFollowModeProcessor` resolves a follow target
     * through and what [HunterPitfall.tick] resolves one through in turn; it is not a repository
     * and cannot add, delete or move anything. The `ArrayList` is the collapse ledger.
     *
     * `NpcRepository` **is** held, and is deliberately not in the forbidden list: a creature that
     * falls into a pit dies, and `despawn` is how every hunter technique kills one. It is the loc
     * repository that must stay unreachable, because a `del` on a pit would take the pit out of the
     * world for everyone until the next restart, and that is the one this feature could plausibly
     * have reached for.
     */
    @Test
    fun `the pitfall engine has no way to reach a loc, controller or obj repository`() {
        val collaborators =
            HunterPitfall::class
                .java
                .declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.type.simpleName }
                .toSet()
        for (forbidden in listOf("LocRepository", "ControllerRepository", "ObjRepository")) {
            assertFalse(forbidden in collaborators, "HunterPitfall must not hold a $forbidden")
        }
        assertEquals(
            setOf(
                "GameRandom",
                "NpcRepository",
                "XpModifiers",
                "PlayerList",
                "IdentityHashMap",
                "ArrayList",
                "HashMap",
            ),
            collaborators,
        )
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

    /**
     * The Hunter xp modifier is *applied*, not merely injected.
     *
     * Every other world in this suite builds its `XpModifiers` from an empty set, which is a flat
     * 1.0, so the `* xpMods.get(player, "stat.hunter")` on the award site could be deleted with
     * every other test still green. Running the same catch twice, once in a doubled world, is what
     * makes the multiplication load-bearing.
     */
    @Test
    fun `the xp modifier scales the pitfall award`() {
        val plain = dismantledFullPitFineXp(hunterXpBonus = 0.0)
        val doubled = dismantledFullPitFineXp(hunterXpBonus = DOUBLE_HUNTER_XP)

        assertEquals(3800, plain, "unmodified, a sunlight antelope is 380.0 xp")
        assertEquals(7600, doubled, "a +100% modifier makes it 760.0")
    }

    /**
     * One dismantled full sunlight pit, in tenths of a point.
     *
     * Replaces [world]: `setUp` puts a fresh default one back before the next test.
     */
    private fun dismantledFullPitFineXp(hunterXpBonus: Double): Int {
        world = HunterPitfallTestWorld(hunterXpBonus = hunterXpBonus)
        val site = HunterPitfallTestWorld.SUNLIGHT_SITE
        val player = world.addPlayer(hunterLvl = 72)
        world.setState(player, site, PitState.Full)

        assertTrue(world.dismantle(player, site))

        return world.hunterFineXp(player)
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
     * neither; the one thing that really leaves a pit there is a logout mid-collapse, and
     * [HunterPitfall.rebuildPits] resolves that on the way back in.
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
     * The spear and the stick tease **identically**, which is this server's decision, not an
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
     * That is this server's call, and the reasoning is worth stating because no source describes
     * it. These npcs carry no `Attack` op and no combat target of their own, so there is nothing in
     * the packed data that reserves one to a player. Refusing the second tease would let anyone
     * park a creature on themselves indefinitely and lock every other hunter out of it, and it
     * would break the wiki's own two-creatures-one-trap procedure, which depends on teasing being
     * cheap and repeatable. The newest teaser therefore wins, and the previous one simply loses the
     * chase.
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
        world.tick()

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
        world.tick()

        assertSame(player, world.chaseTarget(npc))
        assertEquals(player.uid, world.teasedBy(npc))
    }

    /**
     * A creature lured off the map gives up and goes home, which is the bug this leash exists for.
     *
     * `NpcMode.PlayerFollow` has no timeout, no leash and no give-up condition of its own, and
     * `NpcPlayerFollowModeProcessor` teleports the creature onto the player's exact tile past
     * fifteen. Without [HunterPitfall.tick] a teased larupia follows its hunter to Varrock
     * and stands on them there until one of them logs out.
     */
    @Test
    fun `a creature lured past the leash gives up the chase`() {
        val player = world.addPlayer()
        val npc = world.addNpc(HunterPitfallTestWorld.LARUPIA_NPC)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        assertTrue(world.tease(player, npc))
        world.lureNpc(npc, 65)
        world.tick()

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
        world.tick()

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
        world.tick()

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

        world.tick()

        assertEquals(NpcMode.Wander, npc.mode)
        assertNull(world.teasedBy(npc))
    }

    /**
     * Every chase that ends in the same sweep actually ends.
     *
     * The sweep collects what has ended before it ends anything, because [HunterPitfall.stopChasing]
     * writes the map being read - but collecting *entries* is not collecting anything at all. An
     * `IdentityHashMap.Entry` is a live view onto one slot of the map's own table, and a removal
     * runs `closeDeletion`, which rehashes the entries after the hole and can move one into it. A
     * key read back out of a retained entry after that is a neighbour's key, or null. There is no
     * `ConcurrentModificationException`: `Entry.getKey` never looks at `modCount`, so the sweep
     * stops one creature twice and leaves the other following its hunter forever, silently.
     *
     * Twenty-four creatures rather than two because the damage is a table collision, and a table
     * holding two keys rarely has one. Measured over 2,000 runs of the bare `IdentityHashMap`: at
     * four keys the sweep leaves something behind 17% of the time, at twelve 90%, and at twenty or
     * more every single time. Twenty-four is also an ordinary population - a few dozen pitfall
     * creatures is what the map spawns, and one hunter can have teased any number of them.
     *
     * The assertion is on the map rather than on an exception, because there is no exception. A
     * creature still in [HunterPitfall.teasedBy] after this sweep is one the leash has failed to
     * cut loose, which is precisely the unbounded `PlayerFollow` the leash exists to bound.
     */
    @Test
    fun `a sweep that ends two dozen chases at once ends every one of them`() {
        val player = world.addPlayer()
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)
        val npcs =
            List(24) { index ->
                world.addNpc(
                    HunterPitfallTestWorld.LARUPIA_NPC,
                    CoordGrid(3220 + index, 3220, 0),
                )
            }

        for (npc in npcs) {
            assertTrue(world.tease(player, npc), "the test needs every creature chasing")
        }
        for (npc in npcs) {
            world.lureNpc(npc, 65)
        }

        world.tick()

        val stillChasing = npcs.filter { world.teasedBy(it) != null }
        assertTrue(
            stillChasing.isEmpty(),
            "the leash left ${stillChasing.size} of ${npcs.size} creatures chasing",
        )
        val stillFollowing = npcs.filter { it.mode != NpcMode.Wander }
        assertTrue(
            stillFollowing.isEmpty(),
            "${stillFollowing.size} of ${npcs.size} creatures were never handed back",
        )
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
        world.tick()

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

    /* The jump: who goes in, who does not, and what the pit does about it. */

    /**
     * The whole mechanic in one test: tease, jump, and the creature is in the pit.
     *
     * "If the prey is successfully caught, the trap will collapse and the creature will fall into
     * the pit" (wiki, *Pitfall*, oldid=15201220). All three halves of that are asserted, because
     * each can fail on its own: the varbit steps to the collapsing frame, the creature leaves the
     * world, and the chase that led it there is over.
     */
    @Test
    fun `jumping a set pit with a creature crossing catches it and collapses the trap`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, npc) = armedPitWithChaser(site, hunterLvl = 31)

        assertTrue(world.jump(player, site))

        assertEquals(PitState.Catching, world.stateOf(player, site))
        assertEquals(2, world.varbitOf(player, site), "state 2 is the collapsing trap")
        assertFalse(npc.isVisible, "the creature is in the pit, not on the map")
        assertNull(world.teasedBy(npc), "a creature in a pit is not chasing anybody")
        assertEquals(NpcMode.Wander, npc.mode)
    }

    /**
     * A pit a logout stranded mid-collapse is finished on the way back in, not reset.
     *
     * This is the exact state a relog leaves: the varbit persists at [PitState.Catching] and the
     * collapse ledger does not, so nothing in the feature would ever move the pit again -
     * `landCollapses` walks entries that no longer exist, and `dismantlePit` refuses that state
     * outright. Set directly rather than reached through a jump, because setting the varbit with an
     * empty ledger *is* what a loaded save looks like.
     *
     * Finished rather than reset, and the assertion is the whole argument for that choice: the
     * creature was despawned before the varbit was ever written, so the catch has already happened
     * and only its rendering is unfinished. Paying it out mints nothing.
     */
    @Test
    fun `the login rebuild finishes a collapse a logout stranded`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.setState(player, site, PitState.Catching)

        world.rebuildPits(player)

        assertEquals(PitState.Full, world.stateOf(player, site))
        assertTrue(world.dismantle(player, site), "the rebuilt catch is collectable")
        assertEquals(PitState.Empty, world.stateOf(player, site))
    }

    /**
     * Every other state is left exactly as it was.
     *
     * The rebuild is a repair for one stranded value, not a login-time sweep of the technique. An
     * armed pit that survived a logout is still armed, and a full one is still waiting to be
     * dismantled; touching either would cost the player a log or a catch for having logged out.
     */
    @Test
    fun `the login rebuild leaves every other pit state alone`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)

        for (state in listOf(PitState.Empty, PitState.Set, PitState.Full, PitState.FullRotated)) {
            world.setState(player, site, state)
            world.rebuildPits(player)
            assertEquals(state, world.stateOf(player, site), "$state must survive a login")
        }
    }

    /**
     * A collapse that is still counting down is not finished early by a rebuild.
     *
     * Unreachable in production - `landCollapses` drops a departed owner's entries, so a login
     * never finds one of its own - but it is the one way this repair could do damage rather than
     * fix it: landing a catch early would let the pit be dismantled while a second creature was
     * still in the air, which is the mint-from-nothing shape the whole ledger exists to prevent.
     * The guard is one lookup, and this is what proves it is doing something.
     */
    @Test
    fun `the login rebuild does not finish a collapse that is still in flight`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, _) = armedPitWithChaser(site, hunterLvl = 31)
        assertTrue(world.jump(player, site))

        world.rebuildPits(player)

        assertEquals(PitState.Catching, world.stateOf(player, site), "still collapsing")
        // The literal, not `COLLAPSE_CYCLES`: a count read off the constant under test would move
        // with it and assert nothing.
        world.tick(5)
        assertEquals(PitState.Full, world.stateOf(player, site), "and it lands on its own clock")
    }

    /**
     * The pit spends five cycles collapsing and then shows the catch.
     *
     * Both figures are literals rather than the constant under test, which is the point of pinning
     * them: a collapse shortened or lengthened by a cycle has to fail here. The four-tick half also
     * pins the comparison - a landing written on the cycle the count *reaches* zero rather than one
     * cycle early is the off-by-one this catches.
     */
    @Test
    fun `the collapse lands five cycles later and leaves a full pit`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, _) = armedPitWithChaser(site, hunterLvl = 31)
        assertTrue(world.jump(player, site))

        world.tick(4)
        assertEquals(PitState.Catching, world.stateOf(player, site), "four cycles is not enough")

        world.tick(1)
        assertEquals(PitState.Full, world.stateOf(player, site))
        assertEquals(3, world.varbitOf(player, site), "state 3 is the collapsed trap")
    }

    /**
     * A jump with nothing behind you is just a jump.
     *
     * The pit is left armed - a vault does not spring it - and no random draw is taken, which is
     * the assertion that says the catch was never *rolled* rather than merely never landed.
     */
    @Test
    fun `a jump with nothing chasing catches nothing and leaves the pit armed`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.setState(player, site, PitState.Set)

        assertFalse(world.jump(player, site))

        assertEquals(PitState.Set, world.stateOf(player, site))
        assertEquals(0, world.random.doubleDraws, "nothing to catch must not consume a draw")
    }

    /**
     * An empty pit catches nothing, even with a creature standing on it.
     *
     * The cache declares `Jump` on the spiked pit alone - an empty pit carries `Trap` and nothing
     * else - so this is unreachable by clicking, and that is exactly why it is worth a test: the op
     * layer is a later task's, and a handler wired to the wrong loc state would otherwise catch
     * creatures over a hole with no spikes in it.
     */
    @Test
    fun `a jump on an empty pit does nothing, even with a creature crossing it`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)
        val npc = world.addCreatureAt(site)
        assertTrue(world.tease(player, npc))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        assertFalse(world.jump(player, site))

        assertEquals(PitState.Empty, world.stateOf(player, site))
        assertTrue(npc.isVisible, "the creature must be left where it was")
        assertEquals(0, world.random.doubleDraws)
    }

    /**
     * Once the collapse has landed the window is shut: a full pit catches nothing more.
     *
     * This is the two-creature technique's bound **in time**, and only that. What stops a hunter
     * feeding creature after creature into one pit on a single log is the count in
     * `HunterPitfall.PIT_CAPACITY`, which is charged against the arming rather than against what
     * the pit is holding - see `a third creature cannot be caught on the log that already took
     * two` and the test below it. This one says the window closes even with the count unspent.
     */
    @Test
    fun `a jump on a pit that has finished collapsing catches nothing`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, _) = armedPitWithChaser(site, hunterLvl = 31)
        assertTrue(world.jump(player, site))
        world.tick(5)
        assertEquals(PitState.Full, world.stateOf(player, site))

        val second = world.addCreatureAt(site, tilesAway = 1)
        assertTrue(world.tease(player, second))
        val drawsBefore = world.random.doubleDraws

        assertFalse(world.jump(player, site))

        assertEquals(PitState.Full, world.stateOf(player, site))
        assertTrue(second.isVisible, "a full pit must not swallow a second creature")
        assertEquals(drawsBefore, world.random.doubleDraws, "a shut window must not roll")
    }

    /* The roll: two creatures that cannot fail, and three that can. */

    /**
     * An antelope is caught every time, and **no draw is taken for it at all**.
     *
     * "Unlike other creatures hunted via pitfall traps (such as horned graahks), players will
     * always succeed in hunting sunlight antelopes" (wiki, *Sunlight antelope*, oldid=15240378),
     * and the same sentence appears on *Moonlight antelope* (oldid=15197091). Both therefore carry
     * a null `(low, high)` pair, and null has to mean "there is no roll" rather than "there is a
     * roll that always wins": the two are indistinguishable in outcome and completely different in
     * meaning, and a rate that always wins is a rate somebody can later tune downwards by accident.
     *
     * The draw counter is what tells them apart. The scripted RNG is left on its highest draw, so
     * any roll at all - of a real rate, or of a `(256, 256)` pair standing in for certainty - would
     * miss and fail the loop long before the counter is read.
     */
    @Test
    fun `an antelope is always caught, and is never rolled for`() {
        val site = HunterPitfallTestWorld.MOONLIGHT_SITE
        val player = world.addPlayer(hunterLvl = 91)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW

        repeat(50) { attempt ->
            // Emptied rather than merely re-armed: a pit holds two creatures at once, and fifty
            // catches stacked into one of them would be testing the cap instead of the draw.
            world.clearPits(player)
            world.setState(player, site, PitState.Set)
            val npc = world.addCreatureAt(site, tilesAway = attempt % 3)
            assertTrue(world.tease(player, npc))
            assertTrue(world.jump(player, site), "attempt $attempt must catch")
        }

        assertEquals(0, world.random.doubleDraws, "a null pair must take no draw whatever")
    }

    /**
     * A cat can fail, and a failure leaves everything to be done again.
     *
     * "If not successful, the creature will jump over the trap and the player has to lure it
     * again." (wiki, *Pitfall*, oldid=15201220). So the pit stays armed - the log is not spent by a
     * miss - and the creature is still chasing, which is what "lure it again" is possible from.
     */
    @Test
    fun `a cat can fail, and its miss leaves the pit armed and the creature chasing`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW
        val (player, npc) = armedPitWithChaser(site, hunterLvl = 31)

        assertFalse(world.jump(player, site))

        assertEquals(PitState.Set, world.stateOf(player, site))
        assertTrue(npc.isVisible, "a creature that jumped the trap is still on the map")
        assertEquals(player.uid, world.teasedBy(npc), "it keeps chasing, to be lured again")
        assertEquals(1, world.random.doubleDraws, "exactly one draw per attempt")
    }

    /**
     * The larupia's chance at its own level is the engine's own curve, to the draw either side.
     *
     * `SkillingSuccessRate.successRate` is `(1 + floor(low * (99 - L) / 98 + high * (L - 1) / 98 +
     * 0.5)) / 256`. For the larupia's `(53, 325)` at level 31 that is `(1 + floor(36.7755 + 99.4898
     * + 0.5)) / 256` = `137 / 256` = **0.53515625**, worked out here rather than recomputed from
     * the same function the production code calls - a test that asked `SkillingSuccessRate` what
     * `SkillingSuccessRate` returns would pass whatever either did.
     *
     * The comparison is `rate > draw`, so a draw just under the rate catches and one just over it
     * misses. A pair either side pins the curve, the level it was evaluated at, and the direction
     * of the comparison at once.
     */
    @Test
    fun `the larupia's catch chance at its own level is the published curve`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)

        world.setState(player, site, PitState.Set)
        val lucky = world.addCreatureAt(site)
        assertTrue(world.tease(player, lucky))
        world.random.nextDouble = 0.535
        assertTrue(world.jump(player, site), "0.535 is below 137/256 and must catch")

        world.setState(player, site, PitState.Set)
        val unlucky = world.addCreatureAt(site, tilesAway = 1)
        assertTrue(world.tease(player, unlucky))
        world.random.nextDouble = 0.536
        assertFalse(world.jump(player, site), "0.536 is above 137/256 and must miss")
    }

    /**
     * The hunter's spear does **not** improve the catch, and this is the test that keeps it out.
     *
     * "When using hunter's spears, they give a 5% increased chance to successfully tease creatures"
     * (wiki, *Pitfall*, oldid=15201220), agreeing with the Jagex newspost that page's sibling cites
     * - "an increased chance to successfully tease creatures like Kyatt, Ghaark and Larupia by 5%"
     * (*Varlamore: Part One - Overview*, 20 January 2024). The *Hunter's spear* page instead reads
     * the same bonus onto the catch, contradicting both, and it is the weaker source.
     *
     * That decision otherwise lives only in [HunterPitfall.teaseCreature]'s prose, which cannot
     * fail. The draw is chosen so that **any** of the three plausible ways of applying a +5% would
     * turn this miss into a catch: on the rate multiplicatively (0.5352 x 1.05 = 0.5619), on the
     * rate as five points (0.5852), or on both coefficients as `(53 + 13, 325 + 13)`, which is
     * `150 / 256` = 0.5859. All three sit above 0.55; the base rate, 0.53515625, sits below it.
     */
    @Test
    fun `an equipped hunter's spear does not improve the catch`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val stickUser = world.addPlayer(hunterLvl = 31)
        val spearUser = world.addPlayer(hunterLvl = 31)
        world.giveItem(stickUser, HunterPitfallTestWorld.TEASING_STICK)
        world.giveItem(spearUser, HunterPitfallTestWorld.TEASING_STICK)
        world.wearItem(spearUser, HunterPitfallTestWorld.HUNTERS_SPEAR)

        // The pits are private, so both hunters can arm and jump the same site.
        world.setState(stickUser, site, PitState.Set)
        world.setState(spearUser, site, PitState.Set)
        val stickTarget = world.addCreatureAt(site)
        val spearTarget = world.addCreatureAt(site, tilesAway = 1)
        assertTrue(world.tease(stickUser, stickTarget))
        assertTrue(world.tease(spearUser, spearTarget))
        world.random.nextDouble = 0.55

        assertFalse(world.jump(stickUser, site), "0.55 misses 137/256")
        assertFalse(world.jump(spearUser, site), "the spear must not move the catch roll")

        assertEquals(PitState.Set, world.stateOf(spearUser, site))
        assertEquals(2, world.random.doubleDraws, "both attempts rolled, once each")
    }

    /* The refusal: one pit deep, and not a grudge. */

    /**
     * "These creatures will not jump the same pit twice in a row."
     *
     * The second attempt is at the pit the creature has just vaulted, with an RNG that would catch
     * anything - and it catches nothing, without taking a draw. The draw count is the load-bearing
     * assertion: a refusal implemented as "roll and lose" would leave the outcome right and the
     * meaning wrong, and would occasionally catch.
     */
    @Test
    fun `a creature refuses the pit it has just jumped over`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW
        val (player, npc) = armedPitWithChaser(site, hunterLvl = 31)

        assertFalse(world.jump(player, site), "the first attempt misses")
        assertEquals(1, world.random.doubleDraws)

        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        assertFalse(world.jump(player, site), "the same pit, immediately, is refused")

        assertEquals(PitState.Set, world.stateOf(player, site))
        assertTrue(npc.isVisible)
        assertEquals(1, world.random.doubleDraws, "a refusal must not roll")
    }

    /**
     * The refusal is **one pit deep**, not a grudge that accumulates.
     *
     * "Since these creatures will not jump the same pit twice in a row, the next attempt must be at
     * another pit." (wiki, *Pitfall*, oldid=15201220). Twice *in a row*: once another pit has
     * intervened, the first one is fair game again. Modelling this as a set of every pit a creature
     * has ever refused would quietly retire a hunting ground one pit at a time, and would pass any
     * test that only checked the immediate refusal above.
     */
    @Test
    fun `a creature is caught by the pit it vaulted once another pit has intervened`() {
        val first = HunterPitfallTestWorld.LARUPIA_SITE
        val second = HunterPitfallTestWorld.SECOND_LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW
        val (player, npc) = armedPitWithChaser(first, hunterLvl = 31)
        world.setState(player, second, PitState.Set)

        assertFalse(world.jump(player, first), "vaults the first pit and remembers it")

        world.moveNpcTo(npc, second)
        assertFalse(world.jump(player, second), "vaults the second pit, which replaces the memory")

        world.moveNpcTo(npc, first)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        assertTrue(world.jump(player, first), "the first pit is no longer the last one vaulted")

        assertEquals(PitState.Catching, world.stateOf(player, first))
        assertFalse(npc.isVisible)
    }

    /**
     * A creature refused by one pit is catchable at the next pit along straight away.
     *
     * The other half of the rule: the memory is of the pit, not of the creature's willingness to be
     * caught at all. Without this, a single miss would take a creature out of the technique.
     */
    @Test
    fun `a creature that vaulted one pit is caught by another immediately`() {
        val first = HunterPitfallTestWorld.LARUPIA_SITE
        val second = HunterPitfallTestWorld.SECOND_LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW
        val (player, npc) = armedPitWithChaser(first, hunterLvl = 31)
        world.setState(player, second, PitState.Set)

        assertFalse(world.jump(player, first))

        world.moveNpcTo(npc, second)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        assertTrue(world.jump(player, second))

        assertEquals(PitState.Catching, world.stateOf(player, second))
        assertEquals(PitState.Set, world.stateOf(player, first), "the other pit is untouched")
    }

    /**
     * A chase the leash ends takes the creature's refusal with it.
     *
     * "Twice **in a row**" is a fact about one lure. A creature that vaulted a pit and was then
     * walked so far from home that [HunterPitfall.tick] cut the chase loose is not in the
     * middle of anything any more, and the hunter who teases it again is making a fresh first
     * attempt.
     *
     * The leak underneath this is the reason it is worth a test rather than a judgement call. The
     * refusal map is only ever swept by `tick`, and `tick` walks the *chases* map - so
     * a refusal left behind by a chase that has already been taken out of that map is never looked
     * at again by anything. It outlives the lure, the walk home, and the creature's next respawn,
     * and shows up as a pit that one particular creature will not go near for no reason a player
     * could ever discover.
     */
    @Test
    fun `a chase the leash ends takes the creature's refusal with it`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW
        val (player, npc) = armedPitWithChaser(site, hunterLvl = 31)

        assertFalse(world.jump(player, site), "the first attempt misses and the pit is remembered")

        world.lureNpc(npc, 65)
        world.tick()
        assertNull(world.teasedBy(npc), "the leash has ended the chase")

        world.moveNpcTo(npc, site)
        assertTrue(world.tease(player, npc), "a fresh lure back to the same pit")
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        assertTrue(world.jump(player, site), "a new chase is a fresh first attempt")

        assertEquals(PitState.Catching, world.stateOf(player, site))
        assertFalse(npc.isVisible)
    }

    /* Two creatures, one trap. */

    /**
     * The documented two-for-one, end to end, including both dismantles.
     *
     * "It is possible, if acting quickly, to lure one creature into a trap and tease a second one
     * into the same trap as the first is still walking over it, netting two kills for one trap. 1.
     * Tease creature A and jump over your pitfall trap. 2. Quickly tease nearby creature B and jump
     * over your pitfall trap again while creature A is still walking over it. 3. Dismantle the
     * results of creature A falling. 4. Dismantle the results of creature B falling." (wiki,
     * *Pitfall*, oldid=15201220).
     *
     * This is the test that a per-pit lock would fail: the obvious implementation of a catch -
     * reserving the pit for the creature heading into it, or refusing any state but a freshly armed
     * one - makes a documented technique impossible. The pit is jumped a second time while it is
     * still in [PitState.Catching], and the two catches are then collected one dismantle at a time.
     *
     * **The first dismantle is taken between the two landings**, which is the wiki's own step 3:
     * the results of creature A falling are there to collect as soon as A lands, and B is still in
     * the air at that point. Ticking past the whole window before dismantling steps straight over
     * that sequence, and the hole it leaves is not cosmetic: collecting A off a pit that only
     * looked at what had *landed* writes the pit empty, and B then arrives on an empty pit and is
     * thrown away having paid nothing. The middle assertion is the load-bearing one: the pit has to
     * be left **collapsing**, because B has still to land in it.
     */
    @Test
    fun `two creatures caught in one collapse are worth a dismantle each`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, first) = armedPitWithChaser(site, hunterLvl = 31)

        assertTrue(world.jump(player, site), "creature A goes in")
        world.tick(2)
        assertEquals(PitState.Catching, world.stateOf(player, site), "A is still crossing")

        val second = world.addCreatureAt(site, tilesAway = 1)
        assertTrue(world.tease(player, second))
        assertTrue(world.jump(player, site), "creature B goes into the same trap")

        assertFalse(first.isVisible)
        assertFalse(second.isVisible)

        world.tick(3)
        assertEquals(PitState.Full, world.stateOf(player, site), "A has landed, B has two to go")

        assertTrue(world.dismantle(player, site), "the results of creature A falling")
        assertEquals(1, world.itemCount(player, "obj.hunting_fur_jaguar_perfect"))
        assertEquals(
            PitState.Catching,
            world.stateOf(player, site),
            "the pit stays collapsing: creature B has still to land in it",
        )

        world.tick(2)
        assertEquals(PitState.Full, world.stateOf(player, site), "and B lands in it")

        assertTrue(world.dismantle(player, site), "the results of creature B falling")
        assertEquals(2, world.itemCount(player, "obj.hunting_fur_jaguar_perfect"))
        assertEquals(2, world.itemCount(player, "obj.big_bones"))
        assertEquals(PitState.Empty, world.stateOf(player, site), "and now the pit is empty")
    }

    /**
     * The same two-for-one with both catches landed, which is the other order to collect in.
     *
     * A hunter who does not dismantle the instant creature A lands has two catches sitting in one
     * pit, and they are still worth a dismantle each. This is the half that pins the *second*
     * entry's own rendering being what the pit is left showing - not merely "still full" - since a
     * landed sibling keeps the pit at its own rotation rather than at the collected one's.
     */
    @Test
    fun `both catches landed before the dismantle are still worth a dismantle each`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, _) = armedPitWithChaser(site, hunterLvl = 31)

        assertTrue(world.jump(player, site), "creature A goes in")
        world.tick(2)
        val second = world.addCreatureAt(site, tilesAway = 1)
        assertTrue(world.tease(player, second))
        assertTrue(world.jump(player, site), "creature B goes into the same trap")

        world.tick(5)
        assertEquals(PitState.Full, world.stateOf(player, site), "both have landed")

        assertTrue(world.dismantle(player, site), "the results of creature A falling")
        assertEquals(1, world.itemCount(player, "obj.hunting_fur_jaguar_perfect"))
        assertEquals(PitState.Full, world.stateOf(player, site), "creature B is still in there")

        assertTrue(world.dismantle(player, site), "the results of creature B falling")
        assertEquals(2, world.itemCount(player, "obj.hunting_fur_jaguar_perfect"))
        assertEquals(2, world.itemCount(player, "obj.big_bones"))
        assertEquals(PitState.Empty, world.stateOf(player, site), "and now the pit is empty")
    }

    /**
     * A pit dismantled between the landings cannot be re-armed under the creature still falling.
     *
     * The other half of the same hole, and the one that mints rather than destroys. A collection
     * that wrote the pit empty while a sibling was in the air would hand the player a pit they
     * could arm and catch a third creature in; the abandoned entry would then land on the *new*
     * catch's collapsing frame and the new one land behind it, leaving two landed catches in a pit
     * that had taken one creature since - the same mint-from-nothing shape `clearPits` cancels its
     * own ledger entries to avoid, reached here without `clearPits`.
     *
     * Leaving the pit collapsing closes it at the source: an armed pit is one no creature is
     * falling into, so `trapPit`'s "there is something in this trap already" is the whole guard.
     */
    @Test
    fun `a pit dismantled between the landings cannot be re-armed under the creature falling`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, _) = armedPitWithChaser(site, hunterLvl = 31)

        assertTrue(world.jump(player, site))
        world.tick(2)
        val second = world.addCreatureAt(site, tilesAway = 1)
        assertTrue(world.tease(player, second))
        assertTrue(world.jump(player, site))

        world.tick(3)
        assertTrue(world.dismantle(player, site), "the results of creature A falling")

        world.giveTrapKit(player)
        assertFalse(world.trap(player, site), "creature B is still falling into this pit")
        assertEquals(PitState.Catching, world.stateOf(player, site))
        val logs = world.itemCount(player, HunterPitfallTestWorld.LOGS)
        assertEquals(1, logs, "the log is not spent")

        world.tick(2)
        assertTrue(world.dismantle(player, site), "the results of creature B falling")
        assertEquals(2, world.itemCount(player, "obj.hunting_fur_jaguar_perfect"), "two creatures")
        assertEquals(PitState.Empty, world.stateOf(player, site))
    }

    /**
     * One log takes two creatures and no more, however quick the hunter is.
     *
     * The wiki's technique is a two-for-one and its four steps name exactly two creatures: "lure
     * one creature into a trap and tease a second one into the same trap as the first is still
     * walking over it, netting two kills for one trap" (wiki, *Pitfall*, oldid=15201220). Nothing
     * describes a third. Left bounded by the collapse timing alone, a third *is* reachable - the
     * window is five cycles wide and a hunter with a creature already teased needs one click - so
     * how many creatures a single log buys would be a number nobody chose, and one that a later
     * tweak to the collapse timing would quietly change.
     *
     * This is the narrow half: nothing has been collected, so the pit is holding two and the count
     * charged against the log is two, and a cap counting either would refuse the third. The test
     * below it is the half that tells those two readings apart.
     *
     * The draw counter is asserted for the reason the refusal rule's is: a cap implemented as a
     * roll that loses would leave the outcome right and the meaning wrong.
     */
    @Test
    fun `a third creature cannot be caught on the log that already took two`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, _) = armedPitWithChaser(site, hunterLvl = 31)

        assertTrue(world.jump(player, site), "creature A goes in")
        world.tick(1)
        val second = world.addCreatureAt(site, tilesAway = 1)
        assertTrue(world.tease(player, second))
        assertTrue(world.jump(player, site), "creature B goes in behind it")

        world.tick(1)
        val third = world.addCreatureAt(site, tilesAway = 2)
        assertTrue(world.tease(player, third))
        val drawsBefore = world.random.doubleDraws

        assertFalse(world.jump(player, site), "the pit already holds two")

        assertTrue(third.isVisible, "the third creature is left standing on the map")
        assertEquals(drawsBefore, world.random.doubleDraws, "a full pit must not roll")
        assertEquals(PitState.Catching, world.stateOf(player, site))

        world.tick(5)
        assertTrue(world.dismantle(player, site))
        assertTrue(world.dismantle(player, site))
        assertEquals(2, world.itemCount(player, "obj.hunting_fur_jaguar_perfect"), "two, not three")
        assertEquals(PitState.Empty, world.stateOf(player, site), "two dismantles empty the pit")
    }

    /**
     * Collecting the first of two catches does **not** buy the log a third creature.
     *
     * The bug this pins was live, and the sequence is four clicks a hunter doing the documented
     * two-for-one would make anyway. A cap counted against what the pit is *holding* falls by one
     * the instant creature A is collected - and `HunterPitfall.takeCatch` correctly leaves such a
     * pit at [PitState.Catching], because creature B has still to land in it, which is a state the
     * `Jump` op accepts. So the freed place is immediately usable, and the chain repeats for as
     * long as the hunter keeps one catch in the air: three creatures on one log, then four.
     *
     * The count therefore belongs to the **arming**, not to the pit's contents. Charged that way,
     * the log that took A and B is spent whatever the hunter collects, and the third creature is
     * refused with B still falling - which is exactly the moment a cap counted against the pit's
     * contents says yes.
     *
     * The draw counter is asserted for the reason the narrow half's is: a refusal implemented as a
     * roll that loses would leave the outcome right and the meaning wrong. The two dismantles at
     * the end are what says nothing was destroyed to get it - two creatures went in and two are
     * paid for.
     */
    @Test
    fun `collecting one catch does not free the log a place for a third`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, _) = armedPitWithChaser(site, hunterLvl = 31)

        assertTrue(world.jump(player, site), "creature A goes in")
        world.tick(2)
        val second = world.addCreatureAt(site, tilesAway = 1)
        assertTrue(world.tease(player, second))
        assertTrue(world.jump(player, site), "creature B goes in behind it")

        world.tick(3)
        assertEquals(PitState.Full, world.stateOf(player, site), "A has landed, B has two to go")
        assertTrue(world.dismantle(player, site), "the results of creature A falling")
        assertEquals(
            PitState.Catching,
            world.stateOf(player, site),
            "the pit stays collapsing: creature B has still to land in it",
        )

        val third = world.addCreatureAt(site, tilesAway = 2)
        assertTrue(world.tease(player, third))
        val drawsBefore = world.random.doubleDraws

        assertFalse(world.jump(player, site), "the log that armed this pit has taken its two")

        assertTrue(third.isVisible, "the third creature is left standing on the map")
        assertEquals(drawsBefore, world.random.doubleDraws, "a spent log must not roll")

        world.tick(2)
        assertTrue(world.dismantle(player, site), "the results of creature B falling")
        assertEquals(2, world.itemCount(player, "obj.hunting_fur_jaguar_perfect"), "two, not three")
        assertEquals(PitState.Empty, world.stateOf(player, site))
    }

    /**
     * A second log buys a second pair, which is the bound not being a lifetime.
     *
     * The counterweight to the two tests above: the cap is charged against the arming, so arming
     * the pit again with another log has to hand it a full pair back. A cap that had over-tightened
     * into "this pit has taken its two, ever" would pass both of those and fail here, and the two
     * are indistinguishable until a pit is emptied and rebuilt.
     *
     * Built through `trapPit` rather than written, because the log is the thing under test: the
     * hunter pays a second one and gets a second window for it.
     */
    @Test
    fun `a second log buys the pit a second pair of catches`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, _) = armedPitWithChaser(site, hunterLvl = 31)

        assertTrue(world.jump(player, site), "creature A goes in")
        world.tick(2)
        val second = world.addCreatureAt(site, tilesAway = 1)
        assertTrue(world.tease(player, second))
        assertTrue(world.jump(player, site), "creature B goes in behind it")

        world.tick(5)
        assertTrue(world.dismantle(player, site), "the results of creature A falling")
        assertTrue(world.dismantle(player, site), "the results of creature B falling")
        assertEquals(PitState.Empty, world.stateOf(player, site), "the first log is spent")

        world.giveTrapKit(player)
        assertTrue(world.trap(player, site), "a second log arms the pit again")
        assertEquals(PitState.Set, world.stateOf(player, site))

        val third = world.addCreatureAt(site, tilesAway = 2)
        assertTrue(world.tease(player, third))

        assertTrue(world.jump(player, site), "the new log takes a creature of its own")

        assertEquals(PitState.Catching, world.stateOf(player, site))
        world.tick(5)
        assertTrue(world.dismantle(player, site))
        assertEquals(3, world.itemCount(player, "obj.hunting_fur_jaguar_perfect"), "two, then one")
    }

    /**
     * A pit that is full with nothing left in the ledger empties on one dismantle.
     *
     * The transient half of the two-creature window - which pit holds two - does not survive a
     * logout, and the varbit that does cannot express it. So a full pit with no ledger entry, which
     * is what a relog leaves behind, has to pay exactly once and empty rather than becoming a pit
     * that can never be emptied.
     */
    @Test
    fun `a full pit with no pending catch pays once and empties`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 31)
        world.setState(player, site, PitState.Full)

        assertTrue(world.dismantle(player, site))

        assertEquals(1, world.itemCount(player, "obj.hunting_fur_jaguar_perfect"))
        assertEquals(PitState.Empty, world.stateOf(player, site))
    }

    /* Whose catch it is, what kind, and how close. */

    /**
     * A creature chasing somebody else does not fall into your pit.
     *
     * The chase is recorded against the hunter who teased it, and this is what that record is for:
     * without the owner check, the first player to jump any pit would collect whatever the whole
     * hunting ground had lured.
     */
    @Test
    fun `a creature chasing another hunter is not caught`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val teaser = world.addPlayer(hunterLvl = 31)
        val jumper = world.addPlayer(hunterLvl = 31)
        world.giveItem(teaser, HunterPitfallTestWorld.TEASING_STICK)
        world.setState(jumper, site, PitState.Set)
        val npc = world.addCreatureAt(site)
        assertTrue(world.tease(teaser, npc))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        assertFalse(world.jump(jumper, site))

        assertEquals(PitState.Set, world.stateOf(jumper, site))
        assertTrue(npc.isVisible)
        assertEquals(teaser.uid, world.teasedBy(npc), "somebody else's lure is left alone")
        assertEquals(0, world.random.doubleDraws)
    }

    /**
     * A graahk does not fall into a larupia pit.
     *
     * The five hunting grounds do not overlap, so this cannot happen by walking - but the pit's
     * loot, its experience and its catch rate are all the *site's* creature's, so a pit that
     * accepted whatever was chasing would pay larupia fur for a graahk. The five species are
     * checked by npc id rather than by area for that reason.
     */
    @Test
    fun `a creature of another species does not fall into this pit`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        val player = world.addPlayer(hunterLvl = 41)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)
        world.setState(player, site, PitState.Set)
        val graahk = world.addNpc(HunterPitfallTestWorld.GRAAHK_NPC, site.coords)
        assertTrue(world.tease(player, graahk))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        assertFalse(world.jump(player, site))

        assertEquals(PitState.Set, world.stateOf(player, site))
        assertTrue(graahk.isVisible)
        assertEquals(0, world.random.doubleDraws)
    }

    /**
     * The catch reaches three tiles from the pit and stops at four.
     *
     * Both figures are literals rather than the constant they are testing, and the pair pins the
     * comparison as well as the distance: a `<` where the code says `<=` would fail the first half
     * and leave the second passing. Three is the figure both reference servers arrived at from
     * opposite directions; see [HunterPitfall.jumpPit].
     */
    @Test
    fun `a creature three tiles from the pit is caught and one four tiles away is not`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val (near, _) = armedPitWithChaser(site, hunterLvl = 31, tilesAway = 3)
        assertTrue(world.jump(near, site), "three tiles is crossing the pit")
        assertEquals(PitState.Catching, world.stateOf(near, site))

        val (far, farNpc) = armedPitWithChaser(site, hunterLvl = 31, tilesAway = 4)
        assertFalse(world.jump(far, site), "four tiles is not")
        assertEquals(PitState.Set, world.stateOf(far, site))
        assertTrue(farNpc.isVisible)
        assertEquals(1, world.random.doubleDraws, "only the catch within range rolled")
    }

    /* The collapsed rendering, the ledger, and the payout's timing. */

    /**
     * The two collapsed states are the same corpse a half-turn apart, and the side the creature
     * crossed from is what picks between them.
     *
     * The cache pairs every creature's collapsed loc with a `_180` twin - larupia 19232/19235,
     * graahk 19231/19234, kyatt 19233/19236 - and the *Pitfall* page's infobox lists each pair
     * under one "Collapsed trap" entry, which is what says the difference is orientation and not
     * content. Both states carry `Dismantle` and pay identically; this pins that the choice is made
     * from the creature's tile at all rather than being hardcoded to one of the two.
     */
    @Test
    fun `the collapsed rendering follows the side the creature crossed from`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        val (fromEast, _) = armedPitWithChaser(site, hunterLvl = 31, tilesAway = 1)
        assertTrue(world.jump(fromEast, site))
        world.tick(5)
        assertEquals(PitState.Full, world.stateOf(fromEast, site))
        assertEquals(3, world.varbitOf(fromEast, site))

        val (fromWest, _) = armedPitWithChaser(site, hunterLvl = 31, tilesAway = -1)
        assertTrue(world.jump(fromWest, site))
        world.tick(5)
        assertEquals(PitState.FullRotated, world.stateOf(fromWest, site))
        assertEquals(4, world.varbitOf(fromWest, site))
    }

    /**
     * A catch pays nothing until the pit is taken apart.
     *
     * Every other reward in this feature is on the dismantle, and the catch has to stay that way:
     * the pit is what holds the creature, and a catch that paid on the way in would pay twice for
     * anyone who then dismantled the pit it left behind.
     */
    @Test
    fun `a catch awards nothing until the pit is dismantled`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, _) = armedPitWithChaser(site, hunterLvl = 31)

        assertTrue(world.jump(player, site))
        world.tick(5)

        assertEquals(0, world.hunterXp(player), "no experience for the catch itself")
        assertEquals(0, world.itemCount(player, "obj.big_bones"))

        assertTrue(world.dismantle(player, site))

        assertEquals(180, world.hunterXp(player), "the larupia's 180, paid on collection")
        assertEquals(1, world.itemCount(player, "obj.big_bones"))
    }

    /**
     * Clearing a player's pits cancels a collapse that has not landed yet.
     *
     * [HunterPitfall.clearPits] is the suites' own reset - nothing in the game calls it - and a
     * catch left in the ledger by it does not merely linger: it lands on whatever that pit is doing
     * later. The sequence below is the one that turns it into a duplicated payout - clear a pit
     * mid-collapse, rebuild it, catch something else, and the abandoned catch lands on the *new*
     * collapse's frame, ending it early and leaving the real one behind it as a second, unearned
     * dismantle. One creature, two furs.
     *
     * The first assertion is where that shows: three cycles after the second catch the pit must
     * still be collapsing, because nothing else is due to land.
     */
    @Test
    fun `clearing a player's pits cancels a collapse that has not landed`() {
        val site = HunterPitfallTestWorld.LARUPIA_SITE
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val (player, _) = armedPitWithChaser(site, hunterLvl = 31)
        assertTrue(world.jump(player, site))

        world.clearPits(player)
        assertEquals(PitState.Empty, world.stateOf(player, site), "a cleared pit is empty")
        world.tick(2)

        // The pit is rebuilt and catches something else while the abandoned collapse would still
        // have been in the air.
        world.setState(player, site, PitState.Set)
        val second = world.addCreatureAt(site, tilesAway = 1)
        assertTrue(world.tease(player, second))
        assertTrue(world.jump(player, site))

        world.tick(3)
        assertEquals(PitState.Catching, world.stateOf(player, site), "nothing else is due to land")

        world.tick(2)
        assertEquals(PitState.Full, world.stateOf(player, site))

        assertTrue(world.dismantle(player, site))
        assertEquals(1, world.itemCount(player, "obj.hunting_fur_jaguar_perfect"), "one creature")
        assertEquals(PitState.Empty, world.stateOf(player, site), "one catch, one dismantle")
    }

    /**
     * Every creature's leap sequence is **packed**, not merely resolvable to an id.
     *
     * `PathingEntityCommon.anim` reads the sequence's own priority out of the packed cache and
     * dereferences the result, so a name that resolves to an id with no definition behind it throws
     * on the first catch rather than at boot. A gameval needs both declarations - the id and the
     * packed definition - and `PitfallCreaturesTest` only proves the first half of that.
     */
    @Test
    fun `every creature's leap sequence is packed, not merely resolvable`() {
        for (creature in PitfallCreatures.all) {
            assertNotNull(
                ServerCacheManager.getAnim(creature.leapSeq.asRSCM(RSCMType.SEQ)),
                "no packed seq for ${creature.leapSeq}",
            )
        }
    }

    /**
     * An armed pit, a hunter carrying a teasing stick, and the pit's own creature already chasing
     * them from [tilesAway] tiles east of the pit.
     *
     * The pit is written rather than built, because what is under test below is the jump: a build
     * would drag the level gate, the knife and the log tiers into every one of these tests.
     */
    private fun armedPitWithChaser(
        site: PitfallSite,
        hunterLvl: Int,
        tilesAway: Int = 0,
    ): Pair<Player, Npc> {
        val player = world.addPlayer(hunterLvl = hunterLvl)
        world.setState(player, site, PitState.Set)
        world.giveItem(player, HunterPitfallTestWorld.TEASING_STICK)
        val npc = world.addCreatureAt(site, tilesAway)
        assertTrue(world.tease(player, npc), "the test needs the creature chasing")
        return player to npc
    }
}
