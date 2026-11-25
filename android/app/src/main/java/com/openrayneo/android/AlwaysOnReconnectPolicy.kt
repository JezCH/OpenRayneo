// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Main-thread, in-memory consent for recovering one explicitly started daily capture.
 * Never persists permission across service death; the old record must finish before restart.
 */
internal class AlwaysOnReconnectPolicy(private val windowMs: Long = 300_000L) {
    private enum class Phase { NONE, ACTIVE, WAITING_STOP, WAITING_LINK }
    private var phase = Phase.NONE
    private var lostAt = 0L
    val pending: Boolean get() = phase == Phase.WAITING_STOP || phase == Phase.WAITING_LINK

    fun arm() { phase = Phase.ACTIVE }
    fun cancel() { phase = Phase.NONE }
    fun linkLost(eligible: Boolean, now: Long) {
        if (pending) return
        if (phase == Phase.ACTIVE && eligible) {
            lostAt = now
            phase = Phase.WAITING_STOP
        } else cancel()
    }
    fun captureStopped() { if (phase == Phase.ACTIVE) cancel() }
    fun recordStopped() { if (phase == Phase.WAITING_STOP) phase = Phase.WAITING_LINK }
    fun expire(now: Long): Boolean {
        if (!pending || now - lostAt < windowMs) return false
        cancel()
        return true
    }
    fun take(now: Long, linkReady: Boolean, idle: Boolean): Boolean {
        expire(now)
        if (phase != Phase.WAITING_LINK || !linkReady || !idle) return false
        cancel() // Consume before attempting the next capture, even if starting fails.
        return true
    }
}
