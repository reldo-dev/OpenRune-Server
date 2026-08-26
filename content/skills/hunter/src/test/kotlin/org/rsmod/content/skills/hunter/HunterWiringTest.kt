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
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.events.EngineQueueEvents
import org.rsmod.api.player.events.PlayerQueueEvents
import org.rsmod.api.player.events.interact.HeldObjEvents
import org.rsmod.api.player.events.interact.HeldUEvents
import org.rsmod.api.player.events.interact.LocContentEvents
import org.rsmod.api.player.events.interact.LocEvents
import org.rsmod.api.player.events.interact.NpcEvents
import org.rsmod.api.player.events.interact.WornObjEvents
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
 * What the ten hunter scripts actually put on the event bus.
 *
 * **The gap this closes.** Every other test in this module reaches the ops by calling
 * `setDeadfall`, `collectTrap`, `catchKebbit` and friends directly. That proves the *bodies* are
 * right and says nothing about whether a player clicking the thing ever reaches them: an op
 * registered on the wrong content group, on the wrong op index, or not registered at all would
 * leave all 127 of those tests green and the feature dead in game. Nothing else covers it - the
 * packed-data verifier checks values, and no in-client scenario exists for the net trap, magic box,
 * falconry or butterflies.
 *
 * The mechanism is the same one `PluginScriptLoader` uses at boot. A [PluginScript]'s only method
 * is `ScriptContext.startup()`; running it against a fresh [EventBus] populates that bus exactly as
 * a real boot would, and the registrations can then be read back.
 *
 * **Keys are resolved the way production resolves them.** `onOpContentLoc1("content.x")` keys on
 * `"content.x".asRSCM(RSCMType.CONTENT)`, `onOpNpc1` on the npc id, `onAiConTimer` on the controller
 * id, and `onAreaExit` on `composeLongKey(area, EngineQueueType.AreaExit.id)`. Every lookup below
 * goes through the same call, so a test cannot pass against a key the game never asks for.
 *
 * **Registration is only half of wiring.** A `content.hunter_box_trap` handler is unreachable from
 * any loc that does not carry that group - the two-declaration rule, and the exact gap that left a
 * collapsed box trap unclearable until `hunting_boxtrap_failed` was given its group. So the second
 * half of this class walks the loc, npc and obj states the dispatch branches on and asserts against
 * the **packed cache**: the right content group, and the op the handler is registered for.
 *
 * Serialised for the reason the rest of the suite is: `ServerCacheManager` is a singleton and
 * `RSCM` memoises into a plain `HashMap`.
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
     * One registration covers two physically distinct locs. The young tree and the "Net trap" beside
     * it are separate loc ids on separate tiles that share `content.hunter_net_trap`, so the group
     * has to be on both halves for either click to arrive; that is asserted in
     * [every dispatched loc state carries its content group and the op it is dispatched for].
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
        val bus = Wiring().start(scripts.falconry)

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

    /**
     * Tracking registers one op1 per burrow and per clue, and both ops on every catch spot.
     *
     * Every gameval walked here comes from [TrackingNetworks] itself - the same tables
     * `TrackingEvents.startup()` loops over - so a network added or a placement changed joins this
     * assertion on its own, the same reasoning [dispatchedLocStates] uses for the trap families.
     */
    @Test
    fun `tracking registers an op1 on every burrow and every clue loc`() {
        val bus = Wiring().start(scripts.tracking)

        for (loc in TrackingNetworks.burrowLocs.keys) {
            assertTrue(bus.hasOpLoc1(loc), "op1 (`Inspect`) on burrow $loc")
        }
        for (loc in TrackingNetworks.clueLocs.keys) {
            assertTrue(bus.hasOpLoc1(loc), "op1 (`Inspect`) on clue $loc")
        }
    }

    @Test
    fun `tracking registers both ops on every catch spot`() {
        val bus = Wiring().start(scripts.tracking)

        for (loc in TrackingNetworks.catchLocs.keys) {
            assertTrue(bus.hasOpLoc1(loc), "op1 (`Search`) on catch spot $loc")
            assertTrue(bus.hasOpLoc2(loc), "op2 (`Attack`) on catch spot $loc")
        }
    }

    /**
     * The ring of pursuit's three ops, and the packed obj definition that carries two of them.
     *
     * Held `Check` and `Break` are `iop3`/`iop4`, asserted against the cache below rather than
     * taken on trust. The worn `Check` is op**2**, not op3: `WornInteractions` maps
     * `IfButtonOp.Op2` onto `param.wear_op1`, which is the param the ring carries its `Check`
     * string in - registering `onOpWorn3` would put a handler on a button the client never sends.
     * `TrackingLoopTest` asks the same question of a real boot.
     */
    @Test
    fun `tracking registers check and break on the ring of pursuit`() {
        val bus = Wiring().start(scripts.tracking)

        assertTrue(bus.hasOpHeld3(RING_OBJ), "held op3 (`Check`) on $RING_OBJ")
        assertTrue(bus.hasOpHeld4(RING_OBJ), "held op4 (`Break`) on $RING_OBJ")
        assertTrue(bus.hasOpWorn2(RING_OBJ), "worn op2 (`Check`) on $RING_OBJ")

        val type =
            ServerCacheManager.getItem(RING_OBJ.asRSCM(RSCMType.OBJ))
                ?: error("No packed obj definition for $RING_OBJ")
        val ops = type.interfaceOptions
        assertTrue(type.hasInvOp(HeldOp.Op3), "$RING_OBJ must carry iop3 (`Check`): $ops")
        assertTrue(type.hasInvOp(HeldOp.Op4), "$RING_OBJ must carry iop4 (`Break`): $ops")
    }

    /**
     * Logout must discard the in-memory trail state, and login must re-arm the reset queue rather
     * than write varps directly.
     *
     * The logout half is the single most important assertion in this class: `HunterTracking` holds
     * an `IdentityHashMap<Player, TrailState>` with strong references, so a logout that is not
     * wired retains every player who logged out mid-trail forever - a real leak, not a theoretical
     * one. The login half guards the companion trap: a varp/varbit write from `onPlayerLogin`
     * itself would update the server and leave the client rendering stale footprints, because
     * `VarPlayerIntMapSetter` short-circuits while `processedMapClock == 0` - exactly the state
     * during login.
     */
    @Test
    fun `tracking registers the logout discard and the login reset queue`() {
        val bus = Wiring().start(scripts.tracking)

        assertTrue(bus.hasPlayerLogout(), "discardState on logout")
        assertTrue(bus.hasPlayerLogin(), "the login re-arm")
        assertTrue(bus.hasSoftQueue(TRACKING_RESET_QUEUE), "loginReset on $TRACKING_RESET_QUEUE")
    }

    /**
     * Pitfall trapping registers three ops across **nine child locs**, and no content group at all.
     *
     * The nine are the `multiloc` children the twenty-five sites resolve to, so this is the crab
     * trap's shape answered without a group: nine direct registrations against a
     * `[gamevals.content]` id plus nine `contentGroup` blocks. The walk is driven off
     * [PitfallSites] rather than a transcribed list, so a child added to the family joins on its
     * own - and the two size assertions are here because that only helps if the table is the size
     * the cache says it is.
     */
    @Test
    fun `pitfall registers Trap, Jump and Dismantle on every op-carrying child loc`() {
        val bus = Wiring().start(scripts.pitfall)

        val empty = PitfallSites.EMPTY_LOC
        val set = PitfallSites.SET_LOC
        assertTrue(bus.hasOpLoc3(empty), "op3 (`Trap`) on $empty")
        assertTrue(bus.hasOpLoc1(set), "op1 (`Jump`) on $set")

        assertEquals(7, PitfallSites.fullLocs.size, "seven full renderings draw `Dismantle`")
        assertEquals(8, PitfallSites.dismantleLocs.size, "and the spiked pit draws it too")
        for (loc in PitfallSites.dismantleLocs) {
            assertTrue(bus.hasOpLoc2(loc), "op2 (`Dismantle`) on $loc")
        }

        // Nothing of a pitfall exists in the world, so there is no controller to tick - and no
        // content group is claimed, because the registrations are by loc id.
        assertFalse(bus.hasAiConTimer(TRAP_CONTROLLER), "no controller is created")
        assertFalse(bus.hasAiConTimer(FALCON_CONTROLLER), "no controller is created")
        for (group in ALL_TRAP_GROUPS + CRAB_GROUP + BIRD_HOUSE_GROUP) {
            assertFalse(bus.hasOpContentLoc1(group), "pitfalls must not claim $group")
        }
    }

    /** `Tease` is registered on all five creature npcs, which is what starts every chase. */
    @Test
    fun `pitfall registers a tease on every creature npc`() {
        val bus = Wiring().start(scripts.pitfall)

        assertEquals(5, PitfallCreatures.all.size, "all five pitfall creatures ship")
        for (creature in PitfallCreatures.all) {
            assertTrue(bus.hasOpNpc1(creature.npc), "`Tease` on ${creature.npc}")
        }
    }

    /**
     * **The single most important assertion in this class.**
     *
     * `HunterPitfall.tick` is inert until something registers it on `GameLifecycle.LateCycle`, and
     * it carries two things nothing else carries: the leash that ends a chase, and the landing of
     * a collapse. Without this one line a teased creature follows its hunter across the world
     * forever - `NpcModeProcessor` gives `NpcMode.PlayerFollow` no timeout, no leash and no
     * give-up, and teleports the creature onto the player's tile past 15 tiles - and no catch ever
     * finishes collapsing, so no pit can be dismantled. **Every one of this module's other tests
     * stays green through all of that**, because they drive the hook by hand.
     */
    @Test
    fun `pitfall registers the late cycle hook that bounds chases and lands catches`() {
        val bus = Wiring().start(scripts.pitfall)

        assertTrue(bus.hasLateCycle(), "GameLifecycle.LateCycle -> HunterPitfall.tick")
    }

    /**
     * The login rebuild rides a soft queue, and there is deliberately no logout hook.
     *
     * The queue half guards the companion trap [`tracking registers the logout discard and the
     * login reset queue`] names: a varbit written from `onPlayerLogin` itself updates the server
     * and leaves the client drawing the old frame, because `VarPlayerIntMapSetter` short-circuits
     * while `processedMapClock == 0`. A pit stranded at `PitState.Catching` by a logout is the one
     * piece of pitfall state that cannot fix itself, and this is what resolves it.
     *
     * The logout half is an assertion of absence with a reason behind it: `HunterTracking` needs a
     * logout because it holds an `IdentityHashMap<Player, TrailState>`, whereas `HunterPitfall`
     * keys everything by `Npc` and stores a `PlayerUid`, and `tick` sweeps a departed owner's
     * chases and collapses on the next cycle. If a `Player` is ever put in a field there, this
     * assertion is the thing that has to be argued with first.
     */
    @Test
    fun `pitfall registers the login rebuild queue and no logout hook`() {
        val bus = Wiring().start(scripts.pitfall)

        assertTrue(bus.hasPlayerLogin(), "the stranded-collapse rebuild")
        assertTrue(bus.hasSoftQueue(PITFALL_REBUILD_QUEUE), "rebuildPits on $PITFALL_REBUILD_QUEUE")
        assertFalse(bus.hasPlayerLogout(), "nothing here holds a Player to drop")
    }

    /**
     * The **site** locs, their antelope companions, and the op-less children are not registered.
     *
     * The site locs are `multiloc` parents with no ops; `LocInteractions.opTrigger` tries the
     * type-level `LocEvents.OpN` before it recurses into the child, so a per-id registration on a
     * site would shadow the child's handler for every state at once. The other two groups are the
     * ones easiest to add by mistake: the four `hunter_pitfall_full_antelope_*` locs look like the
     * missing rows of [PitfallSites.fullLocs] and are `active=no` with no ops, and
     * `hunting_pitfall_invis_collpased` declares `op2=Dismantle` and is in **no** multiloc chain at
     * this revision, so a handler on it is dead code that reads as live.
     */
    @Test
    fun `no pitfall site, companion or op-less loc is registered by id`() {
        val bus = Wiring().start(*scripts.all.toTypedArray())

        assertEquals(25, PitfallSites.baseLocIds.size, "all twenty-five sites resolve")
        val unregistered =
            PitfallSites.all.map { it.baseLoc } +
                PitfallSites.all.mapNotNull { it.animalLoc } +
                PitfallSites.opLessLocs
        assertEquals(40, unregistered.size, "25 sites, 9 companions and 6 op-less children")

        for (loc in unregistered) {
            val id = loc.asRSCM(RSCMType.LOC)
            assertFalse(bus.eventBus.contains(LocEvents.Op1::class.java, id), "op1 on $loc")
            assertFalse(bus.eventBus.contains(LocEvents.Op2::class.java, id), "op2 on $loc")
            assertFalse(bus.eventBus.contains(LocEvents.Op3::class.java, id), "op3 on $loc")
        }
    }

    /* The once-only invariant. */

    /**
     * `onAiConTimer(TRAP_CONTROLLER)` is registered by exactly one of the five trap scripts.
     *
     * The handler keys on the controller **type**, which all five families share, so a second
     * registration would run every laid trap's tick twice per cycle - halving every trap's lifetime
     * and doubling every catch roll. The count is taken per script on its own bus rather than
     * inferred from the combined one, because `EventBus.subscribeKeyed` throws on a duplicate key:
     * on a shared bus a double registration is a boot crash rather than a wrong number, and this
     * test would then be asserting the engine's guard instead of the invariant.
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
     * The op-less transient states are under none of the five hunter groups, and carry no ops.
     *
     * `_setting_`, `_trapping_` and `_failing_` are frames shown for a couple of cycles while a trap
     * springs. Giving one a group would put a state under a handler whose `when` has no branch for
     * it - the `Nothing interesting happens.` fall-through, on a loc the player is not supposed to be
     * able to click at all.
     *
     * The check is "not one of ours" rather than `contentGroup == -1`, because **`-1` never survives
     * the pack**: `ObjectServerCodec` writes opcode 6 as a `USHORT`, so an unset content group reads
     * back as `65535`. Measured on this cache: 59,717 of 60,000 loc definitions carry `65535` and not
     * one carries `-1`. An `== -1` assertion against a packed loc would be false for every loc in the
     * game, which is why [dev.openrune.types.ObjectServerType.isContentType] compares to a resolved
     * id rather than testing for absence.
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
            for (creature in PitfallCreatures.all) {
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

    /**
     * Every pitfall child a handler sits on really draws that op, and the op-less ones really are.
     *
     * The pitfall half of [`every dispatched loc state carries its content group and the op it is
     * dispatched for`], minus the group: these nine are registered by id, so the only declaration
     * behind each registration is the op itself. A `Dismantle` handler on a loc that draws no op2
     * is unreachable and looks fine.
     *
     * `hunting_pitfall_invis_collpased` is excluded from the second half and pinned on its own,
     * because it is the one loc that carries an op and is still unreachable - no base loc names it
     * in any multiloc chain, so no varbit value can render it.
     */
    @Test
    fun `every pitfall child loc carries the op it is registered for`() {
        val expected = buildList {
            add(PitfallSites.EMPTY_LOC to InteractionOp.Op3)
            add(PitfallSites.SET_LOC to InteractionOp.Op1)
            for (loc in PitfallSites.dismantleLocs) {
                add(loc to InteractionOp.Op2)
            }
        }

        for ((loc, op) in expected) {
            val type =
                ServerCacheManager.getObject(loc.asRSCM(RSCMType.LOC))
                    ?: error("No packed loc definition for $loc")
            assertTrue(type.hasOp(op), "$loc must carry ${op.name} (${type.actions})")
        }

        for (loc in PitfallSites.opLessLocs - UNREACHABLE_PIT_LOC) {
            val type =
                ServerCacheManager.getObject(loc.asRSCM(RSCMType.LOC))
                    ?: error("No packed loc definition for $loc")
            for (op in InteractionOp.entries) {
                assertFalse(type.hasOp(op), "$loc must draw no ${op.name} (${type.actions})")
            }
        }

        val unreachable =
            ServerCacheManager.getObject(UNREACHABLE_PIT_LOC.asRSCM(RSCMType.LOC))
                ?: error("No packed loc definition for $UNREACHABLE_PIT_LOC")
        assertTrue(unreachable.hasOp(Op2), "$UNREACHABLE_PIT_LOC draws an op2 and is still dead")
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
        private val trackingWorld = HunterTrackingTestWorld()
        private val pitfallWorld = HunterPitfallTestWorld()

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
        val tracking = TrackingEvents(trackingWorld.tracking)
        val pitfall = PitfallEvents(pitfallWorld.pitfall)

        /** The five families that share [TRAP_CONTROLLER], in declaration order. */
        val trapFamily: List<PluginScript> = listOf(birdSnare, boxTrap, deadfall, netTrap, magicBox)

        val all: List<PluginScript> =
            trapFamily +
                listOf(falconry, butterfly, crabTrap, impling, birdHouse, tracking, pitfall)
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

        fun hasOpHeld3(obj: String): Boolean =
            eventBus.contains(HeldObjEvents.Op3::class.java, obj.asRSCM(RSCMType.OBJ))

        fun hasOpHeld4(obj: String): Boolean =
            eventBus.contains(HeldObjEvents.Op4::class.java, obj.asRSCM(RSCMType.OBJ))

        /** The equipped counterpart: a different event class, keyed on the same obj id. */
        fun hasOpWorn2(obj: String): Boolean =
            eventBus.contains(WornObjEvents.Op2::class.java, obj.asRSCM(RSCMType.OBJ))

        fun hasOpLoc1(loc: String): Boolean =
            eventBus.contains(LocEvents.Op1::class.java, loc.asRSCM(RSCMType.LOC))

        fun hasOpLoc2(loc: String): Boolean =
            eventBus.contains(LocEvents.Op2::class.java, loc.asRSCM(RSCMType.LOC))

        fun hasOpLoc3(loc: String): Boolean =
            eventBus.contains(LocEvents.Op3::class.java, loc.asRSCM(RSCMType.LOC))

        /**
         * `onEvent<GameLifecycle.LateCycle>` subscribes an [org.rsmod.events.UnboundEvent], so it
         * lives in the same map [hasPlayerLogin] reads rather than in `contains`' suspend map. Read
         * per script rather than on the combined bus: [ImplingEvents] registers one too, and on a
         * shared bus a missing pitfall hook would be hidden by the spawner's.
         */
        fun hasLateCycle(): Boolean =
            eventBus.unbound[GameLifecycle.LateCycle::class.java].orEmpty().isNotEmpty()

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

        /** The logout counterpart of [hasPlayerLogin], same unbound map. */
        fun hasPlayerLogout(): Boolean =
            eventBus.unbound[SessionStateEvent.Logout::class.java].orEmpty().isNotEmpty()

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
        private const val RING_OBJ = HunterTracking.RING_OF_PURSUIT

        /**
         * The pitfall loc that draws `op2=Dismantle` and can still never be clicked: no base loc
         * names it in any multiloc chain at this revision. The misspelling is the cache's.
         */
        private const val UNREACHABLE_PIT_LOC = "loc.hunting_pitfall_invis_collpased"

        private const val FALCONER_NPC = "npc.hunting_npc_falconer"
        private const val FALCONRY_AREA = "area.piscatoris_falconry"
    }
}
