package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import org.rsmod.api.table.hunter.HunterBirdhouseTypesRow

/**
 * One tier of bird house.
 *
 * [builtState], [fullState] and [birdState] are this tier's positions in the space's multiloc chain,
 * and they are **read off the packed loc rather than computed**. The arithmetic
 * `tier = (varp - 1) / 3` holds today and the test asserts it, but deriving the indices from the
 * cache means a chain that is ever reordered moves this table with it instead of silently rendering
 * the wrong bird house - the same reason [CrabTrapSites] derives its own states.
 */
data class BirdHouseType(
    val obj: String,
    val hunterLevel: Int,
    val hunterXp: Int,
    val craftingLevel: Int,
    val craftingXp: Int,
    val logs: String,
    val builtLoc: String,
    val fullLoc: String,
    val birdLoc: String,
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
 * The nine tiers a [BirdHouseSpace] can hold, and where each one sits in the space's multiloc chain.
 *
 * A space is a multiloc over a **varp** holding `0..27`: index 0 is the bare space and the rest are
 * three states for each of nine tiers. That the state is a varp and not a varbit is the whole reason
 * this technique needs almost no server-authored state - see [BirdHouseSpaces].
 *
 * The chain is read from the first space, because all four carry byte-identical `multiLoc` lists;
 * `BirdHouseTablesTest` asserts that rather than assuming it.
 */
object BirdHouseTypes {
    val all: List<BirdHouseType> by lazy {
        HunterBirdhouseTypesRow.all().sortedBy(HunterBirdhouseTypesRow::rowId).map(::type)
    }

    /**
     * The multiloc children of a space, masked the way `LocInteractions.multiLoc` masks them.
     *
     * 29 entries, not 28: indices 0..27 are the real children and index 28 is the `65535` "no loc"
     * sentinel. Nothing here indexes past 27, and [stateOf] finds a child by searching rather than
     * by arithmetic, so the tail is harmless - but it is why a length check against 28 would fail.
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

    private fun type(row: HunterBirdhouseTypesRow): BirdHouseType {
        val built = row.builtLoc.internalName
        val full = row.fullLoc.internalName
        val bird = row.birdLoc.internalName
        return BirdHouseType(
            obj = row.obj.internalName,
            hunterLevel = row.hunterLevel,
            hunterXp = row.hunterXp,
            craftingLevel = row.craftingLevel,
            craftingXp = row.craftingXp,
            logs = row.logs.internalName,
            builtLoc = built,
            fullLoc = full,
            birdLoc = bird,
            nestPermille = row.nestPermille,
            builtState = stateOf(built),
            fullState = stateOf(full),
            birdState = stateOf(bird),
        )
    }

    private fun stateOf(child: String): Int {
        val state = children.indexOf(child.asRSCM(RSCMType.LOC))
        check(state >= 0) { "$child is not among the bird house space's multiloc children." }
        return state
    }
}
