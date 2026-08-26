package org.rsmod.content.skills.hunter

/**
 * How many traps a hunter may have live at once: 1 below level 20, then 2, 3, 4 and 5 at 20, 40, 60
 * and 80.
 *
 * Transcribed from the "Multiple traps" table on the *Pitfall* page (wiki, oldid=15201220), which
 * gives the cap at five Hunter levels - 1, 20, 40, 60, 80 - each holding until the next. The same
 * ladder governs the five tile-based trap families in [HunterTrap], which counted it out
 * separately until the two copies were folded together here.
 *
 * `HunterCrabTrap.crabTrapCap` is deliberately **not** one of these callers. Its published table
 * starts at 2 and has no below-20 rung at all, because the lowest crab site is level 21 and the
 * bottom of this ladder is unreachable there. The two agree at every level crab trapping can be
 * done at, which is exactly why folding it in here would be a silent behaviour change rather than
 * a de-duplication: it would give crab trapping a rung its source does not have.
 *
 * Callers read it from the *effective* Hunter level, so a boost raises the cap.
 */
internal object TrapLadder {
    fun cap(level: Int): Int =
        when {
            level >= 80 -> 5
            level >= 60 -> 4
            level >= 40 -> 3
            level >= 20 -> 2
            else -> 1
        }
}
