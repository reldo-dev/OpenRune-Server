package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.game.entity.Player

/**
 * The crab-trapping lifecycle, in the branches a live client cannot be made to take.
 *
 * The valuable half of this file is the refusals and the races: the Construction gate, a matured
 * catch landing on a trap that was emptied while the crab was walking, the materials that are
 * consumed against the tools that are not, and the cap. A player can demonstrate a successful catch
 * in ten seconds; nothing but a test can demonstrate that an unbaited trap catches nothing *ever*,
 * because the way to observe that is to wait forever.
 *
 * Serialised for the reason the rest of the suite is: `ServerCacheManager` is a singleton and `RSCM`
 * memoises into a plain `HashMap`.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class CrabTrapTest {
    private lateinit var world: HunterCrabTrapTestWorld

    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
        world = HunterCrabTrapTestWorld()
    }

    /* The never-touch-a-map-loc invariant. */

    /**
     * Crab trapping cannot delete a map loc, because it cannot reach one.
     *
     * The deadfall and the net trap both need a runtime `check` to keep a permanent map loc away
     * from `locRepo.del`. This technique needs none: it holds no loc repository at all, so the
     * failure mode is not guarded against, it is unrepresentable. Asserting on the constructor is
     * the only way to say that - a test that watched a repository stay untouched would keep passing
     * the day somebody injected one.
     */
    @Test
    fun `the crab trap engine has no way to reach a loc, controller or npc repository`() {
        // Instance fields only: the companion's constants are static and are not collaborators.
        val collaborators =
            HunterCrabTrap::class
                .java
                .declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.type.simpleName }
                .toSet()
        for (forbidden in
            listOf("LocRepository", "ControllerRepository", "NpcRepository", "ObjRepository")) {
            assertFalse(forbidden in collaborators, "HunterCrabTrap must not hold a $forbidden")
        }
        assertEquals(setOf("GameRandom", "XpModifiers"), collaborators)
    }

    /* Build. */

    @Test
    fun `building a hole with the level, the tools and the materials leaves an empty trap`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = world.addPlayer(constructionLvl = HunterCrabTrap.CRAB_TRAP_CONSTRUCTION_LEVEL)
        world.giveBuildKit(player)

        assertTrue(world.build(player, site))
        assertEquals(site.builtState, world.stateOf(player, site))
    }

    /**
     * The Construction gate, one level below the requirement.
     *
     * Unreachable from a client without an account parked at Construction 9, and the branch most
     * likely to be quietly dropped: the wiki mentions it in one sentence of a Hunter page.
     */
    @Test
    fun `a hole cannot be built one level below the construction requirement`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player =
            world.addPlayer(constructionLvl = HunterCrabTrap.CRAB_TRAP_CONSTRUCTION_LEVEL - 1)
        world.giveBuildKit(player)

        assertFalse(world.build(player, site))
        assertEquals(site.unbuiltState, world.stateOf(player, site))
        assertMaterialsUntouched(player)
    }

    /** Building is not Hunter-gated: "All traps can be built immediately, regardless of level." */
    @Test
    fun `a level-1 hunter can build a trap they cannot yet bait`() {
        val site = HunterCrabTrapTestWorld.RAINBOW_SITE
        val player = world.addPlayer(hunterLvl = 1)
        world.giveBuildKit(player)
        world.giveItem(player, site.creature.bait, 5)

        assertTrue(world.build(player, site), "the build is not Hunter-gated")
        assertFalse(world.bait(player, site), "the bait is")
        assertEquals(site.builtState, world.stateOf(player, site))
    }

    /** The saw and the hammer are tools: held, checked, and never consumed. */
    @Test
    fun `a build consumes the plank, the bucket and two nails and keeps both tools`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = world.addPlayer()
        world.giveBuildKit(player, nailCount = 5)

        assertTrue(world.build(player, site))

        assertEquals(0, world.itemCount(player, HunterCrabTrap.CRAB_TRAP_PLANK), "plank consumed")
        assertEquals(0, world.itemCount(player, HunterCrabTrap.CRAB_TRAP_BUCKET), "bucket consumed")
        assertEquals(
            5 - HunterCrabTrap.CRAB_TRAP_NAIL_COUNT,
            world.itemCount(player, "obj.nails"),
            "exactly ${HunterCrabTrap.CRAB_TRAP_NAIL_COUNT} nails consumed",
        )
        assertEquals(1, world.itemCount(player, "obj.poh_saw"), "the saw is a tool")
        assertEquals(
            1,
            world.itemCount(player, HunterCrabTrap.CRAB_TRAP_HAMMER),
            "the hammer is a tool",
        )
    }

    /** One nail short is a refusal, and it costs nothing. */
    @Test
    fun `a build with one nail is refused and charges nothing`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = world.addPlayer()
        world.giveBuildKit(player, nailCount = HunterCrabTrap.CRAB_TRAP_NAIL_COUNT - 1)

        assertFalse(world.build(player, site))
        assertEquals(site.unbuiltState, world.stateOf(player, site))
        assertEquals(1, world.itemCount(player, HunterCrabTrap.CRAB_TRAP_PLANK))
        assertEquals(1, world.itemCount(player, HunterCrabTrap.CRAB_TRAP_BUCKET))
        assertEquals(1, world.itemCount(player, "obj.nails"))
    }

    /**
     * A short stack in an early slot must not refuse a build the later stack can pay for.
     *
     * "two nails of any type" - so a single leftover bronze nail sitting above a hundred steel ones
     * is not a reason to fail. The steel is charged and the bronze is left alone.
     */
    @Test
    fun `a single stray nail above a usable stack does not block the build`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = world.addPlayer()
        world.giveItem(player, "obj.nails_bronze", 1)
        world.giveBuildKit(player, nailCount = 10)

        assertTrue(world.build(player, site))
        assertEquals(1, world.itemCount(player, "obj.nails_bronze"), "the short stack is untouched")
        assertEquals(
            10 - HunterCrabTrap.CRAB_TRAP_NAIL_COUNT,
            world.itemCount(player, "obj.nails"),
        )
    }

    @Test
    fun `every accepted nail type can build a trap`() {
        for (nails in HunterCrabTrap.CRAB_TRAP_NAILS) {
            val site = HunterCrabTrapTestWorld.RED_SITE
            val player = world.addPlayer()
            world.giveBuildKit(player, nails = nails)
            assertTrue(world.build(player, site), "$nails must build a trap")
        }
    }

    @Test
    fun `every accepted saw can build a trap, and no saw at all cannot`() {
        for (saw in HunterCrabTrap.CRAB_TRAP_SAWS) {
            val site = HunterCrabTrapTestWorld.RED_SITE
            val player = world.addPlayer()
            world.giveBuildKit(player, saw = saw)
            assertTrue(world.build(player, site), "$saw must build a trap")
        }

        val sawless = world.addPlayer()
        world.giveItem(sawless, HunterCrabTrap.CRAB_TRAP_HAMMER)
        world.giveItem(sawless, HunterCrabTrap.CRAB_TRAP_PLANK)
        world.giveItem(sawless, HunterCrabTrap.CRAB_TRAP_BUCKET)
        world.giveItem(sawless, "obj.nails", HunterCrabTrap.CRAB_TRAP_NAIL_COUNT)
        assertFalse(world.build(sawless, HunterCrabTrapTestWorld.RED_SITE))
    }

    /** "Built traps remain there permanently and cannot be removed; they only need to be built once." */
    @Test
    fun `a built trap cannot be built again`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = world.addPlayer()
        world.giveBuildKit(player)
        world.giveBuildKit(player)

        assertTrue(world.build(player, site))
        assertFalse(world.build(player, site), "the hole is gone")
        assertEquals(1, world.itemCount(player, HunterCrabTrap.CRAB_TRAP_PLANK), "second plank kept")
    }

    /* Bait. */

    @Test
    fun `baiting consumes one offcut and schedules exactly one catch at the creature's delay`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = builtTrap(site)
        world.giveItem(player, site.creature.bait, 4)

        assertTrue(world.bait(player, site))
        assertEquals(site.baitedState, world.stateOf(player, site))
        assertEquals(3, world.itemCount(player, site.creature.bait), "one offcut consumed")

        val pending = world.pendingCatches(player)
        assertEquals(1, pending.size, "one pending catch")
        assertEquals(site.creature.catchDelay, pending.single().remainingCycles)
        assertEquals(site.index, pending.single().args)
    }

    /** A hole is not a trap, whatever the player is carrying. */
    @Test
    fun `an unbuilt hole cannot be baited`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = world.addPlayer()
        world.giveItem(player, site.creature.bait, 4)

        assertFalse(world.bait(player, site))
        assertEquals(site.unbuiltState, world.stateOf(player, site))
        assertEquals(4, world.itemCount(player, site.creature.bait))
        assertTrue(world.pendingCatches(player).isEmpty())
    }

    /** One level below the crab's own requirement, with the bait in hand. */
    @Test
    fun `a trap cannot be baited one level below the crab's requirement`() {
        val site = HunterCrabTrapTestWorld.BLUE_SITE
        val player = builtTrap(site)
        player.statMap.setCurrentLevel("stat.hunter", (site.creature.level - 1).toByte())
        world.giveItem(player, site.creature.bait, 4)

        assertFalse(world.bait(player, site))
        assertEquals(site.builtState, world.stateOf(player, site))
        assertEquals(4, world.itemCount(player, site.creature.bait), "nothing charged")
    }

    /**
     * The bait a site takes is the site's, not the player's choice.
     *
     * A rainbow trap refuses plain fish offcuts even though they are the bait every other crab
     * takes, and vice versa. The cache renders a different baited model for each, so accepting the
     * wrong one would show a trap baited with something it is not holding.
     */
    @Test
    fun `a trap refuses the other crab's bait`() {
        val rainbow = HunterCrabTrapTestWorld.RAINBOW_SITE
        val plainBait = HunterCrabTrapTestWorld.RED_SITE.creature.bait
        assertNotEquals(plainBait, rainbow.creature.bait, "the two baits really differ")

        val player = builtTrap(rainbow)
        world.giveItem(player, plainBait, 10)

        assertFalse(world.bait(player, rainbow))
        assertEquals(rainbow.builtState, world.stateOf(player, rainbow))
        assertEquals(10, world.itemCount(player, plainBait), "the wrong bait is not eaten")
    }

    /** The cap counts baited and full traps together: "active (baited or full)". */
    @Test
    fun `the trap cap counts baited and full traps and follows the hunter ladder`() {
        assertEquals(2, HunterCrabTrap.crabTrapCap(21))
        assertEquals(2, HunterCrabTrap.crabTrapCap(39))
        assertEquals(3, HunterCrabTrap.crabTrapCap(40))
        assertEquals(4, HunterCrabTrap.crabTrapCap(60))
        assertEquals(5, HunterCrabTrap.crabTrapCap(80))

        val player = world.addPlayer(hunterLvl = 21)
        val sites = CrabTrapSites.all.filter { it.creature.level == 21 }.take(3)
        for (site in sites) {
            world.setState(player, site, site.builtState)
        }
        world.giveItem(player, sites.first().creature.bait, 10)

        assertTrue(world.bait(player, sites[0]))
        assertTrue(world.bait(player, sites[1]))
        assertFalse(world.bait(player, sites[2]), "a level-21 hunter gets two")
        assertEquals(2, world.pendingCatches(player).size)

        // Now let one fill. It is still active, so the third trap is still refused.
        world.catchArrives(player, sites[0])
        assertTrue(sites[0].isActive(world.stateOf(player, sites[0])))
        assertFalse(world.bait(player, sites[2]), "a full trap still counts")
    }

    /** A boost raises the cap, because the ladder is read from the effective level. */
    @Test
    fun `a boosted hunter level raises the cap`() {
        val player = world.addPlayer(hunterLvl = 21)
        player.statMap.setCurrentLevel("stat.hunter", 40)
        assertEquals(40, player.hunterLvl)
        assertEquals(3, HunterCrabTrap.crabTrapCap(player.hunterLvl))
    }

    /* The catch. */

    @Test
    fun `a baited trap fills when the catch arrives`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = baitedTrap(site)

        world.catchArrives(player, site)

        assertTrue(world.stateOf(player, site) in site.fullStates)
        assertEquals(
            site.creature.variants.single(),
            site.variantAt(world.stateOf(player, site)),
        )
    }

    /**
     * **The trap does nothing until it is baited.**
     *
     * The single most important assertion in this file, and the one a live client cannot force: a
     * matured catch that lands on a trap which is not baited must do nothing at all. It is reachable
     * in game - empty a trap in the second before its crab arrives - but not on demand, and getting
     * it wrong mints a crab from a trap that was never baited, or from a hole that was never built.
     */
    @Test
    fun `a catch arriving at an unbaited trap does nothing`() {
        val site = HunterCrabTrapTestWorld.RED_SITE

        for (state in listOf(site.unbuiltState, site.builtState)) {
            val player = world.addPlayer()
            world.setState(player, site, state)
            world.catchArrives(player, site)
            assertEquals(state, world.stateOf(player, site), "state $state must not change")
            assertNull(site.variantAt(world.stateOf(player, site)))
        }
    }

    /** Emptying a baited trap before its crab arrives cancels the catch, which then finds nothing. */
    @Test
    fun `emptying a baited trap returns the bait and strands the pending catch`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = baitedTrap(site)
        val bait = site.creature.bait
        val before = world.itemCount(player, bait)

        assertTrue(world.empty(player, site))
        assertEquals(site.builtState, world.stateOf(player, site))
        assertEquals(before + 1, world.itemCount(player, bait), "the bait comes back")

        // The queue is still pending and now lands on an unbaited trap.
        world.catchArrives(player, site)
        assertEquals(site.builtState, world.stateOf(player, site), "no crab from an empty trap")
    }

    /** A full trap hands over the crab and its xp, and goes back to empty rather than to a hole. */
    @Test
    fun `emptying a full trap awards the crab and its xp and leaves the trap built`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = baitedTrap(site)
        world.catchArrives(player, site)
        val variant = checkNotNull(site.variantAt(world.stateOf(player, site)))

        assertTrue(world.empty(player, site))

        assertEquals(1, world.itemCount(player, variant.caught))
        assertEquals(site.builtState, world.stateOf(player, site), "the trap is permanent")
        assertEquals(
            site.creature.xp / 10,
            player.statMap.getXP("stat.hunter"),
            "xp is the x10 column divided once",
        )
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
    fun `the xp modifier scales the crab award`() {
        val plain = emptiedRedTrapFineXp(hunterXpBonus = 0.0)
        val doubled = emptiedRedTrapFineXp(hunterXpBonus = DOUBLE_HUNTER_XP)

        // The wiki overview table's 64 xp for a red crab, in the stat map's tenths.
        assertEquals(640, plain, "unmodified, a red crab is 64.0 xp")
        assertEquals(1280, doubled, "a +100% modifier makes it 128.0")
    }

    /**
     * One emptied red trap, in tenths of a point.
     *
     * Replaces [world] rather than taking one as an argument: `baitedTrap` and the rest of the
     * harness read the field, and `setUp` puts a fresh default one back before the next test.
     */
    private fun emptiedRedTrapFineXp(hunterXpBonus: Double): Int {
        world = HunterCrabTrapTestWorld(hunterXpBonus = hunterXpBonus)
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = baitedTrap(site)
        world.catchArrives(player, site)

        assertTrue(world.empty(player, site))

        return player.statMap.getFineXP("stat.hunter")
    }

    @Test
    fun `emptying a full trap is refused when there is no room for the crab`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = baitedTrap(site)
        world.catchArrives(player, site)
        val fullState = world.stateOf(player, site)
        world.fillInventory(player)

        assertFalse(world.empty(player, site))
        assertEquals(fullState, world.stateOf(player, site), "the crab stays in the trap")
        assertEquals(0, player.statMap.getXP("stat.hunter"), "and so does its xp")
    }

    @Test
    fun `emptying an empty trap does nothing`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = builtTrap(site)

        assertFalse(world.empty(player, site))
        assertEquals(site.builtState, world.stateOf(player, site))
    }

    /**
     * A rainbow catch picks one of three colourways, and the crab matches the trap.
     *
     * Each of the three draws is forced, so all three branches are covered rather than whichever one
     * the RNG happened to give. The reward is read back from the varbit the catch wrote, which is
     * the same route the `Empty` op takes - so a mismatch between the trap's colour and the crab's
     * would fail here.
     */
    @Test
    fun `a rainbow catch picks a colourway and hands over that colourway's crab`() {
        val site = HunterCrabTrapTestWorld.RAINBOW_SITE
        assertEquals(3, site.creature.variants.size)

        for ((pick, variant) in site.creature.variants.withIndex()) {
            val player = baitedTrap(site)
            world.random.nextInt = pick

            world.catchArrives(player, site)

            assertEquals(site.fullStates[pick], world.stateOf(player, site))
            assertTrue(world.empty(player, site))
            assertEquals(1, world.itemCount(player, variant.caught), "colourway $pick")
        }
    }

    /** A single-colourway crab never draws, which is why red and blue cost no randomness. */
    @Test
    fun `a red catch consumes no random draw`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val player = baitedTrap(site)
        val before = world.random.intDraws

        world.catchArrives(player, site)

        assertEquals(before, world.random.intDraws, "one colourway needs no roll")
    }

    /* Logout and back. */

    /**
     * A trap baited before a logout is re-armed on the way back in.
     *
     * The varbit is saved with the player and the queue is not, so without the login re-arm a trap
     * baited a second before a disconnect would come back baited and stay that way forever. This is
     * a persistence-boundary bug: nothing a session-long client test could ever see.
     */
    @Test
    fun `logging in re-arms a pending catch for every baited trap and nothing else`() {
        val player = world.addPlayer()
        val baited = HunterCrabTrapTestWorld.RED_SITE
        val built = CrabTrapSites.all.first { it.creature.level == 21 && it != baited }
        world.setState(player, baited, baited.baitedState)
        world.setState(player, built, built.builtState)

        world.login(player)

        val pending = world.pendingCatches(player)
        assertEquals(listOf(baited.index), pending.map { it.args }, "only the baited trap")
        assertEquals(baited.creature.catchDelay, pending.single().remainingCycles)
    }

    @Test
    fun `logging in with no baited traps schedules nothing`() {
        val player = world.addPlayer()
        world.login(player)
        assertTrue(world.pendingCatches(player).isEmpty())
    }

    /* Isolation. */

    /**
     * Two players do not share a hole.
     *
     * The whole reason this technique has no controller: a crab trap is a varbit on the player, so
     * one player's baited trap is another player's untouched hole on the same tile. Any design that
     * put the state in the world would fail this.
     */
    @Test
    fun `two players' traps on the same site are independent`() {
        val site = HunterCrabTrapTestWorld.RED_SITE
        val first = baitedTrap(site)
        val second = world.addPlayer()

        assertEquals(site.baitedState, world.stateOf(first, site))
        assertEquals(site.unbuiltState, world.stateOf(second, site), "untouched for the second")

        world.catchArrives(first, site)
        assertTrue(world.stateOf(first, site) in site.fullStates)
        assertEquals(site.unbuiltState, world.stateOf(second, site))
    }

    private fun builtTrap(site: CrabTrapSite): Player {
        val player = world.addPlayer()
        world.setState(player, site, site.builtState)
        return player
    }

    private fun baitedTrap(site: CrabTrapSite): Player {
        val player = builtTrap(site)
        world.giveItem(player, site.creature.bait, 5)
        check(world.bait(player, site)) { "Failed to bait ${site.loc}." }
        return player
    }

    private fun assertMaterialsUntouched(player: Player) {
        assertEquals(1, world.itemCount(player, HunterCrabTrap.CRAB_TRAP_PLANK))
        assertEquals(1, world.itemCount(player, HunterCrabTrap.CRAB_TRAP_BUCKET))
        assertEquals(
            HunterCrabTrap.CRAB_TRAP_NAIL_COUNT,
            world.itemCount(player, "obj.nails"),
        )
    }
}
