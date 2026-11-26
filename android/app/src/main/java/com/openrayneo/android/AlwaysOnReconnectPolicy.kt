// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Main-thread consent for one explicitly started daily capture.
 * It survives time away from the same paired glasses, not explicit stop, a new
 * device or service death. The old record must finish before starting another.
 */
internal class AlwaysOnReconnectPolicy {
    private enum class Phase { NONE, ACTIVE, WAITING_STOP, WAITING_LINK }
    private var phase = Phase.NONE
    val armed: Boolean get() = phase == Phase.ACTIVE
    val pending: Boolean get() = phase == Phase.WAITING_STOP || phase == Phase.WAITING_LINK

    fun arm() { phase = Phase.ACTIVE }
    fun cancel() { phase = Phase.NONE }
    fun linkLost(eligible: Boolean) {
        if (pending) return
        if (armed && eligible) phase = Phase.WAITING_STOP
        else cancel()
    }
    /** The original daily record may be paused for a native wake. Only its existing
     *  reconnect consent may transfer; do not grant consent to a new capture. */
    fun suspendedLinkLost(eligible: Boolean, consent: Boolean, recordRunning: Boolean) {
        if (pending) return
        if (eligible && consent) phase = if (recordRunning) Phase.WAITING_STOP else Phase.WAITING_LINK
        else cancel()
    }
    fun captureStopped() { if (armed) cancel() }
    fun recordStopped() { if (phase == Phase.WAITING_STOP) phase = Phase.WAITING_LINK }
    fun take(linkReady: Boolean, idle: Boolean): Boolean {
        if (phase != Phase.WAITING_LINK || !linkReady || !idle) return false
        cancel() // Consume before attempting the next capture, even if starting fails.
        return true
    }
}
