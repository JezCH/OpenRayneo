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
        /** Non-null only when the authorized full route producer has bound this
         * route to that actual NAVER walking notification. A notification alone
         * cannot construct a Route or supply its identity or map. */
        val naverWalkNotificationKey: String? get() = null
        /** Latest real observation for this route; null if not available. */
        fun observation(): Observation?
        /** Terminal source failure after real observations, not a guess from missing GPS. */
        val failureReason: String? get() = null
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
    private var preferRaster = false
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
        route.failureReason?.let { failure = it; return null }
        if (session.rasterState == NativeRasterTransfer.State.FAILED) {
            failure = "순정 지도 전송 거부 또는 시간 초과"
            return null
        }
        if (arrivalSent) return null
        // Do not start or continue transmitting stale pixels after the live
        // source expires. A partially transmitted expired frame is not reused.
        // A fresh terminal observation does not send stale image data.
        // Let it finish the route even when an older partial image expired.
        val freshArrival = observation?.guidance?.let {
            it.arrival && it.observedAtMs > lastGuidance
        } == true
        if (!freshArrival && session.rasterState == NativeRasterTransfer.State.SENDING &&
            (lastMapAt < 0 || now - lastMapAt >= NativePhoneGuidance.VALID_FOR_MS)) {
            failure = "순정 지도 원본 갱신 중단"
            return null
        }
        if (observation == null) return null
        val guidance = observation.guidance
        val newGuidance = guidance.observedAtMs > lastGuidance
        fun guidanceWrite(): Write {
            val packet = session.guidanceFrame(sequence, route.generation, route.token, guidance, now)
            lastGuidance = guidance.observedAtMs
            arrivalSent = guidance.arrival
            preferRaster = true
            return Write(packet)
        }
        fun rasterWrite(): Write? {
            val view = session.rasterView
            if (view != null && (view.generation != lastView || guidance.observedAtMs > lastMapAt)) {
                val png = if (view.view == NativeRasterFrame.View.MINI) observation.miniPng else observation.overviewPng
                if (png != null && session.submitRaster(view, png)) {
                    lastView = view.generation
                    lastMapAt = guidance.observedAtMs
                }
            }
            return session.nextRasterWrite(sequence, now)?.let {
                preferRaster = false
                Write(it.packet, it)
            }
        }
        // First guidance registers the route; arrival must not wait behind
        // image fragments. Afterwards alternate when both are available so
        // a continuously updating provider cannot starve requested map frames.
        val write = when {
            newGuidance && (lastGuidance < 0 || guidance.arrival || !preferRaster) -> guidanceWrite()
            else -> rasterWrite() ?: if (newGuidance) guidanceWrite() else null
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
