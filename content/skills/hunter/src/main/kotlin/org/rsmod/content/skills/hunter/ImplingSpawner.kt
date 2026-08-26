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
 * Turns the map's invisible impling markers into implings.
 *
 * `.data` ships **61 "precursor" npcs** - `vislevel=0`, no models, no ops, no name - and not one
 * spawn of any catchable impling outside Puro-Puro. That is not missing content: the precursors
 * *are* the spawn data, and the live server replaces each with a rolled impling. *Impling*
 * (oldid=15303398): "In most cases, they initially spawn as an invisible NPC, and 'roam' invisibly
 * for two minutes... Once implings become visible, a high-pitched 'beckon whistle' sound plays."
 *
 * Which impling a marker produces is decided by **which marker it is** - the five symbols are the
 * wiki's four spawn tiers plus Prifddinas - and the odds are published; see [ImplingSpawns].
 *
 * ## What this deliberately does not reproduce
 *
 * The wiki describes the overworld as a single global 30-minute cycle: every wandering impling
 * despawns at once and a fresh batch is rolled and scattered over the spawn points. This spawner is
 * **per anchor** instead - each marker independently counts down, produces one impling, and starts
 * again once that impling is caught or times out. The observable difference is that implings here
 * do not all vanish on the same tick.
 *
 * That is a deliberate choice, not an oversight. The global model needs a list of spawn points to
 * scatter a batch across; ours are fixed positions in the map data, and a batch model would leave
 * most of them empty most of the time while the wiki's own Puro-Puro description - "35 spawn points
 * which produce an invisible NPC... and respawns immediately upon capture" - is exactly the
 * per-anchor behaviour. Reproducing the global cycle would mean inventing spawn-point selection on
 * top of anchors we already have.
 */
@Singleton
class ImplingSpawner
@Inject
constructor(
    private val npcList: NpcList,
    private val npcRepo: NpcRepository,
    private val gameRandom: GameRandom,
) {
    /**
     * Built once, on the first cycle rather than at startup, because the map's own npc spawns have
     * to be in the world before they can be found. Null until then.
     */
    private var anchors: List<Anchor>? = null

    /** How many markers were found, for the boot probe to assert against rather than guess. */
    val anchorCount: Int
        get() = anchors?.size ?: 0

    /**
     * Hands a caught impling back, if this spawner made it.
     *
     * The two kinds of impling have to be ended differently and they share npc ids, so only the
     * spawner can tell them apart. A **map-placed** impling - the 51 fixed low-tier spawns inside
     * Puro-Puro - belongs to the engine, and `despawn` is right for it: the engine puts it back on
     * its own tile after its respawn delay. A **spawner-made** one must be `del`eted instead,
     * because `despawn` schedules an engine respawn of *that same npc*, which would return the same
     * creature on the engine's clock and quietly defeat the tier roll - the marker would produce one
     * creature for the rest of the world's life.
     *
     * @return true if the impling was ours and has been removed; false if the caller should despawn
     *   it in the ordinary way.
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
        /**
         * "roam invisibly for two minutes" - 200 cycles at 0.6s. Also what a captured impling waits
         * before its marker produces another, which the wiki states as immediate respawn of the
         * *invisible* npc, i.e. the same two-minute wait over again rather than an instant impling.
         */
        private const val REVEAL_CYCLES = 200

        /** "They will roam for approximately thirty minutes before disappearing." */
        private const val VISIBLE_CYCLES = 3000

        /**
         * The five markers, and the tier each stands for.
         *
         * Common/uncommon/rare read onto the wiki's low/mid/high, and the split between the two
         * high tables is carried by the marker itself - `_maze` is the Puro-Puro one. Counts in
         * `.data`: 33 common (10 of them in Puro-Puro), 19 uncommon (12), 4 rare, 4 rare `_maze`,
         * and the single Prifddinas marker.
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
