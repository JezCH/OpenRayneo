// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** A real route source must supply correlated, fresh navigation and rendered map
 * observations. Notification-only HUD text and place-search coordinates do not
 * satisfy this contract. All calls are serialized on the service main loop. */
internal class NativeLiveNavigation(
    val session: NativeNavigationStartup.Session,
    val route: Route
) {
    interface Route {
        val parameters: NativeNavigationStartup.Parameters
        val generation: Long
        val token: String
        /** Latest real observation for this route; null if not available. */
        fun observation(): Observation?
        fun close()
    }
    class Observation(val guidance: NativePhoneGuidance.Snapshot,
                      val miniPng: ByteArray?, val overviewPng: ByteArray?)
    class Write(val packet: ByteArray, val raster: NativeRasterTransfer.Write? = null)
    private var lastGuidance = -1L
    private var lastMapAt = -1L
    private var lastView = -1L
    private var inFlight: Write? = null
    private var deadline = 0L
    private var closed = false
    private var lastFreshAt = -1L
    private var arrivalSent = false
    var failure: String? = null
        private set

    /** Returns at most one packet, never renews an old observation's TTL. */
    fun next(sequence: Int, now: Long): Write? {
        if (closed || failure != null || session.phase != NativeNavigationStartup.Session.Phase.READY) return null
        if (lastFreshAt < 0) lastFreshAt = now
        val observation = route.observation()?.takeIf { it.guidance.freshAt(now) }
        if (observation != null) lastFreshAt = maxOf(lastFreshAt, observation.guidance.observedAtMs)
        if (now - lastFreshAt >= 10_000L) { failure = "실시간 경로 갱신 중단"; return null }
        if (inFlight != null) {
            if (now >= deadline) failure = "순정 내비 전송 시간 초과"
            return null
        }
        if (session.rasterState == NativeRasterTransfer.State.FAILED) {
            failure = "순정 지도 전송 거부 또는 시간 초과"
            return null
        }
        if (arrivalSent) return null
        val guidance = observation?.guidance
        val write = if (guidance != null && guidance.observedAtMs > lastGuidance) {
            val packet = session.guidanceFrame(sequence, route.generation, route.token, guidance, now)
            lastGuidance = guidance.observedAtMs
            arrivalSent = guidance.arrival
            Write(packet)
        } else {
            val view = session.rasterView
            if (view != null && observation != null &&
                (view.generation != lastView || observation.guidance.observedAtMs > lastMapAt)) {
                val png = if (view.view == NativeRasterFrame.View.MINI) observation.miniPng else observation.overviewPng
                if (png != null && session.submitRaster(view, png)) {
                    lastView = view.generation
                    lastMapAt = observation.guidance.observedAtMs
                }
            }
            session.nextRasterWrite(sequence, now)?.let { Write(it.packet, it) }
        }
        if (write != null) { inFlight = write; deadline = now + 5_000L }
        return write
    }
    fun written(write: Write, success: Boolean, now: Long) {
        if (closed || inFlight !== write) return
        inFlight = null
        write.raster?.let { session.rasterWritten(it, success, now) }
        if (!success) failure = "순정 내비 전송 실패"
    }
    val arrived get() = arrivalSent && inFlight == null && failure == null
    fun close() { if (!closed) { closed = true; inFlight = null; runCatching { route.close() } } }
}
