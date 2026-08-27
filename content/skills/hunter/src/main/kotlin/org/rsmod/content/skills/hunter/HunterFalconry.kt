package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
import java.util.IdentityHashMap
import org.rsmod.api.controller.vars.intVarCon
import org.rsmod.api.player.output.ChatType
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.controller.ControllerRepository
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.utils.skills.SkillingSuccessRate
import org.rsmod.game.entity.Controller
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.PlayerList
import org.rsmod.game.entity.player.PlayerUid
import org.rsmod.game.inv.Inventory
import org.rsmod.game.inv.isType
import org.rsmod.map.CoordGrid

/** The controller type a falcon holding prey is anchored to. */
const val FALCON_CONTROLLER: String = "controller.hunter_falcon"

const val FALCON_GLOVE: String = "obj.falcon_gloves"

const val FALCON_GLOVE_WITH_BIRD: String = "obj.falcon_on_gloves"

/** "For a fee of 500 coins" (wiki, *Falconry*, oldid=14840978). */
const val FALCONRY_RENTAL_FEE: Int = 500

/**
 * How long an unretrieved falcon sits on its catch before flying back. The behaviour and the lost
 * catch are sourced; the duration is not (docs/hunter.md).
 */
const val FALCON_TIMEOUT_CYCLES: Int = 100

/** Unsourced; walking speed as the conservative reading. Distance costs time, never rate. */
const val FALCON_CYCLES_PER_TILE: Int = 1

/** The roll must never resolve on the same cycle the op arrived. */
const val FALCON_MIN_FLIGHT_CYCLES: Int = 1

private const val COINS: String = "obj.coins"

var Controller.falconOwner: Int by intVarCon("varcon.hunter_falcon_owner")

/**
 * Rent, catch, retrieve and time out - the whole of falconry. Not a trap: it shares no code with
 * [HunterTrap], borrowing only the controller-plus-varcon idea. The bird and its controller are
 * paired by identity ([FalconLinks]), never by tile. Design notes: docs/hunter.md.
 */
class HunterFalconry
@Inject
constructor(
    private val conRepo: ControllerRepository,
    private val npcRepo: NpcRepository,
    private val playerList: PlayerList,
    // Named `gameRandom`, not `random`: the `ProtectedAccess` receiver has a `random` of its own
    // that silently shadows a field of that name at every use site.
    private val gameRandom: GameRandom,
    private val xpMods: XpModifiers,
) {
    private val falcons = FalconLinks()

    // The Talk-to dialogue tree and the 500k permanent unlock behind it are out of scope
    // (docs/hunter.md), so every rental is charged.
    fun ProtectedAccess.rentFalcon(): Boolean {
        // Both states block: a player whose bird is out on a catch holds the empty glove, and
        // renting a second would run two falcons at once.
        if (holdsGlove(FALCON_GLOVE_WITH_BIRD) || holdsGlove(FALCON_GLOVE)) {
            mes("You already have a falcon.")
            return false
        }

        if (inv.count(COINS) < FALCONRY_RENTAL_FEE) {
            mes("You need $FALCONRY_RENTAL_FEE coins to rent a falcon.")
            return false
        }

        if (invDel(inv, COINS, FALCONRY_RENTAL_FEE).failure) {
            mes("You need $FALCONRY_RENTAL_FEE coins to rent a falcon.")
            return false
        }

        // Checked *after* the charge: an exact coin stack frees the slot the glove takes. If there
        // is still no room the fee goes straight back.
        if (inv.freeSpace() < hunterInvSlotsNeeded(inv, FALCON_GLOVE_WITH_BIRD, 1)) {
            invAdd(inv, COINS, FALCONRY_RENTAL_FEE)
            mes("You don't have enough inventory space to carry the falconer's glove.")
            return false
        }

        invAdd(inv, FALCON_GLOVE_WITH_BIRD, 1)
        return true
    }

    // Deliberately not re-locked after the roll: a player may start on another kebbit while an
    // earlier catch sits unretrieved - each falcon carries its own controller and timeout.
    suspend fun ProtectedAccess.catchKebbit(target: Npc): Boolean {
        val creature = FalconryCreatures.byNpcId(target.visType.id) ?: return false

        // The wiki's empty-handed "kebbit will escape" is not modelled: despawning on a gloveless
        // click would let a player clear the enclosure for free.
        if (!holdsGlove(FALCON_GLOVE_WITH_BIRD)) {
            mes("You need a falcon to catch a kebbit.")
            return false
        }

        // Gated before the roll so an under-levelled attempt never consumes a draw; no falconry
        // creature has a negative `successLow` to reproduce the refusal implicitly.
        if (player.hunterLvl < creature.level) {
            mes("You need a Hunter level of ${creature.level} to catch this kebbit.")
            return false
        }

        // The bird leaves the glove before the flight: the empty glove is what stops a second
        // catch being started mid-flight.
        if (!swapGlove(from = FALCON_GLOVE_WITH_BIRD, to = FALCON_GLOVE)) {
            return false
        }

        val tile = target.coords
        faceEntitySquare(target)
        delay(flightCycles(player.coords, tile))

        // Re-checked by identity and position - the kebbit can leave during the flight. Presence,
        // deliberately not `isValidTarget()`: that requires `hitpoints > 0`, which no kebbit
        // declares, so it would fail every catch.
        if (!target.isVisible || target.coords != tile || !isStillThere(target, tile)) {
            returnFalconToGlove()
            mes("The kebbit got away.")
            return false
        }

        val caught =
            SkillingSuccessRate.successRate(
                low = creature.successLow,
                high = creature.successHigh,
                level = player.hunterLvl,
                maxLevel = MAX_HUNTER_LEVEL,
            ) > gameRandom.randomDouble()

        if (!caught) {
            // A miss leaves the kebbit where it is; despawning it would be an invention.
            returnFalconToGlove()
            return false
        }

        npcRepo.despawn(target, target.visType.respawnRate)

        val falcon = spawnFalcon(creature, tile)
        check(falcon.coords == tile) { "Falcon spawned off its prey's tile." }
        anchorFalcon(falcon, player.uid.packed)
        return true
    }

    /**
     * Pairs [falcon] with the controller that owns its timeout. Which kebbit is underneath is not
     * recorded and must not be: the per-kebbit falcon npc already says it, and a second copy could
     * only disagree. `internal` for the test world; [catchKebbit] is the only server caller.
     */
    internal fun anchorFalcon(falcon: Npc, ownerUid: Int): Controller {
        val controller = Controller(FALCON_CONTROLLER, falcon.coords)
        conRepo.add(controller, FALCON_TIMEOUT_CYCLES)
        controller.falconOwner = ownerUid
        controller.aiTimer(1)
        falcons.link(controller, falcon)
        return controller
    }

    // XP is awarded here, not at the catch - the timeout depends on it: a falcon that paid on
    // landing would pay for prey the player never collected.
    fun ProtectedAccess.retrieveFalcon(falcon: Npc): Boolean {
        val creature = FalconryCreatures.byFalconNpcId(falcon.visType.id) ?: return false

        val controller = falcons.controller(falcon) ?: return false
        if (controller.falconOwner != player.uid.packed) {
            mes("This isn't your falcon.")
            return false
        }

        // Rolled once, up front, so the space check and the awards agree on the same numbers.
        val awards = creature.caught.map { it.obj to rollQuantity(gameRandom, it.quantity) }

        // The glove swap costs nothing net - one out, one in - so only the catch needs room.
        val slotsNeeded = awards.sumOf { (obj, count) -> hunterInvSlotsNeeded(inv, obj, count) }
        if (inv.freeSpace() < slotsNeeded) {
            mes("Your inventory is too full to hold any more.")
            soundSynth("synth.pillory_wrong")
            return false
        }

        for ((obj, count) in awards) {
            invAdd(inv, obj, count)
        }

        // A player who dropped the empty glove mid-catch still gets their catch; silently minting
        // a loaded glove would duplicate a rental.
        returnFalconToGlove()

        // Stored x10.
        val xp = (creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
        statAdvance("stat.hunter", xp)

        npcRepo.del(falcon, Int.MAX_VALUE)
        endFalcon(controller)
        return true
    }

    // Unlike a trap's tick this survives the owner logging out: a falcon has already rolled, so
    // it has no live-level dependency. The timeout is the catch being *lost* (docs/hunter.md).
    fun Controller.falconTick() {
        val falcon = falcons.falcon(this)?.takeIf(Npc::isSlotAssigned)
        if (falcon == null) {
            // The link is by identity, so this branch means the npc really was unregistered - not
            // merely a bird that walked off its prey's tile.
            endFalcon(this)
            return
        }

        // ControllerRepository deletes an expired controller silently, which would strand the
        // npc, so give up one cycle early - as the trap tick does.
        if (duration <= 1) {
            npcRepo.del(falcon, Int.MAX_VALUE)
            // The bird flies back to Matthias, not to the glove; the message is the wiki's own
            // wording.
            PlayerUid(falconOwner)
                .resolve(playerList)
                ?.mes(
                    "Your falcon has left its prey. You see it heading back toward the falconer.",
                    ChatType.GameMessage,
                )
            endFalcon(this)
            return
        }

        aiTimer(1)
    }

    /** Ends a catch: the controller goes, and its link with the bird goes with it. */
    private fun endFalcon(controller: Controller) {
        falcons.unlink(controller)
        conRepo.del(controller)
    }

    // The glove is Matthias's property and does not leave the enclosure; walking out and
    // teleporting both land here. NOT applied on logout - see [FalconryEvents].
    fun ProtectedAccess.stripFalconGloves(): Boolean {
        var stripped = false
        var unequipped = false
        for (source in listOf(inv, worn)) {
            for (glove in FALCON_GLOVES) {
                val held = source.count(glove)
                if (held > 0 && invDel(source, glove, held).success) {
                    stripped = true
                    unequipped = unequipped || source === worn
                }
            }
        }
        if (unequipped) {
            rebuildAppearance()
        }
        if (stripped) {
            mes("You return the falconer's glove as you leave.")
        }
        return stripped
    }

    private fun flightCycles(from: CoordGrid, to: CoordGrid): Int {
        val distance = from.chebyshevDistance(to)
        return maxOf(FALCON_MIN_FLIGHT_CYCLES, distance * FALCON_CYCLES_PER_TILE)
    }

    private fun isStillThere(npc: Npc, coords: CoordGrid): Boolean =
        npcRepo.findAll(coords).any { it === npc }

    // The controller owns the timeout; a duration on the npc as well would be two clocks for one
    // deadline, and the one that fired first would leave the other's cleanup undone.
    private fun spawnFalcon(creature: FalconryCreature, coords: CoordGrid): Npc {
        val type =
            ServerCacheManager.getNpc(creature.falconNpc.asRSCM(RSCMType.NPC))
                ?: error("Missing falcon npc type: ${creature.falconNpc}")
        val falcon = Npc(type, coords)
        npcRepo.add(falcon, Int.MAX_VALUE)
        return falcon
    }

    private fun ProtectedAccess.returnFalconToGlove(): Boolean =
        swapGlove(from = FALCON_GLOVE, to = FALCON_GLOVE_WITH_BIRD)

    // Delete before add, and back into the same slot, so a worn glove transforms on the hand
    // instead of falling into the backpack (the two objs share a `wearpos`).
    private fun ProtectedAccess.swapGlove(from: String, to: String): Boolean {
        val held = gloveInv(from) ?: return false
        val slot = held.indices.firstOrNull { held[it].isType(from) } ?: return false
        if (invDel(held, from, 1, slot = slot).failure) {
            return false
        }
        invAdd(held, to, 1, slot = slot)
        if (held === worn) {
            rebuildAppearance()
        }
        return true
    }

    // The glove is equipment (both objs are `iop2=Wear`), so the worn slot counts: reading `inv`
    // alone tells a player they have no falcon while one sits on their hand. `inv` first, so a
    // player carrying one glove and wearing the other spends the carried one.
    private fun ProtectedAccess.gloveInv(glove: String): Inventory? =
        when {
            inv.contains(glove) -> inv
            worn.contains(glove) -> worn
            else -> null
        }

    private fun ProtectedAccess.holdsGlove(glove: String): Boolean = gloveInv(glove) != null

    private companion object {
        private val FALCON_GLOVES: List<String> = listOf(FALCON_GLOVE, FALCON_GLOVE_WITH_BIRD)
    }
}

/**
 * Which bird belongs to which controller, paired by **identity**: falcon npcs wander by default,
 * and a tile-keyed lookup silently voids the catch the moment the bird steps off it
 * (docs/hunter.md). Not persisted - both halves are runtime objects a restart takes with it.
 */
private class FalconLinks {
    private val byController = IdentityHashMap<Controller, Npc>()
    private val byFalcon = IdentityHashMap<Npc, Controller>()

    fun link(controller: Controller, falcon: Npc) {
        byController[controller] = falcon
        byFalcon[falcon] = controller
    }

    fun falcon(controller: Controller): Npc? = byController[controller]

    fun controller(falcon: Npc): Controller? = byFalcon[falcon]

    fun unlink(controller: Controller) {
        val falcon = byController.remove(controller) ?: return
        byFalcon.remove(falcon)
    }
}
