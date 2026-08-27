package org.rsmod.content.skills.hunter

import org.rsmod.api.table.hunter.HunterCrabCreaturesRow

/**
 * One colourway of a crab: the trap the player is looking at and the crab they take out of it are
 * the same colour by construction (docs/hunter.md).
 */
data class CrabVariant(val caught: String, val fullLoc: String)

/**
 * A single crab-trapping creature - not a [HunterCreature]: it has no rate (crabs cannot fail to
 * be caught) and no npc (nothing is lured, found or despawned). See docs/hunter.md.
 */
data class CrabCreature(
    val level: Int,
    val xp: Int,
    val bait: String,
    val variants: List<CrabVariant>,
    val catchDelay: Int,
)

/**
 * The crab creature table, read back from the packed dbtable. Nothing persists an index into this
 * list: a trap's entire state is the site's own cache varbit.
 */
object CrabCreatures {
    val all: List<CrabCreature> by lazy {
        HunterCrabCreaturesRow.all().sortedBy(HunterCrabCreaturesRow::rowId).map(::creature)
    }

    // The parallel-list guard: a ragged column edit must fail by name, not as an
    // IndexOutOfBounds at the moment a rainbow trap fills.
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
