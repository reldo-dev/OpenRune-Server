package org.rsmod.content.skills.hunter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.content.skills.hunter.HunterTrapTestWorld.Companion.TRAP_TILE
import org.rsmod.game.entity.Player

/**
 * The player-facing half of the engine: the five `ProtectedAccess` ops.
 *
 * These were expected to be out of reach, because the harness that used to supply a player context
 * (`api/testing`) was deleted. They are not: `ProtectedAccessContextFactory.empty()` supplies a
 * context whose every dependency throws on first touch, and none of the hunter ops touch one. The
 * suspending `setDeadfall` needs a little more - see [HunterTrapTestWorld.startProtected] - but it
 * runs too.
 *
 * Two things here cannot be reached and are covered nowhere:
 * - the **timed** half of a loc change, including `setDeadfall`'s safety-net revert of an abandoned
 *   `SETTING` boulder. `LocRepository.processDurations` is `internal` to `api:repo` and is driven by
 *   the game loop, so a duration set from content can be observed as scheduled but never as fired.
 * - `mes` / `soundSynth` output. Both run without a client, but a `NoopClient` records nothing, so a
 *   test can assert that a refusal *happened* and not which message it sent.
 *
 * Serialised against the other world-driven hunter tests: `test-conventions` turns JUnit parallel
 * execution on for every module, and these share more than their own worlds - `ServerCacheManager`
 * is a singleton and `RSCM` memoises name-to-id lookups in a plain `HashMap`, which is not safe to
 * fill from several threads at once. Run concurrently, they failed differently on every run.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class HunterTrapOpsTest {
    private lateinit var world: HunterTrapTestWorld

    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
        world = HunterTrapTestWorld()
    }

    /* setDeadfall. */

    @Test
    fun `arming a deadfall consumes the log, keeps the knife and records both`() {
        val player = hunter(level = 99, carrying = listOf("obj.knife", "obj.logs"))
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val boulder = world.boundLocAt(TRAP_TILE)!!

        val armed = world.runProtected(player) { it.setDeadfall(boulder) }

        assertTrue(armed)
        assertEquals(HunterTrapStates.DEADFALL_ARMED, world.locNameAt(TRAP_TILE))
        assertTrue(player.inv.contains("obj.knife"), "The knife is a tool and is kept.")
        assertFalse(player.inv.contains("obj.logs"), "The log is consumed.")

        val controller = checkNotNull(world.controllerAt(TRAP_TILE))
        assertEquals(TrapFamily.DEADFALL.ordinal, controller.trapFamily)
        assertEquals(objId("obj.logs"), controller.trapDeadfallLog, "Dismantling hands this back.")
        assertEquals(listOf(TRAP_TILE.packed), player.hunterTrapCoords)
    }

    /**
     * The invariant behind `fix: charge the deadfall log only once the boulder is armed`.
     *
     * Charging up front cost the player a log for nothing twice over - on a contested boulder and on
     * a logout during the set delay - and neither is observable in play, because in both cases the
     * player simply finds themselves one log short with no message. The set delay is the only window
     * in which the difference exists, so this steps the op cycle by cycle rather than awaiting it.
     */
    @Test
    fun `the deadfall log is not charged until after the set delay`() {
        val player = hunter(level = 99, carrying = listOf("obj.knife", "obj.logs"))
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val boulder = world.boundLocAt(TRAP_TILE)!!

        val run = world.startProtected(player) { it.setDeadfall(boulder) }

        repeat(DEADFALL_SET_CYCLES) {
            assertFalse(run.isFinished, "Still setting.")
            assertTrue(player.inv.contains("obj.logs"), "The log is still the player's, cycle $it.")
            assertEquals(HunterTrapStates.DEADFALL_SETTING, world.locNameAt(TRAP_TILE))
            run.advanceCycle()
        }

        assertTrue(run.await())
        assertFalse(player.inv.contains("obj.logs"))
    }

    /** Losing the log during the set earns a refusal, not a boulder armed for free. */
    @Test
    fun `a deadfall set whose log is gone by the end is refused`() {
        val player = hunter(level = 99, carrying = listOf("obj.knife", "obj.logs"))
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val boulder = world.boundLocAt(TRAP_TILE)!!

        val run = world.startProtected(player) { it.setDeadfall(boulder) }
        world.protectedAccess(player).invDel(player.inv, "obj.logs", 1)

        assertFalse(run.await())
        assertNull(world.controllerAt(TRAP_TILE))
        assertFalse(world.locNameAt(TRAP_TILE) == HunterTrapStates.DEADFALL_ARMED)
    }

    @Test
    fun `a deadfall needs the level, a knife and a log`() {
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val boulder = world.boundLocAt(TRAP_TILE)!!

        val underLevelled =
            hunter(level = DEADFALL_LEVEL_REQ - 1, carrying = listOf("obj.knife", "obj.logs"))
        assertFalse(world.runProtected(underLevelled) { it.setDeadfall(boulder) }, "level")

        val knifeless = hunter(level = 99, carrying = listOf("obj.logs"))
        assertFalse(world.runProtected(knifeless) { it.setDeadfall(boulder) }, "knife")

        val logless = hunter(level = 99, carrying = listOf("obj.knife"))
        assertFalse(world.runProtected(logless) { it.setDeadfall(boulder) }, "log")

        assertEquals(HunterTrapStates.DEADFALL_BOULDER, world.locNameAt(TRAP_TILE))
        assertNull(world.controllerAt(TRAP_TILE))
    }

    /**
     * A fletching knife is the other accepted tool, and is the one a player training Fletching is
     * likely to be carrying instead of a plain knife.
     */
    @Test
    fun `a fletching knife arms a deadfall too`() {
        val player = hunter(level = 99, carrying = listOf("obj.fletching_knife", "obj.logs"))
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val boulder = world.boundLocAt(TRAP_TILE)!!

        assertTrue(world.runProtected(player) { it.setDeadfall(boulder) })
    }

    /**
     * The exclusion list against the *packed* firemaking logs table rather than against itself.
     *
     * `DeadfallLogicTest` checks the predicate on plain strings; this checks the thing that actually
     * matters - that `usableLogIds`, built by filtering every `input` row of the firemaking table,
     * really does leave these two out. A player carrying only redwood must be refused, not quietly
     * charged one.
     */
    @Test
    fun `redwood and arctic pine logs cannot arm a deadfall`() {
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val boulder = world.boundLocAt(TRAP_TILE)!!

        for (log in listOf("obj.redwood_logs", "obj.arctic_pine_log")) {
            val player = hunter(level = 99, carrying = listOf("obj.knife", log))
            assertFalse(world.runProtected(player) { it.setDeadfall(boulder) }, log)
            assertTrue(player.inv.contains(log), "$log must not be consumed.")
        }
    }

    /**
     * The protective half of the same rule. Every one of these is a real firemaking input row, so
     * "any type of log" sweeps them in unless they are excluded - and the set path picks the first
     * usable log by slot order, so a player carrying a clue step's logs above their ordinary ones
     * would have the clue item destroyed to arm a boulder.
     */
    @Test
    fun `a clue step's coloured logs cannot arm a deadfall`() {
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val boulder = world.boundLocAt(TRAP_TILE)!!

        val trailLogs =
            listOf(
                "obj.blue_logs",
                "obj.green_logs",
                "obj.red_logs",
                "obj.trail_logs_purple",
                "obj.trail_logs_white",
            )
        for (log in trailLogs) {
            val player = hunter(level = 99, carrying = listOf("obj.knife", log))
            assertFalse(world.runProtected(player) { it.setDeadfall(boulder) }, log)
            assertTrue(player.inv.contains(log), "$log must not be consumed.")
        }
    }

    /**
     * A clue log ranked *above* an ordinary one must not be picked. The set path takes the first
     * usable log by slot order with no second ranking pass, so this only holds because the clue logs
     * are excluded outright rather than merely sorted last.
     */
    @Test
    fun `an ordinary log below a clue log is the one that gets used`() {
        val player = hunter(level = 99, carrying = listOf("obj.knife", "obj.red_logs", "obj.logs"))
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        val boulder = world.boundLocAt(TRAP_TILE)!!

        assertTrue(world.runProtected(player) { it.setDeadfall(boulder) })

        assertTrue(player.inv.contains("obj.red_logs"), "The clue log is untouched.")
        assertFalse(player.inv.contains("obj.logs"))
    }

    /** "Unlike most hunter traps, only one deadfall trap can be set up at once." (wiki.) */
    @Test
    fun `only one deadfall can be set at a time`() {
        val player = hunter(level = 99, carrying = listOf("obj.knife", "obj.logs", "obj.logs"))
        val second = TRAP_TILE.translate(2, 0)
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        world.addMapLoc(second, HunterTrapStates.DEADFALL_BOULDER)

        assertTrue(world.runProtected(player) { it.setDeadfall(world.boundLocAt(TRAP_TILE)!!) })
        assertFalse(world.runProtected(player) { it.setDeadfall(world.boundLocAt(second)!!) })

        assertEquals(HunterTrapStates.DEADFALL_BOULDER, world.locNameAt(second))
    }

    /* dismantleDeadfall. */

    /**
     * Dismantling is a `locRepo.change` back to the boulder, never a delete - the same invariant the
     * tick tests cover, reached through the op a player actually clicks.
     */
    @Test
    fun `dismantling an armed deadfall hands the log back and unsets the boulder`() {
        val player = hunter(level = 99, carrying = listOf("obj.knife", "obj.logs"))
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        world.runProtected(player) { it.setDeadfall(world.boundLocAt(TRAP_TILE)!!) }

        val armed = world.boundLocAt(TRAP_TILE)!!
        assertTrue(world.runProtected(player) { it.dismantleDeadfall(armed) })

        assertEquals(HunterTrapStates.DEADFALL_BOULDER, world.locNameAt(TRAP_TILE))
        assertTrue(world.deadfallPresent(TRAP_TILE), "The boulder is changed, never deleted.")
        assertTrue(player.inv.contains("obj.logs"), "The log comes back.")
        assertNull(world.controllerAt(TRAP_TILE))
        assertEquals(emptyList<Int>(), player.hunterTrapCoords)
    }

    @Test
    fun `dismantling someone else's deadfall is refused and changes nothing`() {
        val owner = hunter(level = 99, carrying = listOf("obj.knife", "obj.logs"))
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        world.runProtected(owner) { it.setDeadfall(world.boundLocAt(TRAP_TILE)!!) }

        val thief = hunter(level = 99, carrying = emptyList())
        val armed = world.boundLocAt(TRAP_TILE)!!
        assertFalse(world.runProtected(thief) { it.dismantleDeadfall(armed) })

        assertEquals(HunterTrapStates.DEADFALL_ARMED, world.locNameAt(TRAP_TILE))
        assertFalse(thief.inv.contains("obj.logs"))
        assertNotNull(world.controllerAt(TRAP_TILE))
    }

    /* layTrap. */

    @Test
    fun `laying a box trap consumes the trap item and records the coord`() {
        val player = hunter(level = 99, carrying = listOf("obj.hunting_box_trap"))

        assertTrue(world.runProtected(player) { it.layTrap(TrapFamily.BOX, TRAP_TILE) })

        assertEquals("loc.hunting_boxtrap_empty", world.locNameAt(TRAP_TILE))
        assertFalse(player.inv.contains("obj.hunting_box_trap"))
        assertEquals(listOf(TRAP_TILE.packed), player.hunterTrapCoords)
        assertNotNull(world.controllerAt(TRAP_TILE))
    }

    @Test
    fun `laying a trap without the item is refused`() {
        val player = hunter(level = 99, carrying = emptyList())

        assertFalse(world.runProtected(player) { it.layTrap(TrapFamily.BOX, TRAP_TILE) })

        assertNull(world.locAt(TRAP_TILE))
        assertNull(world.controllerAt(TRAP_TILE))
    }

    @Test
    fun `a tile that already holds a trap cannot take another`() {
        val player = hunter(level = 99, carrying = listOf("obj.hunting_box_trap", "obj.hunting_box_trap"))

        assertTrue(world.runProtected(player) { it.layTrap(TrapFamily.BOX, TRAP_TILE) })
        assertFalse(world.runProtected(player) { it.layTrap(TrapFamily.BOX, TRAP_TILE) })

        assertTrue(player.inv.contains("obj.hunting_box_trap"), "The second trap is not consumed.")
    }

    /**
     * The cap is read from the *effective* level, and a level-1 hunter gets one trap. The stored
     * coords are what enforces it, so this is also the check that laying writes them.
     */
    @Test
    fun `a level-1 hunter can only lay one trap`() {
        val player =
            hunter(level = 1, carrying = listOf("obj.hunting_ojibway_bird_snare", "obj.hunting_ojibway_bird_snare"))

        assertTrue(world.runProtected(player) { it.layTrap(TrapFamily.SNARE, TRAP_TILE) })
        assertFalse(
            world.runProtected(player) { it.layTrap(TrapFamily.SNARE, TRAP_TILE.translate(2, 0)) }
        )

        assertEquals(1, player.hunterTrapCoords.size)
        assertNull(world.locAt(TRAP_TILE.translate(2, 0)))
    }

    /* collectTrap and takeTrap. */

    @Test
    fun `collecting a sprung box trap awards the catch, returns the trap and grants xp`() {
        val player = hunter(level = 99, carrying = listOf("obj.hunting_box_trap"))
        val controller = springBoxTrapOn(player)

        val sprung = world.boundLocAt(TRAP_TILE)!!
        assertTrue(world.runProtected(player) { it.collectTrap(sprung) })

        assertTrue(player.inv.contains("obj.chinchompa_captured"), "The catch.")
        assertTrue(player.inv.contains("obj.hunting_box_trap"), "The trap item comes back.")
        assertNull(world.locAt(TRAP_TILE))
        assertNull(world.controllerAt(TRAP_TILE))
        assertEquals(emptyList<Int>(), player.hunterTrapCoords)

        // Creature xp is stored x10 in the packed table and divided by ten once, at the award.
        val creature = HunterCreatures.all[controller.trapCreature]
        assertEquals(creature.xp / 10, player.statMap.getXP("stat.hunter"))
    }

    @Test
    fun `collecting someone else's trap is refused and leaves it standing`() {
        val owner = hunter(level = 99, carrying = listOf("obj.hunting_box_trap"))
        springBoxTrapOn(owner)

        val thief = hunter(level = 99, carrying = emptyList())
        val sprung = world.boundLocAt(TRAP_TILE)!!
        assertFalse(world.runProtected(thief) { it.collectTrap(sprung) })

        assertFalse(thief.inv.contains("obj.chinchompa_captured"))
        assertNotNull(world.controllerAt(TRAP_TILE))
    }

    /**
     * A refused collect must be a no-op, not a partial one: the space check runs before anything is
     * awarded, so the trap is still there to try again with a slot free.
     */
    @Test
    fun `a full inventory refuses the collect and awards nothing`() {
        val player = hunter(level = 99, carrying = listOf("obj.hunting_box_trap"))
        springBoxTrapOn(player)
        val access = world.protectedAccess(player)
        while (player.inv.freeSpace() > 0) {
            access.invAdd(player.inv, "obj.bones", 1)
        }

        val sprung = world.boundLocAt(TRAP_TILE)!!
        assertFalse(world.runProtected(player) { it.collectTrap(sprung) })

        assertFalse(player.inv.contains("obj.chinchompa_captured"))
        assertNotNull(world.controllerAt(TRAP_TILE), "The trap is still there to come back to.")
        assertEquals(0, player.statMap.getXP("stat.hunter"), "No xp on a refused collect.")
    }

    /**
     * A collapsed trap outlives its controller, so `takeTrap` on one has nobody to check ownership
     * against; whoever clears the tile keeps the trap item. It cannot mint a second one because the
     * loc is deleted in the same call.
     */
    @Test
    fun `taking a collapsed trap hands the item back and clears the tile`() {
        val owner = hunter(level = 99, carrying = listOf("obj.hunting_box_trap"))
        world.runProtected(owner) { it.layTrap(TrapFamily.BOX, TRAP_TILE) }
        val controller = world.controllerAt(TRAP_TILE)!!
        controller.duration = 1
        world.tick(controller)
        assertNull(world.controllerAt(TRAP_TILE), "Collapsed.")

        val wreck = world.boundLocAt(TRAP_TILE)!!
        assertTrue(world.runProtected(owner) { it.takeTrap(wreck, TrapFamily.BOX) })

        assertTrue(owner.inv.contains("obj.hunting_box_trap"))
        assertNull(world.locAt(TRAP_TILE))
    }

    /** `takeTrap` on a trap that still has a controller routes to the collect transaction. */
    @Test
    fun `taking a sprung trap collects it instead`() {
        val player = hunter(level = 99, carrying = listOf("obj.hunting_box_trap"))
        springBoxTrapOn(player)

        val sprung = world.boundLocAt(TRAP_TILE)!!
        assertTrue(world.runProtected(player) { it.takeTrap(sprung, TrapFamily.BOX) })

        assertTrue(player.inv.contains("obj.chinchompa_captured"))
        assertTrue(player.inv.contains("obj.hunting_box_trap"))
    }

    /**
     * The boulder invariant once more, through the collect op: a deadfall's teardown is
     * `endTrapLoc`, which changes rather than deletes, and the deadfall contributes no trap item.
     */
    @Test
    fun `collecting a deadfall returns the boulder unset and hands back no trap item`() {
        val player = hunter(level = 99, carrying = listOf("obj.knife", "obj.logs"))
        world.addMapLoc(TRAP_TILE, HunterTrapStates.DEADFALL_BOULDER)
        world.runProtected(player) { it.setDeadfall(world.boundLocAt(TRAP_TILE)!!) }

        world.addNpc("npc.huntingbeast_claws", TRAP_TILE.translate(1, 0))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        val controller = world.controllerAt(TRAP_TILE)!!
        world.tick(controller)
        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)

        val full = world.boundLocAt(TRAP_TILE)!!
        assertTrue(world.runProtected(player) { it.collectTrap(full) })

        assertEquals(HunterTrapStates.DEADFALL_BOULDER, world.locNameAt(TRAP_TILE))
        assertTrue(world.deadfallPresent(TRAP_TILE), "The boulder is changed, never deleted.")
        assertTrue(player.inv.contains("obj.huntingbeast_claws"), "The catch.")
        assertFalse(
            player.inv.contains("obj.hunting_box_trap") ||
                player.inv.contains("obj.hunting_ojibway_bird_snare"),
            "A deadfall has no trap item to hand back.",
        )
    }

    /* Helpers. */

    private fun hunter(level: Int, carrying: List<String>): Player {
        val player = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = level)
        val access = world.protectedAccess(player)
        for (obj in carrying) {
            access.invAdd(player.inv, obj, 1)
        }
        return player
    }

    /** Lays [player]'s box trap on [TRAP_TILE] and springs it on a chinchompa, then settles it. */
    private fun springBoxTrapOn(player: Player): org.rsmod.game.entity.Controller {
        world.runProtected(player) { it.layTrap(TrapFamily.BOX, TRAP_TILE) }
        val controller = world.controllerAt(TRAP_TILE)!!
        world.addNpc("npc.hunting_chinchompa", TRAP_TILE.translate(0, 1))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        world.tick(controller)
        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)
        return controller
    }

    private fun objId(internal: String): Int =
        dev.openrune.rscm.RSCM.getRSCM(internal)
}
