package org.rsmod.content.skills.hunter

import dtx.core.ArgMap
import dtx.core.RollResult
import dtx.core.flatten
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

/**
 * The empty jar a Puro-Puro catch consumes, `obj.ii_impling_jar` (11260), `name=Impling jar`.
 *
 * One per impling kept, and - unlike [BUTTERFLY_JAR] - mandatory here rather than optional. See
 * [HunterImpling.catchImpling].
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
 * on Gielinor, impling jars must be used when catching implings in Puro-Puro." *Baby impling*
 * (oldid=15297388) says the same thing from the other side and closes the barehanded loophole: "In
 * Puro-Puro, empty impling jars are required to catch any implings, whether catching them by net or
 * by hand." So a jarless attempt is **refused** here, where a jarless butterfly catch is a legal
 * catch that simply flies away.
 *
 * **There is no area check, and there must not be one.** All six rows in
 * [ImplingCreatures] are `_maze` npcs - the Puro-Puro-only ids - and they are the only impling npcs
 * with spawns in `.data`. "In Puro-Puro" is therefore already answered by *which npc was clicked*,
 * and an area lookup would be a second, weaker way of asking the same question that could disagree
 * with the first. When the overworld implings ship they will arrive as their own npc ids with their
 * own rules, not as these six standing somewhere else.
 *
 * ## What is not modelled
 *
 * **`iop3=Loot` on the filled jar**, which is the other half of the reward and has its own published
 * drop table per impling. A catch here produces the jar; opening it is a separate feature.
 *
 * **The overworld implings**, which need a spawner - `.data` ships five invisible, op-less
 * "precursor" markers that the live game replaces with a rolled impling type, and nothing here does
 * that. **Imp defenders**, the Puro-Puro retaliation, and **crop circles** are out for the same
 * reason: they are their own features, not a branch of this one.
 */
class HunterImpling
@Inject
constructor(
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    // Named `gameRandom`, not `random`, for the reason [HunterButterfly] spells out at its own
    // constructor: `ProtectedAccess` has a `random` property and an extension receiver's member
    // wins over the dispatch receiver's field, so a field called `random` here would be silently
    // shadowed at every use site and the catch roll would draw from the player's context RNG.
    private val gameRandom: GameRandom,
    private val xpMods: XpModifiers,
) {
    /**
     * `Catch` on an impling: gate on level, then on the jar, then roll once.
     *
     * The two gates are both ahead of the roll, and both ahead of the animation. The level gate is
     * there for the reason [HunterButterfly.catchButterfly] and the trap tick have theirs - so an
     * under-levelled attempt never consumes a random draw - and the jar gate joins it because in
     * Puro-Puro a jarless attempt is not a catch at all, so swinging at one and rolling for it would
     * both mislead the player and burn a draw on an outcome that was already decided.
     *
     * No delay and no lock, exactly as butterfly netting has none: a net swing lands on the tile the
     * player is already standing next to, and no source describes a wait.
     *
     * @return true only if the impling was caught and jarred; false covers a miss, an under-levelled
     *   attempt, an attempt with no empty jar, and an npc that is not a shipped impling at all.
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

        // Refused before the swing and before the draw. The live server string is not recoverable
        // offline, so this says what the wiki says rather than guessing at Jagex's wording.
        if (!inv.contains(IMPLING_JAR)) {
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

        // Jarred *before* the creature is removed, so a swap that somehow fails leaves the impling
        // on the map rather than deleting it for nothing. Reaching the `false` branch means the
        // delete failed with the jar present, which nothing here can produce - but if it ever does,
        // the impling has to survive it, because the alternative is a despawn that paid out nothing.
        if (!jarCatch(creature)) {
            return false
        }

        npcRepo.despawn(target, target.visType.respawnRate)

        // The Puro-Puro value, not the overworld one, because every row in this table is a `_maze`
        // spawn. Stored x10 so fractional values survive the table, and awarded on the catch, not on
        // collection - there is nothing to collect.
        val xp = (creature.xpPuro / 10.0) * xpMods.get(player, "stat.hunter")
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
     * @return false only if the obj is not one of the six jars this slice ships.
     */
    fun ProtectedAccess.openJar(jar: String): Boolean {
        val table = ImplingLoot.forJar(jar) ?: return false
        if (invDel(inv, jar, 1).failure) {
            return false
        }

        when (val result = table.roll(player, ArgMap()).flatten()) {
            is RollResult.Nothing -> Unit
            is RollResult.Single -> giveDrop(result.result)
            is RollResult.ListOf -> result.results.forEach { giveDrop(it) }
        }

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
     * One rolled reward.
     *
     * `isNothing` is a real outcome here, not an error: the baby impling's table carries a 1/10
     * nothing slot, and the wiki notes the "you acquire some loot" message still shows.
     */
    private fun ProtectedAccess.giveDrop(drop: DropRollItem) {
        if (drop.isNothing || !drop.condition(player)) {
            return
        }
        val obj = drop.transformObj(player) ?: drop.obj
        invAddOrDrop(objRepo, obj, drop.rollCount(random))
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
