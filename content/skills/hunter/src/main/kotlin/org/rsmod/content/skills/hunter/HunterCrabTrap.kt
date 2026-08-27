package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.constructionLvl
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.player.vars.VarPlayerIntMapSetter
import org.rsmod.api.random.GameRandom
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.loc.BoundLocInfo

const val CRAB_CATCH_QUEUE: String = "queue.hunter_crab_catch"

/**
 * Crab trapping: build, bait, wait, empty. A crab trap is not an object in the world - the whole
 * feature is a varbit write, so `locRepo` is never touched, traps are private per player, and
 * there is no roll ("players cannot fail to catch a crab"): [GameRandom] is drawn only to pick the
 * rainbow crab's colourway. Design notes: docs/hunter.md.
 */
class HunterCrabTrap
@Inject
constructor(
    // Named `gameRandom`, not `random`, for the reason [HunterButterfly] documents: a field called
    // `random` inside a `ProtectedAccess` extension is silently shadowed by the receiver's own.
    private val gameRandom: GameRandom,
    private val xpMods: XpModifiers,
) {
    // The deletes are unwound on failure rather than trusted: a half-built trap would charge the
    // player for a hole that is still a hole.
    fun ProtectedAccess.buildCrabTrap(loc: BoundLocInfo): Boolean {
        val site = CrabTrapSites.byLocId(loc.id) ?: return false
        val state = crabTrapState(site)
        if (state != site.unbuiltState) {
            mes("There is already a trap built here.")
            return false
        }

        if (player.constructionLvl < CRAB_TRAP_CONSTRUCTION_LEVEL) {
            mes(
                "You need a Construction level of $CRAB_TRAP_CONSTRUCTION_LEVEL to build a crab trap."
            )
            return false
        }

        if (CRAB_TRAP_SAWS.none(inv::contains) || !inv.contains(CRAB_TRAP_HAMMER)) {
            mes("You need a saw and a hammer to build a crab trap.")
            return false
        }

        // First-by-slot-order is ours, as [HunterTrap.setDeadfall]'s log pick is - but this one
        // also requires the stack to cover both nails, so a leftover bronze nail in slot one does
        // not refuse a build the steel stack below could pay for.
        val nails = inv.firstNotNullOfOrNull { obj -> obj?.let { nailObj(it) } }
        val materials =
            listOf(CRAB_TRAP_PLANK to 1, CRAB_TRAP_BUCKET to 1) +
                listOfNotNull(nails?.let { it to CRAB_TRAP_NAIL_COUNT })
        val missing =
            nails == null || materials.any { (obj, count) -> inv.count(obj) < count }
        if (missing) {
            mes("You need a plank, a bucket and $CRAB_TRAP_NAIL_COUNT nails to build a crab trap.")
            return false
        }

        val charged = mutableListOf<Pair<String, Int>>()
        for ((obj, count) in materials) {
            if (invDel(inv, obj, count).failure) {
                for ((refund, amount) in charged) {
                    invAdd(inv, refund, amount)
                }
                mes(
                    "You need a plank, a bucket and $CRAB_TRAP_NAIL_COUNT nails to build a crab trap."
                )
                return false
            }
            charged += obj to count
        }

        setCrabTrapState(site, site.builtState)
        mes("You build a crab trap over the hole.")
        return true
    }

    // The Hunter level is checked here, not at build time: building is level-free and only
    // *active* traps are capped. Which bait a trap takes is the site's choice, not the player's.
    fun ProtectedAccess.baitCrabTrap(loc: BoundLocInfo): Boolean {
        val site = CrabTrapSites.byLocId(loc.id) ?: return false
        val state = crabTrapState(site)
        if (state == site.unbuiltState) {
            mes("You need to build a trap here first.")
            return false
        }
        if (state != site.builtState) {
            mes("That trap is already baited.")
            return false
        }

        val creature = site.creature
        if (player.hunterLvl < creature.level) {
            mes("You need a Hunter level of ${creature.level} to use this trap.")
            return false
        }

        val cap = crabTrapCap(player.hunterLvl)
        if (player.activeCrabTraps() >= cap) {
            val plural = if (cap == 1) "trap" else "traps"
            mes("You can only have $cap active $plural at your Hunter level.")
            return false
        }

        if (invDel(inv, creature.bait, 1).failure) {
            val name = creature.bait.baitName()
            mes("You need some $name to bait this trap.")
            return false
        }

        setCrabTrapState(site, site.baitedState)

        // Soft, and on the player: there is nothing of a crab trap in the world to hang a timer
        // off, and the arrival must not interrupt whatever the player is doing when it lands.
        player.softQueue(CRAB_CATCH_QUEUE, creature.catchDelay, site.index)
        return true
    }

    // That emptying a baited trap returns the bait is unsourced - the recoverable half of that
    // uncertainty, as [HunterTrap.dismantleDeadfall] argues for the log (docs/hunter.md).
    fun ProtectedAccess.emptyCrabTrap(loc: BoundLocInfo): Boolean {
        val site = CrabTrapSites.byLocId(loc.id) ?: return false
        val state = crabTrapState(site)

        val variant = site.variantAt(state)
        if (variant == null) {
            if (state != site.baitedState) {
                mes("There is nothing in that trap.")
                return false
            }
            if (inv.freeSpace() < hunterInvSlotsNeeded(inv, site.creature.bait, 1)) {
                mes("Your inventory is too full to hold any more.")
                soundSynth("synth.pillory_wrong")
                return false
            }
            invAdd(inv, site.creature.bait, 1)
            setCrabTrapState(site, site.builtState)
            return true
        }

        if (inv.freeSpace() < hunterInvSlotsNeeded(inv, variant.caught, 1)) {
            mes("Your inventory is too full to hold any more.")
            soundSynth("synth.pillory_wrong")
            return false
        }

        invAdd(inv, variant.caught, 1)

        // Stored x10.
        val xp = (site.creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
        statAdvance("stat.hunter", xp)

        setCrabTrapState(site, site.builtState)
        return true
    }

    // Re-checked: the queue outlives the state that scheduled it, and a matured catch landing on
    // an emptied trap would mint a crab out of nothing. Not a [ProtectedAccess] extension - a crab
    // arriving is not a player action and must not wait for the player to be free.
    fun Player.crabTrapCatchArrives(siteIndex: Int) {
        val site = CrabTrapSites.all.getOrNull(siteIndex) ?: return
        if (crabTrapState(site) != site.baitedState) {
            return
        }

        // The only random draw in the whole technique, and only when there is a real choice.
        val variants = site.creature.variants
        val pick = if (variants.size == 1) 0 else gameRandom.of(variants.size)
        setCrabTrapState(site, site.fullStates[pick])
    }

    // The varbit is persisted with the player, the queue is not: without the re-arm a trap baited
    // just before logout comes back baited forever (docs/hunter.md).
    fun Player.rearmCrabTrapCatches() {
        for (site in CrabTrapSites.all) {
            if (crabTrapState(site) == site.baitedState) {
                softQueue(CRAB_CATCH_QUEUE, site.creature.catchDelay, site.index)
            }
        }
    }

    fun Player.activeCrabTraps(): Int =
        CrabTrapSites.all.count { it.isActive(crabTrapState(it)) }

    fun Player.crabTrapState(site: CrabTrapSite): Int = vars[site.varbit]

    private fun ProtectedAccess.crabTrapState(site: CrabTrapSite): Int =
        player.crabTrapState(site)

    private fun ProtectedAccess.setCrabTrapState(site: CrabTrapSite, state: Int) {
        player.setCrabTrapState(site, state)
    }

    // `VarPlayerIntMapSetter` rather than an `intVarBit` delegate: the varbit is resolved from
    // the loc at runtime, and twenty delegates could each be paired with the wrong loc.
    private fun Player.setCrabTrapState(site: CrabTrapSite, state: Int) {
        VarPlayerIntMapSetter.set(this, site.varbit, state)
    }

    private fun String.baitName(): String =
        ServerCacheManager.getItem(asRSCM(RSCMType.OBJ))?.name?.lowercase() ?: "bait"

    private fun ProtectedAccess.nailObj(held: InvObj): String? {
        val obj = nailObjsById[held.id] ?: return null
        return obj.takeIf { inv.count(it) >= CRAB_TRAP_NAIL_COUNT }
    }

    companion object {
        /** Twice sourced: the wiki and the cache's own `skill_feature` rows agree on 10. */
        const val CRAB_TRAP_CONSTRUCTION_LEVEL: Int = 10

        /** `name=Plank`; there is no `obj.plank`. */
        const val CRAB_TRAP_PLANK: String = "obj.woodplank"

        /** `name=Bucket`; the plain `obj.bucket` is a milk bucket. */
        const val CRAB_TRAP_BUCKET: String = "obj.bucket_empty"

        const val CRAB_TRAP_HAMMER: String = "obj.hammer"

        /** "two nails of any type for each trap." */
        const val CRAB_TRAP_NAIL_COUNT: Int = 2

        // The crystal and Amy's saws are unsourced for this technique specifically; accepting
        // them is the recoverable direction (docs/hunter.md).
        val CRAB_TRAP_SAWS: List<String> =
            listOf("obj.poh_saw", "obj.eyeglo_crystal_saw", "obj.wearable_saw")

        // `obj.any_nails` is deliberately absent: a display-only duplicate no player can hold.
        // `obj.nails` is the real steel nails.
        val CRAB_TRAP_NAILS: List<String> =
            listOf(
                "obj.nails_bronze",
                "obj.nails_iron",
                "obj.nails",
                "obj.nails_black",
                "obj.nails_mithril",
                "obj.nails_adamant",
                "obj.nails_rune",
                "obj.nails_dragon",
            )

        // Keyed id-to-symbol: the caller needs the symbol back, and `getReverseMapping` scans.
        private val nailObjsById: Map<Int, String> by lazy {
            CRAB_TRAP_NAILS.associateBy { it.asRSCM(RSCMType.OBJ) }
        }

        /**
         * A different published table from a different source that happens to agree with
         * [TrapLadder] everywhere reachable - deliberately not delegated (docs/hunter.md).
         */
        fun crabTrapCap(level: Int): Int =
            when {
                level >= 80 -> 5
                level >= 60 -> 4
                level >= 40 -> 3
                else -> 2
            }
    }
}
