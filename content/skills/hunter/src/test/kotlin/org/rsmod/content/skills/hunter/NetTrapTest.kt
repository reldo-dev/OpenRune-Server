package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.content.skills.hunter.HunterTrapTestWorld.Companion.TRAP_CREATURE_FAILED
import org.rsmod.content.skills.hunter.HunterTrapTestWorld.Companion.TRAP_CREATURE_NONE
import org.rsmod.content.skills.hunter.HunterTrapTestWorld.Companion.TRAP_TILE
import org.rsmod.game.entity.Controller
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.LocAngle

/**
 * The net trap, the only family that owns two tiles.
 *
 * Three things here are worth testing for reasons a live client cannot supply:
 * - **The young tree must never be deleted.** It is a permanent map loc, and `LocRepository` only
 *   schedules a respawn for a delete with a finite duration, so one `locRepo.del` takes that tree
 *   spot out of the world until the next restart, with no error and nothing to notice it by short
 *   of a player reporting a missing tree. Every assertion below that says "the tree is still there"
 *   is that invariant; [a deleted young tree really does disappear] is the control proving the
 *   harness can tell the difference.
 * - **The failure branch drops the rope and net on the ground.** No other family does this - the
 *   deadfall loses its log, the portable families hand their trap item back - and a failed catch is
 *   a rare event a player cannot force, so a scripted draw is the only way to reach it at all.
 * - **The two locs have to stay paired.** The offset is a pure function of the tree's angle
 *   ([NetTrapLogicTest]); what these check is that the *world* honours it - that the net really is
 *   spawned there, that an op landing on it walks back to the right tree, and that the pair is torn
 *   down together rather than leaving half a trap standing.
 *
 * Serialised against the other world-driven hunter tests for the reason given on
 * [HunterTrapTickTest]: `ServerCacheManager` is a singleton and `RSCM` memoises into a plain
 * `HashMap`.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class NetTrapTest {
    private lateinit var world: HunterTrapTestWorld

    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
        world = HunterTrapTestWorld()
    }

    /* Setting. */

    @Test
    fun `setting a net trap arms both locs and consumes the rope and net`() {
        val player = hunter(level = 99, carrying = NET_TRAP_KIT)
        world.addNetTrapTree(TRAP_TILE, swampLizard)

        assertTrue(world.runProtected(player) { it.setNetTrap(world.boundLocAt(TRAP_TILE)!!) })

        assertEquals(swampLizard.setLoc, world.locNameAt(TRAP_TILE), "The tree is bent over.")
        assertEquals(swampLizard.netSetLoc, world.netLocNameAt(TRAP_TILE), "The net is strung.")
        assertTrue(world.netTrapTreePresent(TRAP_TILE), "The tree is changed, never deleted.")

        assertFalse(player.inv.contains("obj.rope"))
        assertFalse(player.inv.contains("obj.net"))

        val controller = checkNotNull(world.controllerAt(TRAP_TILE))
        assertEquals(TrapFamily.NETTRAP.ordinal, controller.trapFamily)
        assertEquals(listOf(TRAP_TILE.packed), player.hunterTrapCoords)
    }

    /**
     * The pairing, in the world rather than in the arithmetic. The net has to land on the tile the
     * tree's *own* angle names, which is what makes it recoverable from the net alone - so this
     * uses an angle that is not the harness default.
     */
    @Test
    fun `the net is spawned on the tile the tree's angle points at`() {
        val player = hunter(level = 99, carrying = NET_TRAP_KIT)
        world.addNetTrapTree(TRAP_TILE, swampLizard, angle = LocAngle.North)

        assertTrue(world.runProtected(player) { it.setNetTrap(world.boundLocAt(TRAP_TILE)!!) })

        val expected = netTrapCoords(TRAP_TILE, LocAngle.North)
        assertNotEquals(TRAP_TILE, expected)
        assertEquals(swampLizard.netSetLoc, world.locNameAt(expected))
        // And the net carries the tree's angle, which is the whole of how the inverse works.
        assertEquals(LocAngle.North, world.locAt(expected)!!.angle)
    }

    /**
     * Open decision: the net's tile is occupied. The set is refused outright rather than shuffled
     * to another neighbour - a fallback tile would need state nothing in the feature keeps, and
     * half a trap would consume the player's rope and net for something that can never spring.
     *
     * Refusing has to be free, hence the inventory assertion: a player told "no room" walks to the
     * next tree, a player charged for it does not get their materials back.
     */
    @Test
    fun `a net trap whose net tile is occupied is refused and costs nothing`() {
        val player = hunter(level = 99, carrying = NET_TRAP_KIT + listOf("obj.hunting_box_trap"))
        world.addNetTrapTree(TRAP_TILE, swampLizard)

        val netCoords = netTrapCoords(TRAP_TILE, LocAngle.West)
        assertTrue(world.runProtected(player) { it.layTrap(TrapFamily.BOX, netCoords) })

        assertFalse(world.runProtected(player) { it.setNetTrap(world.boundLocAt(TRAP_TILE)!!) })

        assertEquals(swampLizard.upLoc, world.locNameAt(TRAP_TILE), "The tree is untouched.")
        assertNull(world.controllerAt(TRAP_TILE))
        assertTrue(player.inv.contains("obj.rope"), "The rope must not be consumed.")
        assertTrue(player.inv.contains("obj.net"), "The net must not be consumed.")
    }

    /**
     * The level gate is the *creature's* own, read off the tree that was clicked, not one number
     * for the whole family. Every young tree in the world belongs to exactly one salamander, so the
     * tree already says which requirement applies - and the family spans 29 to 79, which is far too
     * wide for a single constant to stand in for.
     */
    @Test
    fun `a net trap needs the tree's own creature level`() {
        world.addNetTrapTree(TRAP_TILE, swampLizard)

        val under = hunter(level = swampLizard.level - 1, carrying = NET_TRAP_KIT)
        assertFalse(world.runProtected(under) { it.setNetTrap(world.boundLocAt(TRAP_TILE)!!) })
        assertTrue(under.inv.contains("obj.rope"), "A refused set costs nothing.")

        val exactly = hunter(level = swampLizard.level, carrying = NET_TRAP_KIT)
        assertTrue(world.runProtected(exactly) { it.setNetTrap(world.boundLocAt(TRAP_TILE)!!) })
    }

    /** Both halves of the kit are needed, and neither is consumed without the other. */
    @Test
    fun `a net trap needs both a rope and a small fishing net`() {
        world.addNetTrapTree(TRAP_TILE, swampLizard)

        val ropeOnly = hunter(level = 99, carrying = listOf("obj.rope"))
        assertFalse(world.runProtected(ropeOnly) { it.setNetTrap(world.boundLocAt(TRAP_TILE)!!) })
        assertTrue(ropeOnly.inv.contains("obj.rope"))

        val netOnly = hunter(level = 99, carrying = listOf("obj.net"))
        assertFalse(world.runProtected(netOnly) { it.setNetTrap(world.boundLocAt(TRAP_TILE)!!) })
        assertTrue(netOnly.inv.contains("obj.net"))

        assertEquals(swampLizard.upLoc, world.locNameAt(TRAP_TILE))
        assertNull(world.controllerAt(TRAP_TILE))
    }

    /** The deadfall's rule, on this family's materials: nothing is charged until the trap is up. */
    @Test
    fun `the rope and net are not charged until after the set delay`() {
        val player = hunter(level = 99, carrying = NET_TRAP_KIT)
        world.addNetTrapTree(TRAP_TILE, swampLizard)

        val run = world.startProtected(player) { it.setNetTrap(world.boundLocAt(TRAP_TILE)!!) }

        repeat(NET_TRAP_SET_CYCLES) {
            assertFalse(run.isFinished, "Still setting.")
            assertTrue(player.inv.contains("obj.rope"), "The rope is still the player's, cycle $it.")
            assertEquals(swampLizard.settingLoc, world.locNameAt(TRAP_TILE))
            run.advanceCycle()
        }

        assertTrue(run.await())
        assertFalse(player.inv.contains("obj.rope"))
    }

    /* Catching. */

    @Test
    fun `a successful catch shows the catching net, then settles into the full net`() {
        val controller = armedTrapWithLizardInRange()
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)

        assertEquals(swampLizard.npc, HunterCreatures.all[controller.trapCreature].npc)
        assertEquals(swampLizard.trappingLoc, world.netLocNameAt(TRAP_TILE))
        assertEquals(swampLizard.setLoc, world.locNameAt(TRAP_TILE), "The tree stays bent over.")

        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)

        assertEquals(swampLizard.fullLoc, world.netLocNameAt(TRAP_TILE))
        assertTrue(world.netTrapTreePresent(TRAP_TILE), "The tree survives the catch.")
        assertNotNull(world.controllerAt(TRAP_TILE), "A caught trap waits for its owner.")
    }

    /**
     * The branch no live client can force, and the one that is unique to this family: "If not
     * successful, the tree will snap back to its original position, and the small fishing net and
     * rope will appear on the ground" (wiki, *Net trap*).
     *
     * Both halves of that sentence are asserted, plus the consequence: the trap is over, so its
     * controller goes with it. Getting this wrong is invisible in play - the player finds an empty
     * trap and two missing items and assumes bad luck.
     */
    @Test
    fun `a failed catch drops the rope and net on the ground and unbends the tree`() {
        val controller = armedTrapWithLizardInRange(hunterLvl = swampLizard.level)
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW

        world.tick(controller)

        assertEquals(TRAP_CREATURE_FAILED, controller.trapCreature)
        assertEquals(swampLizard.failingLoc, world.netLocNameAt(TRAP_TILE))
        assertEquals(
            emptyList<String>(),
            world.objNamesAt(TRAP_TILE),
            "Nothing drops until the failure settles.",
        )

        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)

        assertEquals(
            listOf("obj.rope", "obj.net").sorted(),
            world.objNamesAt(TRAP_TILE).sorted(),
            "The rope and net fall onto the ground.",
        )
        assertEquals(swampLizard.upLoc, world.locNameAt(TRAP_TILE), "The tree snaps back.")
        assertTrue(world.netTrapTreePresent(TRAP_TILE), "The tree is changed, never deleted.")
        assertNull(world.controllerAt(TRAP_TILE), "The trap is over.")
    }

    /**
     * The drop lands on the *trap's* tile, not the player's.
     *
     * Open decision, and the trap tile is the only position that always exists: a net trap can fail
     * while its owner is halfway across the map, or while they are logged out entirely, and there
     * is no player tile to use then. It is also where a player walking back to check their trap
     * looks.
     */
    @Test
    fun `the failed catch drops at the trap, not at the player`() {
        val controller = armedTrapWithLizardInRange(hunterLvl = swampLizard.level)
        val owner = ownerOf(controller)
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW

        world.tick(controller)
        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)

        assertNotEquals(TRAP_TILE, owner.coords, "The owner is standing clear of the trap.")
        assertEquals(emptyList<String>(), world.objNamesAt(owner.coords))
        assertEquals(2, world.objNamesAt(TRAP_TILE).size)
    }

    /**
     * A trap that simply timed out shares the failure path, deliberately: an unattended net trap
     * must not silently swallow two items that a failed catch would have given back.
     */
    @Test
    fun `an expiring net trap unbends the tree and drops the kit`() {
        val controller = armedTrap()

        controller.duration = 1
        world.tick(controller)

        assertEquals(swampLizard.upLoc, world.locNameAt(TRAP_TILE))
        assertEquals(2, world.objNamesAt(TRAP_TILE).size)
        assertNull(world.controllerAt(TRAP_TILE))
    }

    /* Collecting and dismantling. */

    @Test
    fun `collecting a full net awards the catch, returns the kit and clears both locs`() {
        val controller = armedTrapWithLizardInRange()
        val player = ownerOf(controller)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        world.tick(controller)
        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)

        val net = world.boundLocAt(netTrapCoords(TRAP_TILE, LocAngle.West))!!
        assertTrue(world.runProtected(player) { it.collectNetTrap(net) })

        assertTrue(player.inv.contains("obj.green_salamander"), "The catch.")
        assertTrue(player.inv.contains("obj.rope"), "The rope comes back.")
        assertTrue(player.inv.contains("obj.net"), "The net comes back.")
        assertEquals(swampLizard.upLoc, world.locNameAt(TRAP_TILE), "The tree is unset again.")
        assertTrue(world.netTrapTreePresent(TRAP_TILE), "The tree is changed, never deleted.")
        assertNull(world.netLocAt(TRAP_TILE), "The spawned net is gone.")
        assertNull(world.controllerAt(TRAP_TILE))
        assertEquals(emptyList<Int>(), player.hunterTrapCoords)

        // Creature xp is stored x10 in the packed table and divided by ten once, at the award.
        assertEquals(swampLizard.xp / 10, player.statMap.getXP("stat.hunter"))
    }

    /** Both halves carry `Dismantle`, and clicking the tree is the direct case. */
    @Test
    fun `dismantling from the tree undoes both locs and returns the kit`() {
        val controller = armedTrap()

        assertTrue(
            world.runProtected(ownerOf(controller)) {
                it.dismantleNetTrap(world.boundLocAt(TRAP_TILE)!!)
            }
        )

        assertDismantled(ownerOf(controller))
    }

    /**
     * The indirect case, and the one that exercises the inverse: an op on the net has to walk back
     * a tile to the tree its controller is anchored to. Getting that wrong would leave the click
     * doing nothing at all, or worse, acting on some unrelated tile.
     */
    @Test
    fun `dismantling from the net undoes both locs and returns the kit`() {
        val controller = armedTrap()
        val net = world.boundLocAt(netTrapCoords(TRAP_TILE, LocAngle.West))!!

        assertTrue(world.runProtected(ownerOf(controller)) { it.dismantleNetTrap(net) })

        assertDismantled(ownerOf(controller))
    }

    @Test
    fun `dismantling someone else's net trap is refused and changes nothing`() {
        armedTrap()
        val thief = hunter(level = 99, carrying = emptyList())

        val tree = world.boundLocAt(TRAP_TILE)!!
        assertFalse(world.runProtected(thief) { it.dismantleNetTrap(tree) })

        assertFalse(thief.inv.contains("obj.rope"))
        assertEquals(swampLizard.setLoc, world.locNameAt(TRAP_TILE))
        assertNotNull(world.controllerAt(TRAP_TILE))
    }

    /**
     * A sprung-and-empty net outlives its controller. Its rope and net are already on the ground -
     * the failure dropped them - so clearing the wreck must hand back nothing, or the pair is
     * minted twice.
     */
    @Test
    fun `clearing a failed net hands nothing back`() {
        val controller = armedTrapWithLizardInRange(hunterLvl = swampLizard.level)
        val player = ownerOf(controller)
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW
        world.tick(controller)
        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)
        assertNull(world.controllerAt(TRAP_TILE), "Failed.")

        val wreck = world.boundLocAt(netTrapCoords(TRAP_TILE, LocAngle.West))!!
        assertTrue(world.runProtected(player) { it.dismantleNetTrap(wreck) })

        assertFalse(player.inv.contains("obj.rope"), "Already on the ground; not minted again.")
        assertFalse(player.inv.contains("obj.net"))
        assertNull(world.netLocAt(TRAP_TILE))
        assertTrue(world.netTrapTreePresent(TRAP_TILE))
    }

    /* The occupancy guard. */

    /**
     * "it may be caught, but only when the player is not standing on the net" (wiki, *Net trap*).
     *
     * **The net, not the tree.** This is the one family whose guarded tile is not the tile its
     * controller sits on, and the pair of tests below is the only thing that pins the difference:
     * suppressing on the wrong tile would look exactly like ordinary bad luck to a player.
     */
    @Test
    fun `a player standing on the net suppresses the catch`() {
        val controller = armedTrapWithLizardInRange()
        world.addPlayer(netTrapCoords(TRAP_TILE, LocAngle.West), hunterLvl = 99)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)

        assertEquals(TRAP_CREATURE_NONE, controller.trapCreature)
        assertEquals(0, world.random.doubleDraws, "The roll must not even happen.")
    }

    /** The other half: the tree is not the net, and standing at its foot suppresses nothing. */
    @Test
    fun `a player standing on the tree does not suppress the catch`() {
        val controller = armedTrapWithLizardInRange()
        world.addPlayer(TRAP_TILE, hunterLvl = 99)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)

        assertTrue(controller.trapCreature >= 0, "Expected a catch, got ${controller.trapCreature}")
    }

    /* The never-delete invariant. */

    /**
     * The teardown path that *does* delete must refuse a sapling rather than tidy itself away. A
     * corrupt `trapFamily` ordinal is the only route into it, and [HunterTrap]'s guard throws
     * there deliberately: a loud tick is recoverable by restarting, where a silent permanent delete
     * is not.
     */
    @Test
    fun `the portable teardown path refuses to delete a young tree`() {
        val controller = armedTrap()
        controller.trapFamily = TrapFamily.entries.size

        val error = assertThrows<IllegalStateException> { world.tick(controller) }
        assertTrue(
            error.message.orEmpty().contains("permanent map loc"),
            "Expected the sapling guard, got: ${error.message}",
        )
        assertTrue(world.netTrapTreePresent(TRAP_TILE))
    }

    /**
     * The control for every "the tree is still there" assertion above: proves a delete really is
     * observable, so their passing is not an artefact of the harness reporting a loc that is no
     * longer registered.
     */
    @Test
    fun `a deleted young tree really does disappear`() {
        world.addNetTrapTree(TRAP_TILE, swampLizard)
        val tree = world.locAt(TRAP_TILE)
        assertNotNull(tree)

        world.locRepo.del(tree!!, Int.MAX_VALUE)

        assertFalse(world.netTrapTreePresent(TRAP_TILE))
    }

    /**
     * Losing the net still has to put the tree back. Without it a permanent map loc is left bent
     * over with nothing alive to unbend it - the same class of failure as deleting it, reached a
     * different way.
     */
    @Test
    fun `a net trap whose net vanished unbends its tree`() {
        val controller = armedTrap()

        world.locRepo.del(world.netLocAt(TRAP_TILE)!!, Int.MAX_VALUE)
        // The tick asserts the controller outlived a single cycle before accepting a missing loc.
        world.advance(2)

        world.tick(controller)

        assertEquals(swampLizard.upLoc, world.locNameAt(TRAP_TILE))
        assertTrue(world.netTrapTreePresent(TRAP_TILE))
        assertNull(world.controllerAt(TRAP_TILE))
    }

    /* Helpers. */

    private val swampLizard: HunterCreature
        get() = HunterCreatures.netTrap.first { it.npc == "npc.salamander_green" }

    private fun ownerOf(controller: Controller): Player =
        world.playerList.first { it.uid.packed == controller.trapOwner }

    /** Both locs undone, the tree still standing, and the kit back in [player]'s inventory. */
    private fun assertDismantled(player: Player) {
        assertTrue(player.inv.contains("obj.rope"), "The rope comes back.")
        assertTrue(player.inv.contains("obj.net"), "The net comes back.")
        assertEquals(swampLizard.upLoc, world.locNameAt(TRAP_TILE))
        assertTrue(world.netTrapTreePresent(TRAP_TILE), "The tree is changed, never deleted.")
        assertNull(world.netLocAt(TRAP_TILE), "The spawned net is gone.")
        assertNull(world.controllerAt(TRAP_TILE))
    }

    private fun hunter(level: Int, carrying: List<String>): Player {
        val player = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = level)
        val access = world.protectedAccess(player)
        for (obj in carrying) {
            access.invAdd(player.inv, obj, 1)
        }
        return player
    }

    /** A tree on [TRAP_TILE] with its net strung west of it and its owner standing clear. */
    private fun armedTrap(hunterLvl: Int = 99): Controller {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = hunterLvl)
        world.addNetTrapTree(TRAP_TILE, swampLizard)
        return world.armNetTrap(TRAP_TILE, owner, swampLizard)
    }

    /**
     * [armedTrap] with a swamp lizard beside the net.
     *
     * [hunterLvl] defaults to 99, where the swamp lizard's rate is 361/256 and the catch is
     * certain. Tests that need a miss pass its requirement level, where the rate is a real fraction
     * (141/256) - see [ScriptedRandom.HIGHEST_DRAW].
     */
    private fun armedTrapWithLizardInRange(hunterLvl: Int = 99): Controller {
        val controller = armedTrap(hunterLvl)
        val netCoords = netTrapCoords(TRAP_TILE, LocAngle.West)
        world.addNpc("npc.salamander_green", netCoords.translate(0, 1))
        return controller
    }

    private companion object {
        private val NET_TRAP_KIT = listOf("obj.rope", "obj.net")
    }
}
