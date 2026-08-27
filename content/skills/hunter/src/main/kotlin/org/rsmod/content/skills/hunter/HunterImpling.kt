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

const val IMPLING_JAR: String = "obj.ii_impling_jar"

/** One in this many jar-opens destroys the empty jar instead of returning it. */
private const val JAR_BREAK_CHANCE: Int = 10

/**
 * Catching implings: butterfly netting with one extra rule - a jarless attempt is refused inside
 * Puro-Puro and is a legal loot-paying catch everywhere else. Where the player stands decides the
 * jar; the npc id that was caught decides the experience, because the wiki ties experience to the
 * spawn's origin. Sources and the not-modelled list: docs/hunter.md.
 */
class HunterImpling
@Inject
constructor(
    private val npcRepo: NpcRepository,
    private val objRepo: ObjRepository,
    private val spawner: ImplingSpawner,
    // Named `gameRandom`, not `random`: the `ProtectedAccess` receiver has a `random` of its own
    // that silently shadows a field of that name at every use site.
    private val gameRandom: GameRandom,
    private val xpMods: XpModifiers,
) {
    // Both gates sit ahead of the roll and the animation, so a decided outcome never consumes a
    // draw. No delay and no lock, as butterfly netting has none.
    fun ProtectedAccess.catchImpling(target: Npc): Boolean {
        val creature = ImplingCreatures.byNpcId(target.visType.id) ?: return false

        val barehanded = !isHoldingNet()
        val required = creature.level + if (barehanded) HunterButterfly.BAREHANDED_LEVELS else 0

        if (player.hunterLvl < required) {
            val how = if (barehanded) " barehanded" else ""
            mes("You need a Hunter level of $required to catch this$how.")
            return false
        }

        // Read once: it decides both the refusal below and which reward the catch pays out.
        val jarred = inv.contains(IMPLING_JAR)

        // "impling jars must be used when catching implings in Puro-Puro" - the string is ours.
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

        // Paid *before* the creature is removed, so a failed swap leaves it on the map.
        if (jarred) {
            if (!jarCatch(creature)) {
                return false
            }
        } else {
            lootBarehanded(creature)
        }

        // A spawner-made impling is removed outright and its marker restarts; a map-placed one is
        // despawned so the engine returns it. Wrong way round pins a marker to one creature.
        if (!spawner.release(target)) {
            npcRepo.despawn(target, target.visType.respawnRate)
        }

        // Keyed on the caught npc id, not the player's location (docs/hunter.md). Stored x10.
        val xp = (creature.experienceFor(target.visType.id) / 10.0) * xpMods.get(player, "stat.hunter")
        statAdvance("stat.hunter", xp)
        return true
    }

    // The jar is tradeable, so no level gate here and there must not be one. Consumed before the
    // roll; rewards go through `invAddOrDrop` since a jar can pay more than one slot's worth.
    fun ProtectedAccess.openJar(jar: String): Boolean {
        val table = ImplingLoot.forJar(jar) ?: return false
        if (invDel(inv, jar, 1).failure) {
            return false
        }

        rollTable(table)

        // "with a 10% chance of it breaking" - sourced to Mod Ash, the one hedged number here.
        if (gameRandom.randomBoolean(JAR_BREAK_CHANCE)) {
            mes("You break the jar as you open it.")
        } else {
            invAddOrDrop(objRepo, IMPLING_JAR, 1)
        }
        return true
    }

    // The same [ImplingLoot] row the jar opens, not a second table. The lucky impling pays
    // nothing here - the same disclosed gap its jar carries (docs/hunter.md).
    private fun ProtectedAccess.lootBarehanded(creature: ImplingCreature) {
        val jar = creature.caught.singleOrNull()?.obj ?: return
        val table = ImplingLoot.forJar(jar) ?: return
        rollTable(table)
    }

    private fun ProtectedAccess.rollTable(table: RSDropTable<Player, DropRollItem>) {
        when (val result = table.roll(player, ArgMap()).flatten()) {
            is RollResult.Nothing -> Unit
            is RollResult.Single -> giveDrop(result.result)
            is RollResult.ListOf -> result.results.forEach { giveDrop(it) }
        }
    }

    // `isNothing` is a real outcome: the baby impling's table carries a genuine 1/10 nothing.
    private fun ProtectedAccess.giveDrop(drop: DropRollItem) {
        if (drop.isNothing || !drop.condition(player)) {
            return
        }
        val obj = drop.transformObj(player) ?: drop.obj
        invAddOrDrop(objRepo, obj, drop.rollCount(gameRandom))
    }

    // Never needs a free slot, and delete-before-add, for [HunterButterfly.jarCatch]'s reasons.
    private fun ProtectedAccess.jarCatch(creature: ImplingCreature): Boolean {
        if (invDel(inv, IMPLING_JAR, 1).failure) {
            return false
        }
        for (reward in creature.caught) {
            invAdd(inv, reward.obj, rollQuantity(gameRandom, reward.quantity))
        }
        return true
    }

    // "Wielding" means *worn*; restated rather than shared - the rule is the nets', not the
    // technique's.
    private fun ProtectedAccess.isHoldingNet(): Boolean =
        worn.contains(BUTTERFLY_NET) || worn.contains(MAGIC_BUTTERFLY_NET)

    // Unambiguous here, unlike the butterflies': all twelve pages and the prose agree that
    // barehanded matches the magic net.
    private fun ProtectedAccess.usesFasterCurve(): Boolean =
        worn.contains(MAGIC_BUTTERFLY_NET) || !isHoldingNet()
}
