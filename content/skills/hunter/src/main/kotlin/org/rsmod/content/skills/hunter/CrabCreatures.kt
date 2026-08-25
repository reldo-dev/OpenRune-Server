package org.rsmod.content.skills.hunter

import org.rsmod.api.table.hunter.HunterCrabCreaturesRow

/**
 * One colourway of a crab: the obj a catch hands over, and the full-trap loc that shows it.
 *
 * The pair exists because the rainbow crab has three of them and red and blue have one. They are not
 * three creatures - the three `rainbow_crab_*` objs share a name, a description, a cost and both
 * processing params, and differ only in a recolour table that each one shares byte for byte with the
 * `crab_trap_full_rainbow_*` loc of the same letter. So a rainbow catch picks a colourway, and the
 * trap the player is looking at and the crab they take out of it are the same colour by
 * construction.
 */
data class CrabVariant(val caught: String, val fullLoc: String)

/**
 * A single crab-trapping creature.
 *
 * Deliberately **not** a [HunterCreature], and for a stronger reason than [FalconryCreature] or
 * [ButterflyCreature] are not:
 * - It has no `(low, high)` at all. "Unlike other methods, players cannot fail to catch a crab"
 *   (wiki, *Crab trapping*), so there is no rate to store and [HunterCrabTrap] makes no roll. A
 *   `HunterCreature` row would have forced two fabricated coefficients for a formula this technique
 *   never evaluates.
 * - It has no npc. The three crab npcs carry no ops and are never touched; a crab trap is a
 *   per-player varbit on a map-placed multiloc, so nothing is lured, found or despawned.
 * - Its loc states are not per-creature in the way the deadfall's and net trap's are: `unbuilt`,
 *   `built` and the two baited states are shared by every crab, and only the full state varies -
 *   which is [variants].
 *
 * [catchDelay] is how many cycles a baited trap takes to fill. It is a column rather than a constant
 * because the rainbow crab's is different, and it is sourced exactly: "Red and blue crabs: 15 ticks
 * (9s); Rainbow crabs: 25 ticks (15s)".
 */
data class CrabCreature(
    val level: Int,
    val xp: Int,
    val bait: String,
    val variants: List<CrabVariant>,
    val catchDelay: Int,
)

/**
 * The crab creature table, read back from the packed dbtable.
 *
 * The values and their provenance live in `HunterTables.kt` in the `hunter-pack` module; this is
 * only the adapter, exactly as [HunterCreatures] is for the five trap tables.
 *
 * Sorted by dbrow id for tidiness rather than for safety. **Nothing persists an index into this
 * list.** A crab trap's entire state is the site's own cache varbit - which crab a site yields is a
 * property of the site, recovered from the packed `multiLoc` - so unlike [HunterCreatures.all] and
 * [FalconryCreatures.all], reordering or inserting here could not misfile anything already in the
 * world.
 */
object CrabCreatures {
    val all: List<CrabCreature> by lazy {
        HunterCrabCreaturesRow.all().sortedBy(HunterCrabCreaturesRow::rowId).map(::creature)
    }

    /**
     * `caught_items` and `full_loc` are parallel lists - entry `i` of each is the same colourway -
     * so a column edit that drops one entry has to fail here by name rather than as an
     * `IndexOutOfBounds` at the moment a rainbow trap fills. The same guard [HunterCreatures.snare]
     * and its two siblings carry, for the same reason.
     */
    private fun creature(row: HunterCrabCreaturesRow): CrabCreature {
        val variantCount = row.caughtItems.size
        require(variantCount > 0) { "Row ${row.rowId} has no caught items." }
        require(row.fullLoc.size == variantCount) {
            "Row ${row.rowId} has mismatched variant sizes: items=$variantCount, " +
                "full_loc=${row.fullLoc.size}"
        }
        return CrabCreature(
            level = row.level,
            xp = row.xp,
            bait = row.bait.internalName,
            variants =
                row.caughtItems.mapIndexed { i, obj ->
                    CrabVariant(obj.internalName, row.fullLoc[i].internalName)
                },
            catchDelay = row.catchDelay,
        )
    }
}
