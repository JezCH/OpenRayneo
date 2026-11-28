// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject

/**
 * Device-driven stock 1.0.6.11 remote raster controller.
 * 0x39 mode2/seq0 view request serializer 0x105C5474, 0x38 mode1/seq0
 * state serializer 0x105C58B4, generation producer 0x1075264C and
 * identity gate 0x10751454 (runs 46127602414, 46127650206, 46127685237).
 * Call on the session owner's serialized event loop. No timer, rendering,
 * location access, or transport is hidden inside this controller.
 */
internal class NativeRasterTransfer(
    private val route: NativeRouteScene.Identity,
    private val limits: NativeNavigationCapabilities.RasterLimits
) {
    internal class ViewRequest internal constructor(
        val generation: Long, val view: NativeRasterFrame.View,
        val width: Int, val height: Int
    ) {
        override fun toString() = "ViewRequest(" + view.name + ")"
    }

    enum class State { WAITING_VIEW, WAITING_IMAGE, SENDING, AWAITING_COMMIT, QUERYING, COMMITTED, FAILED, CLOSED }
    var state = State.WAITING_VIEW
        private set
    var viewRequest: ViewRequest? = null
        private set
    private var nextFrameSequence = 1L
    private var encoded: NativeRasterFrame.Encoded? = null
    private var nextFragment = 0
    private var deadline = 0L
    private var statusQueries = 0
    // An outstanding transport callback survives a superseding view. Do not
    // issue a new frame until the old write has completed.
    private var outstanding: Write? = null
    private var blockedTransport = false
    var failureCode: Long? = null
        private set

    internal class Write internal constructor(
        val packet: ByteArray, internal val generation: Long, internal val finalFragment: Boolean,
        internal val statusQuery: Boolean
    ) {
        override fun toString() = "RasterWrite(bytes=" + packet.size + ")"
    }

    private fun uint(body: JSONObject, field: String): Long? = when (val v = body.opt(field)) {
        is Int -> v.toLong().takeIf { it >= 0 }
        is Long -> v.takeIf { it in 0..0xffffffffL }
        else -> null
    }

    private fun request(message: ProtocolCodec.Message): ViewRequest? {
        if (message.version != 1 || message.type != 0x39 || message.mode != 2L ||
            message.sequence != 0L || message.audio?.isNotEmpty() == true) return null
        val text = message.json ?: return null
        if (text.length !in 1..8192) return null
        val body = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (body.opt("sid") != route.sid || body.opt("route_token") != route.token ||
            uint(body, "route_revision") != route.revision || uint(body, "route_generation") != route.generation ||
            body.opt("codec") != "PNG") return null
        val generation = uint(body, "view_generation")?.takeIf { it > 0 } ?: return null
        val view = when (body.opt("view_mode")) {
            "MINI" -> NativeRasterFrame.View.MINI
            "OVERVIEW" -> NativeRasterFrame.View.OVERVIEW
            else -> return null
        }
        val size = if (view == NativeRasterFrame.View.MINI) limits.mini else limits.overview
        if (uint(body, "width") != size.width.toLong() || uint(body, "height") != size.height.toLong()) return null
        return ViewRequest(generation, view, size.width, size.height)
    }

    /** Return true only for a correlated new request or a correlated frame reply.
     * An old or duplicate request must never restart an already processed view. */
    fun receive(message: ProtocolCodec.Message): Boolean {
        if (state == State.CLOSED || blockedTransport) return false
        if (message.type == 0x39) {
            val request = request(message) ?: return false
            if (request.generation <= (viewRequest?.generation ?: 0L)) return false
            viewRequest = request
            encoded = null
            nextFragment = 0
            nextFrameSequence = 1L
            deadline = 0
            statusQueries = 0
            failureCode = null
            state = State.WAITING_IMAGE
            return true
        }
        if (state !in setOf(State.AWAITING_COMMIT, State.QUERYING)) return false
        val reply = encoded?.reply(message) ?: return false
        when (reply) {
            NativeRasterFrame.Reply.Committed -> finish(State.COMMITTED)
            NativeRasterFrame.Reply.Pending -> {
                // Do not retransmit a pending frame. At most one more status
                // query may be scheduled using the existing deadline.
                if (statusQueries >= MAX_STATUS_QUERIES) finish(State.FAILED, 43)
                else state = State.AWAITING_COMMIT
            }
            NativeRasterFrame.Reply.NotFound -> finish(State.FAILED, 42)
            is NativeRasterFrame.Reply.Rejected -> finish(State.FAILED, reply.code)
        }
        return true
    }

    /** The renderer must return the exact current request, with bytes for that
     * route and view. A newer request invalidates any asynchronous render result.
     * Additional fresh images may be submitted after a positive commit. */
    fun submit(request: ViewRequest, png: ByteArray): Boolean {
        if (blockedTransport || request !== viewRequest ||
            state !in setOf(State.WAITING_IMAGE, State.COMMITTED) || outstanding != null ||
            nextFrameSequence !in 1..0xffffffffL) return false
        val key = NativeRasterFrame.Key(route, request.generation, nextFrameSequence)
        val frame = NativeRasterFrame.encode(limits, key, request.view, png)
        encoded = frame
        nextFrameSequence++
        nextFragment = 0
        statusQueries = 0
        deadline = 0
        failureCode = null
        state = State.SENDING
        return true
    }

    /** At most one packet outstanding. Timeout is a conservative phone-side
     * policy, not a recovered firmware timing constant. No blind fragment retry. */
    fun nextWrite(transportSequence: Int, nowMs: Long): Write? {
        require(transportSequence in 0..65535 && nowMs in 0..Long.MAX_VALUE - REPLY_TIMEOUT_MS)
        if (blockedTransport || outstanding != null || state == State.CLOSED) return null
        val request = viewRequest ?: return null
        val frame = encoded ?: return null
        val write = when (state) {
            State.SENDING -> {
                val fragment = frame.fragments[nextFragment]
                val final = nextFragment == frame.fragments.lastIndex
                Write(fragment.frame(transportSequence), request.generation, final, false).also {
                    nextFragment++
                    if (final) {
                        // Device response can precede the final transport callback.
                        state = State.AWAITING_COMMIT
                        deadline = nowMs + REPLY_TIMEOUT_MS
                    }
                }
            }
            State.AWAITING_COMMIT -> {
                if (nowMs < deadline) return null
                if (statusQueries >= MAX_STATUS_QUERIES) {
                    finish(State.FAILED)
                    return null
                }
                Write(frame.statusQuery(transportSequence), request.generation, false, true).also {
                    statusQueries++
                    state = State.QUERYING
                    deadline = nowMs + REPLY_TIMEOUT_MS
                }
            }
            State.QUERYING -> {
                if (nowMs >= deadline) {
                    if (statusQueries >= MAX_STATUS_QUERIES) finish(State.FAILED)
                    else state = State.AWAITING_COMMIT
                }
                return null
            }
            else -> return null
        }
        outstanding = write
        return write
    }

    /** A transport callback does not mean decode or HUD success. A failed or
     * timed-out write leaves delivery uncertain; stop until session reset. */
    fun written(write: Write, success: Boolean, nowMs: Long) {
        require(nowMs in 0..Long.MAX_VALUE - REPLY_TIMEOUT_MS)
        if (outstanding !== write) return
        outstanding = null
        if (state == State.CLOSED) return
        if (!success) {
            blockedTransport = true
            finish(State.FAILED)
            return
        }
        if (write.generation != viewRequest?.generation) return
        if (state in setOf(State.AWAITING_COMMIT, State.QUERYING) &&
            (write.finalFragment || write.statusQuery)) deadline = nowMs + REPLY_TIMEOUT_MS
    }

    fun close() {
        finish(State.CLOSED)
        viewRequest = null
        outstanding = null
    }

    private fun finish(next: State, code: Long? = null) {
        state = next
        failureCode = code
        encoded = null
        deadline = 0
    }

    private companion object {
        const val REPLY_TIMEOUT_MS = 5_000L
        const val MAX_STATUS_QUERIES = 2
    }
}
