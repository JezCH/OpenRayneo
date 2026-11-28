// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject
import java.util.UUID

/** Stock 1.0.6.11 phone-originated start (0x10761594, 0x10741458),
 * reply (0x105c6f58), ready (0x105c5070), initial revision=1 and
 * route registration (0x1073d764, 0x1073c18c). No transport side effects.
 * A real route source and exact peer profile must be established by the caller.
 * This class must not be used to start a session from GPX geometry alone. */
internal object NativeNavigationStartup {
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
     * Start is intentionally inaccessible without a genuine route source at integration.
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
        private var accepted = false
        private var ready = false
        private var scene: NativeRouteScene.Encoded? = null
        private var endSent = false
        var rejectionCode: Int? = null
            private set

        /** Caller must verify actual negotiated capabilities and source availability
         * before invoking. Code 0 is a request, never a received ACK. */
        fun startFrame(sequence: Int): ByteArray {
            check(phase == Phase.NEW)
            val body = parameters.json().put("sid", sid).put("trigger", 1).put("code", 0)
            val packet = ProtocolCodec.display(0x20, sequence, 1, body.toString())
            phase = Phase.STARTING
            return packet
        }
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
                val end = body(message, 3) ?: return
                if ((end.opt("reason_code") as? Int) in 1..7) {
                    phase = Phase.ENDED
                    scene = null
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
                        if (!parameters.matchesReply(reply)) return
                        accepted = true
                    }
                    4 -> {
                        val response = body(message, 4) ?: return
                        if (!parameters.matchesProfile(response)) return
                        val runtime = response.opt("runtime_version") as? String ?: return
                        if (runtime.isBlank() || runtime.toByteArray(Charsets.UTF_8).size > 128) return
                        ready = true
                    }
                }
                if (accepted && ready) phase = Phase.READY
            } else if (phase == Phase.SCENE_PENDING && scene?.committed(message) == true) {
                phase = Phase.SCENE_ACCEPTED
            }
        }

        /** First scene registers revision=1 in stock runtime. The provider supplies
         * the genuine route token and both geometries; no guessed local segment. */
        fun initialScene(generation: Long, token: String, sceneId: String,
                         overview: NativeRouteScene.Geometry, local: NativeRouteScene.Geometry): List<NativeRouteScene.Fragment> {
            check(phase == Phase.READY && parameters.profile.scene == "ROUTE_SCENE_V1")
            val encoded = NativeRouteScene.encode(NativeRouteScene.Identity(sid, 1, generation, token),
                sceneId, parameters.travelMode, overview, local)
            scene = encoded
            phase = Phase.SCENE_PENDING
            return encoded.fragments
        }

        /** Explicit phone-side termination. Stock reason table index 2 = MOBILE_APP_CLOSE.
         * The returned packet is not a proof of device-side termination. */
        fun closeFrame(sequence: Int): ByteArray? {
            if (phase == Phase.NEW || phase == Phase.ENDED || endSent) return null
            endSent = true
            phase = Phase.ENDED
            scene = null
            val body = JSONObject().put("sid", sid).put("reason_code", 2).put("text", "MOBILE_APP_CLOSE")
            return ProtocolCodec.display(0x20, sequence, 3, body.toString())
        }
        override fun toString() = "Session(phase=" + phase.name + ")"
    }
}
