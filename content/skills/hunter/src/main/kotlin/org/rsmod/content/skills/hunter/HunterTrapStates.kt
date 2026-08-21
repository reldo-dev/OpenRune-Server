package org.rsmod.content.skills.hunter

object HunterTrapStates {
    // "npc.hunting_bird_jungle" -> "jungle"; "npc.hunting_chinchompa_big" -> "chinchompa_big"
    private fun key(creature: HunterCreature): String =
        when (creature.family) {
            TrapFamily.SNARE -> creature.npc.substringAfterLast("hunting_bird_")
            TrapFamily.BOX -> creature.npc.substringAfter("npc.hunting_")
        }

    fun setLoc(family: TrapFamily): String =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap"
            TrapFamily.BOX -> "loc.hunting_boxtrap_empty"
        }

    fun trappingLoc(creature: HunterCreature, approach: Char): String =
        when (creature.family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_trapping_${key(creature)}"
            TrapFamily.BOX -> "loc.hunting_boxtrap_trapping_${key(creature)}_$approach"
        }

    fun fullLoc(creature: HunterCreature): String =
        when (creature.family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_full_${key(creature)}"
            TrapFamily.BOX -> "loc.hunting_boxtrap_full_${key(creature)}"
        }

    fun failingLoc(family: TrapFamily): String =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_failing"
            TrapFamily.BOX -> "loc.hunting_boxtrap_failing"
        }

    fun failedLoc(family: TrapFamily): String =
        when (family) {
            TrapFamily.SNARE -> "loc.hunting_ojibway_trap_broken"
            TrapFamily.BOX -> "loc.hunting_boxtrap_failed"
        }
}
