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
import org.rsmod.content.skills.hunter.HunterTrackingTestWorld.Companion.COLD_SPOT
import org.rsmod.content.skills.hunter.HunterTrackingTestWorld.Companion.HOT_SPOT

/**
 * Following a trail: inspect, advance, catch.
 *
 * Every case builds the synthetic diamond from [HunterTrackingTestWorld.network], whose segments
 * carry **real** `varbit.hunting_trail_state8_*` gamevals so each write resolves through the same
 * `ServerCacheManager` path the running server takes. Assertions read those varbits back one at a
 * time and never a varp: varp 925 carries unrelated fields (e.g., `lumbridge_alchemy_high`
 * from another system), so a varp assertion would be asserting their state too.
 *
 * Serialised for the reason the rest of the suite is - `ServerCacheManager` is a singleton and
 * `RSCM` memoises into a plain `HashMap`, so a cache-touching class run beside another races on
 * shared mutable state.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class HunterTrackingTest {
    @BeforeEach
    fun setUp() {
        HunterTestCache.load()
    }

    /* Inspecting a burrow */

    @Test
    fun `inspecting a burrow below the creature's level refuses and writes nothing`() {
        val world = HunterTrackingTestWorld()
        // The razor-backed kebbit, whose 49 is the highest requirement any tracking creature has.
        val network = HunterTrackingTestWorld.network(TrackingCreatures.razorBacked)
        val player = world.addPlayer(hunterLvl = 48)

        assertFalse(world.inspectBurrow(player, network))

        assertNull(world.tracking.trailOf(player), "no trail is generated")
        assertEquals(List(5) { 0 }, world.segmentValues(player, network), "and nothing is rendered")
        assertTrue(world.wasTold(player, "Hunter level of 49"))
        assertEquals(0, world.random.intDraws, "a refused inspect consumes no random draw")
    }

    @Test
    fun `inspecting a burrow generates a trail and reveals exactly one segment`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer(hunterLvl = 1)
        world.random.nextInt = 0

        assertTrue(world.inspectBurrow(player, network))

        val trail = checkNotNull(world.tracking.trailOf(player))
        assertEquals(2, trail.steps.size, "trail 0 is the two-segment path to the hot spot")
        assertEquals(1, trail.revealed)
        // 4 is the forward footprint variant; every later segment is still hidden.
        assertEquals(listOf(4, 0, 0, 0, 0), world.segmentValues(player, network))
    }

    @Test
    fun `inspecting a second burrow while already following that trail refuses`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.random.nextInt = 0
        assertTrue(world.inspectBurrow(player, network))

        assertFalse(world.inspectBurrow(player, network))

        assertEquals(1, checkNotNull(world.tracking.trailOf(player)).revealed)
        assertEquals(listOf(4, 0, 0, 0, 0), world.segmentValues(player, network))
        assertTrue(world.wasTold(player, "already following"))
    }

    @Test
    fun `inspecting a burrow in another area clears the trail abandoned in the first`() {
        val world = HunterTrackingTestWorld()
        // Two networks off different varbit blocks, which is what two areas are: a trail cleared
        // in one has to be observable separately from a trail rendered in the other.
        val rellekka = HunterTrackingTestWorld.network(TrackingCreatures.polar)
        val feldip = HunterTrackingTestWorld.network(TrackingCreatures.feldipWeasel, block = 6)
        val player = world.addPlayer()
        world.random.nextInt = 0
        assertTrue(world.inspectBurrow(player, rellekka))
        assertTrue(world.inspectClue(player, rellekka, "loc.hunting_trail_clue8_1"))
        assertEquals(listOf(4, 4, 0, 0, 0), world.segmentValues(player, rellekka), "test setup")

        assertTrue(world.inspectBurrow(player, feldip))

        // The footprints left behind are the whole point: dropping the entry without clearing them
        // would leave a trail rendering in Rellekka that the server has no state for and that no
        // clue can ever advance. This switch is the only way out of that short of relogging.
        assertEquals(
            List(5) { 0 },
            world.segmentValues(player, rellekka),
            "the abandoned trail stops rendering",
        )
        assertEquals(listOf(4, 0, 0, 0, 0), world.segmentValues(player, feldip))
        val trail = checkNotNull(world.tracking.trailOf(player))
        assertEquals(feldip, trail.network, "the tracked trail is the new area's")
        assertEquals(1, trail.revealed)
    }

    /* Inspecting a clue */

    @Test
    fun `inspecting the wrong clue does not advance`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.random.nextInt = 0
        world.inspectBurrow(player, network)

        // Segment 2 belongs to a different branch of the diamond and is not this trail's next step.
        assertFalse(world.inspectClue(player, network, "loc.hunting_trail_clue8_2"))

        assertEquals(1, checkNotNull(world.tracking.trailOf(player)).revealed)
        assertEquals(listOf(4, 0, 0, 0, 0), world.segmentValues(player, network))
        assertTrue(world.wasTold(player, "nothing of interest"))
    }

    @Test
    fun `inspecting the right clue advances and renders the next segment`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.random.nextInt = 0
        world.inspectBurrow(player, network)

        assertTrue(world.inspectClue(player, network, "loc.hunting_trail_clue8_1"))

        val trail = checkNotNull(world.tracking.trailOf(player))
        assertEquals(2, trail.revealed)
        assertTrue(trail.complete)
        assertEquals(listOf(4, 4, 0, 0, 0), world.segmentValues(player, network))
    }

    @Test
    fun `a clue click with no trail at all finds nothing`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()

        assertFalse(world.inspectClue(player, network, "loc.hunting_trail_clue8_0"))

        assertEquals(List(5) { 0 }, world.segmentValues(player, network))
    }

    /* Searching the catch spot */

    @Test
    fun `searching the spot a completed trail ends at reports movement`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.random.nextInt = 0
        world.inspectBurrow(player, network)
        world.followToTheEnd(player, network)

        assertTrue(world.searchCatchSpot(player, network, HOT_SPOT))
        assertFalse(world.searchCatchSpot(player, network, COLD_SPOT), "the other placement is cold")
    }

    /* Attacking the catch spot */

    @Test
    fun `attacking without a wielded noose wand refuses`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        // Carried, not wielded. The wiki is explicit: "right-click 'Attack' while *wielding* a
        // noose wand".
        world.giveItem(player, HunterTracking.NOOSE_WAND)
        world.random.nextInt = 0
        world.inspectBurrow(player, network)
        world.followToTheEnd(player, network)

        assertFalse(world.attackCatchSpot(player, network, HOT_SPOT))

        assertEquals(0, world.itemCount(player, "obj.huntingbeast_polar_fur"), "no loot")
        assertEquals(0, player.statMap.getFineXP("stat.hunter"), "no experience")
        assertNotNull(world.tracking.trailOf(player), "the trail survives a refusal")
        assertTrue(world.wasTold(player, "noose wand"))
    }

    @Test
    fun `attacking the spot the trail ends at catches, loots and awards thirty experience`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.wieldNooseWand(player)
        world.random.nextInt = 0
        world.inspectBurrow(player, network)
        world.followToTheEnd(player, network)

        assertTrue(world.attackCatchSpot(player, network, HOT_SPOT))

        // "Catching a kebbit gives the player bones, raw beast meat, and kebbit fur." (wiki,
        // *Tracking*.) Literal gamevals, so a change to the table has to be argued for here too.
        assertEquals(1, world.itemCount(player, "obj.huntingbeast_polar_fur"))
        assertEquals(1, world.itemCount(player, "obj.spit_raw_beast_meat"))
        assertEquals(1, world.itemCount(player, "obj.bones"))
        // The wiki's *Tracking* table gives the polar kebbit 30 experience. Fine xp is stored x10,
        // so 300 is 30.0 exactly - pinned as a literal rather than read back off the table.
        assertEquals(300, player.statMap.getFineXP("stat.hunter"))
        assertNull(world.tracking.trailOf(player), "the trail is spent")
        assertEquals(List(5) { 0 }, world.segmentValues(player, network), "and stops rendering")
    }

    /**
     * The Hunter xp modifier is *applied*, not merely injected.
     *
     * Every other world in this suite builds its `XpModifiers` from an empty set, which is a flat
     * 1.0, so the `* xpMods.get(player, "stat.hunter")` on the award site could be deleted with the
     * rest of the suite still green. Running the same catch twice, once in a doubled world, is what
     * makes the multiplication load-bearing.
     */
    @Test
    fun `the xp modifier scales the tracking award`() {
        val plain = caughtPolarKebbitFineXp(hunterXpBonus = 0.0)
        val doubled = caughtPolarKebbitFineXp(hunterXpBonus = DOUBLE_HUNTER_XP)

        // The wiki *Tracking* table's 30 xp for a polar kebbit, in the stat map's tenths.
        assertEquals(300, plain, "unmodified, the polar kebbit is 30.0 xp")
        assertEquals(600, doubled, "a +100% modifier makes it 60.0")
    }

    /** One caught polar kebbit at the end of its trail, in tenths of a point. */
    private fun caughtPolarKebbitFineXp(hunterXpBonus: Double): Int {
        val world = HunterTrackingTestWorld(hunterXpBonus = hunterXpBonus)
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.wieldNooseWand(player)
        world.random.nextInt = 0
        world.inspectBurrow(player, network)
        world.followToTheEnd(player, network)

        assertTrue(world.attackCatchSpot(player, network, HOT_SPOT))

        return player.statMap.getFineXP("stat.hunter")
    }

    @Test
    fun `attacking a different catch spot of the same network misses without clearing the trail`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.wieldNooseWand(player)
        world.random.nextInt = 0
        world.inspectBurrow(player, network)
        world.followToTheEnd(player, network)

        // Same loc gameval, different tile - which is exactly why the catch spot is matched on
        // coordinate and the clues and burrows are not.
        assertFalse(world.attackCatchSpot(player, network, COLD_SPOT))

        assertEquals(0, world.itemCount(player, "obj.huntingbeast_polar_fur"))
        assertEquals(0, player.statMap.getFineXP("stat.hunter"))
        assertEquals(listOf(4, 4, 0, 0, 0), world.segmentValues(player, network), "trail intact")
        assertNotNull(world.tracking.trailOf(player))
    }

    @Test
    fun `attacking an incomplete trail's end misses`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.wieldNooseWand(player)
        world.random.nextInt = 0
        world.inspectBurrow(player, network)

        assertFalse(world.attackCatchSpot(player, network, HOT_SPOT))

        assertEquals(0, world.itemCount(player, "obj.huntingbeast_polar_fur"))
        assertEquals(1, checkNotNull(world.tracking.trailOf(player)).revealed)
    }

    @Test
    fun `a full inventory refuses before the catch`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.wieldNooseWand(player)
        world.random.nextInt = 0
        world.inspectBurrow(player, network)
        world.followToTheEnd(player, network)
        // Two free slots against three unstackable rewards: one short, so nothing may be taken.
        world.fillInventory(player, free = 2)

        assertFalse(world.attackCatchSpot(player, network, HOT_SPOT))

        assertEquals(0, world.itemCount(player, "obj.huntingbeast_polar_fur"))
        assertEquals(0, world.itemCount(player, "obj.spit_raw_beast_meat"))
        assertEquals(0, world.itemCount(player, "obj.bones"), "and no partial payout")
        assertEquals(0, player.statMap.getFineXP("stat.hunter"))
        assertNotNull(world.tracking.trailOf(player), "the kebbit waits")
        assertTrue(world.wasTold(player, "inventory"))
    }

    /* Ring of pursuit */

    @Test
    fun `a worn ring of pursuit reveals the whole trail at once`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.wearRingOfPursuit(player)
        // Trail 3 is the three-segment path that traverses the rung backwards, so it renders a
        // reversed step as well as two forward ones.
        world.random.nextInt = 3

        assertTrue(world.inspectBurrow(player, network))

        val trail = checkNotNull(world.tracking.trailOf(player))
        assertEquals(3, trail.steps.size)
        assertEquals(3, trail.revealed, "the ring skips straight to the end")
        assertTrue(trail.complete)
        // s2 and s1 forward (4), s4 reversed (3); s0 and s3 are not on this trail.
        assertEquals(listOf(0, 4, 4, 0, 3), world.segmentValues(player, network))
        assertEquals(1, player.trackingRingCharges, "one of the ten charges is spent")
    }

    @Test
    fun `the tenth ring use destroys the ring and resets the counter`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.wearRingOfPursuit(player)
        world.random.nextInt = 0
        // Nine charges already spent, so this inspect is the tenth and last.
        player.trackingRingCharges = 9

        assertTrue(world.inspectBurrow(player, network))

        assertFalse(
            player.worn.contains(HunterTracking.RING_OF_PURSUIT),
            "the ring crumbles on its tenth use",
        )
        assertEquals(0, player.trackingRingCharges, "and the allowance resets for the next ring")
        assertTrue(world.wasTold(player, "ring of pursuit"))
        assertTrue(checkNotNull(world.tracking.trailOf(player)).complete, "the reveal still lands")
    }

    @Test
    fun `ring charges belong to the player and survive swapping the ring`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.wearRingOfPursuit(player)
        world.random.nextInt = 0

        world.inspectBurrow(player, network)
        assertEquals(1, player.trackingRingCharges)

        // A different ring entirely: taken off, dropped, and a fresh one put on.
        world.removeRingOfPursuit(player)
        world.wearRingOfPursuit(player)
        world.tracking.clearTrail(player, network)

        world.inspectBurrow(player, network)
        assertEquals(2, player.trackingRingCharges, "the count follows the player, not the ring")
    }

    @Test
    fun `an unworn ring reveals nothing extra and spends no charge`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        // Carried, not worn: "Wearing a ring of pursuit when inspecting a kebbit burrow will reveal
        // the kebbit's entire track at once." (wiki, *Tracking*.)
        world.giveItem(player, HunterTracking.RING_OF_PURSUIT)
        world.random.nextInt = 3

        world.inspectBurrow(player, network)

        assertEquals(1, checkNotNull(world.tracking.trailOf(player)).revealed)
        assertEquals(0, player.trackingRingCharges)
        assertEquals(1, world.itemCount(player, HunterTracking.RING_OF_PURSUIT), "and is not spent")
    }

    /* Check and Break */

    @Test
    fun `checking an unused ring reports all ten charges`() {
        val world = HunterTrackingTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, HunterTracking.RING_OF_PURSUIT)

        world.checkRingCharges(player)

        // Ten, not zero: the counter stores charges used, and that representation must not reach
        // the player. The literal is pinned rather than read off `RING_CHARGES`.
        assertTrue(world.wasTold(player, "has 10 charges remaining"))
    }

    @Test
    fun `checking a part-spent ring reports what is left, not what is used`() {
        val world = HunterTrackingTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, HunterTracking.RING_OF_PURSUIT)
        player.trackingRingCharges = 4

        world.checkRingCharges(player)

        assertTrue(world.wasTold(player, "has 6 charges remaining"), "four used leaves six")
    }

    @Test
    fun `the last charge is reported in the singular`() {
        val world = HunterTrackingTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, HunterTracking.RING_OF_PURSUIT)
        player.trackingRingCharges = 9

        world.checkRingCharges(player)

        assertTrue(world.wasTold(player, "has 1 charge remaining"))
    }

    @Test
    fun `breaking a ring destroys it and restores the full allowance`() {
        val world = HunterTrackingTestWorld()
        val player = world.addPlayer()
        world.giveItem(player, HunterTracking.RING_OF_PURSUIT)
        player.trackingRingCharges = 4

        world.breakRing(player)

        assertEquals(0, world.itemCount(player, HunterTracking.RING_OF_PURSUIT), "the ring is gone")
        assertEquals(0, player.trackingRingCharges)
        world.checkRingCharges(player)
        assertTrue(world.wasTold(player, "has 10 charges remaining"), "the next ring starts full")
    }

    @Test
    fun `breaking with nothing in that slot destroys nothing and keeps the count`() {
        val world = HunterTrackingTestWorld()
        val player = world.addPlayer()
        player.trackingRingCharges = 4

        world.breakRing(player)

        assertEquals(4, player.trackingRingCharges, "a failed deletion is not a free reset")
    }

    @Test
    fun `checking after the ring crumbles reports a full allowance for the next one`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.wearRingOfPursuit(player)
        world.random.nextInt = 0
        // Nine spent, so this inspect is the tenth use and the ring crumbles on it.
        player.trackingRingCharges = 9
        assertTrue(world.inspectBurrow(player, network))
        assertFalse(player.worn.contains(HunterTracking.RING_OF_PURSUIT), "test setup")

        world.checkRingCharges(player)

        assertTrue(world.wasTold(player, "has 10 charges remaining"))
    }

    /* Lifecycle */

    @Test
    fun `clearTrail zeroes only that trail's own segment varbits`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.random.nextInt = 0
        world.inspectBurrow(player, network)
        world.followToTheEnd(player, network)
        // A segment that is not on this trail, left showing by something else entirely.
        world.setVarbit(player, "varbit.hunting_trail_state8_3", 4)

        world.tracking.clearTrail(player, network)

        assertNull(world.tracking.trailOf(player))
        assertEquals(listOf(0, 0, 0, 4, 0), world.segmentValues(player, network))
    }

    @Test
    fun `discardState drops the trail without touching the varbits`() {
        val world = HunterTrackingTestWorld()
        val network = HunterTrackingTestWorld.network()
        val player = world.addPlayer()
        world.random.nextInt = 0
        world.inspectBurrow(player, network)

        world.tracking.discardState(player)

        assertNull(world.tracking.trailOf(player), "a logout takes the in-memory entry")
        assertEquals(listOf(4, 0, 0, 0, 0), world.segmentValues(player, network), "the varps persist")
    }

    @Test
    fun `loginReset hides every placed segment in every network`() {
        val world = HunterTrackingTestWorld()
        val player = world.addPlayer()
        val someSegments =
            TrackingNetworks.all.map { it.segments.first().varbit } +
                TrackingNetworks.all.map { it.segments.last().varbit }
        for (varbit in someSegments) {
            world.setVarbit(player, varbit, 4)
        }

        world.tracking.loginReset(player)

        for (network in TrackingNetworks.all) {
            for (segment in network.segments) {
                assertEquals(0, world.varbitOf(player, segment.varbit), segment.varbit)
            }
        }
    }
}
