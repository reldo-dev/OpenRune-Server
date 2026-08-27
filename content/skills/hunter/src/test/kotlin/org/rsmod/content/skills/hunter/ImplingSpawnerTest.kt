package org.rsmod.content.skills.hunter

import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.map.CoordGrid

/**
 * The spawner that turns the map's invisible markers into implings.
 *
 * Without this nothing in `.data` produces a catchable impling outside Puro-Puro's six low-tier
 * creatures, so these are the tests that decide whether two thirds of the technique is reachable at
 * all. The RNG is pinned so a roll selects a known entry rather than a random one.
 */
@ResourceLock(HUNTER_TEST_WORLD_LOCK)
class ImplingSpawnerTest {
    @Test
    fun aMarkerProducesNothingUntilTheRevealDelayHasPassed() {
        val world = HunterButterflyTestWorld()
        world.addNpc(COMMON_PRECURSOR, OVERWORLD)

        repeat(REVEAL_CYCLES - 1) { world.implingSpawner.tick() }

        assertNull(implingAt(world, OVERWORLD), "no impling before the reveal delay")
    }

    /**
     * "In most cases, they initially spawn as an invisible NPC, and 'roam' invisibly for two
     * minutes" - 200 cycles at 0.6s, which is [REVEAL_CYCLES].
     */
    @Test
    fun aCommonMarkerProducesALowTierImplingOnceTheDelayPasses() {
        val world = HunterButterflyTestWorld()
        world.addNpc(COMMON_PRECURSOR, OVERWORLD)
        world.random.nextInt = 0

        repeat(REVEAL_CYCLES) { world.implingSpawner.tick() }

        val spawned = implingAt(world, OVERWORLD)
        val lowTier = ImplingSpawns.table(ImplingTier.Low).map { it.npc }
        assertTrue(spawned in lowTier, "$spawned is not one of the low tier's creatures")
    }

    /** An overworld marker produces the overworld form, which is what pays overworld experience. */
    @Test
    fun anOverworldMarkerProducesTheOverworldForm() {
        val world = HunterButterflyTestWorld()
        world.addNpc(COMMON_PRECURSOR, OVERWORLD)
        world.random.nextInt = 0

        repeat(REVEAL_CYCLES) { world.implingSpawner.tick() }

        val spawned = checkNotNull(implingAt(world, OVERWORLD))
        assertTrue(!spawned.endsWith("_maze"), "$spawned is the Puro-Puro form on an overworld tile")
        val creature = checkNotNull(ImplingCreatures.byNpc("${spawned}_maze"))
        assertEquals(spawned, creature.npcOverworld)
    }

    /**
     * The same marker symbol inside Puro-Puro produces the `_maze` form instead.
     *
     * `ii_common_impling_precursor` is placed in both places - 23 overworld and 10 in Puro-Puro - so
     * the marker cannot decide this on its own and the spawner reads its position. Getting it wrong
     * would pay overworld experience for a Puro-Puro catch, which is the larger number.
     */
    @Test
    fun aMarkerInsidePuroPuroProducesThePuroPuroForm() {
        val world = HunterButterflyTestWorld()
        world.addNpc(COMMON_PRECURSOR, PURO_PURO)
        world.random.nextInt = 0

        repeat(REVEAL_CYCLES) { world.implingSpawner.tick() }

        val spawned = checkNotNull(implingAt(world, PURO_PURO))
        assertTrue(spawned.endsWith("_maze"), "$spawned is not the Puro-Puro form inside Puro-Puro")
    }

    /** The Prifddinas marker is not a weighted roll: it always produces the crystal impling. */
    @Test
    fun thePrifddinasMarkerAlwaysProducesTheCrystalImpling() {
        val world = HunterButterflyTestWorld()
        world.addNpc("npc.ii_impling_type_12_precursor", OVERWORLD)

        repeat(REVEAL_CYCLES) { world.implingSpawner.tick() }

        assertEquals("npc.ii_impling_type_12_johnny", implingAt(world, OVERWORLD))
    }

    /**
     * A caught impling's marker produces another after the reveal delay, and not before.
     *
     * "When an impling is captured, the invisible NPC will respawn immediately and once again roam
     * for two minutes before becoming visible." The spawner notices the catch by the npc losing its
     * slot - nothing tells it - which is the same check the falcon lifetime uses, and for the same
     * reason: holding a reference to a despawned npc is what broke falconry.
     */
    @Test
    fun aCaughtImplingIsReplacedAfterTheRevealDelay() {
        val world = HunterButterflyTestWorld()
        world.addNpc(COMMON_PRECURSOR, OVERWORLD)
        world.random.nextInt = 0
        repeat(REVEAL_CYCLES) { world.implingSpawner.tick() }

        val spawned =
            checkNotNull(
                world.npcRepo.findAll(OVERWORLD).firstOrNull {
                    it.isVisible && ImplingCreatures.byNpcId(it.visType.id) != null
                }
            )
        // Released the way a catch releases it, not by a raw despawn: `despawn` schedules an
        // engine respawn of the same npc, which is exactly the behaviour this must not have.
        assertTrue(world.implingSpawner.release(spawned), "the spawner should own what it made")
        world.implingSpawner.tick()
        assertNull(implingAt(world, OVERWORLD), "the marker should not refill on the same cycle")

        repeat(REVEAL_CYCLES) { world.implingSpawner.tick() }
        assertTrue(implingAt(world, OVERWORLD) != null, "the marker should have produced another")
    }

    /** Only the five marker symbols are anchors; any other npc standing there is not one. */
    @Test
    fun anUnrelatedNpcIsNotAnAnchor() {
        val world = HunterButterflyTestWorld()
        world.addNpc("npc.ii_lost_impling", OVERWORLD)

        repeat(REVEAL_CYCLES * 2) { world.implingSpawner.tick() }

        assertEquals(0, world.implingSpawner.anchorCount, "the Wandering impling is not a marker")
    }

    /**
     * The impling standing on [coords], if any.
     *
     * Not `npcNameAt`: the marker never leaves the tile it produced from, and it is the npc that
     * lookup finds first. Filtering by "is this a shipped impling" is also the honest question -
     * the test wants to know what was produced, not what is standing there.
     */
    private fun implingAt(world: HunterButterflyTestWorld, coords: CoordGrid): String? {
        val npc =
            world.npcRepo.findAll(coords).firstOrNull {
                it.isVisible && ImplingCreatures.byNpcId(it.visType.id) != null
            } ?: return null
        val creature = checkNotNull(ImplingCreatures.byNpcId(npc.visType.id))
        val overworld = creature.npcOverworld.asRSCM(RSCMType.NPC)
        return if (npc.visType.id == overworld) creature.npcOverworld else creature.npc
    }

    private companion object {
        private const val COMMON_PRECURSOR = "npc.ii_common_impling_precursor"

        /** [ImplingSpawner]'s own two-minute reveal delay, restated so a change to it fails here. */
        private const val REVEAL_CYCLES = 200

        /** Inside map square 40,67, which is the whole of Puro-Puro. */
        private val PURO_PURO = CoordGrid(0, 40, 67, 10, 10)

        /** Anywhere that is not that square. */
        private val OVERWORLD = CoordGrid(0, 50, 50, 10, 10)

        @JvmStatic
        @BeforeAll
        fun loadCache() {
            HunterTestCache.load()
        }
    }
}
