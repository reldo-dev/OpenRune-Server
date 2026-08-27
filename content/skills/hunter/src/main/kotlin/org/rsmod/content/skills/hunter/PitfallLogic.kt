package org.rsmod.content.skills.hunter

/**
 * The five values a `hunt_pitfall_state<n>` varbit can hold, read off the cache's own multiloc
 * chain. [Catching] carries no ops at all; the two full states are the same corpse a half-turn
 * apart.
 */
enum class PitState(val varbitValue: Int) {
    Empty(0),
    Set(1),
    Catching(2),
    Full(3),
    FullRotated(4);

    companion object {
        /**
         * The state a varbit value renders as.
         *
         * The varbit is three bits wide, so 5, 6 and 7 are representable even though the cache
         * defines no multiloc child for any of them - there is nothing for the client to draw from
         * a `hunting_pitfall_<n>` loc holding one of those values. This throws rather than mapping
         * them to [Empty] defensively: every write to this varbit is ours, so a value outside 0..4
         * reaching here means the server itself is confused about what state a pit is in. Coercing
         * that silently to [Empty] would make the corruption invisible at the one place positioned
         * to catch it, and it would resurface later as a pit that renders as something the server's
         * own state disagrees with, with nothing in any log to say why.
         */
        fun of(varbitValue: Int): PitState =
            entries.find { it.varbitValue == varbitValue }
                ?: throw IllegalArgumentException(
                    "Not a pitfall state: $varbitValue (must be one of 0..4)"
                )
    }
}

/**
 * How many pitfalls a player may have baited or full at once.
 *
 * Transcribed from the "Multiple traps" table on the *Pitfall* page (wiki, oldid=15201220), which
 * gives the cap at five Hunter levels - 1, 20, 40, 60, 80 - each holding until the next: 1 below
 * level 20, 2 from 20, 3 from 40, 4 from 60, 5 from 80.
 *
 * The five tile-based trap families run on the same ladder, so the numbers live in [TrapLadder] and
 * this is the pitfall's name for them. It stays a named entry point rather than having callers
 * reach for [TrapLadder] directly, because a pitfall counts player-varbit state on permanent
 * scenery where `HunterTrap` counts controllers laid on tiles: if the published tables ever part,
 * they part here.
 *
 * Read from the effective level, so a boost raises the cap.
 */
object PitfallLogic {
    fun maxTraps(hunterLevel: Int): Int = TrapLadder.cap(hunterLevel)
}
