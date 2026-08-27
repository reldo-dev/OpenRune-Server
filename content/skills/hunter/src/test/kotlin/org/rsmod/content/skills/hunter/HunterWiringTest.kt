package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.api.controller.events.ControllerAIEvents
import org.rsmod.api.player.events.EngineQueueEvents
import org.rsmod.api.player.events.PlayerQueueEvents
import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.events.interact.HeldUEvents
import org.rsmod.api.player.events.interact.LocContentEvents
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.events.interact.NpcEvents
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.entity.player.SessionStateEvent
import org.rsmod.game.interact.HeldOp
import org.rsmod.game.interact.InteractionOp
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.game.queue.EngineQueueType
import org.rsmod.game.type.hasInvOp
import org.rsmod.game.type.hasOp
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * What the hunter scripts actually put on the event bus. The rest of the suite calls the op bodies
 * directly, so a handler registered on the wrong group, the wrong op index, or not at all would
 * leave everything green and the feature dead in game. Each script's `startup()` is run against a
 * fresh [EventBus] - the same mechanism `PluginScriptLoader` uses at boot - and every lookup
 * resolves its key through the same call the matching `on...` helper uses. The second half walks
 * the loc states the dispatch branches on and asserts the *packed cache* carries the group and ops
 * each handler is registered for. Serialised: `ServerCacheManager` is a singleton and `RSCM`
 * memoises into a plain `HashMap`.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class HunterWiringTest {
    private lateinit var scripts: HunterScripts

    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
        scripts = HunterScripts()
    }

    /* Per-technique registration. */

    @Test
    fun `bird snare registers its lay op, both loc ops and the shared trap tick`() {
        val bus = Wiring().start(scripts.birdSnare)

        assertTrue(bus.hasOpHeld1(SNARE_OBJ), "`Lay` on $SNARE_OBJ")
        assertTrue(bus.hasOpContentLoc1(SNARE_GROUP), "op1 (`Dismantle`/`Check`) on $SNARE_GROUP")
        assertTrue(bus.hasOpContentLoc2(SNARE_GROUP), "op2 (`Investigate`) on $SNARE_GROUP")
        assertTrue(bus.hasAiConTimer(TRAP_CONTROLLER), "the trap tick on $TRAP_CONTROLLER")

        // Its three op1 states share one group, so nothing may leak onto another family's.
        assertFalse(bus.hasOpContentLoc1(BOX_GROUP), "the snare must not claim $BOX_GROUP")
        assertFalse(bus.hasOpContentLoc1(DEADFALL_GROUP), "the snare must not claim $DEADFALL_GROUP")
        assertFalse(bus.hasOpContentLoc1(NET_TRAP_GROUP), "the snare must not claim $NET_TRAP_GROUP")
        assertFalse(bus.hasOpContentLoc1(MAGIC_BOX_GROUP), "the snare must not claim $MAGIC_BOX_GROUP")
    }

    @Test
    fun `box trap registers its lay op and both loc ops, and not the shared trap tick`() {
        val bus = Wiring().start(scripts.boxTrap)

        assertTrue(bus.hasOpHeld1(BOX_OBJ), "`Lay` on $BOX_OBJ")
        assertTrue(bus.hasOpContentLoc1(BOX_GROUP), "op1 (`Dismantle`/`Check`) on $BOX_GROUP")
        assertTrue(bus.hasOpContentLoc2(BOX_GROUP), "op2 (`Investigate`) on $BOX_GROUP")

        // Deliberate: registering it here as well would run every laid trap's tick twice a cycle.
        assertFalse(bus.hasAiConTimer(TRAP_CONTROLLER), "the trap tick belongs to BirdSnareEvents")
        assertFalse(bus.hasOpContentLoc1(SNARE_GROUP), "the box trap must not claim $SNARE_GROUP")
    }

    /**
     * The deadfall registers nothing held, because nothing is carried: a boulder is armed in place
     * with a log and a knife, both of which are `Use`-free inventory reads rather than ops.
     */
    @Test
    fun `deadfall registers both loc ops and nothing held`() {
        val bus = Wiring().start(scripts.deadfall)

        assertTrue(bus.hasOpContentLoc1(DEADFALL_GROUP), "op1 (`Set-trap`/`Dismantle`/`Check`)")
        assertTrue(bus.hasOpContentLoc2(DEADFALL_GROUP), "op2 (`Investigate`)")

        assertFalse(bus.hasOpHeld1(SNARE_OBJ), "nothing is laid from the inventory")
        assertFalse(bus.hasOpHeld1(BOX_OBJ), "nothing is laid from the inventory")
        assertFalse(bus.hasOpHeld1(MAGIC_BOX_OBJ), "nothing is laid from the inventory")
        assertFalse(bus.hasAiConTimer(TRAP_CONTROLLER), "the trap tick belongs to BirdSnareEvents")
    }

    /**
     * One registration covers two physically distinct locs: the group has to be on both halves for
     * either click to arrive.
     */
    @Test
    fun `net trap registers both loc ops on the one group its two halves share`() {
        val bus = Wiring().start(scripts.netTrap)

        assertTrue(bus.hasOpContentLoc1(NET_TRAP_GROUP), "op1 (`Set-trap`/`Dismantle`/`Check`)")
        assertTrue(bus.hasOpContentLoc2(NET_TRAP_GROUP), "op2 (`Investigate`)")

        assertFalse(bus.hasAiConTimer(TRAP_CONTROLLER), "the trap tick belongs to BirdSnareEvents")
        assertFalse(bus.hasOpContentLoc1(DEADFALL_GROUP), "the net trap must not claim the deadfall")
    }

    @Test
    fun `magic box registers its activate op and both loc ops`() {
        val bus = Wiring().start(scripts.magicBox)

        assertTrue(bus.hasOpHeld1(MAGIC_BOX_OBJ), "`Activate` on $MAGIC_BOX_OBJ")
        assertTrue(bus.hasOpContentLoc1(MAGIC_BOX_GROUP), "op1 (`Deactivate`/`Retrieve`)")
        assertTrue(bus.hasOpContentLoc2(MAGIC_BOX_GROUP), "op2 (`Investigate`)")

        assertFalse(bus.hasAiConTimer(TRAP_CONTROLLER), "the trap tick belongs to BirdSnareEvents")
    }

    /**
     * Falconry is registered per npc rather than by content group, so "the right key" here means one
     * registration per npc id and none on any other.
     */
    @Test
    fun `falconry registers Matthias, every kebbit, every falcon, the tick and the area exit`() {
        val bus = Wiring().start(scripts.falconry, scripts.butterfly, scripts.impling)

        // `op3=Quick-falcon` on npc 1340, and no op1 - his `Talk-to` goes through a dialogue tree
        // that is out of scope, so claiming op1 would replace it with a rental.
        assertTrue(bus.hasOpNpc3(FALCONER_NPC), "`Quick-falcon` on $FALCONER_NPC")
        assertFalse(bus.hasOpNpc1(FALCONER_NPC), "`Talk-to` is not implemented here")

        val creatures = FalconryCreatures.all
        assertEquals(3, creatures.size, "three falconry kebbits ship today")
        for (creature in creatures) {
            assertTrue(bus.hasOpNpc1(creature.npc), "`Catch` on ${creature.npc}")
            assertTrue(bus.hasOpNpc1(creature.falconNpc), "`Retrieve` on ${creature.falconNpc}")
        }

        assertTrue(bus.hasAiConTimer(FALCON_CONTROLLER), "the falcon tick on $FALCON_CONTROLLER")
        assertTrue(bus.hasAreaExit(FALCONRY_AREA), "the glove strip on exiting $FALCONRY_AREA")
        // The bus is only half of an engine-queue registration; the cache is what makes the engine
        // bother to publish the event at all.
        assertTrue(bus.areaExitIsCached(FALCONRY_AREA), "$FALCONRY_AREA in the engine queue cache")

        // A different controller type from the trap's, deliberately: a falcon arriving at the trap
        // tick would be read as a trap with a corrupt family.
        assertFalse(bus.hasAiConTimer(TRAP_CONTROLLER), "falconry must not claim $TRAP_CONTROLLER")
    }

    @Test
    fun `butterfly netting registers one catch per creature and nothing else`() {
        val bus = Wiring().start(scripts.butterfly)

        val creatures = ButterflyCreatures.all
        assertEquals(5, creatures.size, "four butterflies and the sunlight moth")
        for (creature in creatures) {
            assertTrue(bus.hasOpNpc1(creature.npc), "`Catch` on ${creature.npc}")
        }

        // Nothing is laid, nothing is rented and no controller is ever created, so there is nothing
        // else to register. Asserting the absence is the point: a stray timer here would tick a
        // controller type this feature never creates.
        assertFalse(bus.hasAiConTimer(TRAP_CONTROLLER), "no controller is created")
        assertFalse(bus.hasAiConTimer(FALCON_CONTROLLER), "no controller is created")
        assertFalse(bus.hasAreaExit(FALCONRY_AREA), "nothing is rented")
        for (group in ALL_TRAP_GROUPS) {
            assertFalse(bus.hasOpContentLoc1(group), "butterflies touch no locs ($group)")
        }
    }

    /**
     * Impling catching registers exactly the same shape butterfly netting does, one op per npc.
     *
     * The absence half is what this is for. `iop3=Loot` on the filled jar is a separate feature with
     * its own drop tables, so a handler appearing anywhere here would mean it had been half-wired.
     */
    @Test
    fun `impling catching registers one catch per creature and nothing else`() {
        val bus = Wiring().start(scripts.impling)

        val creatures = ImplingCreatures.all
        assertEquals(12, creatures.size, "all twelve implings ship")
        for (creature in creatures) {
            // Both forms, because both are npcs a player can click. Registering only the Puro-Puro
            // one would leave every overworld impling uncatchable, and no other test would notice.
            assertTrue(bus.hasOpNpc1(creature.npc), "`Catch` on ${creature.npc}")
            assertTrue(bus.hasOpNpc1(creature.npcOverworld), "`Catch` on ${creature.npcOverworld}")
        }

        // Nothing is laid, nothing is rented and no controller is ever created.
        assertFalse(bus.hasAiConTimer(TRAP_CONTROLLER), "no controller is created")
        assertFalse(bus.hasAiConTimer(FALCON_CONTROLLER), "no controller is created")
        assertFalse(bus.hasAreaExit(FALCONRY_AREA), "nothing is rented")
        for (group in ALL_TRAP_GROUPS) {
            assertFalse(bus.hasOpContentLoc1(group), "implings touch no locs ($group)")
        }
    }

    /**
     * Crab trapping registers one loc op, one soft queue and one login hook, and no controller tick.
     *
     * The soft queue is the piece most likely to be missing without anything noticing: a bait that
     * scheduled a catch nobody had subscribed to would leave every trap baited forever, and no other
     * test in this module would see it, because they all call the queue body directly.
     */
    @Test
    fun `crab trapping registers its one loc op, the catch queue and the login re-arm`() {
        val bus = Wiring().start(scripts.crabTrap)

        assertTrue(bus.hasOpContentLoc1(CRAB_GROUP), "op1 (`Build-trap`/`Bait`/`Empty`)")
        assertTrue(bus.hasSoftQueue(CRAB_CATCH_QUEUE), "the catch on $CRAB_CATCH_QUEUE")
        assertTrue(bus.hasPlayerLogin(), "the pending-catch re-arm")

        // Three different transactions share one op1, so there is no op2 anywhere in the family.
        assertFalse(bus.hasOpContentLoc2(CRAB_GROUP), "no crab trap loc draws an op2")
        // Nothing of a crab trap exists in the world, so there is no controller to tick.
        assertFalse(bus.hasAiConTimer(TRAP_CONTROLLER), "no controller is created")
        assertFalse(bus.hasAiConTimer(FALCON_CONTROLLER), "no controller is created")
        for (group in ALL_TRAP_GROUPS) {
            assertFalse(bus.hasOpContentLoc1(group), "crab trapping must not claim $group")
        }
    }

    /**
     * The **site** locs are not registered, and must not be.
     *
     * They are `multiloc` parents with no ops. `LocInteractions.opTrigger` tries the type-level
     * `LocEvents.OpN` before it reaches the content handler, so a per-id registration on a site
     * would shadow the child's handler for every state at once - the loudest possible version of the
     * shadowing bug the deadfall's own guard covers.
     */
    @Test
    fun `no crab trap site loc is registered by id`() {
        val bus = Wiring().start(*scripts.all.toTypedArray())

        for (site in CrabTrapSites.all) {
            assertFalse(
                bus.eventBus.contains(LocEvents.Op1::class.java, site.locId.toLong()),
                "op1 shadow on ${site.loc}",
            )
        }
        for (loc in CrabTrapSites.lifecycleLocs) {
            val id = loc.asRSCM(RSCMType.LOC)
            assertFalse(bus.eventBus.contains(LocEvents.Op1::class.java, id), "op1 shadow on $loc")
        }
    }

    /**
     * Bird houses register **four** loc ops, which is the most of any technique here.
     *
     * The op indices line up across the four states even though the labels do not - op1 is
     * `Build`/`Interact`, op2 is `Seeds`, op3 is `Dismantle`/`Empty`, op4 is `Reset` - so one group
     * and four handlers cover all 28 children. Missing any one of the four leaves that transaction
     * unreachable with every other test in the module still green: nothing else in this suite goes
     * through the event bus.
     */
    @Test
    fun `bird houses register all four loc ops, the fill queue and the login re-arm`() {
        val bus = Wiring().start(scripts.birdHouse)

        assertTrue(bus.hasOpContentLoc1(BIRD_HOUSE_GROUP), "op1 (`Build`/`Interact`)")
        assertTrue(bus.hasOpContentLoc2(BIRD_HOUSE_GROUP), "op2 (`Seeds`)")
        assertTrue(bus.hasOpContentLoc3(BIRD_HOUSE_GROUP), "op3 (`Dismantle`/`Empty`)")
        assertTrue(bus.hasOpContentLoc4(BIRD_HOUSE_GROUP), "op4 (`Reset`)")
        assertTrue(bus.hasSoftQueue(BIRDHOUSE_FILL_QUEUE), "the fill on $BIRDHOUSE_FILL_QUEUE")
        assertTrue(bus.hasPlayerLogin(), "the deadline re-arm")

        // No child carries an op5, and nothing of a bird house exists in the world to tick.
        assertFalse(bus.hasOpContentLoc5(BIRD_HOUSE_GROUP), "no bird house loc draws an op5")
        assertFalse(bus.hasAiConTimer(TRAP_CONTROLLER), "no controller is created")
        assertFalse(bus.hasAiConTimer(FALCON_CONTROLLER), "no controller is created")
        for (group in ALL_TRAP_GROUPS + CRAB_GROUP) {
            assertFalse(bus.hasOpContentLoc1(group), "bird houses must not claim $group")
        }
    }

    /**
     * The nine `clockwork on logs` pairs, which are how a bird house enters the game at all.
     *
     * Walked off [BirdHouseTypes] rather than listed, the way the trap families' loc states are: a
     * tenth tier joins this assertion on its own. The pair is asserted in *registration* order,
     * because that is the only order `onOpHeldU` stores - see [Wiring.hasOpHeldU].
     */
    @Test
    fun `bird houses register a craft pair on every tier's logs`() {
        val bus = Wiring().start(scripts.birdHouse)

        assertEquals(9, BirdHouseTypes.all.size, "nine tiers ship")
        for (type in BirdHouseTypes.all) {
            assertTrue(
                bus.hasOpHeldU(HunterBirdHouse.CLOCKWORK, type.logs),
                "a clockwork on ${type.logs} makes ${type.obj}",
            )
        }

        // The tools are held and read, never used on anything, so neither carries a pair of its own.
        assertFalse(
            bus.hasOpHeldU(HunterBirdHouse.CLOCKWORK, HunterBirdHouse.CHISEL),
            "the chisel is a held tool, not a material",
        )
        assertFalse(
            bus.hasOpHeldU(HunterBirdHouse.CLOCKWORK, HunterBirdHouse.HAMMER),
            "and so is the hammer",
        )
    }

    /**
     * The **space** locs are not registered, and must not be.
     *
     * The same shadowing hazard the crab trap sites have, and the same reasoning: the four spaces are
     * `multiloc` parents with no ops, and `LocInteractions.opTrigger` tries the type-level
     * `LocEvents.OpN` before it reaches the content handler.
     */
    @Test
    fun `no bird house space loc is registered by id`() {
        val bus = Wiring().start(*scripts.all.toTypedArray())

        for (space in BirdHouseSpaces.all) {
            assertFalse(
                bus.eventBus.contains(LocEvents.Op1::class.java, space.locId.toLong()),
                "op1 shadow on ${space.loc}",
            )
        }
    }

    /* The once-only invariant. */

    /**
     * `onAiConTimer(TRAP_CONTROLLER)` is registered by exactly one trap script. Counted per script
     * on its own bus: `EventBus.subscribeKeyed` throws on a duplicate key, so on a shared bus this
     * would assert the engine's guard instead of the invariant.
     */
    @Test
    fun `the shared trap tick is registered exactly once across the whole trap family`() {
        val registrars =
            scripts.trapFamily.filter { Wiring().start(it).hasAiConTimer(TRAP_CONTROLLER) }

        assertEquals(
            listOf(BirdSnareEvents::class.java),
            registrars.map { it.javaClass },
            "exactly one trap script may register $TRAP_CONTROLLER",
        )
    }

    /** The same invariant, seen from the boot path: all ten scripts share one bus at startup. */
    @Test
    fun `all ten scripts start together on one bus without a duplicate key`() {
        val bus = Wiring().start(*scripts.all.toTypedArray())

        assertTrue(bus.hasAiConTimer(TRAP_CONTROLLER))
        assertTrue(bus.hasAiConTimer(FALCON_CONTROLLER))
        for (group in ALL_TRAP_GROUPS) {
            assertTrue(bus.hasOpContentLoc1(group), "op1 on $group")
            assertTrue(bus.hasOpContentLoc2(group), "op2 on $group")
        }
        assertTrue(bus.hasOpContentLoc1(CRAB_GROUP), "op1 on $CRAB_GROUP")
        assertTrue(bus.hasSoftQueue(CRAB_CATCH_QUEUE), "the crab catch queue")
    }

    /* Op-index and shadowing guards. */

    /**
     * No hunter handler sits on an op slot the client never draws.
     *
     * `onOpContentLocN` dispatches on the group and slot, not on the op's label, so a handler on op3
     * would be silently unreachable rather than wrong-looking.
     */
    @Test
    fun `no loc handler is registered on an op index the hunter locs do not carry`() {
        val bus = Wiring().start(*scripts.all.toTypedArray())

        for (group in ALL_TRAP_GROUPS) {
            val id = group.asRSCM(RSCMType.CONTENT)
            assertFalse(bus.eventBus.contains(LocContentEvents.Op3::class.java, id), "op3 on $group")
            assertFalse(bus.eventBus.contains(LocContentEvents.Op4::class.java, id), "op4 on $group")
            assertFalse(bus.eventBus.contains(LocContentEvents.Op5::class.java, id), "op5 on $group")
        }
    }

    @Test
    fun `no npc handler is registered on an op index the hunter npcs do not carry`() {
        val bus = Wiring().start(scripts.falconry, scripts.butterfly, scripts.impling)

        val catchTargets =
            FalconryCreatures.all.map { it.npc } +
                FalconryCreatures.all.map { it.falconNpc } +
                ButterflyCreatures.all.map { it.npc } +
                ImplingCreatures.all.map { it.npc }
        for (npc in catchTargets) {
            val id = npc.asRSCM(RSCMType.NPC)
            assertFalse(bus.eventBus.contains(NpcEvents.Op2::class.java, id), "op2 on $npc")
            assertFalse(bus.eventBus.contains(NpcEvents.Op3::class.java, id), "op3 on $npc")
            assertFalse(bus.eventBus.contains(NpcEvents.Op4::class.java, id), "op4 on $npc")
            assertFalse(bus.eventBus.contains(NpcEvents.Op5::class.java, id), "op5 on $npc")
        }
    }

    /**
     * No hunter loc is claimed by loc **id** as well as by content group.
     *
     * `LocInteractions.opTrigger` tries the type-level `LocEvents.OpN` first and returns as soon as
     * it hits, so a per-id registration on any hunter state would shadow the content handler for
     * that state alone - the one shape of wiring bug that presents as "the trap works except when
     * it's full".
     */
    @Test
    fun `no per-loc-id registration shadows a hunter content group`() {
        val bus = Wiring().start(*scripts.all.toTypedArray())

        for ((loc, _) in dispatchedLocStates()) {
            val id = loc.asRSCM(RSCMType.LOC)
            assertFalse(bus.eventBus.contains(LocEvents.Op1::class.java, id), "op1 shadow on $loc")
            assertFalse(bus.eventBus.contains(LocEvents.Op2::class.java, id), "op2 shadow on $loc")
        }
    }

    /* The packed-cache half: a registration is unreachable without the declaration behind it. */

    /**
     * Every loc state a hunter handler dispatches on carries the group it is registered under, and
     * the op slot it is dispatched for.
     *
     * This is the half a bus assertion cannot see. `content.hunter_box_trap` resolving to an id and
     * `hunting_boxtrap_failed` carrying that id are two independent declarations; the second was
     * missing once already, and a collapsed box trap was unclearable until it was added.
     */
    @Test
    fun `every dispatched loc state carries its content group and the op it is dispatched for`() {
        for ((loc, expected) in dispatchedLocStates()) {
            val type =
                ServerCacheManager.getObject(loc.asRSCM(RSCMType.LOC))
                    ?: error("No packed loc definition for $loc")
            assertTrue(
                type.isContentType(expected.group),
                "$loc must carry ${expected.group}, has contentGroup=${type.contentGroup}",
            )
            for (op in expected.ops) {
                assertTrue(type.hasOp(op), "$loc must carry ${op.name} (${type.actions})")
            }
        }
    }

    /**
     * The op-less transient frames are under none of the hunter groups, and carry no ops - a group
     * would put a state under a handler with no branch for it. The check is "not one of ours"
     * rather than `contentGroup == -1` because `-1` never survives the pack: opcode 6 is a USHORT,
     * so an unset group reads back as `65535` (measured: 59,717 of 60,000 locs).
     */
    @Test
    fun `the op-less transient loc states are under none of the hunter content groups`() {
        val claimed =
            transientLocStates().mapNotNull { loc ->
                val type =
                    ServerCacheManager.getObject(loc.asRSCM(RSCMType.LOC))
                        ?: error("No packed loc definition for $loc")
                val group = ALL_TRAP_GROUPS.firstOrNull(type::isContentType)
                group?.let { loc to it }
            }
        assertEquals(emptyList<Pair<String, String>>(), claimed, "transient frames must be ungrouped")

        for (loc in transientLocStates()) {
            val type = checkNotNull(ServerCacheManager.getObject(loc.asRSCM(RSCMType.LOC)))
            assertFalse(type.hasOp(InteractionOp.Op1), "$loc must carry no op1")
            assertFalse(type.hasOp(InteractionOp.Op2), "$loc must carry no op2")
        }
    }

    /** Every npc a handler is registered on really draws that op in the packed cache. */
    @Test
    fun `every registered npc op exists on the packed npc definition`() {
        val expected = buildList {
            add(FALCONER_NPC to InteractionOp.Op3)
            for (creature in FalconryCreatures.all) {
                add(creature.npc to InteractionOp.Op1)
                add(creature.falconNpc to InteractionOp.Op1)
            }
            for (creature in ButterflyCreatures.all) {
                add(creature.npc to InteractionOp.Op1)
            }
            for (creature in ImplingCreatures.all) {
                add(creature.npc to InteractionOp.Op1)
            }
        }

        for ((npc, op) in expected) {
            val type =
                ServerCacheManager.getNpc(npc.asRSCM(RSCMType.NPC))
                    ?: error("No packed npc definition for $npc")
            assertTrue(type.hasOp(op.slot), "$npc must carry ${op.name} (${type.actions})")
        }
    }

    /** Every obj a lay op is registered on really draws an inventory op1. */
    @Test
    fun `every lay obj carries an inventory op1 on the packed obj definition`() {
        for (obj in listOf(SNARE_OBJ, BOX_OBJ, MAGIC_BOX_OBJ)) {
            val type =
                ServerCacheManager.getItem(obj.asRSCM(RSCMType.OBJ))
                    ?: error("No packed obj definition for $obj")
            assertTrue(type.hasInvOp(HeldOp.Op1), "$obj must carry iop1")
        }
    }

    /* Fixtures. */

    /** A loc state the dispatch branches on: which group routes it, and which ops it must draw. */
    private data class LocExpectation(val group: String, val ops: List<InteractionOp>)

    /**
     * Every loc state a hunter handler can be reached through, mapped to the group that routes it.
     *
     * Built from [HunterTrapStates] and [HunterCreatures] - the same tables the production `when`
     * branches read - rather than from a transcribed list, so a creature added to a table joins this
     * matrix on its own.
     */
    private fun dispatchedLocStates(): Map<String, LocExpectation> = buildMap {
        fun put(loc: String, group: String, vararg ops: InteractionOp) {
            put(loc, LocExpectation(group, ops.toList()))
        }

        // Bird snare: `Dismantle` on the armed and broken states, `Check` on each `_full_`, and
        // `Investigate` on the armed one alone.
        put(checkNotNull(HunterTrapStates.setLoc(TrapFamily.SNARE)), SNARE_GROUP, Op1, Op2)
        put(HunterTrapStates.failedLoc(TrapFamily.SNARE), SNARE_GROUP, Op1)
        for (creature in creatures(TrapFamily.SNARE)) {
            put(HunterTrapStates.fullLoc(creature), SNARE_GROUP, Op1)
        }

        // Box trap: the same shape. `hunting_boxtrap_failed` is the state that was missing its group.
        put(checkNotNull(HunterTrapStates.setLoc(TrapFamily.BOX)), BOX_GROUP, Op1, Op2)
        put(HunterTrapStates.failedLoc(TrapFamily.BOX), BOX_GROUP, Op1)
        for (creature in creatures(TrapFamily.BOX)) {
            put(HunterTrapStates.fullLoc(creature), BOX_GROUP, Op1)
        }

        // Deadfall: `Set-trap` and `Dismantle` are opposite transactions on one group, which is why
        // `DeadfallEvents.op1` dispatches on the loc id rather than reusing `takeTrap`.
        put(HunterTrapStates.DEADFALL_BOULDER, DEADFALL_GROUP, Op1)
        put(HunterTrapStates.DEADFALL_ARMED, DEADFALL_GROUP, Op1, Op2)
        for (creature in HunterCreatures.deadfall) {
            put(HunterTrapStates.fullLoc(creature), DEADFALL_GROUP, Op1)
        }

        // Net trap: two physically distinct locs under one group. Both armed halves carry
        // `Dismantle` and `Investigate`, which is what lets either one take the trap down.
        for (creature in HunterCreatures.netTrap) {
            put(HunterTrapStates.upLoc(creature), NET_TRAP_GROUP, Op1)
            put(HunterTrapStates.armedTreeLoc(creature), NET_TRAP_GROUP, Op1, Op2)
            put(HunterTrapStates.netSetLoc(creature), NET_TRAP_GROUP, Op1, Op2)
            put(HunterTrapStates.fullLoc(creature), NET_TRAP_GROUP, Op1)
            put(HunterTrapStates.failedLoc(TrapFamily.NETTRAP, creature), NET_TRAP_GROUP, Op1)
        }

        // Magic box.
        put(HunterTrapStates.MAGIC_BOX_EMPTY, MAGIC_BOX_GROUP, Op1, Op2)
        put(HunterTrapStates.MAGIC_BOX_FULL, MAGIC_BOX_GROUP, Op1)
        put(HunterTrapStates.MAGIC_BOX_FAILED, MAGIC_BOX_GROUP, Op1)
    }

    /**
     * The frames shown mid-spring, which carry no ops and therefore no content group.
     *
     * The four compass offsets are there for the box trap alone: it is the only family with one
     * `_trapping_` loc per side, and [HunterTrapStates.trappingLoc] is the function that picks one.
     * Every other family ignores the offsets, so the set collapses on its own.
     */
    private fun transientLocStates(): Set<String> = buildSet {
        add(HunterTrapStates.DEADFALL_SETTING)
        add(HunterTrapStates.DEADFALL_FAILING)
        add(HunterTrapStates.MAGIC_BOX_TRAPPING)
        add(HunterTrapStates.failingLoc(TrapFamily.SNARE))
        add(HunterTrapStates.failingLoc(TrapFamily.BOX))

        val offsets = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        for (creature in HunterCreatures.all) {
            // The magic box's failure frame is its wreck, which does carry `Deactivate`.
            if (creature.family == TrapFamily.MAGICBOX) continue
            for ((dx, dz) in offsets) {
                add(HunterTrapStates.trappingLoc(creature, dx, dz))
            }
        }
        for (creature in HunterCreatures.netTrap) {
            add(HunterTrapStates.settingLoc(creature))
            add(HunterTrapStates.failingLoc(TrapFamily.NETTRAP, creature))
        }
    }

    private fun creatures(family: TrapFamily): List<HunterCreature> =
        HunterCreatures.all.filter { it.family == family }

    /**
     * The ten scripts, built over the same worlds the rest of the suite uses.
     *
     * The collaborators are only there to satisfy the constructors - `startup()` never touches them,
     * because every handler body it registers is a lambda that is not run here.
     */
    private class HunterScripts {
        private val trapWorld = HunterTrapTestWorld()
        private val falconWorld = HunterFalconryTestWorld()
        private val butterflyWorld = HunterButterflyTestWorld()
        private val crabWorld = HunterCrabTrapTestWorld()
        private val birdHouseWorld = HunterBirdHouseTestWorld()

        val birdSnare = BirdSnareEvents(trapWorld.trap, trapWorld.conRepo)
        val boxTrap = BoxTrapEvents(trapWorld.trap, trapWorld.conRepo)
        val deadfall = DeadfallEvents(trapWorld.trap, trapWorld.conRepo)
        val netTrap = NetTrapEvents(trapWorld.trap)
        val magicBox = MagicBoxEvents(trapWorld.trap, trapWorld.conRepo)
        val falconry = FalconryEvents(falconWorld.falconry)
        val butterfly = ButterflyEvents(butterflyWorld.butterfly)
        val crabTrap = CrabTrapEvents(crabWorld.crabTrap)
        val impling = ImplingEvents(butterflyWorld.impling, butterflyWorld.implingSpawner)
        val birdHouse = BirdHouseEvents(birdHouseWorld.birdHouse)

        /** The five families that share [TRAP_CONTROLLER], in declaration order. */
        val trapFamily: List<PluginScript> = listOf(birdSnare, boxTrap, deadfall, netTrap, magicBox)

        val all: List<PluginScript> =
            trapFamily + listOf(falconry, butterfly, crabTrap, impling, birdHouse)
    }

    /**
     * A fresh [EventBus] and [EngineQueueCache], and the readers for what a script put in them.
     *
     * Each reader resolves its key through the same `asRSCM` / `composeLongKey` call the matching
     * `on…` helper uses, so the test and the game ask the bus the same question.
     */
    private class Wiring {
        val eventBus = EventBus()
        private val engineQueue = EngineQueueCache()
        private val context = ScriptContext(eventBus, CheatCommandMap(), engineQueue)

        fun start(vararg scripts: PluginScript): Wiring = apply {
            for (script in scripts) {
                with(script) { context.startup() }
            }
        }

        fun hasOpContentLoc1(content: String): Boolean =
            eventBus.contains(LocContentEvents.Op1::class.java, content.asRSCM(RSCMType.CONTENT))

        fun hasOpContentLoc2(content: String): Boolean =
            eventBus.contains(LocContentEvents.Op2::class.java, content.asRSCM(RSCMType.CONTENT))

        fun hasOpContentLoc3(content: String): Boolean =
            eventBus.contains(LocContentEvents.Op3::class.java, content.asRSCM(RSCMType.CONTENT))

        fun hasOpContentLoc4(content: String): Boolean =
            eventBus.contains(LocContentEvents.Op4::class.java, content.asRSCM(RSCMType.CONTENT))

        fun hasOpContentLoc5(content: String): Boolean =
            eventBus.contains(LocContentEvents.Op5::class.java, content.asRSCM(RSCMType.CONTENT))

        fun hasOpNpc1(npc: String): Boolean =
            eventBus.contains(NpcEvents.Op1::class.java, npc.asRSCM(RSCMType.NPC))

        fun hasOpNpc3(npc: String): Boolean =
            eventBus.contains(NpcEvents.Op3::class.java, npc.asRSCM(RSCMType.NPC))

        fun hasOpHeld1(obj: String): Boolean =
            eventBus.contains(HeldObjEvents.Op1::class.java, obj.asRSCM(RSCMType.OBJ))

        /**
         * `onOpHeldU` composes the two obj ids into one long key, in **registration** order.
         *
         * Order matters here and nowhere else in this class: the API refuses the reversed pair at
         * boot, so asserting the wrong way round would fail against a correct registration.
         */
        fun hasOpHeldU(first: String, second: String): Boolean =
            eventBus.contains(
                HeldUEvents.Type::class.java,
                EventBus.composeLongKey(first.asRSCM(RSCMType.OBJ), second.asRSCM(RSCMType.OBJ)),
            )

        /**
         * `onAiConTimer` subscribes a [org.rsmod.events.KeyedEvent], not a suspending one, so
         * `EventBus.contains` - which only reads the suspend map - cannot answer this. The keyed
         * map's own `get` can, and a non-null handler is the same thing the `AiConTimerProcessor`
         * looks up each cycle.
         */
        fun hasAiConTimer(controller: String): Boolean =
            eventBus.keyed[
                ControllerAIEvents.Timer::class.java, controller.asRSCM(RSCMType.CONTROLLER)] != null

        fun hasAreaExit(area: String): Boolean =
            eventBus.contains(EngineQueueEvents.Labelled::class.java, areaExitKey(area))

        /**
         * `onPlayerSoftQueueWithArgs` subscribes a [org.rsmod.events.KeyedEvent], so
         * `EventBus.contains` - which only reads the suspend map - cannot answer this. It is the
         * same shape as [hasAiConTimer], and the same trap.
         */
        fun hasSoftQueue(queue: String): Boolean =
            eventBus.keyed[
                PlayerQueueEvents.Soft::class.java, queue.asRSCM(RSCMType.QUEUE).toLong()] != null

        /** `onPlayerLogin` subscribes an unbound event, which is a third map again. */
        fun hasPlayerLogin(): Boolean =
            eventBus.unbound[SessionStateEvent.Login::class.java].orEmpty().isNotEmpty()

        fun areaExitIsCached(area: String): Boolean =
            engineQueue.hasScript(EngineQueueType.AreaExit, area.asRSCM(RSCMType.AREA))

        private fun areaExitKey(area: String): Long =
            EventBus.composeLongKey(area.asRSCM(RSCMType.AREA), EngineQueueType.AreaExit.id)
    }

    private companion object {
        private val Op1 = InteractionOp.Op1
        private val Op2 = InteractionOp.Op2

        private const val SNARE_GROUP = "content.hunter_bird_snare"
        private const val BOX_GROUP = "content.hunter_box_trap"
        private const val DEADFALL_GROUP = "content.hunter_deadfall"
        private const val NET_TRAP_GROUP = "content.hunter_net_trap"
        private const val MAGIC_BOX_GROUP = "content.hunter_magic_box"
        private const val CRAB_GROUP = "content.hunter_crab_trap"
        private const val BIRD_HOUSE_GROUP = "content.hunter_bird_house"

        private val ALL_TRAP_GROUPS =
            listOf(SNARE_GROUP, BOX_GROUP, DEADFALL_GROUP, NET_TRAP_GROUP, MAGIC_BOX_GROUP)

        private const val SNARE_OBJ = "obj.hunting_ojibway_bird_snare"
        private const val BOX_OBJ = "obj.hunting_box_trap"
        private const val MAGIC_BOX_OBJ = "obj.magic_imp_box"

        private const val FALCONER_NPC = "npc.hunting_npc_falconer"
        private const val FALCONRY_AREA = "area.piscatoris_falconry"
    }
}
