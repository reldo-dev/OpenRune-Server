package org.rsmod.content.skills.hunter

import org.rsmod.api.player.vars.intVarp
import org.rsmod.game.entity.Player

/**
 * The queue that resets a player's revealed hunter trail at login.
 *
 * A varp written from an `onPlayerLogin` handler updates the server but never reaches the client:
 * `VarPlayerIntMapSetter` short-circuits its transmit branch while `processedMapClock == 0`, which
 * is exactly the state during the login event. So the reset has to ride a soft queue instead,
 * exactly as the bird house fill does.
 *
 * Declared here; nothing arms this queue yet - that is later work in this slice.
 */
const val TRACKING_RESET_QUEUE: String = "queue.hunter_tracking_reset"

/**
 * How many of a ring of pursuit's ten charges [Player] has used so far (0-9).
 *
 * Per the wiki, a worn ring of pursuit reveals a kebbit's whole trail and holds ten charges before
 * it crumbles, and those charges belong to the player, not the ring: swapping, dropping or alching
 * a ring must not reset the count, which is exactly what a player varp models for free. Unwritten
 * reads back `0`, so a fresh account starts with the full ten-charge allowance.
 *
 * Declared here; nothing reads or decrements it yet - that is later work in this slice.
 */
internal var Player.trackingRingCharges: Int by intVarp("varp.hunter_ring_of_pursuit_charges")
