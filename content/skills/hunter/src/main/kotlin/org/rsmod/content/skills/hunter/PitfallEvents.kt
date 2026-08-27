package org.rsmod.content.skills.hunter

import jakarta.inject.Inject
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onOpLoc1
import org.rsmod.api.script.onOpLoc2
import org.rsmod.api.script.onOpLoc3
import org.rsmod.api.script.onOpNpc1
import org.rsmod.api.script.onPlayerLogin
import org.rsmod.api.script.onPlayerSoftQueue
import org.rsmod.game.loc.BoundLocInfo
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Pitfall trapping's player-facing ops, its cycle hook and its login rebuild - all ops present on
 * the cache types, registered on the `multiloc` children (the base locs carry none). The
 * `LateCycle` hook is load-bearing: without it no chase ever ends and no collapse ever lands.
 * See docs/hunter.md.
 */
class PitfallEvents @Inject constructor(private val pitfall: HunterPitfall) : PluginScript() {
    override fun ScriptContext.startup() {
        // Forces the lazy base-loc id map, so a mistyped `hunting_pitfall_<n>` throws here rather
        // than at whichever click happens to reach it first. The twenty-five literal rows in
        // [PitfallSites] touch `RSCM` nowhere else, so without this the only thing proving they all
        // resolve is a player walking up to each pit in turn.
        check(PitfallSites.baseLocIds.distinct().size == PITFALL_SITES) {
            "Expected $PITFALL_SITES distinct pitfall site locs, resolved " +
                "${PitfallSites.baseLocIds.distinct().size}."
        }

        // The one registration in this class that is not a click, and the one that fails silently.
        // `HunterPitfall.tick` is the whole of the chase leash and the whole of the collapse
        // landing; without this line a teased creature follows its hunter across the world forever
        // and no catch ever lands, with every unit test in the module still green.
        onEvent<GameLifecycle.LateCycle> { pitfall.tick() }

        onOpLoc3(PitfallSites.EMPTY_LOC) { trap(it.loc) }
        onOpLoc1(PitfallSites.SET_LOC) { jump(it.loc) }

        // One handler across the spiked pit and all seven full renderings: `Dismantle` means "take
        // the armed pit apart" on one and "collect the catch" on the others, and
        // `HunterPitfall.dismantlePit` branches on the varbit rather than on which loc it was
        // handed. Driven off the table so a child added to the family joins on its own.
        for (loc in PitfallSites.dismantleLocs) {
            onOpLoc2(loc) { dismantle(it.loc) }
        }

        // `op1=Tease` already exists on all five creature npcs. Registered by npc rather than by
        // content group for the reason [ImplingEvents] gives: a group would need a
        // `[gamevals.content]` id *and* a `contentGroup` on each npc in
        // `.data/raw-cache/server/npcs.toml`, and five explicit registrations for five npcs are
        // both shorter and impossible to half-declare.
        for (creature in PitfallCreatures.all) {
            onOpNpc1(creature.npc) { with(pitfall) { teaseCreature(it.npc) } }
        }

        // A pit stranded mid-collapse by a logout is the one piece of pitfall state that cannot fix
        // itself: the varbit persists and the ledger entry does not. The rebuild rides a soft queue
        // rather than running from the login event, because a varbit written while
        // `processedMapClock == 0` updates the server and leaves the client drawing the old frame.
        // See [PITFALL_REBUILD_QUEUE] and [HunterPitfall.rebuildPits].
        onPlayerLogin { player.softQueue(PITFALL_REBUILD_QUEUE, 1) }
        onPlayerSoftQueue(PITFALL_REBUILD_QUEUE) { pitfall.rebuildPits(player) }

        // No `onPlayerLogout`. [HunterTracking] needs one because it holds an
        // `IdentityHashMap<Player, TrailState>` - a strong reference that would retain every player
        // who ever logged out mid-trail. Nothing here keeps a `Player`: `chases` and `lastVaulted`
        // are keyed by `Npc` and hold a `PlayerUid`, `collapses` holds a `PlayerUid`, and
        // `HunterPitfall.tick` drops both a chase whose teaser no longer resolves and a collapse
        // whose owner no longer resolves on the very next cycle. A logout hook would duplicate a
        // sweep that already runs.
    }

    /** `Trap` on an empty pit: a log and a knife become a spiked pit. */
    private suspend fun ProtectedAccess.trap(loc: BoundLocInfo) {
        arriveDelay()
        val site = site(loc) ?: return
        with(pitfall) { trapPit(site) }
    }

    /** `Jump` on a spiked pit: vault it, and whatever is chasing you may go in. */
    private suspend fun ProtectedAccess.jump(loc: BoundLocInfo) {
        arriveDelay()
        val site = site(loc) ?: return
        with(pitfall) { jumpPit(site) }
    }

    /** `Dismantle` on a spiked pit or a full one - two transactions on one op index. */
    private suspend fun ProtectedAccess.dismantle(loc: BoundLocInfo) {
        arriveDelay()
        val site = site(loc) ?: return
        with(pitfall) { dismantlePit(site) }
    }

    /**
     * The site a click landed on, or null - with a message already sent - if it landed on nothing
     * this feature knows.
     *
     * Unreachable in practice: every loc registered above is a `multiloc` child of one of the
     * twenty-five sites and nothing else, and the startup check proves all twenty-five resolve. It
     * is here because the alternative to a message is a click that does nothing for no stated
     * reason, which is [CrabTrapEvents]' call in the same position.
     */
    private fun ProtectedAccess.site(loc: BoundLocInfo): PitfallSite? {
        val site = PitfallSites.byLocId(loc.id)
        if (site == null) {
            mes("Nothing interesting happens.")
        }
        return site
    }

    private companion object {
        /** Six kyatt, five larupia, five graahk, five sunlight and four moonlight. */
        private const val PITFALL_SITES: Int = 25
    }
}
