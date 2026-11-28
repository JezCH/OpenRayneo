// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject
import java.util.UUID

/** Stock 1.0.6.11 phone-originated start (0x10761594, 0x10741458),
 * reply (0x105c6f58), ready (0x105c5070), initial revision=1 and
 * route registration (0x1073d764, 0x1073c18c). No transport side effects.
 * A real route source and exact peer profile must be established before
 * sending guidance or a map. A separately consented, stationary START/READY/END
 * diagnostic may test session negotiation without sending route data. */
internal object NativeNavigationStartup {
    /** Close an existing matching peer SID, not a fabricated successful start.
     * Stock 0x105c91d2 -> 0x10740ea4 validates current SID and normal end reason.
     * No peer acknowledgement is implied by writing this packet. */
    fun phoneCloseFrame(sequence: Int, sid: String): ByteArray {
        require(sid.matches(Regex("(?i)([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")))
        return ProtocolCodec.display(0x20, sequence, 3, JSONObject().put("sid", sid)
            .put("reason_code", 2).put("text", "MOBILE_APP_CLOSE").toString())
    }
    internal enum class Profile(val map: String, val scene: String) {
        RASTER_FRAME("REMOTE_RASTER_FRAME_V1", "NONE"),
        VECTOR_TILE("REMOTE_VECTOR_TILE_V1", "ROUTE_SCENE_V1"),
        GOOGLE_RASTER("GOOGLE_RASTER_V1", "ROUTE_SCENE_V1")
    }
    internal class Parameters(
        val profile: Profile,
        val travelMode: Int,
        val locale: String,
        val region: String,
        val unitSystem: Int,
        val drivingSide: Int,
        val area: String,
        val destination: String
    ) {
        init {
            require(travelMode in 1..3 && unitSystem in 1..2 && drivingSide in 0..2)
            require(locale.length in 2..35 && locale.matches(Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")))
            require(region.matches(Regex("[A-Z]{2}")))
            require(area in setOf("overseas", "mainland"))
            require(destination.isNotBlank() && destination.toByteArray(Charsets.UTF_8).size <= 96 &&
                destination.none(Char::isISOControl))
        }
        fun profileJson() = JSONObject().put("engine_profile", "PHONE_GUIDANCE_V1")
            .put("map_profile", profile.map).put("route_scene_profile", profile.scene)
        fun json() = profileJson().put("travel_mode", travelMode).put("locale", locale)
            .put("region", region).put("unit_system", unitSystem).put("driving_side", drivingSide)
            .put("area", area).put("destination_name", destination)
        fun matchesProfile(body: JSONObject) = body.opt("engine_profile") == "PHONE_GUIDANCE_V1" &&
            body.opt("map_profile") == profile.map && body.opt("route_scene_profile") == profile.scene
        fun matchesReply(body: JSONObject) = matchesProfile(body) && body.opt("travel_mode") == travelMode &&
            body.opt("locale") == locale && body.opt("region") == region &&
            body.opt("unit_system") == unitSystem && body.opt("driving_side") == drivingSide
        override fun toString() = "Parameters(profile=" + profile.name + ")"
    }

    /** Session identity is local to this phone-hosted navigation, not a provider route ID.
     * Live guidance requires a genuine route source. The start-only diagnostic
     * must not generate a route identity, guidance or map frame.
     * No success claim on transport writes or uncorrelated peer messages. */
    internal class Session(
        val parameters: Parameters,
        private val sid: String = UUID.randomUUID().toString()
    ) {
        init {
            require(sid.matches(Regex("(?i)([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")))
        }
        enum class Phase { NEW, STARTING, READY, SCENE_PENDING, SCENE_ACCEPTED, REJECTED, ENDED }
        var phase = Phase.NEW
            private set
        var startAccepted = false
            private set
        var glassReady = false
            private set
        var incompatibleStartReply = false
            private set
        var incompatibleReady = false
            private set
        val startupEvidence: String get() =
            "START_REPLY " + when {
                startAccepted -> "확인"
                presetReplySent -> "응답 송신"
                rejectionCode != null -> "거부($rejectionCode)"
                incompatibleStartReply -> "규격 불일치"
                else -> "미수신"
            } + " / GLASS_READY " + when {
                glassReady -> "확인"
                incompatibleReady -> "규격 불일치"
                else -> "미수신"
            }

        /** A device-originated END_REQUEST, including after the local close
         * packet was created. No transport ACK or physical-screen inference. */
        fun peerEndReason(message: ProtocolCodec.Message): Int? =
            (body(message, 3)?.opt("reason_code") as? Int)?.takeIf { it in 1..7 }
        private var scene: NativeRouteScene.Encoded? = null
        private var endSent = false
        // A locally prepared route is not proof of acceptance by the glasses.
        private var route: NativeRouteScene.Identity? = null
        private var nextGuidanceSequence = 1L
        private var lastGuidanceAt = -1L
        private var rasterLimits: NativeNavigationCapabilities.RasterLimits? = null
        private var raster: NativeRasterTransfer? = null

        /** Use only limits from this authenticated connection's actual 0x31 reply. */
        fun configureRaster(capabilities: NativeNavigationCapabilities) {
            check(phase == Phase.NEW && parameters.profile == Profile.RASTER_FRAME)
            check(capabilities.phoneGuidance && capabilities.guidance && capabilities.maps)
            rasterLimits = checkNotNull(capabilities.raster)
        }

        val rasterView get() = raster?.viewRequest
        val rasterState get() = raster?.state
        val rasterMapReport get() = raster?.mapReport
        fun submitRaster(request: NativeRasterTransfer.ViewRequest, png: ByteArray): Boolean =
            raster?.submit(request, png) == true
        fun nextRasterWrite(sequence: Int, nowMs: Long) = raster?.nextWrite(sequence, nowMs)
        fun rasterWritten(write: NativeRasterTransfer.Write, success: Boolean, nowMs: Long) {
            raster?.written(write, success, nowMs)
        }
        var rejectionCode: Int? = null
            private set

        /** Caller must verify actual negotiated capabilities and source availability
         * for live navigation. The bounded start-only probe sends no guidance.
         * Code 0 is a request, never a received ACK. */
        fun startFrame(sequence: Int): ByteArray {
            check(phase == Phase.NEW)
            if (parameters.profile == Profile.RASTER_FRAME) check(rasterLimits != null)
            val body = parameters.json().put("sid", sid).put("trigger", 1).put("code", 0)
            val packet = ProtocolCodec.display(0x20, sequence, 1, body.toString())
            phase = Phase.STARTING
            return packet
        }
        /** Reply to an already validated initial glasses preset, using its SID.
         * GLASS_READY is still required; this outgoing reply is not peer ACK. */
        fun acceptInitialPresetFrame(sequence: Int): ByteArray {
            check(phase == Phase.NEW && parameters.profile == Profile.RASTER_FRAME && rasterLimits != null)
            val packet = ProtocolCodec.display(0x20, sequence, 2,
                parameters.json().put("sid", sid).put("code", 0).put("text", "").toString())
            phase = Phase.STARTING
            presetReplySent = true
            return packet
        }
        private var presetReplySent = false
        private fun body(message: ProtocolCodec.Message, type: Int): JSONObject? {
            if (message.type != type || message.version != 1 || message.sequence != 0L ||
                message.mode != 2L || message.audio?.isNotEmpty() == true) return null
            val text = message.json ?: return null
            if (text.length !in 1..8192) return null
            return runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.opt("sid") == sid }
        }
        fun receive(message: ProtocolCodec.Message) {
            if (phase == Phase.NEW || phase == Phase.ENDED) return
            if (message.type == 3) {
                if (peerEndReason(message) != null) {
                    phase = Phase.ENDED
                    scene = null
                    route = null
                    raster?.close()
                    raster = null
                }
                return
            }
            if (phase == Phase.STARTING) {
                when (message.type) {
                    2 -> {
                        val reply = body(message, 2) ?: return
                        val code = reply.opt("code") as? Int ?: return
                        if (code !in 0..52) return
                        if (code != 0) {
                            // Confirmation or rejection: never auto-confirm.
                            rejectionCode = code
                            phase = Phase.REJECTED
                            return
                        }
                        if (!parameters.matchesReply(reply)) {
                            incompatibleStartReply = true
                            return
                        }
                        startAccepted = true
                    }
                    4 -> {
                        val response = body(message, 4) ?: return
                        val runtime = response.opt("runtime_version") as? String
                        if (!parameters.matchesProfile(response) || runtime.isNullOrBlank() ||
                            runtime.toByteArray(Charsets.UTF_8).size > 128) {
                            incompatibleReady = true
                            return
                        }
                        glassReady = true
                    }
                }
                if ((startAccepted || presetReplySent) && glassReady) phase = Phase.READY
            } else if (phase == Phase.READY && raster?.receive(message) == true) {
                return
            } else if (phase == Phase.SCENE_PENDING && scene?.committed(message) == true) {
                phase = Phase.SCENE_ACCEPTED
            }
        }

        /** First scene registers revision=1 in stock runtime. The provider supplies
         * the genuine route token and both geometries; no guessed local segment. */
        fun initialScene(generation: Long, token: String, sceneId: String,
                         overview: NativeRouteScene.Geometry, local: NativeRouteScene.Geometry): List<NativeRouteScene.Fragment> {
            check(phase == Phase.READY && parameters.profile.scene == "ROUTE_SCENE_V1")
            val identity = NativeRouteScene.Identity(sid, 1, generation, token)
            val encoded = NativeRouteScene.encode(identity, sceneId, parameters.travelMode, overview, local)
            route = identity
            scene = encoded
            phase = Phase.SCENE_PENDING
            return encoded.fragments
        }

        /** Prepare 0x34 only after matching START_REPLY and GLASS_READY.
         * Raster/NONE registers revision 1 through the first genuine update;
         * scene profiles require an acknowledged initial scene first.
         * This initial implementation refuses route replacement/rerouting.
         * It does not manufacture observations or refresh stale provider data. */
        fun guidanceFrame(sequence: Int, generation: Long, token: String,
                          snapshot: NativePhoneGuidance.Snapshot, nowMs: Long): ByteArray {
            check((parameters.profile == Profile.RASTER_FRAME && phase == Phase.READY) ||
                (parameters.profile.scene == "ROUTE_SCENE_V1" && phase == Phase.SCENE_ACCEPTED))
            check(snapshot.observedAtMs > lastGuidanceAt && snapshot.freshAt(nowMs))
            val identity = route ?: NativeRouteScene.Identity(sid, 1, generation, token)
            check(identity.generation == generation && identity.token == token)
            val packet = NativePhoneGuidance.frame(sequence, identity, nextGuidanceSequence,
                parameters, snapshot, nowMs)
            route = identity
            if (parameters.profile == Profile.RASTER_FRAME && raster == null) {
                raster = NativeRasterTransfer(identity, checkNotNull(rasterLimits))
            }
            nextGuidanceSequence++
            lastGuidanceAt = snapshot.observedAtMs
            return packet
        }

        /** Explicit phone-side termination. Stock reason table index 2 = MOBILE_APP_CLOSE.
         * The returned packet is not a proof of device-side termination. */
        fun closeFrame(sequence: Int): ByteArray? {
            if (phase == Phase.NEW || phase == Phase.ENDED || endSent) return null
            endSent = true
            phase = Phase.ENDED
            scene = null
            route = null
            raster?.close()
            raster = null
            return phoneCloseFrame(sequence, sid)
        }
        override fun toString() = "Session(phase=" + phase.name + ")"
    }
}
