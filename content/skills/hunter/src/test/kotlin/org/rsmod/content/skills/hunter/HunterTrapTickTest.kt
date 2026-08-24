package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.rsmod.game.loc.LocShape

/**
 * [HunterTrap.hunterTrapTick] driven cycle by cycle against real repositories and a scripted
 * [ScriptedRandom].
 *
 * These cover the parts of the trap engine a live client cannot reach: a trap springs within a
 * couple of cycles of a creature wandering into range, so the intermediate states are barely
 * observable, and a failed catch cannot be forced at all when the roll is genuinely random. Fixing
 * the draw makes both branches ordinary.
 *
 * The `ProtectedAccess`-receiver half of the engine - `layTrap`, `setDeadfall`, `collectTrap`,
 * `takeTrap`, `dismantleDeadfall` - is *not* covered here; see the class KDoc on
 * [HunterTrapTestWorld] for why. Traps are therefore set up by the harness in the same shape those
 * paths leave them in.
 *
 * Serialised against the other world-driven hunter tests: `test-conventions` turns JUnit parallel
 * execution on for every module, and these share more than their own worlds - `ServerCacheManager`
 * is a singleton and `RSCM` memoises name-to-id lookups in a plain `HashMap`, which is not safe to
 * fill from several threads at once. Run concurrently, they failed differently on every run.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class HunterTrapTickTest {
    private lateinit var world: HunterTrapTestWorld

    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
        world = HunterTrapTestWorld()
    }

    /* The deadfall's boulder invariant. */

    /**
     * The highest-consequence rule in the feature. A deadfall boulder is a permanent *map* loc, and
     * `LocRepository` only schedules a respawn for a delete with a finite duration, so a single
     * `locRepo.del` would take that boulder spot out of the world until the next restart - with no
     * error, no log line, and nothing to notice it by short of a player reporting a missing boulder.
     *
     * The assertion is on the effect rather than on which method was called: after every transition
     * of a full catch-and-settle lifecycle the tile must still carry a deadfall loc. See
     * [a deleted boulder really does disappear] for the proof that this can tell the difference.
     */
    @Test
    fun `a deadfall boulder survives every state of a successful catch`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val controller = world.armDeadfall(TRAP_TILE, owner)
        assertTrue(world.deadfallPresent(TRAP_TILE), "armed")

        world.addNpc("npc.huntingbeast_claws", TRAP_TILE.translate(1, 0))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)
        assertTrue(world.deadfallPresent(TRAP_TILE), "mid-catch")

        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)
        assertTrue(world.deadfallPresent(TRAP_TILE), "settled")

        // The collect path is a `ProtectedAccess` extension, so the lifecycle is ended here the way
        // an uncollected trap ends: the lifetime runs out and the boulder is reclaimed.
        controller.duration = 1
        world.tick(controller)
        assertTrue(world.deadfallPresent(TRAP_TILE), "collapsed")
        assertEquals(HunterTrapStates.DEADFALL_BOULDER, world.locNameAt(TRAP_TILE))
    }

    /** The same invariant down the failure branch, which ends the trap a cycle earlier. */
    @Test
    fun `a deadfall boulder survives a failed catch and comes back unset`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = WILD_KEBBIT_LEVEL)
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val controller = world.armDeadfall(TRAP_TILE, owner)

        world.addNpc("npc.huntingbeast_claws", TRAP_TILE.translate(1, 0))
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW

        world.tick(controller)
        assertEquals(TRAP_CREATURE_FAILED, controller.trapCreature)
        assertEquals(HunterTrapStates.DEADFALL_FAILING, world.locNameAt(TRAP_TILE))

        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)

        // "A deadfall that sprang and caught nothing has no collectible failed state" - the boulder
        // goes straight back to unset and the controller has no reason to outlive the spring.
        assertEquals(HunterTrapStates.DEADFALL_BOULDER, world.locNameAt(TRAP_TILE))
        assertNull(world.controllerAt(TRAP_TILE))
    }

    /**
     * The teardown path that *does* delete - the portable one - must refuse a boulder rather than
     * tidy itself away. A corrupt `trapFamily` ordinal is the only route into it for a deadfall, and
     * `clearTrapLoc` throws there deliberately: a loud tick is recoverable by restarting, where a
     * silent permanent delete is not.
     */
    @Test
    fun `the portable teardown path refuses to delete a deadfall boulder`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val controller = world.armDeadfall(TRAP_TILE, owner)

        controller.trapFamily = TrapFamily.entries.size

        val error = assertThrows<IllegalStateException> { world.tick(controller) }
        assertTrue(
            error.message.orEmpty().contains("permanent map loc"),
            "Expected the boulder guard, got: ${error.message}",
        )
        assertTrue(world.deadfallPresent(TRAP_TILE))
    }

    /**
     * The control for the two invariant tests above: proves a delete really is observable, so their
     * passing is not an artefact of the harness reporting a loc that is no longer registered.
     */
    @Test
    fun `a deleted boulder really does disappear`() {
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val boulder = world.locAt(TRAP_TILE)
        assertNotNull(boulder)

        world.locRepo.del(boulder!!, Int.MAX_VALUE)

        assertFalse(world.deadfallPresent(TRAP_TILE))
        assertNull(world.locRepo.findExact(TRAP_TILE, LocShape.CentrepieceStraight))
    }

    /** A corrupt ordinal on a portable trap is tidied away instead, which is the whole contrast. */
    @Test
    fun `a corrupt family ordinal clears a portable trap and deletes its controller`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        val controller = world.layPortableTrap(TrapFamily.BOX, TRAP_TILE, owner)

        controller.trapFamily = TrapFamily.entries.size
        world.tick(controller)

        assertNull(world.locAt(TRAP_TILE))
        assertNull(world.controllerAt(TRAP_TILE))
    }

    /* The occupancy guard. */

    /**
     * "Box traps won't trap prey if players are standing on the trap itself." (wiki, *Box trap >
     * Mechanics*.) Any player, not just the owner.
     */
    @Test
    fun `a player standing on a box trap suppresses the catch`() {
        val controller = boxTrapWithChinchompaInRange()
        world.addPlayer(TRAP_TILE, hunterLvl = 99)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)

        assertEquals(TRAP_CREATURE_NONE, controller.trapCreature)
        assertEquals(0, world.random.doubleDraws, "The roll must not even happen.")
    }

    /** "A bird snare will not catch birds if the user is standing directly on the bird snare." */
    @Test
    fun `a player standing on a bird snare suppresses the catch`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        val controller = world.layPortableTrap(TrapFamily.SNARE, TRAP_TILE, owner)
        world.addNpc("npc.hunting_bird_jungle", TRAP_TILE.translate(1, 0))
        world.addPlayer(TRAP_TILE, hunterLvl = 99)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)

        assertEquals(TRAP_CREATURE_NONE, controller.trapCreature)
    }

    /**
     * "Deadfall traps are not prone to failure by standing where they are set." (wiki, *Deadfall*.)
     *
     * The one family the wiki exempts, and the regression that nothing else would catch: a deadfall
     * that quietly stopped catching while a player stood on the boulder tile would look like bad
     * luck, not a bug.
     */
    @Test
    fun `a player standing on a deadfall does not suppress the catch`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val controller = world.armDeadfall(TRAP_TILE, owner)
        world.addNpc("npc.huntingbeast_claws", TRAP_TILE.translate(1, 0))
        world.addPlayer(TRAP_TILE, hunterLvl = 99)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)

        assertEquals(
            HunterCreatures.all.indexOfFirst { it.npc == "npc.huntingbeast_claws" },
            controller.trapCreature,
        )
    }

    /**
     * `PlayerRegistry.findAll` does not filter hidden or logging-out players, so the tick applies
     * `isValidTarget()` on top. Without it an invisible player parked on a trap would suppress every
     * roll with nothing observable to diagnose it by.
     */
    @Test
    fun `a hidden player on the tile does not suppress the catch`() {
        val controller = boxTrapWithChinchompaInRange()
        val camper = world.addPlayer(TRAP_TILE, hunterLvl = 99)
        world.playerRepo.hide(camper)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)

        assertTrue(controller.trapCreature >= 0, "Expected a catch, got ${controller.trapCreature}")
    }

    /* Catch success and failure. */

    @Test
    fun `a successful catch shows the trapping loc, then settles into the full loc`() {
        val controller = boxTrapWithChinchompaInRange()
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)

        val creature = HunterCreatures.all[controller.trapCreature]
        assertEquals("npc.hunting_chinchompa", creature.npc)
        // The chinchompa was placed one tile north, so the north-side trapping loc is expected.
        assertEquals("loc.hunting_boxtrap_trapping_chinchompa_n", world.locNameAt(TRAP_TILE))

        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)
        assertEquals("loc.hunting_boxtrap_full_chinchompa", world.locNameAt(TRAP_TILE))
        assertNotNull(world.controllerAt(TRAP_TILE), "A caught trap waits for its owner.")
    }

    /**
     * The branch a live client cannot force. A failed catch must take the failing/failed pair, not
     * the trapping/full pair, and must record [TRAP_CREATURE_FAILED] rather than a creature index -
     * getting that wrong would hand out a free chinchompa on a miss.
     */
    @Test
    fun `a failed catch takes the failing loc, then settles into the failed loc`() {
        val controller = boxTrapWithChinchompaInRange(CHINCHOMPA_LEVEL)
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW

        world.tick(controller)

        assertEquals(TRAP_CREATURE_FAILED, controller.trapCreature)
        assertEquals("loc.hunting_boxtrap_failing", world.locNameAt(TRAP_TILE))

        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)
        assertEquals("loc.hunting_boxtrap_failed", world.locNameAt(TRAP_TILE))
    }

    /** Either outcome springs the trap, so either outcome must take the creature off the map. */
    @Test
    fun `both outcomes despawn the creature`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        val caught = world.layPortableTrap(TrapFamily.BOX, TRAP_TILE, owner)
        val prey = world.addNpc("npc.hunting_chinchompa", TRAP_TILE.translate(0, 1))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        world.tick(caught)
        assertFalse(world.npcIsSpawned(prey), "A caught creature is despawned.")

        val missed = HunterTrapTestWorld()
        val missedOwner = missed.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = CHINCHOMPA_LEVEL)
        val controller = missed.layPortableTrap(TrapFamily.BOX, TRAP_TILE, missedOwner)
        val escapee = missed.addNpc("npc.hunting_chinchompa", TRAP_TILE.translate(0, 1))
        missed.random.nextDouble = ScriptedRandom.HIGHEST_DRAW
        missed.tick(controller)
        assertFalse(missed.npcIsSpawned(escapee), "A creature that escaped is despawned too.")
    }

    /**
     * "If the player's Hunter level is too low, the trap will always fail." (wiki.)
     *
     * The regular chinchompa is the creature that needs this stated explicitly: its `successLow` is
     * a *positive* +6, so the engine formula gives a level-1 player a small but non-zero rate, and
     * without the explicit gate a level-1 hunter would catch a level-53 creature. The draw counter
     * pins the second half of the contract - the gate short-circuits, so an under-levelled attempt
     * never consumes a random draw.
     */
    @Test
    fun `an under-levelled owner never catches and never rolls`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 1)
        val controller = world.layPortableTrap(TrapFamily.BOX, TRAP_TILE, owner)
        world.addNpc("npc.hunting_chinchompa", TRAP_TILE.translate(0, 1))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)

        assertEquals(TRAP_CREATURE_FAILED, controller.trapCreature)
        assertEquals(0, world.random.doubleDraws)
    }

    /**
     * `SkillingSuccessRate.successRate` is **not** clamped to `1.0`, and the trap engine compares it
     * against a `randomDouble()` that is by contract in `0..1`. Every creature whose `successHigh`
     * exceeds 256 therefore reaches a point - well below 99 for most of them - where the roll stops
     * being a roll and the catch is certain.
     *
     * That is consistent with the wiki's near-100% charts at high levels, so it is recorded here
     * rather than treated as a defect. It is pinned because it is invisible: nothing in the tick
     * reads as "and above this level the RNG is bypassed", and a future clamp added anywhere in
     * `SkillingSuccessRate` would silently reintroduce failures at 99 across every hunter creature.
     */
    @Test
    fun `a maxed hunter catches whatever the draw`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val controller = world.armDeadfall(TRAP_TILE, owner)
        world.addNpc("npc.huntingbeast_claws", TRAP_TILE.translate(1, 0))
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW

        world.tick(controller)

        assertTrue(
            controller.trapCreature >= 0,
            "The wild kebbit's rate at 99 is 386/256, above any legal draw.",
        )
        assertEquals(1, world.random.doubleDraws, "The draw is still taken, just never decisive.")
    }

    /* Cadence and range. */

    /**
     * "Once a box trap has been set, it will make an attempt every 3 ticks." (wiki.) Rolling every
     * cycle instead would triple the effective catch rate at the same per-attempt chance, which is
     * invisible in play and shows up only as the skill training faster than it should.
     */
    @Test
    fun `a box trap rolls only once every three cycles`() {
        val controller = boxTrapWithChinchompaInRange(CHINCHOMPA_LEVEL)
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW

        // Cycle 0 is the trap's own creation cycle, so it rolls; 1 and 2 must not.
        world.tick(controller)
        assertEquals(1, world.random.doubleDraws)

        // Reset the sprung state so the tick reaches its roll again on the following cycles.
        repeat(2) {
            controller.trapCreature = TRAP_CREATURE_NONE
            world.advance()
            world.tick(controller)
        }
        assertEquals(1, world.random.doubleDraws, "No roll on the two off-cycles.")

        controller.trapCreature = TRAP_CREATURE_NONE
        world.advance()
        world.tick(controller)
        assertEquals(2, world.random.doubleDraws, "Rolls again on the third cycle.")
    }

    /**
     * The box trap's sourced 2-tile radius against the snare's conservative adjacency. A creature
     * two tiles away is in range of one and out of range of the other, which is the only behavioural
     * difference between the two constants.
     */
    @Test
    fun `a creature two tiles away is in range of a box trap but not a snare`() {
        val owner = world.addPlayer(TRAP_TILE.translate(4, 4), hunterLvl = 99)
        val box = world.layPortableTrap(TrapFamily.BOX, TRAP_TILE, owner)
        world.addNpc("npc.hunting_chinchompa", TRAP_TILE.translate(2, 0))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        world.tick(box)
        assertTrue(box.trapCreature >= 0, "Box trap should reach two tiles.")

        val other = HunterTrapTestWorld()
        val snareOwner = other.addPlayer(TRAP_TILE.translate(4, 4), hunterLvl = 99)
        val snare = other.layPortableTrap(TrapFamily.SNARE, TRAP_TILE, snareOwner)
        other.addNpc("npc.hunting_bird_jungle", TRAP_TILE.translate(2, 0))
        other.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        other.tick(snare)
        assertEquals(TRAP_CREATURE_NONE, snare.trapCreature, "Snare should not reach two tiles.")
    }

    /** A trap only catches its own family's creatures, whatever wanders past. */
    @Test
    fun `a box trap ignores a bird and a snare ignores a chinchompa`() {
        val owner = world.addPlayer(TRAP_TILE.translate(4, 4), hunterLvl = 99)
        val box = world.layPortableTrap(TrapFamily.BOX, TRAP_TILE, owner)
        world.addNpc("npc.hunting_bird_jungle", TRAP_TILE.translate(1, 0))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        world.tick(box)
        assertEquals(TRAP_CREATURE_NONE, box.trapCreature)

        val other = HunterTrapTestWorld()
        val snareOwner = other.addPlayer(TRAP_TILE.translate(4, 4), hunterLvl = 99)
        val snare = other.layPortableTrap(TrapFamily.SNARE, TRAP_TILE, snareOwner)
        other.addNpc("npc.hunting_chinchompa", TRAP_TILE.translate(1, 0))
        other.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        other.tick(snare)
        assertEquals(TRAP_CREATURE_NONE, snare.trapCreature)
    }

    /* Lifetime, collapse and expiry. */

    /**
     * "`duration` is the trap's remaining lifetime. ControllerRepository deletes an expired
     * controller silently, which would strand the loc, so collapse one cycle early instead." A
     * portable trap leaves its wreck on the ground so the owner can still come back for the item.
     */
    @Test
    fun `an expiring portable trap leaves a wreck and deletes its controller`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        val controller = world.layPortableTrap(TrapFamily.BOX, TRAP_TILE, owner)

        controller.duration = 1
        world.tick(controller)

        assertEquals("loc.hunting_boxtrap_failed", world.locNameAt(TRAP_TILE))
        assertNull(world.controllerAt(TRAP_TILE))
    }

    /** A deadfall leaves nothing behind: the boulder is simply unset again. */
    @Test
    fun `an expiring deadfall unsets its boulder and deletes its controller`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val controller = world.armDeadfall(TRAP_TILE, owner)

        controller.duration = 1
        world.tick(controller)

        assertEquals(HunterTrapStates.DEADFALL_BOULDER, world.locNameAt(TRAP_TILE))
        assertNull(world.controllerAt(TRAP_TILE))
    }

    /** "Traps belong to a logged-in owner ... live despawns a player's traps when they leave." */
    @Test
    fun `a trap whose owner logged out collapses on the next tick`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        val controller = world.layPortableTrap(TrapFamily.BOX, TRAP_TILE, owner)

        world.removePlayer(owner)
        world.tick(controller)

        assertEquals("loc.hunting_boxtrap_failed", world.locNameAt(TRAP_TILE))
        assertNull(world.controllerAt(TRAP_TILE))
    }

    /**
     * "A sprung trap waits for its owner rather than continuing to decay from wherever its lifetime
     * happened to be when the creature arrived." Without the reset, a trap that sprang late in its
     * life would collapse before the player could walk back to it and take the catch with it.
     */
    @Test
    fun `springing a trap resets its remaining lifetime`() {
        val controller = boxTrapWithChinchompaInRange()
        controller.duration = 5
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)

        assertEquals(TRAP_LIFETIME_CYCLES, controller.duration)
    }

    /**
     * An unattended trap must *not* reset its duration, or a trap laid in an empty field would sit
     * armed forever and the cap would never free up.
     */
    @Test
    fun `an unattended trap does not reset its lifetime`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        val controller = world.layPortableTrap(TrapFamily.BOX, TRAP_TILE, owner)

        controller.duration = 42
        world.tick(controller)

        assertEquals(42, controller.duration)
    }

    /** A controller whose loc has gone is deleted rather than left ticking against nothing. */
    @Test
    fun `a controller whose trap loc vanished deletes itself`() {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = 99)
        val controller = world.layPortableTrap(TrapFamily.BOX, TRAP_TILE, owner)

        val loc = world.locRepo.findExact(TRAP_TILE, LocShape.CentrepieceStraight)
        world.locRepo.del(loc!!, Int.MAX_VALUE)
        // The tick asserts the controller outlived a single cycle before accepting a missing loc.
        world.advance(2)

        world.tick(controller)

        assertNull(world.controllerAt(TRAP_TILE))
    }

    /* Helpers. */

    /**
     * A box trap with its owner standing clear of it and a chinchompa one tile north.
     *
     * [hunterLvl] defaults to 99, where the chinchompa's rate exceeds `1.0` and the catch is
     * certain. Tests that need a miss pass [CHINCHOMPA_LEVEL] instead - see
     * [ScriptedRandom.HIGHEST_DRAW].
     */
    private fun boxTrapWithChinchompaInRange(hunterLvl: Int = 99): Controller {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = hunterLvl)
        val controller = world.layPortableTrap(TrapFamily.BOX, TRAP_TILE, owner)
        world.addNpc("npc.hunting_chinchompa", TRAP_TILE.translate(0, 1))
        return controller
    }

    private companion object {
        /** The regular chinchompa's Hunter requirement, where its rate is a real fraction (~57%). */
        const val CHINCHOMPA_LEVEL: Int = 53

        /** The wild kebbit's, i.e. the lowest deadfall creature's (~43%). */
        const val WILD_KEBBIT_LEVEL: Int = 23
    }
}
