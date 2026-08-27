package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.table.hunter.HunterBirdhouseTypesRow

/**
 * One tier of bird house. The state indices are read off the packed loc rather than computed: the
 * `tier = (varp - 1) / 3` arithmetic holds today, but deriving from the cache means a reordered
 * chain moves this table instead of silently rendering the wrong house.
 */
data class BirdHouseType(
    val obj: String,
    val hunterLevel: Int,
    val hunterXp: Int,
    val craftingLevel: Int,
    val craftingXp: Int,
    val logs: String,
    val nestPermille: Int,
    val builtState: Int,
    val fullState: Int,
    val birdState: Int,
)

/** Which of a tier's three states a space is showing. */
enum class BirdHouseState {
    /** Built and empty; takes seeds. */
    Empty,

    /** Seeded and filling. */
    Filling,

    /** Full of birds; takes Dismantle. */
    Full,
}

/**
 * The nine tiers and where each sits in the space's multiloc chain - a varp holding 0..27. The
 * chain is read from the first space; all four are byte-identical (asserted).
 */
object BirdHouseTypes {
    val all: List<BirdHouseType> by lazy {
        HunterBirdhouseTypesRow.all().sortedBy(HunterBirdhouseTypesRow::rowId).map(::type)
    }

    /**
     * 29 entries, not 28: index 28 is the `65535` sentinel; [stateOf] searches rather than
     * indexes, so the tail is harmless.
     */
    private val children: List<Int> by lazy {
        val space = BirdHouseSpaces.all.first()
        val type =
            checkNotNull(ServerCacheManager.getObject(space.locId)) { "Missing loc: ${space.loc}" }
        type.multiLoc.map { it and 0xFFFF }
    }

    private val byObj: Map<String, BirdHouseType> by lazy { all.associateBy { it.obj } }

    /** The bird house a player is holding, or null if the obj is not one. */
    fun byObj(obj: String): BirdHouseType? = byObj[obj]

    /** The tier a space's varp value is showing, or null if the space is bare. */
    fun byVarpValue(value: Int): BirdHouseType? =
        all.firstOrNull { value == it.builtState || value == it.fullState || value == it.birdState }

    /** Which of the tier's three states [value] is, or null if the space is bare. */
    fun stateOf(value: Int): BirdHouseState? {
        val type = byVarpValue(value) ?: return null
        return when (value) {
            type.builtState -> BirdHouseState.Empty
            type.fullState -> BirdHouseState.Filling
            else -> BirdHouseState.Full
        }
    }

    private fun type(row: HunterBirdhouseTypesRow): BirdHouseType =
        BirdHouseType(
            obj = row.obj.internalName,
            hunterLevel = row.hunterLevel,
            hunterXp = row.hunterXp,
            craftingLevel = row.craftingLevel,
            craftingXp = row.craftingXp,
            logs = row.logs.internalName,
            nestPermille = row.nestPermille,
            builtState = stateOf(row.builtLoc.internalName),
            fullState = stateOf(row.fullLoc.internalName),
            birdState = stateOf(row.birdLoc.internalName),
        )

    private fun stateOf(child: String): Int {
        val state = children.indexOf(child.asRSCM(RSCMType.LOC))
        check(state >= 0) { "$child is not among the bird house space's multiloc children." }
        return state
    }
}
