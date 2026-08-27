package org.rsmod.content.skills.hunter

import org.rsmod.api.player.vars.intVarp
import org.rsmod.game.entity.Player

/**
 * The queue that resets a revealed trail at login. A varp written from `onPlayerLogin` never
 * reaches the client (`VarPlayerIntMapSetter` skips transmit while `processedMapClock == 0`), so
 * the reset rides a soft queue - and doing it directly would still pass every unit test
 * (docs/hunter.md).
 */
const val TRACKING_RESET_QUEUE: String = "queue.hunter_tracking_reset"

/**
 * Charges *used* (0-9): the charges belong to the player, not the ring - swapping or dropping a
 * ring must not reset the count. Unwritten reads 0, a full allowance.
 */
internal var Player.trackingRingCharges: Int by intVarp("varp.hunter_ring_of_pursuit_charges")
