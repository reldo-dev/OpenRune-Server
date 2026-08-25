package org.rsmod.content.skills.hunter

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import jakarta.inject.Inject
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
import org.rsmod.map.CoordGrid

/** The controller type a falcon holding prey is anchored to. */
const val FALCON_CONTROLLER: String = "controller.hunter_falcon"

/**
 * The rented glove with no bird on it - what the player holds while the falcon is in the air or
 * sitting on a catch.
 */
const val FALCON_GLOVE: String = "obj.falcon_gloves"

/** The same glove with the falcon on it: `obj.falcon_on_gloves` (10024). */
const val FALCON_GLOVE_WITH_BIRD: String = "obj.falcon_on_gloves"

/**
 * What Matthias charges to rent the bird.
 *
 * "For a fee of 500 coins, hunters can rent a gyr falcon and a falconer's glove from the falcon
 * expert Matthias at the Piscatoris falconry area." (wiki, *Falconry*, oldid=14840978.)
 */
const val FALCONRY_RENTAL_FEE: Int = 500

/**
 * How long an unretrieved falcon sits on its catch before it gives up and flies back, in cycles.
 *
 * The wiki states the behaviour and the message but not the duration: "If the falcon is not retrieved
 * within a short time, the player will get the message, *Your falcon has left its prey. You see it
 * heading back toward the falconer.*" 100 cycles is ~1 minute at 0.6s/cycle - the same reading of "a
 * short time" that [TRAP_LIFETIME_CYCLES] uses, and by coincidence the same number. **The number is
 * unsourced; the fact that the catch is lost is not** - the same paragraph says "no experience is
 * given for the lost prey".
 */
const val FALCON_TIMEOUT_CYCLES: Int = 100

/**
 * How long the player is locked while the falcon flies, per tile of distance to the kebbit.
 *
 * Unsourced. The wiki says only that "Falcons travel at a definitive speed", which is what makes the
 * delay scale with distance at all rather than being flat; the tiles-per-cycle figure itself is not
 * recoverable offline and is not in the cache, because the flight is entirely server-side. One tile
 * per cycle is the conservative reading - it is the speed a walking entity moves - and it is the
 * whole of the proximity model: see [FALCON_MIN_FLIGHT_CYCLES] for why there is no rate term.
 */
const val FALCON_CYCLES_PER_TILE: Int = 1

/**
 * The floor on the flight delay, so a kebbit caught from an adjacent tile still costs a cycle.
 *
 * Unsourced, like [FALCON_CYCLES_PER_TILE]. Its purpose is that the roll never resolves on the same
 * cycle the op arrived, which would let a player at point-blank range catch on every input tick.
 *
 * Note what this pair is **not**: a success-rate modifier. "Although the success rate is supposedly
 * not affected by proximity, running up to the target before catching it may improve success rate"
 * (wiki, *Falconry*) - and the same paragraph explains the appearance as a timing artefact of flight
 * time, not a rate change. Distance therefore costs time here and nothing else; the published curves
 * are used exactly as charted, with no proximity term.
 */
const val FALCON_MIN_FLIGHT_CYCLES: Int = 1

private const val MAX_HUNTER_LEVEL: Int = 99

private const val COINS: String = "obj.coins"

/** Packed [PlayerUid] of whoever sent the falcon. */
var Controller.falconOwner: Int by intVarCon("varcon.hunter_falcon_owner")

/** Index into [FalconryCreatures.all] - which kebbit the falcon is sitting on. */
var Controller.falconCreature: Int by intVarCon("varcon.hunter_falcon_creature")

/**
 * Rent, catch, retrieve and time out - the whole of falconry.
 *
 * **This is not a trap, and deliberately shares no code with [HunterTrap].** It borrows that class's
 * two load-bearing ideas and nothing else: a [Controller] anchored at a coord as the unit of
 * ownership and lifetime, and a varcon on it as the only persisted state. What it does not borrow is
 * the [TrapFamily] enum, the trap cap, the laid-coord bookkeeping or any loc handling, because
 * falconry has no trap item, no cap and no loc.
 *
 * The controller is anchored at the **falcon's** tile, which is the kebbit's old tile. That tile is
 * the key for everything, exactly as a trap's tile is: the retrieve path finds the controller from
 * the npc it clicked, and the timeout finds the npc from the controller it is ticking.
 *
 * Ownership lives in [falconOwner] rather than an npc attribute. There is no npc-attribute system in
 * this engine, and inventing one for a single feature would be a much larger change than reusing the
 * controller that already has to exist for the timeout.
 */
class HunterFalconry
@Inject
constructor(
    private val conRepo: ControllerRepository,
    private val npcRepo: NpcRepository,
    private val playerList: PlayerList,
    // Named `gameRandom`, not `random` as [HunterTrap] names its own. Almost every function here is
    // a `ProtectedAccess` extension, and `ProtectedAccess` has a `random` property of its own: an
    // extension receiver's member wins over the dispatch receiver's field, so a field called
    // `random` would be silently shadowed at every use site. The catch roll would then draw from the
    // player's context RNG instead of the injected one - which compiles, runs, and makes the roll
    // untestable. `HunterTrap` is not exposed to this because its tick is a `Controller` extension.
    private val gameRandom: GameRandom,
    private val xpMods: XpModifiers,
) {
    /**
     * Hands over a glove with a falcon on it for [FALCONRY_RENTAL_FEE] coins.
     *
     * Wired to Matthias's `op3=Quick-falcon`, which is a real op on npc 1340 and not one invented
     * here - the client already draws it. His `op1=Talk-to` would reach the same place through a
     * dialogue tree, which is out of scope; the 500,000-coin permanent unlock lives behind that
     * dialogue and is out of scope with it, so every rental here is charged.
     *
     * @return false, with a message already sent, if the player already has a bird or cannot pay.
     */
    fun ProtectedAccess.rentFalcon(): Boolean {
        // Both states block, not just the loaded one: a player whose bird is out on a catch is
        // holding the empty glove, and renting a second would let them run two falcons at once.
        if (inv.contains(FALCON_GLOVE_WITH_BIRD) || inv.contains(FALCON_GLOVE)) {
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

        // Checked *after* the charge, not before: paying with an exact stack frees the slot the
        // glove is about to take, so testing up front would refuse a player who can in fact afford
        // it. If there is still no room the coins go straight back - a rental that takes the fee and
        // hands over nothing is the one outcome this must never produce.
        if (inv.freeSpace() < hunterInvSlotsNeeded(inv, FALCON_GLOVE_WITH_BIRD, 1)) {
            invAdd(inv, COINS, FALCONRY_RENTAL_FEE)
            mes("You don't have enough inventory space to carry the falconer's glove.")
            return false
        }

        invAdd(inv, FALCON_GLOVE_WITH_BIRD, 1)
        return true
    }

    /**
     * Sends the falcon at [target]: empties the glove, locks for the flight, then rolls.
     *
     * The catch is deliberately **not** re-locked afterwards, so a player may start on another
     * kebbit while an earlier catch still sits unretrieved. Each falcon carries its own controller
     * and its own timeout, so nothing about that needs coordinating.
     *
     * @return true only if the kebbit was caught; false covers a miss, a refusal and a target that
     *   left during the flight.
     */
    suspend fun ProtectedAccess.catchKebbit(target: Npc): Boolean {
        val internal = RSCM.getReverseMapping(RSCMType.NPC, target.visType.id) ?: return false
        val creature = FalconryCreatures.byNpc(internal) ?: return false

        // "If a player attempts to catch a spotted kebbit without a falcon, the kebbit will escape."
        // (wiki, *Spotted kebbit*.) The escape half is not modelled - despawning a kebbit because
        // someone clicked it empty-handed would let a player clear the enclosure for free.
        if (!inv.contains(FALCON_GLOVE_WITH_BIRD)) {
            mes("You need a falcon to catch a kebbit.")
            return false
        }

        // Gated before the roll, exactly as the trap tick gates its own, so an under-levelled
        // attempt never consumes a random draw. Falconry needs this explicitly for all three
        // creatures: none has a negative `successLow` to reproduce the refusal implicitly.
        if (player.hunterLvl < creature.level) {
            mes("You need a Hunter level of ${creature.level} to catch this kebbit.")
            return false
        }

        // The bird leaves the glove before the flight, not after the roll: the empty glove is what
        // stops a second catch being started mid-flight, and it has to be observable for the whole
        // time the falcon is away.
        if (!swapGlove(from = FALCON_GLOVE_WITH_BIRD, to = FALCON_GLOVE)) {
            return false
        }

        val tile = target.coords
        faceEntitySquare(target)
        delay(flightCycles(player.coords, tile))

        // The kebbit can wander off, be caught by someone else, or respawn during the flight.
        // Re-checked by identity and position rather than assumed, because the falcon is committed
        // by this point and the reward has to belong to the creature that was actually there.
        //
        // Presence, deliberately, and not `Npc.isValidTarget()`: that helper requires
        // `hitpoints > 0`, and no kebbit declares hitpoints in the cache at all - none of the three
        // is attackable. Using it here would fail every single catch.
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
            // A miss leaves the kebbit where it is - it is immediately huntable again. Despawning
            // it would be an invention: no source says a failed falconry catch removes the target,
            // and the cost of guessing wrong is a creature that vanishes on every miss.
            returnFalconToGlove()
            return false
        }

        npcRepo.despawn(target, target.visType.respawnRate)

        val falcon = spawnFalcon(creature, tile)
        val controller = Controller(FALCON_CONTROLLER, tile)
        conRepo.add(controller, FALCON_TIMEOUT_CYCLES)
        controller.falconOwner = player.uid.packed
        controller.falconCreature = FalconryCreatures.all.indexOf(creature)
        controller.aiTimer(1)

        check(falcon.coords == tile) { "Falcon spawned off its prey's tile." }
        return true
    }

    /**
     * `Retrieve` on a falcon sitting on its catch: loot, xp, and the bird back on the glove.
     *
     * **XP is awarded here, not at the catch**, which is what all five trap techniques do and what
     * the timeout depends on: a falcon that gave xp on landing would pay out for prey the player
     * never collected.
     *
     * The reward is read off the *falcon's own npc* rather than the controller's stored index. Both
     * agree, but the npc is the thing the player clicked, and OSRS giving each kebbit its own falcon
     * npc is what makes that possible at all.
     *
     * @return false, with a message already sent where one is warranted, if the falcon is not this
     *   player's or there is no room for what it holds.
     */
    fun ProtectedAccess.retrieveFalcon(falcon: Npc): Boolean {
        val internal = RSCM.getReverseMapping(RSCMType.NPC, falcon.visType.id) ?: return false
        val creature = FalconryCreatures.byFalconNpc(internal) ?: return false

        val controller = conRepo.findExact(falcon.coords, FALCON_CONTROLLER) ?: return false
        if (controller.falconOwner != player.uid.packed) {
            mes("This isn't your falcon.")
            return false
        }

        // Rolled once, up front, so the space check below and the awards further down agree on the
        // same numbers. Every falconry reward is a flat one today, so no draw is actually consumed -
        // the shape is kept because the column supports a range and a future creature may use one.
        val awards = creature.caught.map { it.obj to rollQuantity(it.quantity) }

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

        // The bird comes back whether or not the glove is still held: a player who somehow dropped
        // the empty glove mid-catch still gets their catch, they simply have nothing to put the
        // falcon on. Silently minting a loaded glove for them would duplicate a rental.
        returnFalconToGlove()

        // Creature xp is stored x10 so fractional values survive the table.
        val xp = (creature.xp / 10.0) * xpMods.get(player, "stat.hunter")
        statAdvance("stat.hunter", xp)

        npcRepo.del(falcon, Int.MAX_VALUE)
        conRepo.del(controller)
        return true
    }

    /**
     * One cycle of a falcon sitting on its prey. Re-arms itself every tick until the timeout lands.
     *
     * **Unlike a trap's tick, this does not end when the owner logs out.** A trap collapses without
     * its owner because its roll needs their live Hunter level; a falcon has already rolled, so it
     * has no such dependency - and a falcon that only expired while its owner was watching would sit
     * on the map until the next restart. That difference is the whole reason this is a separate tick
     * rather than a sixth branch of the trap's.
     *
     * The timeout is the catch being **lost**: "If the falcon is not retrieved within a short time...
     * no experience is given for the lost prey." (wiki, *Falconry*.) Nothing is awarded and nothing
     * is dropped - the bird leaves and the prey goes with it.
     */
    fun Controller.falconTick() {
        val falcon = falconAt(coords)
        if (falcon == null) {
            // The npc is gone but the controller is not - a retrieve that raced the tick, or a
            // despawn from outside this feature. Nothing to wait for.
            conRepo.del(this)
            return
        }

        // `duration` is the falcon's remaining patience. ControllerRepository deletes an expired
        // controller silently, which would strand the npc, so give up one cycle early instead -
        // exactly the reason the trap tick collapses at `duration <= 1`.
        if (duration <= 1) {
            npcRepo.del(falcon, Int.MAX_VALUE)
            // The bird flies back to Matthias rather than to the player's glove, so the player is
            // left holding the empty glove and has to go and ask for it back. The message is the
            // wiki's own wording, which is quotable because it is one of the few falconry strings
            // that reached a public page; the server text for it is otherwise unrecoverable offline.
            PlayerUid(falconOwner)
                .resolve(playerList)
                ?.mes(
                    "Your falcon has left its prey. You see it heading back toward the falconer.",
                    ChatType.GameMessage,
                )
            conRepo.del(this)
            return
        }

        aiTimer(1)
    }

    /**
     * Takes both glove states off a player leaving the enclosure.
     *
     * "As long as the player is inside the falconry area, they may talk with the Matthias to get
     * their falcon back without cost" (wiki, *Falconry*) - the glove is Matthias's property and does
     * not leave with you. Applied on walking out *and* on teleporting, because
     * `PlayerAreaProcessor` fires an area exit on any coord change that leaves the polygon and does
     * not care how the player got there.
     *
     * Deliberately **not** applied on logout; see [FalconryEvents] for the guard and why.
     *
     * @return true if anything was taken, so the caller can stay silent when there was nothing to
     *   take - an ordinary walk out of the area by someone who never rented a bird.
     */
    fun ProtectedAccess.stripFalconGloves(): Boolean {
        var stripped = false
        for (glove in FALCON_GLOVES) {
            val held = inv.count(glove)
            if (held > 0 && invDel(inv, glove, held).success) {
                stripped = true
            }
        }
        if (stripped) {
            mes("You return the falconer's glove as you leave.")
        }
        return stripped
    }

    /** A fixed quantity costs no random draw; only a real range consumes one. */
    private fun rollQuantity(quantity: IntRange): Int =
        if (quantity.first == quantity.last) quantity.first else gameRandom.of(quantity)

    /**
     * How long the player is locked while the falcon flies, floored at
     * [FALCON_MIN_FLIGHT_CYCLES].
     *
     * Chebyshev, matching every other distance in this feature and the engine's own movement model.
     */
    private fun flightCycles(from: CoordGrid, to: CoordGrid): Int {
        val distance = from.chebyshevDistance(to)
        return maxOf(FALCON_MIN_FLIGHT_CYCLES, distance * FALCON_CYCLES_PER_TILE)
    }

    /** Whether [npc] is still registered on [coords], by identity rather than by type. */
    private fun isStillThere(npc: Npc, coords: CoordGrid): Boolean =
        npcRepo.findAll(coords).any { it === npc }

    /** The falcon-with-prey standing on [coords], whichever of the three it is. */
    private fun falconAt(coords: CoordGrid): Npc? =
        npcRepo.findAll(coords).firstOrNull { npc ->
            val internal = RSCM.getReverseMapping(RSCMType.NPC, npc.visType.id)
            internal != null && FalconryCreatures.byFalconNpc(internal) != null
        }

    /**
     * Spawns [creature]'s own falcon on [coords] with no lifetime of its own.
     *
     * The controller owns the timeout, so the npc is added indefinitely and removed by whichever of
     * [falconTick] or [retrieveFalcon] gets there first. Giving the npc a duration as well would be
     * two clocks for one deadline, and the one that fired first would leave the other's cleanup
     * undone.
     */
    private fun spawnFalcon(creature: FalconryCreature, coords: CoordGrid): Npc {
        val type =
            ServerCacheManager.getNpc(creature.falconNpc.asRSCM(RSCMType.NPC))
                ?: error("Missing falcon npc type: ${creature.falconNpc}")
        val falcon = Npc(type, coords)
        npcRepo.add(falcon, Int.MAX_VALUE)
        return falcon
    }

    /** Puts the bird back on the glove after a miss or a retrieve. */
    private fun ProtectedAccess.returnFalconToGlove(): Boolean =
        swapGlove(from = FALCON_GLOVE, to = FALCON_GLOVE_WITH_BIRD)

    /**
     * Exchanges one glove state for the other, and never creates one out of nothing.
     *
     * The delete has to succeed before the add runs: the two states are the same item to the player,
     * so an add-then-delete ordering that failed halfway would leave them holding two gloves or
     * none, and the "already have a falcon" check in [rentFalcon] reads both.
     */
    private fun ProtectedAccess.swapGlove(from: String, to: String): Boolean {
        if (invDel(inv, from, 1).failure) {
            return false
        }
        invAdd(inv, to, 1)
        return true
    }

    private companion object {
        /** Both glove states, in the order the area-exit strip walks them. */
        private val FALCON_GLOVES: List<String> = listOf(FALCON_GLOVE, FALCON_GLOVE_WITH_BIRD)
    }
}
