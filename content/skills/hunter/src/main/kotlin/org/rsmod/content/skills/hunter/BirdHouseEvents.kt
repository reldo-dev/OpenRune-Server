package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpContentLoc1
import org.rsmod.api.script.onOpContentLoc2
import org.rsmod.api.script.onOpContentLoc3
import org.rsmod.api.script.onOpContentLoc4
import org.rsmod.api.script.onOpHeldU
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftQueueWithArgs
import org.rsmod.content.skills.Material
import org.rsmod.content.skills.SkillMultiConfig
import org.rsmod.content.skills.SkillMultiEntry
import org.rsmod.content.skills.SkillingActionType
import org.rsmod.content.skills.openSkillMulti
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Bird house trapping's player-facing ops - all read off the 28 packed children. One content
 * group, because the op *indices* line up across the states even though the labels do not (op1
 * Build/Interact, op2 Seeds, op3 Dismantle/Empty, op4 Reset); each handler dispatches on the
 * space's varp, the value the client rendered from. The nine `onOpHeldU` pairs are the technique's
 * entry point - nothing else in the game gives a bird house. See docs/hunter.md.
 */
class BirdHouseEvents @Inject constructor(private val birdHouse: HunterBirdHouse) : PluginScript() {
    override fun ScriptContext.startup() {
        // Forces the lazy space table, so a bird house gameval that does not resolve - a space loc,
        // its varp, or a child loc a tier row names - throws here rather than at whichever click
        // happens to touch it first. Every space is derived from the packed cache, so this is also
        // what proves the derivation ran at all.
        check(BirdHouseSpaces.all.size == BIRDHOUSE_SPACES) {
            "Expected $BIRDHOUSE_SPACES bird house spaces, resolved ${BirdHouseSpaces.all.size}."
        }

        // One `HeldU` pair per tier: a clockwork on that tier's logs. Nine registrations rather than
        // one, because the pair *is* the recipe - there is no ambiguity for a menu to resolve, and
        // `onOpHeldU`'s catch-all overload would put this handler in front of every other use of a
        // clockwork. No content group, interface or gameval is involved.
        for (type in BirdHouseTypes.all) {
            onOpHeldU(HunterBirdHouse.CLOCKWORK, type.logs) { craft(type) }
        }

        onOpContentLoc1(BIRD_HOUSE_GROUP) { op1(it.loc) }
        onOpContentLoc2(BIRD_HOUSE_GROUP) { op2(it.loc) }
        onOpContentLoc3(BIRD_HOUSE_GROUP) { op3(it.loc) }
        onOpContentLoc4(BIRD_HOUSE_GROUP) { op4(it.loc) }

        onPlayerSoftQueueWithArgs<Int>(BIRDHOUSE_FILL_QUEUE) {
            with(birdHouse) { player.birdHouseFillArrives(args) }
        }

        // The deadline is saved with the player and the queue is not, so the in-session half has to
        // be rebuilt on the way back in - and anything that matured while the player was away has to
        // be noticed. Both are the same call.
        onPlayerLogin { with(birdHouse) { player.rearmBirdHouseFills() } }
    }

    /**
     * A clockwork used on [type]'s logs. The menu exists for the *quantity*; refusals are sent
     * before it opens because `openSkillMulti` returns silently when nothing is affordable. The
     * whole run is made on the tick the amount is chosen (docs/hunter.md).
     */
    private suspend fun ProtectedAccess.craft(type: BirdHouseType) {
        val refusal = with(birdHouse) { birdHouseCraftRefusal(type) }
        if (refusal != null) {
            mes(refusal)
            return
        }
        val materials = listOf(Material(type.logs), Material(HunterBirdHouse.CLOCKWORK))
        val config =
            SkillMultiConfig(
                actionType = SkillingActionType.MAKE,
                verb = "make",
                entries = listOf(SkillMultiEntry(type.obj, materials)),
            )
        openSkillMulti(config) { selection ->
            with(birdHouse) {
                for (i in 0 until selection.amount) {
                    if (!craftBirdHouse(type)) {
                        break
                    }
                }
            }
        }
    }

    /** `Build` on a bare space, `Interact` on a full house. */
    private suspend fun ProtectedAccess.op1(loc: BoundLocInfo) {
        arriveDelay()
        matureBefore(loc)
        with(birdHouse) {
            if (BirdHouseTypes.stateOf(spaceState(loc)) == null) {
                buildBirdHouse(loc)
            } else {
                inspectBirdHouse(loc)
            }
        }
    }

    /** `Seeds`, on all three built states. The refusals live in the handler. */
    private suspend fun ProtectedAccess.op2(loc: BoundLocInfo) {
        arriveDelay()
        matureBefore(loc)
        with(birdHouse) { addBirdHouseSeeds(loc) }
    }

    /** `Dismantle` on a filling house, `Empty` on a full one. */
    private suspend fun ProtectedAccess.op3(loc: BoundLocInfo) {
        arriveDelay()
        matureBefore(loc)
        with(birdHouse) {
            when (BirdHouseTypes.stateOf(spaceState(loc))) {
                BirdHouseState.Filling -> dismantleBirdHouse(loc)
                BirdHouseState.Full -> emptyBirdHouse(loc)
                else -> inspectBirdHouse(loc)
            }
        }
    }

    /** `Reset` on a full house: the payout and a fresh house in one action. */
    private suspend fun ProtectedAccess.op4(loc: BoundLocInfo) {
        arriveDelay()
        matureBefore(loc)
        with(birdHouse) { emptyBirdHouse(loc, rebuild = true) }
    }

    /**
     * Matures the space if its fifty minutes elapsed while nothing was watching; runs after
     * [ProtectedAccess.arriveDelay], so a house that matures during the walk is already full.
     */
    private fun ProtectedAccess.matureBefore(loc: BoundLocInfo) {
        val space = BirdHouseSpaces.byLocId(loc.id) ?: return
        with(birdHouse) { player.matureBirdHouse(space) }
    }

    private fun ProtectedAccess.spaceState(loc: BoundLocInfo): Int {
        val space = BirdHouseSpaces.byLocId(loc.id) ?: return BirdHouseSpaces.BARE
        return with(birdHouse) { player.birdHouseState(space) }
    }

    private companion object {
        private const val BIRD_HOUSE_GROUP = "content.hunter_bird_house"
    }
}
