package org.rsmod.content.skills.hunter

import dtx.core.ArgMap
import dtx.core.RollResult
import dtx.core.flatten
import dtx.rs.RSDropTable
import jakarta.inject.Inject
import org.rsmod.api.droptable.DropRollItem
import org.rsmod.api.droptable.rollCount
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.stat.hunterLvl
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.npc.NpcRepository
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.api.utils.skills.SkillingSuccessRate
import org.rsmod.game.entity.Npc
import org.rsmod.game.entity.Player

/**
 * The empty jar a catch consumes, `obj.ii_impling_jar` (11260), `name=Impling jar`.
 *
 * One per impling kept. Mandatory inside Puro-Puro and optional everywhere else, which is the one
 * rule this technique has that butterfly netting does not; see [HunterImpling.catchImpling].
 */
const val IMPLING_JAR: String = "obj.ii_impling_jar"

/** One in this many jar-opens destroys the empty jar instead of returning it. */
private const val JAR_BREAK_CHANCE: Int = 10

/**
 * Catching implings, which is butterfly netting with one extra rule.
 *
 * The two techniques share the nets ([BUTTERFLY_NET], [MAGIC_BUTTERFLY_NET]), the barehanded level
 * cost ([HunterButterfly.BAREHANDED_LEVELS]), the faster curve
 * ([HunterButterfly.NET_BONUS]) and the swap-one-jar-for-another reward, so this is a sibling of
 * [HunterButterfly] rather than of [HunterTrap]: nothing is laid, nothing is transformed, nothing is
 * spawned and nothing is remembered, and there is no loc, no [TrapFamily] entry, no controller, no
 * varcon and no trap cap.
 *
 * The one rule that is not butterfly netting's, from *Puro-Puro* (oldid=15196042): "Unlike elsewhere
 * on Gielinor, impling jars must be used when catching implings in Puro-Puro." Read the "unlike
 * elsewhere" half as carefully as the rest, because it is half the rule. *Baby impling*
 * (oldid=15297388) states both sides: "In Puro-Puro, empty impling jars are required to catch any
 * implings, whether catching them by net or by hand," and "In Gielinor, they can also be caught
 * barehanded or while wielding any item, with or without an empty impling jar. Without an empty jar
 * the player will immediately receive the loot from the impling, instead of the impling itself."
 * *Impling* (oldid=15303398) agrees in one sentence: "Implings caught without an impling jar will
 * be looted immediately."
 *
 * So a jarless attempt is **refused inside Puro-Puro** and is a **legal catch that pays its loot
 * straight into the inventory** anywhere else - which includes every overworld spawn and the
 * Prifddinas crystal impling, a creature that can never be caught in Puro-Puro at all.
 *
 * **Where the player is standing decides the jar; the npc id decides the experience.** The two are
 * different questions and neither answers the other. Every row in [ImplingCreatures] carries both
 * of its creature's npc ids, and the id that was caught is what [ImplingCreature.experienceFor]
 * keys on, because the wiki ties the experience to the spawn's origin - an overworld impling can
 * spawn inside Puro-Puro, so a location check would give the wrong experience and the npc id would
 * give the wrong jar rule.
 *
 * ## What is not modelled
 *
 * **Imp defenders**, the Puro-Puro retaliation, and **crop circles**: they are their own features,
 * not a branch of this one.
 */
class HunterImpling
@Inject
constructor(
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val spawner: ImplingSpawner,
    // Named `gameRandom`, not `random`, for the reason [HunterButterfly] spells out at its own
    // constructor: `ProtectedAccess` has a `random` property and an extension receiver's member
    // wins over the dispatch receiver's field, so a field called `random` here would be silently
    // shadowed at every use site and the catch roll would draw from the player's context RNG.
    private val gameRandom: GameRandom,
    private val xpMods: XpModifiers,
) {
    /**
     * `Catch` on an impling: gate on level, gate on the jar if this is Puro-Puro, then roll once.
     *
     * Both gates are ahead of the roll and ahead of the animation. The level gate is there for the
     * reason [HunterButterfly.catchButterfly] and the trap tick have theirs - so an under-levelled
     * attempt never consumes a random draw - and the jar gate joins it because inside Puro-Puro a
     * jarless attempt is not a catch at all, so swinging at one and rolling for it would both
     * mislead the player and burn a draw on an outcome that was already decided.
     *
     * **Outside Puro-Puro there is no jar gate**, and the catch is not the same catch: a player
     * carrying an empty jar keeps the impling, and a player without one gets the impling's loot
     * instead, rolled off the very table its jar would have carried. See [lootBarehanded].
     *
     * No delay and no lock, exactly as butterfly netting has none: a net swing lands on the tile the
     * player is already standing next to, and no source describes a wait.
     *
     * @return true if the impling was caught, whether it was jarred or looted on the spot; false
     *   covers a miss, an under-levelled attempt, a jarless attempt inside Puro-Puro, and an npc
     *   that is not a shipped impling at all.
     */
    fun ProtectedAccess.catchImpling(target: Npc): Boolean {
        val creature = ImplingCreatures.byNpcId(target.visType.id) ?: return false

        val barehanded = !isHoldingNet()
        val required = creature.level + if (barehanded) HunterButterfly.BAREHANDED_LEVELS else 0

        if (player.hunterLvl < required) {
            val how = if (barehanded) " barehanded" else ""
            mes("You need a Hunter level of $required to catch this$how.")
            return false
        }

        // Read once, before anything can change it, because it decides both the refusal below and
        // which reward the catch pays out.
        val jarred = inv.contains(IMPLING_JAR)

        // Refused before the swing and before the draw, and only here: "Unlike elsewhere on
        // Gielinor, impling jars must be used when catching implings in Puro-Puro." The live server
        // string is not recoverable offline, so this says what the wiki says rather than guessing
        // at Jagex's wording.
        if (!jarred && player.coords.inPuroPuro()) {
            mes("You need an empty impling jar to catch implings here.")
            return false
        }

        faceEntitySquare(target)
        if (!barehanded) {
            anim(BUTTERFLY_NET_SWING)
        }

        val bonus = if (usesFasterCurve()) HunterButterfly.NET_BONUS else 0
        val caught =
            SkillingSuccessRate.successRate(
                low = creature.successLow + bonus,
                high = creature.successHigh + bonus,
                level = player.hunterLvl,
                maxLevel = MAX_HUNTER_LEVEL,
            ) > gameRandom.randomDouble()

        if (!caught) {
            // A miss leaves the impling where it is; it is not consumed by being tried for.
            mes("You fail to catch the impling.")
            return false
        }

        // Paid *before* the creature is removed, so a swap that somehow fails leaves the impling on
        // the map rather than deleting it for nothing. Reaching the `false` branch means the delete
        // failed with the jar present, which nothing here can produce - but if it ever does, the
        // impling has to survive it, because the alternative is a despawn that paid out nothing.
        if (jarred) {
            if (!jarCatch(creature)) {
                return false
            }
        } else {
            lootBarehanded(creature)
        }

        // A spawner-made impling is removed outright and its marker starts again; a map-placed one
        // is despawned so the engine returns it to its own tile. Getting this the wrong way round
        // does not fail visibly - it just pins a marker to one creature forever.
        if (!spawner.release(target)) {
            npcRepo.despawn(target, target.visType.respawnRate)
        }

        // Which of the creature's two experience values applies is decided by the npc id that was
        // caught, because the wiki ties it to the spawn's origin rather than the player's location -
        // an overworld impling caught inside Puro-Puro still pays the overworld value. Stored x10 so
        // fractional values survive the table, and awarded on the catch, not on collection.
        val xp = (creature.experienceFor(target.visType.id) / 10.0) * xpMods.get(player, "stat.hunter")
        statAdvance("stat.hunter", xp)
        return true
    }

    /**
     * `Loot` on a filled jar: take the jar, roll its table, and usually hand the empty one back.
     *
     * Separate from [catchImpling] because the jar is a tradeable item - the player who opens one
     * need not be the player who caught it, need not be in Puro-Puro, and need not have any Hunter
     * level at all. So there is no level gate here and there must not be one.
     *
     * The jar is consumed **before** the roll, so a table that somehow rolled nothing still costs
     * the jar rather than looping. Rewards go through `invAddOrDrop`: a jar can pay out several
     * items where only one slot was freed, and the wiki describes loot falling to the floor rather
     * than the open being refused.
     *
     * @return false only if the obj is not one of the jars this slice ships.
     */
    fun ProtectedAccess.openJar(jar: String): Boolean {
        val table = ImplingLoot.forJar(jar) ?: return false
        if (invDel(inv, jar, 1).failure) {
            return false
        }

        rollTable(table)

        // "An empty impling jar is returned when removing the loot, with a 10% chance of it
        // breaking." Sourced to Mod Ash rather than to Jagex's published table - `Baby impling jar`
        // oldid=15185112 ref 1, "10% flat rate, I think" - which is why it is the one number in this
        // feature carrying a hedge. It applies to every jar and is independent of what was rolled.
        if (gameRandom.randomBoolean(JAR_BREAK_CHANCE)) {
            mes("You break the jar as you open it.")
        } else {
            invAddOrDrop(objRepo, IMPLING_JAR, 1)
        }
        return true
    }

    /**
     * The loot a jarless catch pays out, which is the loot its jar would have carried.
     *
     * "Without an empty jar the player will immediately receive the loot from the impling, instead
     * of the impling itself" (*Baby impling*, oldid=15297388). There is no second table for this:
     * the same [ImplingLoot] row the jar opens is the one that pays. Reachable only outside
     * Puro-Puro, because [catchImpling] has already refused a jarless attempt inside it.
     *
     * The lucky impling pays nothing here, and that is the same disclosed gap its jar carries: its
     * published loot is a clue-reward roll, [ImplingLoot] ships no table for it, and this server has
     * no clue rewards to roll. A missing table is silence, not a failed catch - the impling is still
     * caught and still pays its experience, exactly as a jarred lucky catch does.
     */
    private fun ProtectedAccess.lootBarehanded(creature: ImplingCreature) {
        val jar = creature.caught.singleOrNull()?.obj ?: return
        val table = ImplingLoot.forJar(jar) ?: return
        rollTable(table)
    }

    /**
     * One roll of a jar's table, paid out.
     *
     * Shared by [openJar] and [lootBarehanded] because the wiki describes the jarless catch as
     * receiving *the loot from the impling*, not a variant of it, and two copies of this `when`
     * could drift into being two different tables' worth of behaviour.
     */
    private fun ProtectedAccess.rollTable(table: RSDropTable<Player, DropRollItem>) {
        when (val result = table.roll(player, ArgMap()).flatten()) {
            is RollResult.Nothing -> Unit
            is RollResult.Single -> giveDrop(result.result)
            is RollResult.ListOf -> result.results.forEach { giveDrop(it) }
        }
    }

    /**
     * One rolled reward.
     *
     * `isNothing` is a real outcome here, not an error: the baby impling's table carries a 1/10
     * nothing slot, and the wiki notes the "you acquire some loot" message still shows.
     *
     * The quantity is drawn from [gameRandom] rather than from the receiver's own `random`, for the
     * reason this class names its field that way at all: `ProtectedAccess.random` resolves out of
     * the player's context, and the two are only the same object because Guice happens to bind one
     * [GameRandom]. Reaching for the injected one keeps every draw this feature takes coming from
     * the same place.
     */
    private fun ProtectedAccess.giveDrop(drop: DropRollItem) {
        if (drop.isNothing || !drop.condition(player)) {
            return
        }
        val obj = drop.transformObj(player) ?: drop.obj
        invAddOrDrop(objRepo, obj, drop.rollCount(gameRandom))
    }

    /**
     * Swaps the empty jar for this creature's filled one.
     *
     * **Never needs a free slot**, for the reason [HunterButterfly.jarCatch] documents: both jars are
     * non-stackable, so the delete frees exactly the slot the add takes and a player with a
     * completely full inventory can still jar a catch. Adding a space check here would refuse
     * catches for a reason the game never states.
     *
     * The delete has to succeed before the add runs, so that an ordering which failed halfway cannot
     * mint a filled jar out of nothing.
     *
     * @return false only if the empty jar could not be taken; the caller has already established
     *   that one is carried, so unlike the butterfly's this is not a legal outcome.
     */
    private fun ProtectedAccess.jarCatch(creature: ImplingCreature): Boolean {
        if (invDel(inv, IMPLING_JAR, 1).failure) {
            return false
        }
        for (reward in creature.caught) {
            invAdd(inv, reward.obj, rollQuantity(gameRandom, reward.quantity))
        }
        return true
    }

    /**
     * Whether either net is *worn*, which is what "wielding" means: both nets are
     * `wearpos=righthand` with `iop2=Wield`, so a net sitting in the inventory is not being used and
     * the attempt is barehanded.
     *
     * Restated rather than shared with [HunterButterfly] because both copies are one expression over
     * two constants that this file already imports, and the rule is the nets', not the technique's.
     */
    private fun ProtectedAccess.isHoldingNet(): Boolean =
        worn.contains(BUTTERFLY_NET) || worn.contains(MAGIC_BUTTERFLY_NET)

    /**
     * Whether this attempt gets [HunterButterfly.NET_BONUS] - true for the magic net and for
     * barehanded, false for the plain net.
     *
     * Unambiguous here in a way it is not for butterflies, where one of the three published charts
     * labels its series the other way round. Every one of the twelve impling pages labels its second
     * series "Barehanded or magic butterfly net", and *Impling* (oldid=15303398) states it in prose:
     * "Grabbing an impling with your bare hands has the same success rate as attempting to catch one
     * with a magic butterfly net."
     */
    private fun ProtectedAccess.usesFasterCurve(): Boolean =
        worn.contains(MAGIC_BUTTERFLY_NET) || !isHoldingNet()
}
