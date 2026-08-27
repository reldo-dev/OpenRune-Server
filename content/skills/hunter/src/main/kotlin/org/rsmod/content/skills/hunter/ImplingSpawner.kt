package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.NpcList
import org.rsmod.map.CoordGrid

/**
 * Turns the map's 61 invisible "precursor" markers into implings - the precursors *are* the spawn
 * data, and which impling a marker produces is decided by which marker it is ([ImplingSpawns]).
 * Deliberately per-anchor rather than the wiki's global 30-minute batch cycle: the observable
 * difference is that implings do not all vanish on the same tick (docs/hunter.md).
 */
@Singleton
class ImplingSpawner
@Inject
constructor(
    private val npcList: NpcList,
    private val npcRepo: NpcRepository,
    private val gameRandom: GameRandom,
) {
    // Built on the first cycle, not at startup: the map's spawns must be in the world first.
    private var anchors: List<Anchor>? = null

    // For the boot probe to assert against rather than guess.
    val anchorCount: Int
        get() = anchors?.size ?: 0

    /**
     * Hands a caught impling back, if this spawner made it. A spawner-made impling must be
     * `del`eted, never `despawn`ed: despawn schedules an engine respawn of that same npc and
     * quietly defeats the tier roll forever (docs/hunter.md).
     */
    fun release(npc: Npc): Boolean {
        val anchor = anchors?.firstOrNull { it.spawned === npc } ?: return false
        npcRepo.del(npc, Int.MAX_VALUE)
        anchor.clear()
        return true
    }

    fun tick() {
        val anchors = anchors ?: findAnchors().also { anchors = it }
        for (anchor in anchors) {
            tick(anchor)
        }
    }

    private fun tick(anchor: Anchor) {
        val current = anchor.spawned
        if (current != null) {
            // `isSlotAssigned` is how a caught impling is noticed: `catchImpling` despawns it and
            // nothing tells the spawner. The same check the falcon lifetime uses, for the same
            // reason - holding a reference to a despawned npc is the bug that broke falconry.
            if (!current.isSlotAssigned) {
                anchor.clear()
                return
            }
            if (--anchor.remaining <= 0) {
                npcRepo.del(current, 0)
                anchor.clear()
            }
            return
        }
        if (--anchor.remaining > 0) {
            return
        }
        spawn(anchor)
    }

    private fun spawn(anchor: Anchor) {
        val overworld = ImplingSpawns.roll(anchor.tier, gameRandom)
        val creature = ImplingCreatures.byNpcId(overworld.asRSCM(RSCMType.NPC)) ?: return
        // The Puro-Puro form where the marker is inside Puro-Puro, the overworld form everywhere
        // else. This is the whole reason the row carries both ids: it decides the experience a
        // catch awards, and the wiki ties that to the spawn rather than to the catcher.
        val symbol = if (anchor.puroPuro) creature.npc else creature.npcOverworld
        // Resolving the symbol is not proof of a packed definition - that distinction cost this
        // branch a live failure once - so this errors loudly rather than spawning nothing.
        val type =
            ServerCacheManager.getNpc(symbol.asRSCM(RSCMType.NPC))
                ?: error("Missing impling npc type: $symbol")
        val impling = Npc(type, anchor.coords)
        npcRepo.add(impling, Int.MAX_VALUE)
        anchor.spawned = impling
        anchor.remaining = VISIBLE_CYCLES
    }

    private fun findAnchors(): List<Anchor> {
        val tiers = TIER_BY_PRECURSOR.mapKeys { it.key.asRSCM(RSCMType.NPC) }
        return npcList.mapNotNull { npc ->
            val tier = tiers[npc.visType.id] ?: return@mapNotNull null
            Anchor(npc.coords, tier, npc.coords.inPuroPuro())
        }
    }

    private class Anchor(val coords: CoordGrid, val tier: ImplingTier, val puroPuro: Boolean) {
        var spawned: Npc? = null
        var remaining: Int = REVEAL_CYCLES

        fun clear() {
            spawned = null
            remaining = REVEAL_CYCLES
        }
    }

    private companion object {
        /** "roam invisibly for two minutes" - also the wait after a capture (docs/hunter.md). */
        private const val REVEAL_CYCLES = 200

        /** "They will roam for approximately thirty minutes before disappearing." */
        private const val VISIBLE_CYCLES = 3000

        /**
         * The five markers and the tier each stands for; the split between the two high tables is
         * carried by the marker itself (`_maze` is the Puro-Puro one).
         */
        private val TIER_BY_PRECURSOR =
            mapOf(
                "npc.ii_common_impling_precursor" to ImplingTier.Low,
                "npc.ii_uncommon_impling_precursor" to ImplingTier.Mid,
                "npc.ii_rare_impling_precursor" to ImplingTier.High,
                "npc.ii_rare_impling_precursor_maze" to ImplingTier.HighPuroPuro,
                "npc.ii_impling_type_12_precursor" to ImplingTier.Crystal,
            )
    }
}
