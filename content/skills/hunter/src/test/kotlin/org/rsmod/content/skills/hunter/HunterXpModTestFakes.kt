package org.rsmod.content.skills.hunter

import org.rsmod.api.stats.xpmod.StatXpMod
import org.rsmod.api.stats.xpmod.XpModifiers
import org.rsmod.game.entity.Player

/**
 * The bonus every hunter world is driven with when a test wants a modified award: +100%.
 *
 * A whole doubling rather than the +2.5% a real skilling outfit grants, because fine experience is
 * stored as tenths of a point and truncated on the way in. Doubling is exact for every row of every
 * table in this module; a fractional multiplier would round differently per creature and the
 * expected figure would have to be re-derived table by table for no extra power.
 */
internal const val DOUBLE_HUNTER_XP: Double = 1.0

/**
 * An [XpModifiers] that adds [bonus] to `stat.hunter` and nothing to any other stat.
 *
 * Every world in this suite used to build its own from `emptySet()`, which is a 1.0 multiplier, so
 * the `* xpMods.get(player, "stat.hunter")` on all eight award sites could be deleted with the
 * whole suite still green - the modifier was wired everywhere and exercised nowhere. Worlds take a
 * bonus now, and one test per technique spends it.
 *
 * [bonus] is what [org.rsmod.api.stats.xpmod.XpMod] returns, i.e. the amount added to the base
 * `1.0`: `0.5` is a 1.5x award and [DOUBLE_HUNTER_XP] is a 2x one. `0.0` returns the empty set the
 * worlds carried before, so a default-constructed world is byte for byte the world it was.
 */
internal fun hunterXpModifiers(bonus: Double, craftingBonus: Double = 0.0): XpModifiers {
    val mods = buildSet {
        if (bonus != 0.0) {
            add(HunterXpBonus(bonus))
        }
        if (craftingBonus != 0.0) {
            add(CraftingXpBonus(craftingBonus))
        }
    }
    return XpModifiers(mods)
}

/** The shape a Hunter skilling outfit would have, with a bonus a test picks instead of a cape. */
private class HunterXpBonus(private val bonus: Double) : StatXpMod("stat.hunter") {
    override fun Player.modifier(): Double = bonus
}

/**
 * The same, for `stat.crafting`.
 *
 * Bird house crafting is the only award in this module that is not Hunter experience, and its own
 * `* xpMods.get(player, "stat.crafting")` needs a modifier scoped to *that* stat to be exercised at
 * all - a Hunter bonus leaves it at 1.0 and would let the multiply be deleted unnoticed.
 */
private class CraftingXpBonus(private val bonus: Double) : StatXpMod("stat.crafting") {
    override fun Player.modifier(): Double = bonus
}
