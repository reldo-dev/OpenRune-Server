package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
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
import org.rsmod.content.skills.hunter.HunterTrapTestWorld.Companion.TRAP_CREATURE_FAILED
import org.rsmod.content.skills.hunter.HunterTrapTestWorld.Companion.TRAP_CREATURE_NONE
import org.rsmod.content.skills.hunter.HunterTrapTestWorld.Companion.TRAP_TILE
import org.rsmod.game.entity.Controller
import org.rsmod.game.entity.Player

/**
 * The magic box reuses the portable path wholesale - which is exactly the claim worth testing:
 * nothing in the type system checks that [TrapFamily.MAGICBOX] resolves to loc names that exist,
 * and filing the imp under [TrapFamily.BOX] would have compiled, packed, then thrown at the first
 * catch. Serialised for the reason given on [HunterTrapTickTest].
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class MagicBoxTest {
    private lateinit var world: HunterTrapTestWorld

    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
        world = HunterTrapTestWorld()
    }

    @Test
    fun `laying a magic box consumes the box and spawns its own loc, not a box trap's`() {
        val player = hunter(level = 99, carrying = listOf("obj.magic_imp_box"))

        assertTrue(world.runProtected(player) { it.layTrap(TrapFamily.MAGICBOX, TRAP_TILE) })

        assertEquals(HunterTrapStates.MAGIC_BOX_EMPTY, world.locNameAt(TRAP_TILE))
        assertFalse(player.inv.contains("obj.magic_imp_box"))
        assertEquals(listOf(TRAP_TILE.packed), player.hunterTrapCoords)
        assertEquals(
            TrapFamily.MAGICBOX.ordinal,
            checkNotNull(world.controllerAt(TRAP_TILE)).trapFamily,
        )
    }

    /**
     * The claim the separate table was created for. Every loc name the magic box resolves has to
     * exist in the packed cache - if the family fell through to the box trap's suffix rule these
     * would resolve to `loc.hunting_boxtrap_full_npc.imp` and throw.
     */
    @Test
    fun `every magic box state resolves to a real loc`() {
        val imp = HunterCreatures.magicBox
        val states =
            listOf(
                checkNotNull(HunterTrapStates.setLoc(TrapFamily.MAGICBOX)),
                HunterTrapStates.trappingLoc(imp, dx = 0, dz = 1),
                HunterTrapStates.fullLoc(imp),
                HunterTrapStates.failingLoc(TrapFamily.MAGICBOX),
                HunterTrapStates.failedLoc(TrapFamily.MAGICBOX),
            )

        for (state in states) {
            assertTrue(state.startsWith("loc.hunting_imptrap"), "Unexpected state name: $state")
            assertNotNull(
                ServerCacheManager.getObject(state.asRSCM(RSCMType.LOC)),
                "Missing loc type: $state",
            )
        }
    }

    /**
     * The imp's mid-catch state is direction-independent - one `hunting_imptrap_trapping`, not one
     * per compass side the way the box trap has - so the same loc is expected whichever side of the
     * trap the imp walked in from. Pinned because the shared [HunterTrapStates.trappingLoc] takes
     * the offsets whether or not a family reads them.
     */
    @Test
    fun `the magic box ignores which side the imp came from`() {
        val imp = HunterCreatures.magicBox
        val sides = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)

        val resolved = sides.map { (dx, dz) -> HunterTrapStates.trappingLoc(imp, dx, dz) }

        assertEquals(setOf(HunterTrapStates.MAGIC_BOX_TRAPPING), resolved.toSet())
    }

    @Test
    fun `a successful catch settles into the full box and collects an imp-in-a-box`() {
        val controller = magicBoxWithImpInRange()
        val player = world.playerList.first { it.uid.packed == controller.trapOwner }
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)
        assertEquals(HunterTrapStates.MAGIC_BOX_TRAPPING, world.locNameAt(TRAP_TILE))

        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)
        assertEquals(HunterTrapStates.MAGIC_BOX_FULL, world.locNameAt(TRAP_TILE))

        val full = world.boundLocAt(TRAP_TILE)!!
        assertTrue(world.runProtected(player) { it.collectTrap(full) })

        assertTrue(player.inv.contains("obj.magic_imp_box_full"), "Imp-in-a-box(2).")
        assertFalse(
            player.inv.contains("obj.magic_imp_box_half"),
            "The 1-charge form is what *using* the box leaves, not what catching produces.",
        )
        assertTrue(player.inv.contains("obj.magic_imp_box"), "The box itself comes back.")
        assertNull(world.locAt(TRAP_TILE))
        assertEquals(HunterCreatures.magicBox.xp / 10, player.statMap.getXP("stat.hunter"))
    }

    /**
     * The magic box has no mid-failure frame: the cache holds `empty`, `trapping`, `full` and
     * `failed` and nothing between the last two. A failed catch therefore shows its wreck straight
     * away and the settle step is a no-op rather than a second state change - which is only visible
     * with a scripted draw, since a live client cannot force a miss.
     */
    @Test
    fun `a failed catch goes straight to the failed box`() {
        val controller = magicBoxWithImpInRange(hunterLvl = HunterCreatures.magicBox.level)
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW

        world.tick(controller)

        assertEquals(TRAP_CREATURE_FAILED, controller.trapCreature)
        assertEquals(HunterTrapStates.MAGIC_BOX_FAILED, world.locNameAt(TRAP_TILE))

        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)
        assertEquals(HunterTrapStates.MAGIC_BOX_FAILED, world.locNameAt(TRAP_TILE))
    }

    /** A wreck still owes the player their box, exactly as the other two portable families do. */
    @Test
    fun `taking a failed magic box hands the box back`() {
        val controller = magicBoxWithImpInRange(hunterLvl = HunterCreatures.magicBox.level)
        val player = world.playerList.first { it.uid.packed == controller.trapOwner }
        world.random.nextDouble = ScriptedRandom.HIGHEST_DRAW
        world.tick(controller)
        world.advance(TRAP_SPRING_CYCLES)
        world.tick(controller)

        controller.duration = 1
        world.tick(controller)
        assertNull(world.controllerAt(TRAP_TILE), "Collapsed.")

        val wreck = world.boundLocAt(TRAP_TILE)!!
        assertTrue(world.runProtected(player) { it.takeTrap(wreck, TrapFamily.MAGICBOX) })

        assertTrue(player.inv.contains("obj.magic_imp_box"))
        assertNull(world.locAt(TRAP_TILE))
    }

    /**
     * "When an imp passes the trap, it may be caught, but only when the player is not standing on
     * the trap." (wiki, *Magic box*.)
     */
    @Test
    fun `a player standing on a magic box suppresses the catch`() {
        val controller = magicBoxWithImpInRange()
        world.addPlayer(TRAP_TILE, hunterLvl = 99)
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH

        world.tick(controller)

        assertEquals(TRAP_CREATURE_NONE, controller.trapCreature)
        assertEquals(0, world.random.doubleDraws, "The roll must not even happen.")
    }

    /** A magic box catches imps and nothing else; a box trap does not catch imps. */
    @Test
    fun `a magic box ignores a chinchompa and a box trap ignores an imp`() {
        val controller = magicBox()
        world.addNpc("npc.hunting_chinchompa", TRAP_TILE.translate(1, 0))
        world.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        world.tick(controller)
        assertEquals(TRAP_CREATURE_NONE, controller.trapCreature)

        val other = HunterTrapTestWorld()
        val boxOwner = other.addPlayer(TRAP_TILE.translate(4, 4), hunterLvl = 99)
        val box = other.layPortableTrap(TrapFamily.BOX, TRAP_TILE, boxOwner)
        other.addNpc("npc.imp", TRAP_TILE.translate(1, 0))
        other.random.nextDouble = ScriptedRandom.ALWAYS_CATCH
        other.tick(box)
        assertEquals(TRAP_CREATURE_NONE, box.trapCreature)
    }

    /**
     * The persisted-ordinal rule. A sprung trap stores [TrapFamily.ordinal] in a varcon and its
     * creature as an index into [HunterCreatures.all], so both orders are save data: appending is
     * safe, inserting silently re-files every trap and every catch already written.
     */
    @Test
    fun `family ordinals and creature blocks are appended, never inserted`() {
        assertEquals(0, TrapFamily.SNARE.ordinal)
        assertEquals(1, TrapFamily.BOX.ordinal)
        assertEquals(2, TrapFamily.DEADFALL.ordinal)
        assertEquals(3, TrapFamily.NETTRAP.ordinal)
        assertEquals(4, TrapFamily.MAGICBOX.ordinal)

        val families = HunterCreatures.all.map { it.family }
        val firstNetTrap = families.indexOfFirst { it == TrapFamily.NETTRAP }
        assertTrue(firstNetTrap > 0, "The net trap block must not be first.")
        assertTrue(
            families.take(firstNetTrap).none {
                it == TrapFamily.NETTRAP || it == TrapFamily.MAGICBOX
            },
            "Slice 2's blocks must sit after every block shipped before them.",
        )
        assertEquals(
            listOf(TrapFamily.SNARE, TrapFamily.BOX, TrapFamily.DEADFALL),
            families.take(firstNetTrap).distinct(),
            "The order of the three shipped blocks is save data and must not change.",
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

    private fun magicBox(hunterLvl: Int = 99): Controller {
        val owner = world.addPlayer(TRAP_TILE.translate(3, 3), hunterLvl = hunterLvl)
        val access = world.protectedAccess(owner)
        access.invAdd(owner.inv, "obj.magic_imp_box", 1)
        world.runProtected(owner) { it.layTrap(TrapFamily.MAGICBOX, TRAP_TILE) }
        return world.controllerAt(TRAP_TILE)!!
    }

    /**
     * [magicBox] with an imp beside it. No level makes an imp catch certain (198/256 at 99), so a
     * catch needs [ScriptedRandom.ALWAYS_CATCH].
     */
    private fun magicBoxWithImpInRange(hunterLvl: Int = 99): Controller {
        val controller = magicBox(hunterLvl)
        world.addNpc("npc.imp", TRAP_TILE.translate(0, 1))
        return controller
    }
}
