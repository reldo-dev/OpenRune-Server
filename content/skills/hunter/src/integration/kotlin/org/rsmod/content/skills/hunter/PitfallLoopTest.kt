package org.rsmod.content.skills.hunter

import com.google.inject.Injector
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.NpcMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.rsmod.api.game.process.GameCycle
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.events.interact.NpcEvents
import org.rsmod.api.player.interact.LocInteractions
import org.rsmod.api.player.interact.NpcInteractions
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.registry.loc.LocRegistry
import org.rsmod.api.registry.player.PlayerRegistry
import org.rsmod.api.registry.player.PlayerRegistryResult
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.entity.util.PathingEntityCommon
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey
import org.rsmod.routefinder.collision.CollisionFlagMap

/**
 * Pitfall trapping, driven by the **real** game loop instead of a fake world.
 *
 * The unit suite reaches `trapPit`, `teaseCreature`, `jumpPit` and `dismantlePit` by calling them,
 * over a hand-built world of one loc and a scripted RNG. That proves the bodies, and it is where
 * the value tables and the refusal rules belong. It cannot prove any of these:
 * 1. that a real boot puts the ops on the bus at all - the nine op-carrying children of the
 *    twenty-five sites, and the five creatures' `Tease`;
 * 2. that the authored coordinates name locs a booted world actually holds. Every one of the
 *    twenty-five placements is authored at **level 1** in the cache and resolves to **level 0**
 *    through `LINK_BELOW`, which is why [PitfallSites] carries level 0 - a conclusion drawn from a
 *    decode, and [theAuthoredPitfallSitesArePlacedInTheBootedWorld] is where it would surface as
 *    wrong;
 * 3. **that a teased creature moves at all.** `NpcMode.PlayerFollow` has no other production use
 *    in this repository, and the unit tests only assert the two fields the follow processor
 *    *reads* - they never run the processor. Until [aTeasedLarupiaWalksTowardsItsHunter] ran, the
 *    whole chase was an unexercised claim;
 * 4. that a build-tease-jump-catch-dismantle cycle produces loot and experience with nothing
 *    called by name - the cache's own ops, `PluginScriptLoader`'s registrations, `GameCycle`'s
 *    `LateCycle`, and the engine's `ProtectedAccess` dispatch doing every step.
 *
 * Nothing here is scripted. The catch roll is the server's own `GameRandom`, so the two big-cat
 * paths retry across that creature's own sites rather than pinning a draw - see [catchOne].
 *
 * Lives in the integration source set for the reason [ImplingSpawnerLoopTest] does: a booted server
 * contaminates the unit-test JVM, and a second boot in one JVM dies outright. The harness below
 * deliberately mirrors [TrackingLoopTest]'s rather than sharing it: the two need different halves
 * of it, and a shared base class in a suite whose whole point is one boot per JVM is a worse trade
 * than sixty duplicated lines.
 */
@Execution(ExecutionMode.SAME_THREAD)
class PitfallLoopTest {
    /**
     * Every pitfall op is on the bus after a real boot.
     *
     * `HunterWiringTest` asks the same question of a synthetic bus it populated itself by calling
     * `startup()`. This asks it of the bus `PluginScriptLoader` populated - so a `PitfallEvents`
     * that never got scanned, or that threw during its own startup check and was skipped, fails
     * here and passes there. The lookup is `EventBus.contains`, which is the exact call
     * `LocInteractions.opTrigger` and `NpcInteractions.opTrigger` make when deciding whether a
     * click has a handler.
     *
     * The negative half matters as much as the positive one. The twenty-five map-placed parents
     * carry no ops of their own and must not be subscribed to - a handler there would fire on a
     * click the cache never offers - and [PitfallSites.opLessLocs] are the six locs in the family
     * that no varbit value can render or that carry no op at all.
     */
    @Test
    fun theRealBootRegistersEveryPitfallOp() {
        val world = world()
        var registrations = 0

        assertTrue(world.hasOpLoc3(PitfallSites.EMPTY_LOC), "no op3 (Trap) on an empty pit")
        registrations++

        assertTrue(world.hasOpLoc1(PitfallSites.SET_LOC), "no op1 (Jump) on a spiked pit")
        registrations++

        for (loc in PitfallSites.dismantleLocs) {
            assertTrue(world.hasOpLoc2(loc), "no op2 (Dismantle) handler for $loc")
            registrations++
        }

        for (creature in PitfallCreatures.all) {
            assertTrue(world.hasOpNpc1(creature.npc), "no op1 (Tease) handler for ${creature.npc}")
            registrations++
        }

        // Derived from the tables so a child or a creature added to either joins the sweep on its
        // own, and pinned as a literal beside it so a table that *shrinks* fails here rather than
        // shrinking the loops above into a vacuous pass. `PitfallSitesTest` pins the table sizes
        // themselves.
        val expected = 2 + PitfallSites.dismantleLocs.size + PitfallCreatures.all.size
        assertEquals(expected, registrations, "pitfall should register $expected ops")
        assertEquals(15, registrations, "1 Trap + 1 Jump + 8 Dismantle + 5 Tease")

        for (site in PitfallSites.all) {
            val id = site.baseLoc.asRSCM(RSCMType.LOC)
            assertFalse(world.hasOpLoc1(id), "op1 on the map-placed parent ${site.baseLoc}")
            assertFalse(world.hasOpLoc2(id), "op2 on the map-placed parent ${site.baseLoc}")
            assertFalse(world.hasOpLoc3(id), "op3 on the map-placed parent ${site.baseLoc}")
        }

        for (loc in PitfallSites.opLessLocs) {
            val id = loc.asRSCM(RSCMType.LOC)
            assertFalse(world.hasOpLoc1(id), "op1 on the op-less $loc")
            assertFalse(world.hasOpLoc2(id), "op2 on the op-less $loc")
            assertFalse(world.hasOpLoc3(id), "op3 on the op-less $loc")
        }
    }

    /**
     * Every coordinate [PitfallSites] authors names a loc the booted world actually holds.
     *
     * This is the check that catches a wrong **plane**. The twenty-five sites are authored in the
     * cache at level 1 and reach the running world at level 0, because `GameMapDecoder` shifts a
     * loc down a plane on a `LINK_BELOW` tile - so "placed in the cache" and "clickable in a
     * running server" are different claims, and the table records the second. If the decode behind
     * that conclusion were wrong, the level-0 lookup below would find nothing.
     *
     * The level-1 half is the same claim from the other side, and it is the one that would fail if
     * some later map change stopped shifting these tiles: a site that resolves at **both** planes
     * would mean the table is right by luck.
     */
    @Test
    fun theAuthoredPitfallSitesArePlacedInTheBootedWorld() {
        val world = world()
        val missing = mutableListOf<String>()
        val duplicated = mutableListOf<String>()
        var checked = 0

        val offPlane = mutableListOf<String>()

        for (site in PitfallSites.all) {
            checked++
            world.locOrNull(site.coords, site.baseLoc)
                ?: missing.add("${site.baseLoc} at ${site.coords}")

            val above = CoordGrid(x = site.coords.x, z = site.coords.z, level = 1)
            if (world.locOrNull(above, site.baseLoc) != null) {
                duplicated.add("${site.baseLoc} also at $above")
            }
            if (site.coords.level != 0) {
                offPlane.add("${site.baseLoc} at level ${site.coords.level}")
            }
        }

        // The registry claim is asserted before the plane one, so a table moved to the wrong plane
        // fails as "absent from the booted world" - which is what a player would experience - and
        // not as a note about a field.
        assertEquals(25, checked, "PitfallSites should author 25 coordinates")
        assertEquals(emptyList<String>(), missing, "authored sites absent from the booted world")
        assertEquals(emptyList<String>(), duplicated, "sites the LINK_BELOW shift did not move")
        assertEquals(emptyList<String>(), offPlane, "sites authored off level 0")
    }

    /**
     * A teased larupia **walks**, and it walks towards the hunter who teased it.
     *
     * The most valuable assertion in this file, because nothing else in the repository exercises
     * `NpcMode.PlayerFollow` at all: `teaseCreature` sets the mode and faces the player, and every
     * unit test stops there. Whether the engine then does anything with those two fields was, until
     * this ran, an untested inference from reading `NpcPlayerFollowModeProcessor`.
     *
     * The tease is a real `Tease` click through [NpcInteractions]; the hunter then stands on one of
     * that creature's own pits, between four and fifteen tiles away, and the cycle is stepped. Four
     * as a floor so there is a walk to observe at all; fifteen as a ceiling because
     * `NpcPlayerFollowModeProcessor.VALID_DISTANCE` is where the engine stops walking the creature
     * and **teleports it onto the player** instead. [aTeasedGraahkIsTakenOffTheLeash] is where that
     * teleport is exercised on purpose; here the per-cycle step is asserted to be a step, so a
     * chase that only ever "works" by teleporting cannot pass.
     */
    @Test
    fun aTeasedLarupiaWalksTowardsItsHunter() {
        val world = world()
        val player = world.newPlayer("larupialure")
        world.login(player)
        try {
            world.give(player, TEASING_STICK)

            val (npc, pit) =
                world.chaseSetup(PitfallCreatures.larupia)
                    ?: error("no larupia within $CHASE_WINDOW tiles of one of its own pits")

            world.clickNpc(player, npc, InteractionOp.Op1)
            assertEquals(player.uid, world.pitfall.teasedBy(npc), "the Tease did not start a chase")
            assertEquals(NpcMode.PlayerFollow, npc.mode, "the Tease did not set the follow mode")

            PathingEntityCommon.telejump(player, world.collision, pit.coords)
            val start = npc.coords
            val startGap = start.chebyshevDistance(player.coords)
            assertTrue(startGap >= 4, "the hunter should start clear, was $startGap tiles")

            var longestStep = 0
            var previous = start
            var cycles = 0
            while (
                cycles < CHASE_CYCLES &&
                    npc.coords.chebyshevDistance(player.coords) > CATCH_RANGE
            ) {
                world.cycle.tick()
                longestStep = maxOf(longestStep, previous.chebyshevDistance(npc.coords))
                previous = npc.coords
                cycles++
            }

            val endGap = npc.coords.chebyshevDistance(player.coords)
            assertNotEquals(start, npc.coords, "the teased larupia never moved")
            assertTrue(endGap < startGap, "the larupia did not close the gap: $startGap -> $endGap")
            // `CATCH_RANGE` rather than one tile, and it is the mechanic's own number: a larupia
            // occupies more than a square and the route finder stops when its **bounding box** is
            // adjacent, so the south-west tile `coords` reports comes to rest a couple of tiles
            // out. `HunterPitfall.crossingCreature` measures the same way, so "close enough to be
            // crossing the pit" is exactly the right bar for "the chase brought it here".
            assertTrue(
                endGap <= CATCH_RANGE,
                "the larupia stopped $endGap tiles short of its hunter (size ${npc.size})",
            )
            // A walked step is one tile, or two for a creature the cache runs. Anything larger is
            // the follow processor's teleport, which would make "it closed the gap" meaningless.
            assertTrue(longestStep in 1..2, "the larupia jumped $longestStep tiles in one cycle")
            assertEquals(player.uid, world.pitfall.teasedBy(npc), "the chase ended early")
        } finally {
            world.endChases()
            world.logout(player)
        }
    }

    /**
     * A kyatt: built, teased, jumped, caught and collected on the real loop.
     *
     * The big-cat path, which rolls. `rollCatch` draws from the injected `GameRandom` and nothing
     * here scripts it, so a refusal is a legitimate outcome and [catchOne] retries on the next
     * kyatt pit - dismantling the refused one first, so the trap cap is never the reason a later
     * attempt fails. See [catchOne] for why that is honest rather than a way of hiding a failure.
     *
     * The loot and the experience are pinned as literals off the wiki rather than read back out of
     * [PitfallCreatures], so this compares the code against a source outside it.
     */
    @Test
    fun aKyattIsCaughtAndCollectedOnTheRealLoop() {
        catchAndCollect(
            creature = PitfallCreatures.kyatt,
            username = "kyatthunter",
            expectedFineXp = 3000,
            expectedLoot =
                listOf(
                    "obj.big_bones",
                    "obj.hunting_kyatt_meat",
                    "obj.hunting_fur_tiger_perfect",
                ),
        )
    }

    /**
     * A sunlight antelope: the same cycle down the path that never rolls.
     *
     * The antelopes are a documented 100% catch and carry a null success pair, so `rollCatch`
     * returns before it draws - which makes this the deterministic half of the pair and the one
     * that would fail outright if the roll were reached anyway. Its loot is four lines rather than
     * three, so it also exercises the inventory-room check against a wider catch.
     */
    @Test
    fun aSunlightAntelopeIsCaughtAndCollectedOnTheRealLoop() {
        catchAndCollect(
            creature = PitfallCreatures.sunlight,
            username = "antelopehunt",
            expectedFineXp = 3800,
            expectedLoot =
                listOf(
                    "obj.big_bones",
                    "obj.hunting_antelopesun_meat",
                    "obj.hunting_antelopesun_fur",
                    "obj.hunting_antelopesun_horn",
                ),
        )
    }

    /**
     * A chase dragged across the world ends, and the engine's own teleport is what drags it.
     *
     * The guard `HunterPitfall.tick` exists for. Past `VALID_DISTANCE` the follow processor stops
     * walking the creature and teleports it onto the player every cycle, so a hunter who walks to
     * another hunting ground arrives with a graahk standing on them and no way to be rid of it -
     * these five npcs declare no `Attack` op, so it cannot be killed. `tick`'s spawn-anchored
     * sixty-four-tile leash is the only thing in the server that ends the pursuit.
     *
     * The graahk is despawned afterwards rather than left where the teleport put it: `despawn`
     * brings it back at its own spawn tile, which leaves the shared world as this test found it.
     */
    @Test
    fun aTeasedGraahkIsTakenOffTheLeash() {
        val world = world()
        val player = world.newPlayer("leashtester")
        world.login(player)
        var chaser: Npc? = null
        try {
            world.give(player, TEASING_STICK)

            val npc =
                PitfallSites.all
                    .filter { it.creature === PitfallCreatures.graahk }
                    .firstNotNullOfOrNull { with(world) { liveCreature(it) } }
                    ?: error("no graahk in the world")
            chaser = npc

            world.clickNpc(player, npc, InteractionOp.Op1)
            assertEquals(player.uid, world.pitfall.teasedBy(npc), "the Tease did not start a chase")

            // A kyatt pit: a real, walkable tile in another hunting ground, hundreds of tiles from
            // any graahk spawn. Whichever creature's pit it is does not matter - the leash measures
            // the creature's distance from its own spawn, not from a pit.
            val faraway = PitfallSites.all.first { it.creature === PitfallCreatures.kyatt }
            PathingEntityCommon.telejump(player, world.collision, faraway.coords)
            assertTrue(
                npc.coords.chebyshevDistance(player.coords) > FOLLOW_VALID_DISTANCE,
                "the hunter did not get far enough away to trigger the follow teleport",
            )

            repeat(3) { world.cycle.tick() }

            assertTrue(
                npc.spawnCoords.chebyshevDistance(npc.coords) > CHASE_RANGE,
                "the follow processor did not teleport the graahk past the leash",
            )
            assertNull(world.pitfall.teasedBy(npc), "the leash did not end the chase")
            assertNotEquals(NpcMode.PlayerFollow, npc.mode, "the graahk is still following")
        } finally {
            chaser?.let { world.npcs.despawn(it, it.visType.respawnRate) }
            world.endChases()
            world.logout(player)
        }
    }

    /**
     * One creature, from an empty pit to the loot in the backpack, asserting both ends.
     *
     * Every click below is an [InteractionOp] handed to [LocInteractions] or [NpcInteractions]
     * against something read out of the booted world's registries, resolved by `opTrigger` and
     * dispatched into a real `ProtectedAccess` coroutine on a real `GameCycle.tick()`. No handler
     * is called by name, and the collapse lands only because `PitfallEvents` put
     * `HunterPitfall.tick` on `GameLifecycle.LateCycle` - the one registration in that file whose
     * absence every unit test in the module survives.
     */
    private fun catchAndCollect(
        creature: PitfallCreature,
        username: String,
        expectedFineXp: Int,
        expectedLoot: List<String>,
    ) {
        val world = world()
        val player = world.newPlayer(username)
        world.login(player)
        try {
            world.setHunterLevel(player, 99)
            world.give(player, "obj.knife")
            world.give(player, TEASING_STICK)
            world.give(player, "obj.logs", 5)

            val logsBefore = player.inv.count("obj.logs")
            val lootBefore = expectedLoot.associateWith { player.inv.count(it) }
            val xpBefore = player.statMap.getFineXP(HUNTER_STAT)

            val site = world.catchOne(player, creature)

            assertEquals(
                logsBefore - 1,
                player.inv.count("obj.logs"),
                "arming ${site.baseLoc} should have spent exactly one log",
            )

            // The collapse is a cycle count in `HunterPitfall`, not a player queue, so it lands on
            // the shared clock. Stepping past it rather than up to it: the shared `GameCycle` makes
            // "has not happened yet" an unsafe claim, "has happened by now" a safe one.
            world.tickUntil(COLLAPSE_CYCLES * 3) { world.pitfall.pitState(player, site) in FULL }
            assertTrue(
                world.pitfall.pitState(player, site) in FULL,
                "${site.baseLoc} never finished collapsing - is `tick` on `LateCycle`?",
            )

            world.click(player, site.coords, site.baseLoc, InteractionOp.Op2)

            assertEquals(
                expectedFineXp,
                player.statMap.getFineXP(HUNTER_STAT) - xpBefore,
                "hunter experience for one ${creature.npc}",
            )
            for (obj in expectedLoot) {
                assertEquals(
                    (lootBefore.getValue(obj)) + 1,
                    player.inv.count(obj),
                    "$obj in the backpack after collecting ${site.baseLoc}",
                )
            }
            assertEquals(
                PitState.Empty,
                world.pitfall.pitState(player, site),
                "${site.baseLoc} should be empty once its catch is collected",
            )
        } finally {
            world.pitfall.clearPits(player)
            world.endChases()
            world.logout(player)
        }
    }

    /**
     * Arms a pit, leads a creature over it, and returns the site the catch went into.
     *
     * The retry is the honest shape for an unscripted roll, not a way of hiding a refusal. A big
     * cat's success rate at Hunter 99 is high but not certain, and a refused creature is barred
     * from **that** pit for the rest of its chase - `lastVaulted` - so the retry moves to the next
     * pit of the same kind rather than jumping the same one twice. The refused pit is dismantled
     * first so the trap cap can never become the reason a later attempt fails, and the same chaser
     * is kept: it is already following, and it follows the hunter to the next pit.
     *
     * Fails loudly, listing what happened at each site, if the creature refuses every one of them.
     */
    private fun BootedWorld.catchOne(player: Player, creature: PitfallCreature): PitfallSite {
        val sites = PitfallSites.all.filter { it.creature === creature }
        val refusals = mutableListOf<String>()
        var chaser: Npc? = null

        for (site in sites) {
            val npc =
                chaser?.takeIf { it.isSlotAssigned && it.isVisible }
                    ?: liveCreature(site)
                    ?: continue
            chaser = npc

            if (pitfall.teasedBy(npc) != player.uid) {
                clickNpc(player, npc, InteractionOp.Op1)
                check(pitfall.teasedBy(npc) == player.uid) {
                    "${site.baseLoc}: the Tease did not start a chase"
                }
            }

            click(player, site.coords, site.baseLoc, InteractionOp.Op3)
            check(pitfall.pitState(player, site) == PitState.Set) {
                "${site.baseLoc}: Trap left the pit ${pitfall.pitState(player, site)}"
            }

            // The hunter waits on the pit and lets the chase bring the creature over it, which is
            // the technique. `CATCH_RANGE` is `HunterPitfall`'s own "passes the trap" window.
            tickUntil(ARRIVAL_CYCLES) {
                npc.coords.chebyshevDistance(site.coords) <= CATCH_RANGE &&
                    !player.isAccessProtected
            }
            val gap = npc.coords.chebyshevDistance(site.coords)
            check(gap <= CATCH_RANGE) { "${site.baseLoc}: the chase stalled $gap tiles away" }

            click(player, site.coords, site.baseLoc, InteractionOp.Op1)
            if (pitfall.pitState(player, site) != PitState.Set) {
                return site
            }

            refusals += "${site.baseLoc} (it leapt clear)"
            click(player, site.coords, site.baseLoc, InteractionOp.Op2)
            check(pitfall.pitState(player, site) == PitState.Empty) {
                "${site.baseLoc}: Dismantle left the pit ${pitfall.pitState(player, site)}"
            }
        }
        error("${creature.npc} was not caught at any of its ${sites.size} pits: $refusals")
    }

    /**
     * A live creature of [site]'s own kind near it, or null.
     *
     * A zone radius rather than a tile: these creatures wander, so the spawn tile in
     * `.data/raw-cache/map/npcs` is where one starts and not where one is. Three zones is
     * twenty-four tiles, comfortably wider than any pitfall creature's wander range and narrow
     * enough that it cannot reach another hunting ground.
     */
    private fun BootedWorld.liveCreature(site: PitfallSite): Npc? {
        val npcId = site.creature.npc.asRSCM(RSCMType.NPC)
        return npcs
            .findAll(ZoneKey.from(site.coords), zoneRadius = 3)
            .filter { it.isSlotAssigned && it.isVisible && it.visType.id == npcId }
            .minByOrNull { it.coords.chebyshevDistance(site.coords) }
    }

    /**
     * A creature of [creature]'s kind and one of its own pits between four and fifteen tiles away.
     *
     * Both bounds are the chase test's, and both are read off live coordinates rather than off the
     * spawn table, so a wandering creature narrows or widens the window as it moves.
     */
    private fun BootedWorld.chaseSetup(creature: PitfallCreature): Pair<Npc, PitfallSite>? {
        val sites = PitfallSites.all.filter { it.creature === creature }
        for (anchor in sites) {
            val npc = liveCreature(anchor) ?: continue
            val target =
                sites
                    .filter { npc.coords.chebyshevDistance(it.coords) in CHASE_WINDOW }
                    .maxByOrNull { npc.coords.chebyshevDistance(it.coords) }
            if (target != null) {
                return npc to target
            }
        }
        return null
    }

    /**
     * One loc click, driven to completion.
     *
     * The player is teleported onto the site first so nothing depends on the route finder reaching
     * it: standing on the tile makes `isWithinOpRange` true through `collides`, and the op fires on
     * the next tick without a step being taken. The loc handed over is the **map-placed parent** -
     * `LocInteractions` resolves the multiloc child off the player's own varbit, which is exactly
     * the path a click takes and the reason the ops are registered on the children.
     */
    private fun BootedWorld.click(
        player: Player,
        coords: CoordGrid,
        loc: String,
        op: InteractionOp,
    ) {
        val info = locOrNull(coords, loc) ?: error("$loc is not placed at $coords")
        val type = ServerCacheManager.getObject(info.id) ?: error("no packed type for $loc")
        PathingEntityCommon.telejump(player, collision, coords)
        locInteractions.interact(player, BoundLocInfo(info, type), op)
        runUntilIdle(player)
    }

    /** The same, for an npc: teleported onto the creature so the op fires without a walk. */
    private fun BootedWorld.clickNpc(player: Player, npc: Npc, op: InteractionOp) {
        PathingEntityCommon.telejump(player, collision, npc.coords)
        npcInteractions.interact(player, npc, op)
        runUntilIdle(player)
    }

    /**
     * Ticks until the player has no interaction left and no suspended coroutine.
     *
     * Bounded rather than looping forever: an op that never resumes is a bug worth failing on, and
     * a `while (true)` would hang the build instead of naming it.
     */
    private fun BootedWorld.runUntilIdle(player: Player, maxCycles: Int = 20) {
        var cycles = 0
        do {
            cycle.tick()
        } while ((player.interaction != null || player.isAccessProtected) && ++cycles < maxCycles)
        check(cycles < maxCycles) { "op did not finish within $maxCycles cycles" }
    }

    private class BootedWorld(injector: Injector) {
        val cycle: GameCycle = injector.getInstance(GameCycle::class.java)
        val collision: CollisionFlagMap = injector.getInstance(CollisionFlagMap::class.java)
        val locInteractions: LocInteractions = injector.getInstance(LocInteractions::class.java)
        val npcInteractions: NpcInteractions = injector.getInstance(NpcInteractions::class.java)
        val npcs: NpcRepository = injector.getInstance(NpcRepository::class.java)

        /**
         * The same instance `PitfallEvents` holds: `HunterModule` binds it with `bindInstance`, so
         * the ledger this reads is the one the ops write.
         */
        val pitfall: HunterPitfall = injector.getInstance(HunterPitfall::class.java)

        private val eventBus: EventBus = injector.getInstance(EventBus::class.java)
        private val locRegistry: LocRegistry = injector.getInstance(LocRegistry::class.java)
        private val players: PlayerRegistry = injector.getInstance(PlayerRegistry::class.java)
        private val launcher: ProtectedAccessLauncher =
            injector.getInstance(ProtectedAccessLauncher::class.java)

        /** The lookup `LocInteractions.opTrigger` performs before it dispatches a click. */
        fun hasOpLoc1(loc: String): Boolean = hasOpLoc1(loc.asRSCM(RSCMType.LOC))

        fun hasOpLoc2(loc: String): Boolean = hasOpLoc2(loc.asRSCM(RSCMType.LOC))

        fun hasOpLoc3(loc: String): Boolean = hasOpLoc3(loc.asRSCM(RSCMType.LOC))

        fun hasOpLoc1(loc: Int): Boolean = eventBus.contains(LocEvents.Op1::class.java, loc)

        fun hasOpLoc2(loc: Int): Boolean = eventBus.contains(LocEvents.Op2::class.java, loc)

        fun hasOpLoc3(loc: Int): Boolean = eventBus.contains(LocEvents.Op3::class.java, loc)

        /** The lookup `NpcInteractions.opTrigger` performs before it dispatches an npc click. */
        fun hasOpNpc1(npc: String): Boolean =
            eventBus.contains(NpcEvents.Op1::class.java, npc.asRSCM(RSCMType.NPC))

        fun locOrNull(coords: CoordGrid, loc: String) =
            locRegistry.findType(coords, loc.asRSCM(RSCMType.LOC))

        fun tickUntil(maxCycles: Int, condition: () -> Boolean): Int {
            var cycles = 0
            while (cycles < maxCycles && !condition()) {
                cycle.tick()
                cycles++
            }
            return cycles
        }

        /**
         * Ends every chase this class started.
         *
         * The world is shared with every other class in this source set, and a teased creature is
         * the one thing these tests leave behind that would outlive them: `PlayerFollow` on an npc
         * whose teaser has logged out is ended by `HunterPitfall.tick` on the next cycle anyway,
         * but only if a cycle runs, and the last test in the suite has no reason to run one.
         */
        fun endChases() {
            for (creature in PitfallCreatures.all) {
                val npcId = creature.npc.asRSCM(RSCMType.NPC)
                for (site in PitfallSites.all.filter { it.creature.npc == creature.npc }) {
                    npcs
                        .findAll(ZoneKey.from(site.coords), zoneRadius = 3)
                        .filter { it.isSlotAssigned && it.visType.id == npcId }
                        .filter { pitfall.teasedBy(it) != null }
                        .toList()
                        .forEach(pitfall::stopChasing)
                }
            }
        }

        private var nextUuid = 1L

        fun newPlayer(name: String): Player {
            val player = Player()
            player.username = name
            player.displayName = name
            player.accountId = 1
            player.characterId = 1
            // `PlayerRegistry.add` derives the uid from these, and account loading is what sets
            // them on a real login.
            player.uuid = nextUuid++
            player.observerUUID = player.uuid
            player.coords = SPAWN
            return player
        }

        /**
         * The login the network layer performs, minus the network.
         *
         * `AccountLoadResponseHook` allocates a slot, registers the player and publishes `Login`;
         * `PlayerRegistry.add` publishes `Initialize` on the way, which is what builds the backpack
         * and worn container, so the order matters. `EngineLogin` is deliberately not published -
         * it is the client-resync half of a login and every one of its handlers writes down a
         * channel. See [TrackingLoopTest] for the longer version of the same note.
         */
        fun login(player: Player) {
            player.slotId = players.playerList.nextFreeSlot() ?: error("no free player slot")
            val result = players.add(player)
            check(result is PlayerRegistryResult.Add.Success) { "failed to register: $result" }
            eventBus.publish(SessionStateEvent.Login(player))
        }

        fun logout(player: Player) {
            eventBus.publish(SessionStateEvent.Logout(player))
            players.del(player)
        }

        fun setHunterLevel(player: Player, level: Int) {
            player.statMap.setBaseLevel(HUNTER_STAT, level.toByte())
            player.statMap.setCurrentLevel(HUNTER_STAT, level.toByte())
        }

        fun give(player: Player, obj: String, count: Int = 1) {
            val added = launcher.launch(player) { invAdd(inv, obj, count) }
            check(added) { "could not add $obj x$count" }
        }
    }

    /** The booted world, built once per JVM; see [BootedGame] for why it is shared this widely. */
    private fun world(): BootedWorld =
        shared ?: BootedWorld(BootedGame.injector).also { shared = it }

    private companion object {
        private var shared: BootedWorld? = null

        private const val HUNTER_STAT = "stat.hunter"

        private const val TEASING_STICK = "obj.hunting_teasing_stick"

        /** Lumbridge; anywhere valid will do, since every click teleports first. */
        private val SPAWN = CoordGrid(0, 50, 50, 16, 16)

        /** `HunterPitfall.CATCH_RANGE`: how close "passes the trap" is. */
        private const val CATCH_RANGE = 3

        /** `HunterPitfall.CHASE_RANGE`: the spawn-anchored leash. */
        private const val CHASE_RANGE = 64

        /** `HunterPitfall.COLLAPSE_CYCLES`: how long a catch spends in the air. */
        private const val COLLAPSE_CYCLES = 5

        /** `NpcPlayerFollowModeProcessor.VALID_DISTANCE`: past this the engine teleports. */
        private const val FOLLOW_VALID_DISTANCE = 15

        /**
         * The gap the chase test wants: far enough that there is a walk to watch, near enough that
         * the follow processor walks the creature instead of teleporting it.
         */
        private val CHASE_WINDOW = 4..FOLLOW_VALID_DISTANCE

        /** Generous: a fifteen-tile walk is fifteen cycles, and the creature may route around. */
        private const val CHASE_CYCLES = 60

        /** The same, plus the teleport case, which lands in one. */
        private const val ARRIVAL_CYCLES = 60

        private val FULL = setOf(PitState.Full, PitState.FullRotated)
    }
}
